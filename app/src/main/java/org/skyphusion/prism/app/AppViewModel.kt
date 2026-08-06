package org.skyphusion.prism.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.time.Instant
import org.skyphusion.prism.ChatStreamEvent
import org.skyphusion.prism.ControlPlaneChatMessage
import org.skyphusion.prism.ControlPlaneChatRequest
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.ControlPlaneModel
import org.skyphusion.prism.ConversationCompact
import org.skyphusion.prism.ConversationCompactState
import org.skyphusion.prism.PrismClient
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
  private val appContext: Context,
  private val baseUrl: String = ControlPlaneClient.PRODUCTION_BASE_URL,
) : ViewModel() {
  private var client =
    ControlPlaneClient(
      baseUrl = secrets.get(SecretStoreKeys.CONTROL_PLANE_BASE_URL) ?: baseUrl,
      clientKey = secrets.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY),
    )

  /** Playground Worker client (compact API when [playgroundConversationId] is set). */
  private var playground: PrismClient =
    PrismClient.create(
      baseUrl =
        secrets.get(SecretStoreKeys.PLAYGROUND_BASE_URL) ?: PrismClient.PRODUCTION_BASE_URL,
    ).also { pc ->
      secrets.get(SecretStoreKeys.PLAYGROUND_SESSION_COOKIE)?.let { token ->
        pc.restoreSessionToken(token)
      }
    }

  var enrollmentToken by mutableStateOf("")
  var deviceLabel by mutableStateOf(secrets.get(SecretStoreKeys.DEVICE_LABEL) ?: "Android")
  var hasDeviceKey by mutableStateOf(!client.clientKey.isNullOrBlank())
    private set

  var models = mutableStateListOf<ControlPlaneModel>()
    private set
  var selectedModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_CHAT_MODEL))
  var selectedImageModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_IMAGE_MODEL))
  var selectedVideoModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_VIDEO_MODEL))
  var selectedSpeechModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_SPEECH_MODEL))
  var selectedSttModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_STT_MODEL))
  var selectedMusicModelId by mutableStateOf(secrets.get(SecretStoreKeys.SELECTED_MUSIC_MODEL))
  var balance by mutableStateOf<String?>(null)
  var turns = mutableStateListOf<ChatTurn>()
    private set

  /** Local multi-session chats (device-only). */
  var sessions = mutableStateListOf<ChatSession>()
    private set
  var currentSessionId by mutableStateOf<String?>(null)
    private set
  private val sessionStore = ChatSessionStore(appContext)
  var draft by mutableStateOf("")
  var useStream by mutableStateOf(
    secrets.get(SecretStoreKeys.USE_STREAM)?.let { it != "0" && !it.equals("false", true) } ?: true,
  )
  var hideUnspendable by mutableStateOf(
    secrets.get(SecretStoreKeys.HIDE_UNSPENDABLE)?.let { it != "0" && !it.equals("false", true) } ?: true,
  )
  var isBusy by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<String?>(null)
  var banner by mutableStateOf("Control plane · $baseUrl")

  /** Unauthenticated `GET /health` probe (`null` = not checked yet). */
  var planeHealthOk by mutableStateOf<Boolean?>(null)
    private set
  var planeHealthService by mutableStateOf<String?>(null)
    private set

  /** Device has a usable network path (Wi-Fi / cellular). iOS NWPathMonitor parity. */
  var isNetworkSatisfied by mutableStateOf(true)
    private set

  val planeHealthLabel: String
    get() =
      when (planeHealthOk) {
        null -> "not checked"
        true ->
          planeHealthService?.takeIf { it.isNotBlank() }?.let { "ok · $it" } ?: "ok"
        false -> "unreachable"
      }

  /**
   * True when the last turn is an assistant reply we can re-run under the current model.
   * Client owns the transcript on the control plane.
   */
  val canRegenerateLastReply: Boolean
    get() {
      if (isBusy || !hasDeviceKey) return false
      val last = turns.lastOrNull() ?: return false
      if (last.role != ChatTurn.Role.Assistant) return false
      return turns.getOrNull(turns.lastIndex - 1)?.role == ChatTurn.Role.User
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

  // Speech / STT / music
  var speechInput by mutableStateOf("")
  var speechBusy by mutableStateOf(false)
    private set
  var speechError by mutableStateOf<String?>(null)
  var speechStatus by mutableStateOf<String?>(null)
  /** Last TTS audio as base64 (mp3). */
  var lastSpeechBase64 by mutableStateOf<String?>(null)
  var lastSpeechFormat by mutableStateOf<String?>("mp3")
  var sttAudioDataUrl by mutableStateOf("")
  var lastTranscript by mutableStateOf<String?>(null)
  var musicPrompt by mutableStateOf("")
  var musicLyrics by mutableStateOf("")
  var musicBusy by mutableStateOf(false)
    private set
  var musicError by mutableStateOf<String?>(null)
  var musicStatus by mutableStateOf<String?>(null)
  var lastMusicUrl by mutableStateOf<String?>(null)
  var lastMusicBase64 by mutableStateOf<String?>(null)

  /**
   * Playground conversation id when history is server-side (Worker).
   * Null in control-plane-only mode (client owns the transcript).
   */
  var playgroundConversationId by mutableStateOf<String?>(null)
    private set

  /**
   * Compact state: plane client-side summary, or playground Worker state after compact.
   * UI transcript stays full; wire / server context shrinks.
   */
  var compactState by mutableStateOf<ConversationCompactState?>(null)
    private set
  var compactBusy by mutableStateOf(false)
    private set

  private var lastFailedChatText: String? = null
  private var mediaJob: Job? = null
  private var mediaTimerJob: Job? = null
  private var chatJob: Job? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

  val speechModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "tts" }
        .filter { !hideUnspendable || it.spendable != false }
        .sortedBy { it.displayName ?: it.id }

  val sttModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "stt" }
        .filter { !hideUnspendable || it.spendable != false }
        .sortedBy { it.displayName ?: it.id }

  val musicModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "music" }
        .filter { !hideUnspendable || it.spendable != false }
        .sortedBy { it.displayName ?: it.id }

  val selectedChatModel: ControlPlaneModel?
    get() = chatModels.firstOrNull { it.id == selectedModelId } ?: chatModels.firstOrNull()

  val selectedImageModel: ControlPlaneModel?
    get() = imageModels.firstOrNull { it.id == selectedImageModelId } ?: imageModels.firstOrNull()

  val selectedVideoModel: ControlPlaneModel?
    get() = videoModels.firstOrNull { it.id == selectedVideoModelId } ?: videoModels.firstOrNull()

  val selectedSpeechModel: ControlPlaneModel?
    get() = speechModels.firstOrNull { it.id == selectedSpeechModelId } ?: speechModels.firstOrNull()

  val selectedSttModel: ControlPlaneModel?
    get() = sttModels.firstOrNull { it.id == selectedSttModelId } ?: sttModels.firstOrNull()

  val selectedMusicModel: ControlPlaneModel?
    get() = musicModels.firstOrNull { it.id == selectedMusicModelId } ?: musicModels.firstOrNull()

  val speechSpendPreview: String? get() = spendPreview(selectedSpeechModel)
  val sttSpendPreview: String? get() = spendPreview(selectedSttModel)
  val musicSpendPreview: String? get() = spendPreview(selectedMusicModel)

  /** Unit-price preview for image/video (catalog priceSnippet). */
  fun spendPreview(model: ControlPlaneModel?): String? {
    val p = model?.priceSnippet() ?: return null
    if (p == "included") return "Est. cost: included (no unit charge on this plan rate)"
    return "Est. cost: $p per request (metered after success)"
  }

  val imageSpendPreview: String? get() = spendPreview(selectedImageModel)
  val videoSpendPreview: String? get() = spendPreview(selectedVideoModel)
  val chatSpendPreview: String? get() = spendPreview(selectedChatModel)

  /** Completed user/assistant pairs eligible for compact (web bar: need 3+). */
  val completedChatPairCount: Int
    get() = completedChatPairs().size

  val isCompacted: Boolean
    get() = compactState?.summary?.isNotBlank() == true

  /** Enough history to compact, and not already compacted. */
  val canCompactConversation: Boolean
    get() {
      if (!hasDeviceKey || isBusy || compactBusy || isCompacted) return false
      if (!isNetworkSatisfied) return false
      return completedChatPairCount >= ConversationCompact.MIN_TURNS_TO_COMPACT
    }

  val canExpandConversation: Boolean
    get() = hasDeviceKey && !isBusy && !compactBusy && isCompacted

  init {
    startNetworkMonitor()
    loadSessionsFromDisk()
    probePlaneHealth()
    if (hasDeviceKey) {
      refreshAccount()
      refreshModels()
    }
  }

  override fun onCleared() {
    stopNetworkMonitor()
    try {
      speechPlayer?.release()
    } catch (_: Exception) {
    }
    speechPlayer = null
    super.onCleared()
  }

  /** Foreground resume: health + balance when enrolled (iOS onBecomeActive). */
  fun onBecomeActive() {
    probePlaneHealth()
    if (hasDeviceKey) refreshAccount()
  }

  private fun startNetworkMonitor() {
    val cm =
      appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return
    isNetworkSatisfied = cm.hasUsableInternet()
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          isNetworkSatisfied = true
        }

        override fun onLost(network: Network) {
          isNetworkSatisfied = cm.hasUsableInternet()
        }

        override fun onCapabilitiesChanged(
          network: Network,
          networkCapabilities: NetworkCapabilities,
        ) {
          // Prefer INTERNET alone; VALIDATED can lag on first connect.
          isNetworkSatisfied =
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onUnavailable() {
          isNetworkSatisfied = false
        }
      }
    val request =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    try {
      cm.registerNetworkCallback(request, callback)
      networkCallback = callback
    } catch (_: Exception) {
      // Missing ACCESS_NETWORK_STATE or OEM quirks -- leave default true.
    }
  }

  private fun stopNetworkMonitor() {
    val cb = networkCallback ?: return
    val cm =
      appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return
    try {
      cm.unregisterNetworkCallback(cb)
    } catch (_: Exception) {
      // already unregistered
    }
    networkCallback = null
  }

  private fun ConnectivityManager.hasUsableInternet(): Boolean {
    val network = activeNetwork ?: return false
    val caps = getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
    persistUIPrefs()
  }

  fun updateUseStream(on: Boolean) {
    useStream = on
    persistUIPrefs()
  }

  fun updateHideUnspendable(on: Boolean) {
    hideUnspendable = on
    persistUIPrefs()
  }

  fun persistUIPrefs() {
    secrets.set(SecretStoreKeys.SELECTED_CHAT_MODEL, selectedModelId)
    secrets.set(SecretStoreKeys.SELECTED_IMAGE_MODEL, selectedImageModelId)
    secrets.set(SecretStoreKeys.SELECTED_VIDEO_MODEL, selectedVideoModelId)
    secrets.set(SecretStoreKeys.SELECTED_SPEECH_MODEL, selectedSpeechModelId)
    secrets.set(SecretStoreKeys.SELECTED_STT_MODEL, selectedSttModelId)
    secrets.set(SecretStoreKeys.SELECTED_MUSIC_MODEL, selectedMusicModelId)
    secrets.set(SecretStoreKeys.USE_STREAM, if (useStream) "1" else "0")
    secrets.set(SecretStoreKeys.HIDE_UNSPENDABLE, if (hideUnspendable) "1" else "0")
  }

  /** Plain-text transcript for share (iOS chatTranscriptText). */
  fun chatTranscriptText(): String =
    turns
      .mapNotNull { t ->
        val body = t.text.trim()
        if (body.isEmpty() || body == "(cancelled)") return@mapNotNull null
        when (t.role) {
          ChatTurn.Role.User -> "You: $body"
          ChatTurn.Role.Assistant -> {
            val who = t.modelId ?: "Prism"
            "$who: $body"
          }
          ChatTurn.Role.System -> "System: $body"
        }
      }.joinToString("\n\n")

  fun retryLastImage() {
    if (imagePrompt.trim().isEmpty()) return
    generateImage()
  }

  fun clearImageReference() {
    imageImageRef = ""
  }

  fun clearVideoReference() {
    videoImageRef = ""
  }

  /** Encode photo bytes as a data: URL for i2i / i2v reference fields. */
  fun setImageReferenceData(bytes: ByteArray, mime: String = "image/jpeg") {
    imageImageRef = MediaUtils.bytesToDataUrl(bytes, mime)
  }

  fun setVideoReferenceData(bytes: ByteArray, mime: String = "image/jpeg") {
    videoImageRef = MediaUtils.bytesToDataUrl(bytes, mime)
  }

  /**
   * Save last generated image to the device gallery.
   * Prefers base64 payload; falls back to downloading [lastImageUrl] when needed.
   */
  fun saveLastImageToGallery(onDone: (Boolean, String) -> Unit) {
    viewModelScope.launch {
      try {
        val bytes: ByteArray? =
          lastImageBase64?.let { MediaUtils.decodeBase64Payload(it) }
            ?: lastImageUrl?.let { url ->
              withContext(Dispatchers.IO) {
                java.net.URL(url).openStream().use { it.readBytes() }
              }
            }
        if (bytes == null || bytes.isEmpty()) {
          onDone(false, "No image to save.")
          return@launch
        }
        val ok =
          withContext(Dispatchers.IO) {
            MediaUtils.saveImageToGallery(appContext, bytes)
          }
        if (ok) {
          mediaStatus = "Saved to Photos"
          onDone(true, "Saved to Photos")
        } else {
          onDone(false, "Could not save image.")
        }
      } catch (e: Exception) {
        onDone(false, e.message ?: "Save failed")
      }
    }
  }

  /** Fill draft from an empty-state starter chip. */
  fun applyStarterPrompt(text: String) {
    draft = text
  }

  /** Use a turn's text as the compose draft (context menu). */
  fun useTurnAsDraft(turn: ChatTurn) {
    draft = turn.text
  }

  /**
   * Clipboard → enrollment token field (token or full pcp_ key routed appropriately).
   * Returns true when something was applied.
   */
  fun pasteEnrollmentFromClipboard(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) {
      errorMessage = "Clipboard is empty."
      return false
    }
    val key = normalizeSecret(trimmed)
    if (key.startsWith("pcp_")) {
      importDeviceKey(key)
      return true
    }
    enrollmentToken = trimmed
    errorMessage = null
    return true
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
    compactState = null
    playgroundConversationId = null
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
    if (selectedModelId == null || chatModels.none { it.id == selectedModelId }) {
      selectedModelId =
        chatModels.firstOrNull { it.spendable != false }?.id
          ?: chatModels.firstOrNull()?.id
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
    if (selectedSpeechModelId == null || speechModels.none { it.id == selectedSpeechModelId }) {
      selectedSpeechModelId =
        speechModels.firstOrNull { it.id.contains("aura-2-en") }?.id
          ?: speechModels.firstOrNull { it.id.contains("melotts") }?.id
          ?: speechModels.firstOrNull()?.id
    }
    if (selectedSttModelId == null || sttModels.none { it.id == selectedSttModelId }) {
      selectedSttModelId =
        sttModels.firstOrNull { it.id.contains("whisper") }?.id
          ?: sttModels.firstOrNull()?.id
    }
    if (selectedMusicModelId == null || musicModels.none { it.id == selectedMusicModelId }) {
      selectedMusicModelId = musicModels.firstOrNull()?.id
    }
    persistUIPrefs()
  }

  // --- Multi-session chats (device-local) ---

  private fun loadSessionsFromDisk() {
    val snap = sessionStore.load()
    sessions.clear()
    sessions.addAll(snap.sessions)
    val id =
      snap.currentId?.takeIf { cid -> sessions.any { it.id == cid } }
        ?: sessions.firstOrNull()?.id
    if (id != null) {
      openSession(id, persist = false)
    } else {
      ensureCurrentSession()
    }
  }

  private fun saveSessionsToDisk() {
    sessionStore.save(sessions.toList(), currentSessionId)
  }

  private fun ensureCurrentSession() {
    if (currentSessionId != null && sessions.any { it.id == currentSessionId }) return
    val first = sessions.firstOrNull()
    if (first != null) {
      openSession(first.id, persist = false)
      return
    }
    val s = ChatSession(selectedModelId = selectedModelId)
    sessions.add(0, s)
    currentSessionId = s.id
    turns.clear()
    compactState = null
    saveSessionsToDisk()
  }

  /** Flush live transcript into [sessions] and disk. */
  fun persistCurrentSession() {
    val id = currentSessionId
    if (id == null) {
      if (turns.isEmpty()) return
      val s =
        ChatSession(
          title = ChatSession.makeTitle(turns.toList()),
          turns = turns.toList(),
          selectedModelId = selectedModelId,
          compact = compactState,
        )
      sessions.add(0, s)
      currentSessionId = s.id
      trimSessions()
      saveSessionsToDisk()
      return
    }
    val i = sessions.indexOfFirst { it.id == id }
    if (i >= 0) {
      val prev = sessions[i]
      sessions[i] =
        prev.copy(
          title = ChatSession.makeTitle(turns.toList()),
          turns = turns.toList(),
          selectedModelId = selectedModelId,
          compact = compactState,
          updatedAtMs = System.currentTimeMillis(),
        )
      // Keep newest-first order
      val updated = sessions.removeAt(i)
      sessions.add(0, updated)
    }
    trimSessions()
    saveSessionsToDisk()
  }

  private fun trimSessions() {
    while (sessions.size > ChatSessionStore.SESSION_CAP) {
      sessions.removeAt(sessions.lastIndex)
    }
  }

  fun newChat() {
    cancelChat()
    persistCurrentSession()
    // Drop empty "New chat" shells so the list does not fill with blanks.
    currentSessionId?.let { id ->
      val i = sessions.indexOfFirst { it.id == id }
      if (i >= 0 && sessions[i].turns.isEmpty()) {
        sessions.removeAt(i)
      }
    }
    val s = ChatSession(selectedModelId = selectedModelId)
    sessions.add(0, s)
    currentSessionId = s.id
    turns.clear()
    compactState = null
    errorMessage = null
    clearChatFailure()
    draft = ""
    trimSessions()
    saveSessionsToDisk()
  }

  fun openSession(id: String, persist: Boolean = true) {
    if (persist) persistCurrentSession()
    val s = sessions.firstOrNull { it.id == id } ?: return
    currentSessionId = s.id
    turns.clear()
    turns.addAll(s.turns)
    compactState = s.compact
    if (s.selectedModelId != null && chatModels.any { it.id == s.selectedModelId }) {
      selectedModelId = s.selectedModelId
    }
    clearChatFailure()
    errorMessage = null
    if (persist) saveSessionsToDisk()
  }

  fun deleteSession(id: String) {
    val i = sessions.indexOfFirst { it.id == id }
    if (i < 0) return
    sessions.removeAt(i)
    if (currentSessionId == id) {
      val next = sessions.firstOrNull()
      if (next != null) {
        openSession(next.id, persist = false)
      } else {
        currentSessionId = null
        turns.clear()
        compactState = null
        ensureCurrentSession()
      }
    }
    saveSessionsToDisk()
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
    compactState = null
    // playgroundConversationId is server-owned; leave it until bindPlaygroundConversation(null).
    clearChatFailure()
    persistCurrentSession()
  }

  /**
   * Completed user/assistant pairs (skips empty / error / cancelled assistant shells).
   * [ConversationCompact.Pair.throughTurnIndex] is the linear index of the assistant turn.
   */
  fun completedChatPairs(): List<ConversationCompact.Pair> {
    val pairs = mutableListOf<ConversationCompact.Pair>()
    var i = 0
    val list = turns
    while (i < list.size) {
      val t = list[i]
      if (t.role == ChatTurn.Role.User) {
        val u = t.text.trim()
        if (i + 1 < list.size && list[i + 1].role == ChatTurn.Role.Assistant) {
          val a = list[i + 1].text.trim()
          if (u.isNotEmpty() && a.isNotEmpty() &&
            !a.startsWith("(error)") && !a.startsWith("(cancelled)")
          ) {
            pairs.add(
              ConversationCompact.Pair(
                user = u,
                assistant = a,
                throughTurnIndex = i + 1,
              ),
            )
          }
          i += 2
          continue
        }
      }
      i += 1
    }
    return pairs
  }

  /**
   * Bind a playground conversation (or clear). When set, Compact/Expand hit the
   * Worker `.../compact` endpoints; when null, plane uses client-side summary.
   */
  fun bindPlaygroundConversation(
    id: String?,
    compact: ConversationCompactState? = null,
  ) {
    playgroundConversationId = id?.takeIf { it.isNotBlank() }
    compactState = compact
  }

  /**
   * Compact older turns.
   * - Playground: `POST /api/conversations/:id/compact` when [playgroundConversationId] is set.
   * - Plane: local summary via chat completion (UI transcript unchanged).
   */
  fun compactConversation() {
    if (!canCompactConversation) return
    viewModelScope.launch {
      compactBusy = true
      errorMessage = null
      try {
        val convId = playgroundConversationId
        if (convId != null) {
          performPlaygroundCompact(convId)
        } else {
          performPlaneCompact()
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        handleAuthError(e)
        errorMessage = e.toUserMessage()
      } finally {
        compactBusy = false
      }
    }
  }

  /** Clear compact so the next send uses full history again (Expand). */
  fun expandConversation() {
    if (!canExpandConversation) return
    val convId = playgroundConversationId
    if (convId == null) {
      compactState = null
      banner = "Expanded -- next turn uses full history"
      errorMessage = null
      persistCurrentSession()
      return
    }
    viewModelScope.launch {
      compactBusy = true
      errorMessage = null
      try {
        withContext(Dispatchers.IO) {
          playground.clearConversationCompact(convId)
        }
        compactState = null
        banner = "Expanded -- next turn uses full history"
        persistPlaygroundSession()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        errorMessage = e.toUserMessage()
      } finally {
        compactBusy = false
      }
    }
  }

  private suspend fun performPlaygroundCompact(conversationId: String) {
    val modelId = selectedModelId ?: selectedChatModel?.id
    val res =
      withContext(Dispatchers.IO) {
        playground.compactConversation(
          id = conversationId,
          keepRecent = ConversationCompact.DEFAULT_KEEP_RECENT,
          model = modelId,
        )
      }
    compactState = res.compact
    val n = res.turnsSummarized ?: 0
    val k = res.turnsKeptRaw ?: 0
    banner =
      "Compacted $n turn${if (n == 1) "" else "s"}; keeping $k recent raw"
    persistPlaygroundSession()
  }

  private suspend fun performPlaneCompact() {
    val pairs = completedChatPairs()
    val keep = ConversationCompact.DEFAULT_KEEP_RECENT
    if (pairs.size < keep + 1) {
      errorMessage =
        "Need at least ${keep + 1} completed turns to compact (have ${pairs.size})."
      return
    }
    val split = ConversationCompact.splitPairs(pairs, keepRecent = keep)
    if (split.summarize.isEmpty()) {
      errorMessage = "Nothing to summarize with keep_recent=$keep."
      return
    }
    val modelId = selectedModelId ?: selectedChatModel?.id
    if (modelId == null) {
      errorMessage = "Pick a chat model to run the compact summary."
      return
    }
    if (!isNetworkSatisfied) {
      errorMessage = "No network connection. Reconnect and try again."
      return
    }
    val transcript = ConversationCompact.formatPairsForSummary(split.summarize)
    val messages =
      listOf(
        ControlPlaneChatMessage(role = "system", content = ConversationCompact.SYSTEM_PROMPT),
        ControlPlaneChatMessage(
          role = "user",
          content = "Compress the following conversation into a continuity brief.\n\n$transcript",
        ),
      )
    val raw =
      withContext(Dispatchers.IO) {
        client.chat(model = modelId, messages = messages)
      }
    val summary = ConversationCompact.normalizeSummary(raw)
    if (summary.isEmpty()) {
      errorMessage = "Compact model returned empty summary."
      return
    }
    val through = split.summarize.last().throughTurnIndex
    compactState =
      ConversationCompactState(
        summary = summary,
        throughTurnIndex = through,
        keepRecent = keep,
        model = modelId,
        updatedAt = Instant.now().toString(),
      )
    val n = split.summarize.size
    val k = split.keep.size
    banner =
      "Compacted $n turn${if (n == 1) "" else "s"}; keeping $k recent raw"
    refreshAccount()
    persistCurrentSession()
  }

  private fun persistPlaygroundSession() {
    val token = playground.exportSessionToken()
    secrets.set(SecretStoreKeys.PLAYGROUND_SESSION_COOKIE, token)
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
    if (isBusy) return
    performSend(SendMode.NewFromDraft)
  }

  /** Drop the last assistant reply and re-run the last user turn under the current model. */
  fun regenerateLastReply() {
    if (!canRegenerateLastReply) return
    performSend(SendMode.RegenerateLast)
  }

  private enum class SendMode {
    NewFromDraft,
    RegenerateLast,
  }

  private fun performSend(mode: SendMode) {
    val modelId = selectedModelId ?: selectedChatModel?.id
    if (modelId == null || isBusy) return
    if (!hasDeviceKey) {
      errorMessage = "Enroll a device key before chatting."
      return
    }
    if (!isNetworkSatisfied) {
      errorMessage = "No network connection. Reconnect and try again."
      return
    }
    val model = models.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      errorMessage = "Model is not spendable on this plan"
      return
    }

    val text: String =
      when (mode) {
        SendMode.NewFromDraft -> {
          val t = draft.trim()
          if (t.isEmpty()) return
          draft = ""
          clearChatFailure()
          turns.add(ChatTurn(role = ChatTurn.Role.User, text = t))
          t
        }
        SendMode.RegenerateLast -> {
          while (turns.lastOrNull()?.role == ChatTurn.Role.Assistant) {
            turns.removeAt(turns.lastIndex)
          }
          val user = turns.lastOrNull()
          if (user == null || user.role != ChatTurn.Role.User) {
            errorMessage = "Nothing to regenerate."
            return
          }
          clearChatFailure()
          user.text
        }
      }

    val assistant = ChatTurn(role = ChatTurn.Role.Assistant, text = "", modelId = modelId)
    turns.add(assistant)
    val assistantIndex = turns.lastIndex

    chatJob =
      viewModelScope.launch {
        isBusy = true
        errorMessage = null
        try {
          // With compact active: inject summary system block and only turns after
          // through_turn_index (prism v0.175.7 parity). UI transcript unchanged.
          val history = buildPlaneChatMessages(excludeAssistantIndex = assistantIndex)

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
          persistCurrentSession()
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          handleAuthError(e)
          errorMessage = e.toUserMessage()
          recordChatFailure(text)
          if (turns.getOrNull(assistantIndex)?.text.isNullOrEmpty()) {
            turns.removeAt(assistantIndex)
          }
          persistCurrentSession()
        } finally {
          isBusy = false
        }
      }
  }

  fun generateImage() {
    val modelId = selectedImageModelId ?: return
    val prompt = imagePrompt.trim()
    if (prompt.isEmpty() || mediaBusy) return
    if (!isNetworkSatisfied) {
      mediaError = "No network connection. Reconnect and try again."
      return
    }
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
    if (!isNetworkSatisfied) {
      mediaError = "No network connection. Reconnect and try again."
      return
    }
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

  /**
   * Build OpenAI-style messages for the plane, applying compact if set.
   * [excludeAssistantIndex] skips the in-flight empty assistant shell.
   */
  private fun buildPlaneChatMessages(excludeAssistantIndex: Int): List<ControlPlaneChatMessage> {
    val out = mutableListOf<ControlPlaneChatMessage>()
    val compact = compactState
    if (compact != null) {
      val block = compact.systemBlock
      if (block.isNotEmpty()) {
        out.add(ControlPlaneChatMessage(role = "system", content = block))
      }
    }
    val through = compact?.throughTurnIndex
    for ((idx, turn) in turns.withIndex()) {
      if (idx == excludeAssistantIndex) continue
      if (through != null && idx <= through) continue
      when (turn.role) {
        ChatTurn.Role.User -> {
          if (turn.text.isNotEmpty()) {
            out.add(ControlPlaneChatMessage(role = "user", content = turn.text))
          }
        }
        ChatTurn.Role.Assistant -> {
          val t = turn.text.trim()
          if (t.isNotEmpty() && !t.startsWith("(cancelled)") && !t.startsWith("(error)")) {
            out.add(ControlPlaneChatMessage(role = "assistant", content = turn.text))
          }
        }
        ChatTurn.Role.System -> {
          if (turn.text.isNotEmpty()) {
            out.add(ControlPlaneChatMessage(role = "system", content = turn.text))
          }
        }
      }
    }
    return out
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

  // --- Speech / STT / music ---

  /** When true, auto-play TTS after the next successful [generateSpeech]. */
  private var autoPlaySpeechAfterGenerate: Boolean = false
  private var speechPlayer: android.media.MediaPlayer? = null

  val canSpeakText: Boolean
    get() = hasDeviceKey && speechModels.any { it.spendable != false } && !speechBusy

  fun generateSpeech(autoPlay: Boolean = false) {
    val modelId = selectedSpeechModelId ?: selectedSpeechModel?.id ?: return
    val input = speechInput.trim()
    if (input.isEmpty() || speechBusy) return
    if (!isNetworkSatisfied) {
      speechError = "No network connection. Reconnect and try again."
      return
    }
    val model = speechModels.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      speechError = "Model is not spendable on this plan"
      return
    }
    if (autoPlay) autoPlaySpeechAfterGenerate = true
    viewModelScope.launch {
      speechBusy = true
      speechError = null
      speechStatus = "Generating speech…"
      try {
        val res =
          withContext(Dispatchers.IO) {
            client.generateSpeech(model = modelId, input = input)
          }
        lastSpeechBase64 = res.audioBase64
        lastSpeechFormat = res.format ?: "mp3"
        speechStatus = "Speech ready"
        refreshAccount()
        if (autoPlaySpeechAfterGenerate) {
          autoPlaySpeechAfterGenerate = false
          playLastSpeech()
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        autoPlaySpeechAfterGenerate = false
        handleAuthError(e)
        speechError = e.toUserMessage()
        speechStatus = null
      } finally {
        speechBusy = false
      }
    }
  }

  /** Use assistant text as TTS input and auto-play when ready (iOS parity). */
  fun speakText(text: String) {
    val t = text.trim()
    if (t.isEmpty()) return
    speechInput = t
    generateSpeech(autoPlay = true)
  }

  fun playLastSpeech() {
    val b64 = lastSpeechBase64 ?: return
    try {
      speechPlayer?.release()
      speechPlayer =
        MediaUtils.playAudioBase64(appContext, b64, lastSpeechFormat ?: "mp3")
      speechStatus = "Playing…"
    } catch (e: Exception) {
      speechError = e.message ?: "Playback failed"
    }
  }

  fun transcribeAudio() {
    val modelId = selectedSttModelId ?: selectedSttModel?.id ?: return
    val audio = sttAudioDataUrl.trim()
    if (audio.isEmpty() || speechBusy) return
    if (!isNetworkSatisfied) {
      speechError = "No network connection. Reconnect and try again."
      return
    }
    val model = sttModels.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      speechError = "Model is not spendable on this plan"
      return
    }
    viewModelScope.launch {
      speechBusy = true
      speechError = null
      speechStatus = "Transcribing…"
      try {
        val res =
          withContext(Dispatchers.IO) {
            client.transcribe(model = modelId, audio = audio)
          }
        lastTranscript = res.text
        speechStatus = "Transcript ready"
        refreshAccount()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        handleAuthError(e)
        speechError = e.toUserMessage()
        speechStatus = null
      } finally {
        speechBusy = false
      }
    }
  }

  fun setSttAudioBase64(mime: String, base64: String) {
    val m = mime.ifBlank { "audio/mpeg" }
    sttAudioDataUrl = "data:$m;base64,$base64"
  }

  fun generateMusic() {
    val modelId = selectedMusicModelId ?: selectedMusicModel?.id ?: return
    val prompt = musicPrompt.trim()
    if (prompt.isEmpty() || musicBusy) return
    if (!isNetworkSatisfied) {
      musicError = "No network connection. Reconnect and try again."
      return
    }
    val model = musicModels.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      musicError = "Model is not spendable on this plan"
      return
    }
    viewModelScope.launch {
      musicBusy = true
      musicError = null
      musicStatus = "Generating music…"
      try {
        val res =
          withContext(Dispatchers.IO) {
            client.generateMusic(
              model = modelId,
              prompt = prompt,
              lyrics = musicLyrics.trim().ifEmpty { null },
            )
          }
        lastMusicUrl = res.audioUrl
        lastMusicBase64 =
          res.audio?.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }
        musicStatus = "Music ready"
        refreshAccount()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        handleAuthError(e)
        musicError = e.toUserMessage()
        musicStatus = null
      } finally {
        musicBusy = false
      }
    }
  }

  private fun handleAuthError(e: Exception) {
    if (e is PrismError.ClientRevoked || e is PrismError.Unauthenticated) {
      clearDeviceKey()
    }
  }

  private fun Exception.toUserMessage(): String = prismUserFacingError(this)

  companion object {
    private const val MEDIA_HISTORY_CAP = 20

    /** Empty-state chips; tapping fills the draft (user can edit before send). */
    val starterPrompts: List<String> =
      listOf(
        "Explain this simply, like I am new to the topic:",
        "Summarize the following in three short bullets:",
        "Write a clear product blurb (2 sentences) for:",
        "List practical next steps to debug:",
      )

    fun normalizeSecret(raw: String): String =
      raw
        .trim()
        .replace("\u200b", "")
        .replace("\uFEFF", "")
        .filterNot { it.isWhitespace() }
  }

  class Factory(
    private val secrets: SecretStore,
    private val appContext: Context,
    private val baseUrl: String = ControlPlaneClient.PRODUCTION_BASE_URL,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
        return AppViewModel(secrets, appContext.applicationContext, baseUrl) as T
      }
      throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
  }
}
