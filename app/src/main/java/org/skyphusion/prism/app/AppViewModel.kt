package org.skyphusion.prism.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skyphusion.prism.ChatStreamEvent
import org.skyphusion.prism.ControlPlaneChatMessage
import org.skyphusion.prism.ControlPlaneChatRequest
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.ControlPlaneModel
import org.skyphusion.prism.PrismError
import org.skyphusion.prism.SecretStore
import org.skyphusion.prism.SecretStoreKeys
import org.skyphusion.prism.prismUserFacingError

data class ChatTurn(
  val id: String = UUID.randomUUID().toString(),
  val role: Role,
  var text: String,
  val modelId: String? = null,
) {
  enum class Role { User, Assistant, System }
}

enum class MediaKind { Image, Video }

/** One generated image/video kept in-session for history / re-use (cap 20). */
data class MediaHistoryItem(
  val id: String = UUID.randomUUID().toString(),
  val kind: MediaKind,
  val model: String,
  val prompt: String,
  val createdAtMs: Long = System.currentTimeMillis(),
  val imageBase64: String? = null,
  val imageUrl: String? = null,
  val videoUrl: String? = null,
)

/**
 * Control-plane shell: enroll, chat, image, video (parity with prism-ios plane tabs).
 * Device key lives only in [secrets].
 */
class AppViewModel(
  private val secrets: SecretStore,
  private val baseUrl: String = ControlPlaneClient.PRODUCTION_BASE_URL,
) : ViewModel() {
  private var client =
    ControlPlaneClient(
      baseUrl = secrets.get(SecretStoreKeys.CONTROL_PLANE_BASE_URL) ?: baseUrl,
      clientKey = secrets.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY),
    )

  var enrollmentToken by mutableStateOf("")
  var deviceLabel by mutableStateOf(secrets.get(SecretStoreKeys.DEVICE_LABEL) ?: "Android")
  var hasDeviceKey by mutableStateOf(!client.clientKey.isNullOrBlank())
    private set

  var models = mutableStateListOf<ControlPlaneModel>()
    private set
  var selectedModelId by mutableStateOf<String?>(null)
  var selectedImageModelId by mutableStateOf<String?>(null)
  var selectedVideoModelId by mutableStateOf<String?>(null)
  var balance by mutableStateOf<String?>(null)
  var turns = mutableStateListOf<ChatTurn>()
    private set
  var draft by mutableStateOf("")
  var useStream by mutableStateOf(true)
  var hideUnspendable by mutableStateOf(true)
  var isBusy by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<String?>(null)
  var banner by mutableStateOf("Control plane · $baseUrl")

  /** Unauthenticated `GET /health` probe (`null` = not checked yet). */
  var planeHealthOk by mutableStateOf<Boolean?>(null)
    private set
  var planeHealthService by mutableStateOf<String?>(null)
    private set

  val planeHealthLabel: String
    get() =
      when (planeHealthOk) {
        null -> "not checked"
        true ->
          planeHealthService?.takeIf { it.isNotBlank() }?.let { "ok · $it" } ?: "ok"
        false -> "unreachable"
      }

  // Image / video
  var imagePrompt by mutableStateOf("")
  var imageImageRef by mutableStateOf("")
  var videoPrompt by mutableStateOf("")
  var videoImageRef by mutableStateOf("")
  var mediaBusy by mutableStateOf(false)
    private set
  var mediaStatus by mutableStateOf<String?>(null)
  var mediaError by mutableStateOf<String?>(null)
  var lastImageUrl by mutableStateOf<String?>(null)
  var lastImageBase64 by mutableStateOf<String?>(null)
  var lastVideoUrl by mutableStateOf<String?>(null)
  var mediaElapsedSeconds by mutableStateOf(0)
    private set
  var mediaHistory = mutableStateListOf<MediaHistoryItem>()
    private set
  var canRetryLastChat by mutableStateOf(false)
    private set
  private var lastFailedChatText: String? = null
  private var mediaJob: Job? = null
  private var mediaTimerJob: Job? = null
  private var chatJob: Job? = null

  val imageModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "image" }
        .filter { !hideUnspendable || it.spendable != false }
        .sortedWith(
          compareBy<ControlPlaneModel> {
            when {
              it.requiresImageInput() -> 2
              it.acceptsImageInput() -> 1
              else -> 0
            }
          }.thenBy { it.displayName ?: it.id },
        )

  val videoModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "video" }
        .filter { !hideUnspendable || it.spendable != false }
        .sortedWith(
          compareBy<ControlPlaneModel> {
            // Grok video last (needs plane 0.4.14+ ZDR); prefer working defaults first.
            when {
              it.id.startsWith("xai/grok-imagine-video") -> 2
              it.id.startsWith("minimax/hailuo") -> 1
              else -> 0
            }
          }.thenBy { it.displayName ?: it.id },
        )

  val chatModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "chat" || it.modality == null }
        .filter { !hideUnspendable || it.spendable != false }

  val selectedChatModel: ControlPlaneModel?
    get() = chatModels.firstOrNull { it.id == selectedModelId } ?: chatModels.firstOrNull()

  val selectedImageModel: ControlPlaneModel?
    get() = imageModels.firstOrNull { it.id == selectedImageModelId } ?: imageModels.firstOrNull()

  val selectedVideoModel: ControlPlaneModel?
    get() = videoModels.firstOrNull { it.id == selectedVideoModelId } ?: videoModels.firstOrNull()

  /** Unit-price preview for image/video (catalog priceSnippet). */
  fun spendPreview(model: ControlPlaneModel?): String? {
    val p = model?.priceSnippet() ?: return null
    if (p == "included") return "Est. cost: included (no unit charge on this plan rate)"
    return "Est. cost: $p per request (metered after success)"
  }

  val imageSpendPreview: String? get() = spendPreview(selectedImageModel)
  val videoSpendPreview: String? get() = spendPreview(selectedVideoModel)
  val chatSpendPreview: String? get() = spendPreview(selectedChatModel)

  init {
    probePlaneHealth()
    if (hasDeviceKey) {
      refreshAccount()
      refreshModels()
    }
  }

  /** Foreground resume: health + balance when enrolled (iOS onBecomeActive). */
  fun onBecomeActive() {
    probePlaneHealth()
    if (hasDeviceKey) refreshAccount()
  }

  /** Unauthenticated `GET /health` on the control plane origin. */
  fun probePlaneHealth() {
    viewModelScope.launch {
      try {
        val h =
          withContext(Dispatchers.IO) {
            client.health()
          }
        planeHealthOk = h.ok
        planeHealthService = h.service
        if (!h.ok && models.isEmpty()) {
          banner = "Control plane · health not ok"
        }
      } catch (_: Exception) {
        planeHealthOk = false
        planeHealthService = null
        if (models.isEmpty()) {
          banner = "Control plane · unreachable"
        }
      }
    }
  }

  /** Change chat model without clearing transcript (iOS parity). */
  fun selectChatModel(modelId: String) {
    if (chatModels.none { it.id == modelId }) return
    selectedModelId = modelId
  }

  fun enroll() {
    val token = normalizeSecret(enrollmentToken)
    if (token.isEmpty()) {
      errorMessage = "Enrollment token required"
      return
    }
    if (token.startsWith("pcp_")) {
      enrollmentToken = ""
      importDeviceKey(token)
      return
    }
    viewModelScope.launch {
      isBusy = true
      errorMessage = null
      try {
        val res =
          withContext(Dispatchers.IO) {
            client.enroll(enrollmentToken = token, label = deviceLabel.ifBlank { "Android" })
          }
        secrets.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, res.key)
        secrets.set(SecretStoreKeys.DEVICE_LABEL, deviceLabel.ifBlank { "Android" })
        secrets.set(SecretStoreKeys.CONTROL_PLANE_BASE_URL, baseUrl)
        hasDeviceKey = true
        enrollmentToken = ""
        banner = "Enrolled · ${res.clientId}"
        probePlaneHealth()
        refreshAccount()
        refreshModels()
      } catch (e: Exception) {
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
    }
  }

  /** Inject an existing device key (paste from another store). Never log the value. */
  fun importDeviceKey(raw: String) {
    val key = normalizeSecret(raw)
    if (!key.startsWith("pcp_")) {
      errorMessage =
        if (key.isEmpty()) "Paste a pcp_ device key"
        else "That is not a device key (must start with pcp_)."
      return
    }
    secrets.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, key)
    secrets.set(SecretStoreKeys.CONTROL_PLANE_BASE_URL, baseUrl)
    if (deviceLabel.isNotBlank()) secrets.set(SecretStoreKeys.DEVICE_LABEL, deviceLabel)
    client.setClientKey(key)
    hasDeviceKey = true
    errorMessage = null
    banner = "Device key imported"
    probePlaneHealth()
    refreshAccount()
    refreshModels()
  }

  fun clearDeviceKey() {
    secrets.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, null)
    client.setClientKey(null)
    hasDeviceKey = false
    models.clear()
    selectedModelId = null
    selectedImageModelId = null
    selectedVideoModelId = null
    balance = null
    turns.clear()
    clearMediaResults()
    banner = "Control plane · re-enroll required"
  }

  fun refreshModels() {
    if (!hasDeviceKey) return
    viewModelScope.launch {
      isBusy = true
      errorMessage = null
      try {
        val list =
          withContext(Dispatchers.IO) {
            client.listModels().data
          }
        models.clear()
        models.addAll(list)
        pickDefaults()
      } catch (e: Exception) {
        handleAuthError(e)
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
    }
  }

  private fun pickDefaults() {
    if (selectedModelId == null || models.none { it.id == selectedModelId }) {
      selectedModelId =
        models.firstOrNull { it.spendable != false && it.modality == "chat" }?.id
          ?: models.firstOrNull { it.spendable != false }?.id
          ?: models.firstOrNull()?.id
    }
    if (selectedImageModelId == null || imageModels.none { it.id == selectedImageModelId }) {
      selectedImageModelId =
        imageModels.firstOrNull { it.id.contains("flux-1-schnell") }?.id
          ?: imageModels.firstOrNull { !it.acceptsImageInput() }?.id
          ?: imageModels.firstOrNull()?.id
    }
    if (selectedVideoModelId == null || videoModels.none { it.id == selectedVideoModelId }) {
      selectedVideoModelId =
        videoModels.firstOrNull { it.id == "google/veo-3.1-fast" }?.id
          ?: videoModels.firstOrNull { it.id.startsWith("google/veo") }?.id
          ?: videoModels.firstOrNull { it.id == "bytedance/seedance-2.0-fast" }?.id
          ?: videoModels.firstOrNull {
            !it.id.startsWith("minimax/hailuo") && !it.id.startsWith("xai/grok-imagine-video")
          }?.id
          ?: videoModels.firstOrNull()?.id
    }
  }

  fun refreshAccount() {
    if (!hasDeviceKey) return
    viewModelScope.launch {
      try {
        val me =
          withContext(Dispatchers.IO) {
            client.me()
          }
        balance = me.usage?.balanceDescription()
        me.client?.label?.let { if (deviceLabel.isBlank()) deviceLabel = it }
      } catch (e: Exception) {
        handleAuthError(e)
        // non-fatal for banner
      }
    }
  }

  fun clearChat() {
    turns.clear()
    clearChatFailure()
  }

  fun retryLastFailedChat() {
    val text = lastFailedChatText ?: return
    if (text.isEmpty()) return
    draft = text
    canRetryLastChat = false
    send()
  }

  fun cancelChat() {
    chatJob?.cancel()
    chatJob = null
    isBusy = false
    val last = turns.lastOrNull()
    if (last != null && last.role == ChatTurn.Role.Assistant && last.text.isEmpty()) {
      turns[turns.lastIndex] = last.copy(text = "(cancelled)")
    }
    errorMessage = "Cancelled"
  }

  fun send() {
    val text = draft.trim()
    val modelId = selectedModelId ?: selectedChatModel?.id
    if (text.isEmpty() || modelId == null || isBusy) return
    val model = models.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      errorMessage = "Model is not spendable on this plan"
      return
    }

    draft = ""
    clearChatFailure()
    turns.add(ChatTurn(role = ChatTurn.Role.User, text = text))
    val assistant = ChatTurn(role = ChatTurn.Role.Assistant, text = "", modelId = modelId)
    turns.add(assistant)
    val assistantIndex = turns.lastIndex

    chatJob?.cancel()
    chatJob =
      viewModelScope.launch {
        isBusy = true
        errorMessage = null
        try {
          val history =
            turns.dropLast(1).mapNotNull { t ->
              when (t.role) {
                ChatTurn.Role.User -> ControlPlaneChatMessage("user", t.text)
                ChatTurn.Role.Assistant ->
                  if (t.text.isNotEmpty()) ControlPlaneChatMessage("assistant", t.text) else null
                ChatTurn.Role.System -> ControlPlaneChatMessage("system", t.text)
              }
            }

          if (useStream && model?.streaming != false) {
            val req =
              ControlPlaneChatRequest(
                model = modelId,
                messages = history,
                stream = true,
              )
            withContext(Dispatchers.IO) {
              client.chatCompletionsStream(req)
                .catch { e -> throw e }
                .collect { ev ->
                  when (ev) {
                    is ChatStreamEvent.Delta -> {
                      withContext(Dispatchers.Main) {
                        val cur = turns[assistantIndex]
                        turns[assistantIndex] = cur.copy(text = cur.text + ev.text)
                      }
                    }
                    is ChatStreamEvent.Done -> {
                      val full = ev.fullText
                      if (full != null) {
                        withContext(Dispatchers.Main) {
                          val cur = turns[assistantIndex]
                          if (cur.text.isEmpty()) {
                            turns[assistantIndex] = cur.copy(text = full)
                          }
                        }
                      }
                    }
                    is ChatStreamEvent.Error -> throw PrismError.Server(ev.message)
                    is ChatStreamEvent.Unknown -> Unit
                  }
                }
            }
          } else {
            val reply =
              withContext(Dispatchers.IO) {
                client.chat(model = modelId, messages = history)
              }
            turns[assistantIndex] = assistant.copy(text = reply)
          }
          refreshAccount()
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          handleAuthError(e)
          errorMessage = e.toUserMessage()
          recordChatFailure(text)
          if (turns.getOrNull(assistantIndex)?.text.isNullOrEmpty()) {
            turns.removeAt(assistantIndex)
          }
        } finally {
          isBusy = false
        }
      }
  }

  fun generateImage() {
    val modelId = selectedImageModelId ?: return
    val prompt = imagePrompt.trim()
    if (prompt.isEmpty() || mediaBusy) return
    val model = imageModels.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      mediaError = "Model is not spendable on this plan"
      return
    }
    if (model?.requiresImageInput() == true && imageImageRef.trim().isEmpty()) {
      mediaError = "This model needs a reference image (https or data: URL)."
      return
    }
    mediaJob?.cancel()
    mediaJob =
      viewModelScope.launch {
        mediaBusy = true
        mediaError = null
        mediaStatus = "Generating image…"
        startMediaTimer()
        try {
          val res =
            withContext(Dispatchers.IO) {
              client.generateImage(
                model = modelId,
                prompt = prompt,
                image = imageImageRef.trim().ifEmpty { null },
              )
            }
          lastImageUrl = res.firstDisplayUrl
          lastImageBase64 = res.firstBase64
          mediaStatus = "Image ready"
          pushMediaHistory(
            MediaHistoryItem(
              kind = MediaKind.Image,
              model = modelId,
              prompt = prompt,
              imageBase64 = res.firstBase64,
              imageUrl = res.firstDisplayUrl,
            ),
          )
          refreshAccount()
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          handleAuthError(e)
          mediaError = e.toUserMessage()
          mediaStatus = null
        } finally {
          stopMediaTimer()
          mediaBusy = false
        }
      }
  }

  fun generateVideo() {
    val modelId = selectedVideoModelId ?: return
    val prompt = videoPrompt.trim()
    val image = videoImageRef.trim().ifEmpty { null }
    if ((prompt.isEmpty() && image == null) || mediaBusy) return
    val model = videoModels.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      mediaError = "Model is not spendable on this plan"
      return
    }
    if (model?.requiresImageInput() == true && image == null) {
      mediaError = "This model needs a first-frame image (i2v)."
      return
    }
    mediaJob?.cancel()
    mediaJob =
      viewModelScope.launch {
        mediaBusy = true
        mediaError = null
        mediaStatus = "Generating video (can take 1–3 min)…"
        startMediaTimer()
        try {
          val res =
            withContext(Dispatchers.IO) {
              client.generateVideo(
                model = modelId,
                prompt = prompt.ifEmpty { null },
                image = image,
              )
            }
          lastVideoUrl = res.video
          mediaStatus = "Video ready"
          pushMediaHistory(
            MediaHistoryItem(
              kind = MediaKind.Video,
              model = modelId,
              prompt = prompt.ifEmpty { "(image-only)" },
              videoUrl = res.video,
            ),
          )
          refreshAccount()
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          handleAuthError(e)
          mediaError = e.toUserMessage()
          mediaStatus = null
        } finally {
          stopMediaTimer()
          mediaBusy = false
        }
      }
  }

  fun retryLastVideo() {
    if (videoPrompt.trim().isEmpty() && videoImageRef.trim().isEmpty()) return
    generateVideo()
  }

  fun cancelMedia() {
    mediaJob?.cancel()
    mediaJob = null
    stopMediaTimer()
    mediaBusy = false
    mediaStatus = "Cancelled"
  }

  fun useLastImageAsReference(forVideo: Boolean) {
    val ref =
      lastImageUrl
        ?: lastImageBase64?.let { "data:image/png;base64,$it" }
        ?: return
    if (forVideo) videoImageRef = ref else imageImageRef = ref
  }

  fun restoreMediaHistoryItem(item: MediaHistoryItem) {
    when (item.kind) {
      MediaKind.Image -> {
        lastImageBase64 = item.imageBase64
        lastImageUrl = item.imageUrl
        imagePrompt = item.prompt
        if (imageModels.any { it.id == item.model }) selectedImageModelId = item.model
      }
      MediaKind.Video -> {
        lastVideoUrl = item.videoUrl
        videoPrompt = item.prompt
        if (videoModels.any { it.id == item.model }) selectedVideoModelId = item.model
      }
    }
  }

  fun historyFor(kind: MediaKind): List<MediaHistoryItem> =
    mediaHistory.filter { it.kind == kind }

  private fun pushMediaHistory(item: MediaHistoryItem) {
    mediaHistory.add(0, item)
    while (mediaHistory.size > MEDIA_HISTORY_CAP) {
      mediaHistory.removeAt(mediaHistory.lastIndex)
    }
  }

  private fun startMediaTimer() {
    mediaTimerJob?.cancel()
    mediaElapsedSeconds = 0
    mediaTimerJob =
      viewModelScope.launch {
        while (true) {
          kotlinx.coroutines.delay(1_000)
          mediaElapsedSeconds += 1
        }
      }
  }

  private fun stopMediaTimer() {
    mediaTimerJob?.cancel()
    mediaTimerJob = null
  }

  private fun recordChatFailure(userText: String) {
    lastFailedChatText = userText
    canRetryLastChat = true
  }

  private fun clearChatFailure() {
    lastFailedChatText = null
    canRetryLastChat = false
  }

  private fun clearMediaResults() {
    lastImageUrl = null
    lastImageBase64 = null
    lastVideoUrl = null
    mediaError = null
    mediaStatus = null
  }

  /** For Play Billing redeem (Settings). Null when not enrolled. */
  fun planeClientOrNull(): ControlPlaneClient? =
    if (hasDeviceKey && !client.clientKey.isNullOrBlank()) client else null

  private fun handleAuthError(e: Exception) {
    if (e is PrismError.ClientRevoked || e is PrismError.Unauthenticated) {
      clearDeviceKey()
    }
  }

  private fun Exception.toUserMessage(): String = prismUserFacingError(this)

  companion object {
    private const val MEDIA_HISTORY_CAP = 20

    fun normalizeSecret(raw: String): String =
      raw
        .trim()
        .replace("\u200b", "")
        .replace("\uFEFF", "")
        .filterNot { it.isWhitespace() }
  }

  class Factory(
    private val secrets: SecretStore,
    private val baseUrl: String = ControlPlaneClient.PRODUCTION_BASE_URL,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
        return AppViewModel(secrets, baseUrl) as T
      }
      throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
  }
}
