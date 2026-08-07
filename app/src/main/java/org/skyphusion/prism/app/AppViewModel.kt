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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.skyphusion.prism.ChatAttachment
import org.skyphusion.prism.ChatStreamEvent
import org.skyphusion.prism.ControlPlaneChatMessage
import org.skyphusion.prism.ControlPlaneChatRequest
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.ControlPlaneModel
import org.skyphusion.prism.ConversationCompact
import org.skyphusion.prism.ConversationCompactState
import org.skyphusion.prism.PlaygroundChatRequest
import org.skyphusion.prism.PrismClient
import org.skyphusion.prism.PrismError
import org.skyphusion.prism.SecretStore
import org.skyphusion.prism.SecretStoreKeys
import org.skyphusion.prism.VideoClipDuration
import org.skyphusion.prism.prismUserFacingError

/** Inference backend (iOS BackendKind). Product default is control plane. */
enum class BackendKind {
  ControlPlane,
  Playground,
  ;

  val title: String
    get() =
      when (this) {
        ControlPlane -> "Control plane"
        Playground -> "Playground"
      }

  fun toStorage(): String =
    when (this) {
      ControlPlane -> "controlPlane"
      Playground -> "playground"
    }

  companion object {
    fun fromStorage(raw: String?): BackendKind =
      when (raw?.lowercase()) {
        "playground", "play" -> Playground
        else -> ControlPlane
      }
  }
}

data class ChatTurn(
  val id: String = UUID.randomUUID().toString(),
  val role: Role,
  var text: String,
  val modelId: String? = null,
  /** Vision attachments for this turn (data:image/... URLs). User turns only. */
  val imageDataUrls: List<String>? = null,
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
  private var client: ControlPlaneClient =
    ControlPlaneClient(
      baseUrl = secrets.get(SecretStoreKeys.CONTROL_PLANE_BASE_URL) ?: baseUrl,
      clientKey = secrets.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY),
    )

  /** Playground Worker client (auth, chat, compact). */
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

  /** Product default is control plane; playground is behind developer options. */
  var backend by mutableStateOf(
    BackendKind.fromStorage(secrets.get(SecretStoreKeys.BACKEND_MODE)),
  )
    private set
  var showDeveloperSettings by mutableStateOf(
    secrets.get(SecretStoreKeys.SHOW_DEVELOPER)?.let { it == "1" || it.equals("true", true) }
      ?: false,
  )
  var playgroundAuthenticated by mutableStateOf(false)
    private set
  var sessionUsername by mutableStateOf(secrets.get(SecretStoreKeys.PLAYGROUND_SESSION_USERNAME))
    private set
  var playgroundUsername by mutableStateOf("")
  var playgroundPassword by mutableStateOf("")
  var authMode by mutableStateOf<String?>(null)
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
  /** Dual-pool usage lines for More hub / Settings (iOS planeUsageLines). */
  var planeUsageLines = mutableStateListOf<String>()
    private set
  var turns = mutableStateListOf<ChatTurn>()
    private set

  /** Local multi-session chats (device-only). */
  var sessions = mutableStateListOf<ChatSession>()
    private set
  var currentSessionId by mutableStateOf<String?>(null)
    private set
  private val sessionStore = ChatSessionStore(appContext)
  var draft by mutableStateOf("")
  /** Pending chat image attachments (data URLs) for the next send. Cap 3. */
  var draftImageDataUrls = mutableStateListOf<String>()
    private set
  var serverSyncBusy by mutableStateOf(false)
    private set
  var serverSyncMessage by mutableStateOf<String?>(null)
  /** Full period usage from GET /v1/usage (Usage screen). */
  var usageDetail by mutableStateOf<org.skyphusion.prism.UsageSummary?>(null)
    private set
  var usageBusy by mutableStateOf(false)
    private set
  var usageError by mutableStateOf<String?>(null)
  var chatSttBusy by mutableStateOf(false)
    private set
  /** Last plane request cost from `prism-usage-micro-usd` (non-stream). */
  var lastRequestCost by mutableStateOf<String?>(null)
  /** Face unlock gate (iOS biometricLockEnabled). */
  var biometricLockEnabled by mutableStateOf(
    secrets.get(SecretStoreKeys.BIOMETRIC_LOCK_ENABLED)?.let {
      it == "1" || it.equals("true", true)
    } ?: false,
  )
    private set
  /** True until the user unlocks after launch / background. */
  var isBiometricallyLocked by mutableStateOf(false)
  var liveSttStatus by mutableStateOf<String?>(null)
  private val liveSttFinals = mutableListOf<String>()
  var useStream by mutableStateOf(
    secrets.get(SecretStoreKeys.USE_STREAM)?.let { it != "0" && !it.equals("false", true) } ?: true,
  )
  var hideUnspendable by mutableStateOf(
    secrets.get(SecretStoreKeys.HIDE_UNSPENDABLE)?.let { it != "0" && !it.equals("false", true) } ?: true,
  )
  /** Model picker search (chat/media/audio); selection survives filter. */
  var modelSearch by mutableStateOf("")
  var isBusy by mutableStateOf(false)
    private set

  /** Editable base URLs (developer overrides). */
  var controlPlaneBaseUrl by mutableStateOf(
    secrets.get(SecretStoreKeys.CONTROL_PLANE_BASE_URL) ?: baseUrl,
  )
  var playgroundBaseUrl by mutableStateOf(
    secrets.get(SecretStoreKeys.PLAYGROUND_BASE_URL) ?: PrismClient.PRODUCTION_BASE_URL,
  )
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

  /** True when the current backend can chat. */
  val canChat: Boolean
    get() =
      when (backend) {
        BackendKind.ControlPlane -> hasDeviceKey
        BackendKind.Playground -> playgroundAuthenticated
      }

  /** Image/video/audio/music doors are plane-only. */
  val canUseMediaDoors: Boolean
    get() = backend == BackendKind.ControlPlane && hasDeviceKey

  val needsPlaneEnroll: Boolean
    get() = backend == BackendKind.ControlPlane && !hasDeviceKey

  val needsPlaygroundLogin: Boolean
    get() = backend == BackendKind.Playground && !playgroundAuthenticated

  /**
   * True when the last turn is an assistant reply we can re-run under the current model.
   * Client owns the transcript on the control plane.
   */
  val canRegenerateLastReply: Boolean
    get() {
      if (isBusy || !canChat) return false
      // Playground history is server-side; regenerate is plane-only (client transcript).
      if (backend != BackendKind.ControlPlane) return false
      val last = turns.lastOrNull() ?: return false
      if (last.role != ChatTurn.Role.Assistant) return false
      return turns.getOrNull(turns.lastIndex - 1)?.role == ChatTurn.Role.User
    }

  // Image / video
  var imagePrompt by mutableStateOf("")
  var imageImageRef by mutableStateOf("")
  var videoPrompt by mutableStateOf("")
  var videoImageRef by mutableStateOf("")
  /**
   * User-chosen clip length in seconds. Clamped to the selected video model's CF range
   * on model change and on generate (iOS `videoDurationSeconds` parity).
   */
  var videoDurationSeconds by mutableStateOf(5)
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
  /** Live plane STT WebSocket (linear16 @ 16 kHz). */
  var liveSttRunning by mutableStateOf(false)
    private set
  var liveSttPartial by mutableStateOf("")
  private var liveSttSession: LiveSttSession? = null
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

  private fun matchesModelSearch(m: ControlPlaneModel): Boolean {
    val q = modelSearch.trim().lowercase()
    if (q.isEmpty()) return true
    val hay = "${m.displayName.orEmpty()} ${m.id}".lowercase()
    return hay.contains(q)
  }

  val imageModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "image" }
        .filter { !hideUnspendable || it.spendable != false }
        .filter { matchesModelSearch(it) }
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
        .filter { matchesModelSearch(it) }
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
        .filter { matchesModelSearch(it) }

  /** All chat models ignoring search (selection must survive filter). */
  val allChatModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "chat" || it.modality == null }
        .filter { !hideUnspendable || it.spendable != false }

  val speechModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "tts" }
        .filter { !hideUnspendable || it.spendable != false }
        .filter { matchesModelSearch(it) }
        .sortedBy { it.displayName ?: it.id }

  val sttModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "stt" }
        .filter { !hideUnspendable || it.spendable != false }
        .filter { matchesModelSearch(it) }
        .sortedBy { it.displayName ?: it.id }

  val musicModels: List<ControlPlaneModel>
    get() =
      models
        .filter { it.modality == "music" }
        .filter { !hideUnspendable || it.spendable != false }
        .filter { matchesModelSearch(it) }
        .sortedBy { it.displayName ?: it.id }

  val selectedChatModel: ControlPlaneModel?
    get() =
      allChatModels.firstOrNull { it.id == selectedModelId }
        ?: chatModels.firstOrNull()
        ?: allChatModels.firstOrNull()

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

  /**
   * Chat send cost hint + capability line (token rates are not per-request exact).
   * iOS chatSpendPreview 0.8.3.
   */
  val chatSpendPreview: String?
    get() {
      val m = selectedChatModel ?: return null
      val parts = mutableListOf<String>()
      m.priceSnippet()?.let { p ->
        parts.add(if (p == "included") "Rate: included" else "Rate: $p")
      }
      if (m.supportsVision()) {
        parts.add(
          if (draftImageDataUrls.isEmpty()) "vision-capable" else "vision attach · metered",
        )
      } else if (draftImageDataUrls.isNotEmpty()) {
        parts.add("warning: model may not support vision")
      }
      if (useStream && m.streaming != false) parts.add("stream on")
      m.tier?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
      if (parts.isEmpty()) return null
      return "Send: " + parts.joinToString(" · ")
    }

  /** Completed user/assistant pairs eligible for compact (web bar: need 3+). */
  val completedChatPairCount: Int
    get() = completedChatPairs().size

  val isCompacted: Boolean
    get() = compactState?.summary?.isNotBlank() == true

  /** Enough history to compact, and not already compacted. */
  val canCompactConversation: Boolean
    get() {
      if (!canChat || isBusy || compactBusy || isCompacted) return false
      if (!isNetworkSatisfied) return false
      if (backend == BackendKind.Playground && playgroundConversationId.isNullOrBlank()) {
        return false
      }
      return completedChatPairCount >= ConversationCompact.MIN_TURNS_TO_COMPACT
    }

  val canExpandConversation: Boolean
    get() = canChat && !isBusy && !compactBusy && isCompacted

  init {
    startNetworkMonitor()
    loadSessionsFromDisk()
    // Restore playground session cookie if present.
    secrets.get(SecretStoreKeys.PLAYGROUND_SESSION_COOKIE)?.let { tok ->
      if (playground.restoreSessionToken(tok)) {
        playgroundAuthenticated = true
      }
    }
    // Gate UI before network when biometric lock is on with a stored key.
    if (biometricLockEnabled && hasDeviceKey) {
      isBiometricallyLocked = true
    }
    if (!isBiometricallyLocked) {
      probePlaneHealth()
      when (backend) {
        BackendKind.ControlPlane ->
          if (hasDeviceKey) {
            refreshAccount()
            refreshModels()
          }
        BackendKind.Playground -> refreshModels()
      }
    }
  }

  override fun onCleared() {
    stopNetworkMonitor()
    stopLiveStt(commit = false)
    try {
      speechPlayer?.release()
    } catch (_: Exception) {
    }
    speechPlayer = null
    super.onCleared()
  }

  /** Foreground resume: health + balance + pending Workflow jobs (iOS onBecomeActive). */
  fun onBecomeActive() {
    if (isBiometricallyLocked) return
    probePlaneHealth()
    if (backend == BackendKind.ControlPlane && hasDeviceKey) {
      refreshAccount()
      forceSyncPendingJobs()
    }
  }

  /** Call when scene enters background so the next open requires biometrics. */
  fun lockIfNeeded() {
    if (biometricLockEnabled && hasDeviceKey) {
      isBiometricallyLocked = true
    }
  }

  fun updateBiometricLockEnabled(on: Boolean) {
    biometricLockEnabled = on
    secrets.set(SecretStoreKeys.BIOMETRIC_LOCK_ENABLED, if (on) "1" else "0")
    isBiometricallyLocked = on && hasDeviceKey
  }

  fun unlockBiometrics() {
    isBiometricallyLocked = false
    onBecomeActive()
  }

  fun updateShowDeveloperSettings(on: Boolean) {
    showDeveloperSettings = on
    secrets.set(SecretStoreKeys.SHOW_DEVELOPER, if (on) "1" else "0")
    if (!on && backend == BackendKind.Playground) {
      updateBackend(BackendKind.ControlPlane)
    }
  }

  fun updateBackend(kind: BackendKind) {
    if (backend == kind) return
    backend = kind
    secrets.set(SecretStoreKeys.BACKEND_MODE, kind.toStorage())
    models.clear()
    selectedModelId = null
    modelSearch = ""
    errorMessage = null
    banner =
      when (kind) {
        BackendKind.ControlPlane -> "Control plane · $controlPlaneBaseUrl"
        BackendKind.Playground -> "Playground · $playgroundBaseUrl"
      }
    when (kind) {
      BackendKind.ControlPlane -> {
        if (hasDeviceKey) {
          refreshAccount()
          refreshModels()
        }
      }
      BackendKind.Playground -> refreshModels()
    }
  }

  /** Apply control-plane base URL (developer). Rebuilds client. */
  fun applyControlPlaneBaseUrl(raw: String) {
    val url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return
    controlPlaneBaseUrl = url
    secrets.set(SecretStoreKeys.CONTROL_PLANE_BASE_URL, url)
    val key = secrets.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY)
    client = ControlPlaneClient(baseUrl = url, clientKey = key)
    hasDeviceKey = !key.isNullOrBlank()
    if (backend == BackendKind.ControlPlane) {
      banner = "Control plane · $url"
      if (hasDeviceKey) {
        refreshAccount()
        refreshModels()
      }
    }
  }

  /** Apply playground base URL (developer). Rebuilds client; restores session cookie. */
  fun applyPlaygroundBaseUrl(raw: String) {
    val url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return
    playgroundBaseUrl = url
    secrets.set(SecretStoreKeys.PLAYGROUND_BASE_URL, url)
    playground =
      PrismClient.create(baseUrl = url).also { pc ->
        secrets.get(SecretStoreKeys.PLAYGROUND_SESSION_COOKIE)?.let { tok ->
          pc.restoreSessionToken(tok)
        }
      }
    if (backend == BackendKind.Playground) {
      banner = "Playground · $url"
      refreshModels()
    }
  }

  fun resetControlPlaneBaseUrl() {
    applyControlPlaneBaseUrl(ControlPlaneClient.PRODUCTION_BASE_URL)
  }

  fun resetPlaygroundBaseUrl() {
    applyPlaygroundBaseUrl(PrismClient.PRODUCTION_BASE_URL)
  }

  fun playgroundLogin() {
    val user = playgroundUsername.trim()
    val pass = playgroundPassword
    if (user.isEmpty() || pass.isEmpty()) {
      errorMessage = "Username and password required"
      return
    }
    viewModelScope.launch {
      isBusy = true
      errorMessage = null
      try {
        val res =
          withContext(Dispatchers.IO) {
            playground.login(user, pass)
          }
        playgroundAuthenticated = true
        sessionUsername = res.user?.username ?: user
        secrets.set(SecretStoreKeys.PLAYGROUND_SESSION_USERNAME, sessionUsername)
        persistPlaygroundSession()
        playgroundPassword = ""
        banner = "Signed in · ${sessionUsername}"
        refreshModels()
      } catch (e: Exception) {
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
    }
  }

  fun playgroundSignup() {
    val user = playgroundUsername.trim()
    val pass = playgroundPassword
    if (user.isEmpty() || pass.isEmpty()) {
      errorMessage = "Username and password required"
      return
    }
    viewModelScope.launch {
      isBusy = true
      errorMessage = null
      try {
        val res =
          withContext(Dispatchers.IO) {
            playground.signup(user, pass)
          }
        playgroundAuthenticated = true
        sessionUsername = res.user?.username ?: user
        secrets.set(SecretStoreKeys.PLAYGROUND_SESSION_USERNAME, sessionUsername)
        persistPlaygroundSession()
        playgroundPassword = ""
        banner = "Account created · ${sessionUsername}"
        refreshModels()
      } catch (e: Exception) {
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
    }
  }

  fun playgroundLogout() {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { playground.logout() }
      } catch (_: Exception) {
        playground.clearSession()
      }
      playgroundAuthenticated = false
      sessionUsername = null
      secrets.set(SecretStoreKeys.PLAYGROUND_SESSION_COOKIE, null)
      secrets.set(SecretStoreKeys.PLAYGROUND_SESSION_USERNAME, null)
      models.clear()
      playgroundConversationId = null
      banner = "Playground · signed out"
    }
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
    planeUsageLines.clear()
    turns.clear()
    compactState = null
    playgroundConversationId = null
    clearMediaResults()
    banner = "Control plane · re-enroll required"
  }

  fun refreshModels() {
    viewModelScope.launch {
      isBusy = true
      errorMessage = null
      try {
        when (backend) {
          BackendKind.ControlPlane -> {
            if (!hasDeviceKey) return@launch
            val list =
              withContext(Dispatchers.IO) {
                client.listModels().data
              }
            models.clear()
            models.addAll(list)
          }
          BackendKind.Playground -> {
            val res =
              withContext(Dispatchers.IO) {
                playground.models()
              }
            authMode = res.mode
            if (res.authenticated == true) {
              playgroundAuthenticated = true
              res.username?.let { sessionUsername = it }
              persistPlaygroundSession()
            } else if (res.mode == "public") {
              // Cookie may have expired.
              if (playground.exportSessionToken() == null) {
                playgroundAuthenticated = false
              }
            }
            models.clear()
            models.addAll(res.models.map { it.toControlPlaneModel() })
          }
        }
        pickDefaults()
      } catch (e: Exception) {
        if (backend == BackendKind.ControlPlane) handleAuthError(e)
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
    }
  }

  private fun pickDefaults() {
    // Use unfiltered chat list so search does not wipe selection.
    if (selectedModelId == null || allChatModels.none { it.id == selectedModelId }) {
      selectedModelId =
        allChatModels.firstOrNull { it.spendable != false }?.id
          ?: allChatModels.firstOrNull()?.id
    }
    val images =
      models
        .filter { it.modality == "image" }
        .filter { !hideUnspendable || it.spendable != false }
    val videos =
      models
        .filter { it.modality == "video" }
        .filter { !hideUnspendable || it.spendable != false }
    if (selectedImageModelId == null || images.none { it.id == selectedImageModelId }) {
      selectedImageModelId =
        images.firstOrNull { it.id.contains("flux-1-schnell") }?.id
          ?: images.firstOrNull { !it.acceptsImageInput() }?.id
          ?: images.firstOrNull()?.id
    }
    if (selectedVideoModelId == null || videos.none { it.id == selectedVideoModelId }) {
      // Prefer Seedance for text-to-video (Hailuo is i2v-only; Grok needs ZDR path).
      selectedVideoModelId =
        videos.firstOrNull { it.id == "bytedance/seedance-2.0-fast" }?.id
          ?: videos.firstOrNull { it.id.startsWith("bytedance/seedance") }?.id
          ?: videos.firstOrNull { it.id == "google/veo-3.1-fast" }?.id
          ?: videos.firstOrNull { it.id.startsWith("google/veo") }?.id
          ?: videos.firstOrNull {
            !it.id.startsWith("minimax/hailuo") && !it.id.startsWith("xai/grok-imagine-video")
          }?.id
          ?: videos.firstOrNull()?.id
    }
    clampVideoDurationToSelectedModel()
    val speech =
      models.filter { it.modality == "tts" }.filter { !hideUnspendable || it.spendable != false }
    val stt =
      models.filter { it.modality == "stt" }.filter { !hideUnspendable || it.spendable != false }
    val music =
      models.filter { it.modality == "music" }.filter { !hideUnspendable || it.spendable != false }
    if (selectedSpeechModelId == null || speech.none { it.id == selectedSpeechModelId }) {
      selectedSpeechModelId =
        speech.firstOrNull { it.id.contains("aura-2-en") }?.id
          ?: speech.firstOrNull { it.id.contains("melotts") }?.id
          ?: speech.firstOrNull()?.id
    }
    if (selectedSttModelId == null || stt.none { it.id == selectedSttModelId }) {
      selectedSttModelId =
        stt.firstOrNull { it.id.contains("whisper") }?.id
          ?: stt.firstOrNull()?.id
    }
    if (selectedMusicModelId == null || music.none { it.id == selectedMusicModelId }) {
      selectedMusicModelId = music.firstOrNull()?.id
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
    val convId = playgroundConversationId
    if (id == null) {
      if (turns.isEmpty()) return
      val s =
        ChatSession(
          title = ChatSession.makeTitle(turns.toList()),
          turns = turns.toList(),
          selectedModelId = selectedModelId,
          conversationId = convId,
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
          conversationId = convId ?: prev.conversationId,
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
    playgroundConversationId = null
    errorMessage = null
    clearChatFailure()
    draft = ""
    draftImageDataUrls.clear()
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
    playgroundConversationId = s.conversationId
    if (s.selectedModelId != null && chatModels.any { it.id == s.selectedModelId }) {
      selectedModelId = s.selectedModelId
    }
    clearChatFailure()
    errorMessage = null
    draftImageDataUrls.clear()
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
        planeUsageLines.clear()
        me.usage?.dualPoolLines()?.let { planeUsageLines.addAll(it) }
        me.client?.label?.let { if (deviceLabel.isBlank()) deviceLabel = it }
        publishWidgetBalance(balance)
      } catch (e: Exception) {
        handleAuthError(e)
        // non-fatal for banner
      }
    }
  }

  /** Push spendable line to home-screen widget prefs (no secrets). */
  private fun publishWidgetBalance(text: String?) {
    try {
      val prefs =
        appContext.getSharedPreferences(SecretStoreKeys.WIDGET_PREFS, Context.MODE_PRIVATE)
      prefs
        .edit()
        .putString(SecretStoreKeys.WIDGET_BALANCE, text ?: "—")
        .putString(
          SecretStoreKeys.WIDGET_UPDATED_AT,
          "Updated " +
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
              .format(java.util.Date()),
        )
        .apply()
      BalanceWidgetProvider.requestUpdate(appContext)
    } catch (_: Exception) {
    }
  }

  /** Full period usage for the Usage screen (`GET /v1/usage`). */
  fun refreshUsageDetail() {
    if (!hasDeviceKey) {
      usageError = "Enroll a device key first."
      return
    }
    viewModelScope.launch {
      usageBusy = true
      usageError = null
      try {
        val u =
          withContext(Dispatchers.IO) {
            client.usage()
          }
        usageDetail = u
        planeUsageLines.clear()
        planeUsageLines.addAll(u.dualPoolLines())
        balance = u.balanceDescription()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        handleAuthError(e)
        usageError = e.toUserMessage()
      } finally {
        usageBusy = false
      }
    }
  }

  /** Paste image bytes from system clipboard into chat draft (if any). */
  fun pasteChatImageFromClipboard(bytes: ByteArray?): Boolean {
    if (bytes == null || bytes.isEmpty()) {
      errorMessage = "No image on the clipboard."
      return false
    }
    attachChatImageBytes(bytes)
    return true
  }

  /** Append last STT transcript into the chat draft. */
  fun applyLastTranscriptToDraft() {
    val t = lastTranscript?.trim().orEmpty()
    if (t.isEmpty()) {
      errorMessage = "No transcript yet. Hold the mic to record, or use More → Audio."
      return
    }
    draft = if (draft.isEmpty()) t else "$draft $t"
    banner = "Transcript added to draft"
  }

  /**
   * Transcribe recorded audio and append to the chat draft (composer mic).
   * Uses selected STT model on the control plane.
   */
  fun sttToChatDraft(audioBytes: ByteArray, mime: String = "audio/mp4") {
    if (!canUseMediaDoors) {
      errorMessage = "Control plane + device key required for speech-to-text."
      return
    }
    if (!isNetworkSatisfied) {
      errorMessage = "No network connection."
      return
    }
    val modelId = selectedSttModelId ?: selectedSttModel?.id
    if (modelId == null) {
      errorMessage = "No STT model available."
      return
    }
    if (chatSttBusy || speechBusy) return
    val b64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
    val audio = "data:${mime.ifBlank { "audio/mp4" }};base64,$b64"
    viewModelScope.launch {
      chatSttBusy = true
      errorMessage = null
      try {
        val res =
          withContext(Dispatchers.IO) {
            client.transcribe(model = modelId, audio = audio)
          }
        val t = res.text?.trim().orEmpty()
        if (t.isEmpty()) {
          errorMessage = "Empty transcript."
          return@launch
        }
        lastTranscript = t
        draft = if (draft.isEmpty()) t else "$draft $t"
        banner = "Transcript added to draft"
        refreshAccount()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        handleAuthError(e)
        errorMessage = e.toUserMessage()
      } finally {
        chatSttBusy = false
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
    if (!canChat) {
      errorMessage =
        when (backend) {
          BackendKind.ControlPlane -> "Enroll a device key before chatting."
          BackendKind.Playground -> "Sign in (or sign up) before chatting on the playground."
        }
      return
    }
    if (!isNetworkSatisfied) {
      errorMessage = "No network connection. Reconnect and try again."
      return
    }
    val model = models.firstOrNull { it.id == modelId }
    if (backend == BackendKind.ControlPlane && model?.spendable == false) {
      errorMessage = "Model is not spendable on this plan"
      return
    }

    val sendImages: List<String>
    val text: String
    when (mode) {
      SendMode.NewFromDraft -> {
        val t = draft.trim()
        val imgs = draftImageDataUrls.toList()
        if (t.isEmpty() && imgs.isEmpty()) return
        text = if (t.isEmpty()) "(image)" else t
        sendImages = imgs
        draft = ""
        draftImageDataUrls.clear()
        clearChatFailure()
        turns.add(
          ChatTurn(
            role = ChatTurn.Role.User,
            text = text,
            imageDataUrls = sendImages.takeIf { it.isNotEmpty() },
          ),
        )
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
        text = user.text
        sendImages = user.imageDataUrls.orEmpty()
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
          when (backend) {
            BackendKind.ControlPlane -> sendPlane(modelId, model, assistantIndex, assistant)
            BackendKind.Playground ->
              sendPlayground(modelId, model, text, assistantIndex, assistant, sendImages)
          }
          if (backend == BackendKind.ControlPlane) refreshAccount()
          persistCurrentSession()
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          if (backend == BackendKind.ControlPlane) handleAuthError(e)
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

  private suspend fun sendPlane(
    modelId: String,
    model: ControlPlaneModel?,
    assistantIndex: Int,
    assistant: ChatTurn,
  ) {
    val history = buildPlaneChatMessages(excludeAssistantIndex = assistantIndex)
    if (useStream && model?.streaming != false) {
      lastRequestCost = "Streamed · cost in balance"
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
      // Stream closed with no text (mobile partial body). Fall back once (iOS #33).
      val empty =
        turns.getOrNull(assistantIndex)?.text.isNullOrEmpty()
      if (empty) {
        val res =
          withContext(Dispatchers.IO) {
            client.chatCompletions(
              ControlPlaneChatRequest(model = modelId, messages = history, stream = false),
            )
          }
        val text = res.response.firstContent.orEmpty()
        if (text.isEmpty()) throw PrismError.Server("Empty stream completion")
        turns[assistantIndex] = assistant.copy(text = text)
        lastRequestCost = res.meters.costDescription()
      }
    } else {
      val res =
        withContext(Dispatchers.IO) {
          client.chatCompletions(
            ControlPlaneChatRequest(model = modelId, messages = history, stream = false),
          )
        }
      val reply = res.response.firstContent.orEmpty()
      turns[assistantIndex] = assistant.copy(text = reply)
      lastRequestCost = res.meters.costDescription()
    }
  }

  private suspend fun sendPlayground(
    modelId: String,
    model: ControlPlaneModel?,
    userText: String,
    assistantIndex: Int,
    assistant: ChatTurn,
    imageDataUrls: List<String> = emptyList(),
  ) {
    val atts =
      imageDataUrls.takeIf { it.isNotEmpty() }?.map { ChatAttachment.image(dataURL = it) }
    val body =
      PlaygroundChatRequest(
        model = modelId,
        userInput = userText,
        conversationId = playgroundConversationId,
        attachments = atts,
      )
    if (useStream && model?.streaming != false) {
      withContext(Dispatchers.IO) {
        playground.chatStreamEvents(body)
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
                withContext(Dispatchers.Main) {
                  val full = ev.fullText
                  if (full != null) {
                    val cur = turns[assistantIndex]
                    if (cur.text.isEmpty()) {
                      turns[assistantIndex] = cur.copy(text = full)
                    }
                  }
                  ev.conversationId?.takeIf { it.isNotBlank() }?.let { cid ->
                    playgroundConversationId = cid
                  }
                }
              }
              is ChatStreamEvent.Error -> throw PrismError.Server(ev.message)
              is ChatStreamEvent.Unknown -> Unit
            }
          }
      }
      if (turns.getOrNull(assistantIndex)?.text.isNullOrEmpty()) {
        val res =
          withContext(Dispatchers.IO) {
            playground.chat(body)
          }
        val out = res.output.orEmpty()
        if (out.isEmpty()) throw PrismError.Server("Empty stream completion")
        turns[assistantIndex] = assistant.copy(text = out)
        res.conversationId?.takeIf { it.isNotBlank() }?.let {
          playgroundConversationId = it
        }
      }
    } else {
      val res =
        withContext(Dispatchers.IO) {
          playground.chat(body)
        }
      turns[assistantIndex] = assistant.copy(text = res.output.orEmpty())
      res.conversationId?.takeIf { it.isNotBlank() }?.let {
        playgroundConversationId = it
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

  /** Snap [videoDurationSeconds] into the selected model's legal range. */
  fun clampVideoDurationToSelectedModel() {
    val mid = selectedVideoModelId ?: return
    videoDurationSeconds = VideoClipDuration.limits(mid).clamp(videoDurationSeconds)
  }

  fun selectVideoModel(modelId: String) {
    selectedVideoModelId = modelId
    clampVideoDurationToSelectedModel()
    persistUIPrefs()
  }

  fun setVideoDurationSeconds(seconds: Int) {
    val mid = selectedVideoModelId.orEmpty()
    videoDurationSeconds = VideoClipDuration.limits(mid).clamp(seconds)
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
    val durationSec = VideoClipDuration.limits(modelId).clamp(videoDurationSeconds)
    videoDurationSeconds = durationSec
    val durationWire = VideoClipDuration.wire(modelId, durationSec)
    mediaJob?.cancel()
    mediaJob =
      viewModelScope.launch {
        mediaBusy = true
        mediaError = null
        mediaStatus =
          "Generating $modelId · ${durationSec}s clip · often 1-4 min. Stay in Prism if you can; " +
            "job continues on the plane after accept (lock OK once job id shows)."
        NotificationHelper.ensureChannels(appContext)
        startMediaTimer()
        try {
          val res =
            withContext(Dispatchers.IO) {
              client.generateVideo(
                model = modelId,
                prompt = prompt.ifEmpty { null },
                image = image,
                async = true,
                duration = durationWire,
              )
            }
          if (res.isAsyncAccept) {
            val id = res.id!!
            secrets.set(SecretStoreKeys.PENDING_VIDEO_JOB_ID, id)
            secrets.set(SecretStoreKeys.PENDING_VIDEO_JOB_MODEL, modelId)
            mediaStatus = "Plane job ${id.take(12)}… · runs on plane (lock OK)"
            finishVideoJob(id, modelId, prompt)
          } else {
            clearPendingVideoJob()
            applyVideoResult(res.video, modelId, prompt)
          }
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) {
            if (!secrets.get(SecretStoreKeys.PENDING_VIDEO_JOB_ID).isNullOrBlank()) {
              mediaStatus = "Plane job continues · re-checks when app is active"
              mediaError = null
            } else {
              mediaError = "Cancelled"
              mediaStatus = "Cancelled after ${mediaElapsedSeconds}s"
            }
            return@launch
          }
          handleAuthError(e)
          if (!secrets.get(SecretStoreKeys.PENDING_VIDEO_JOB_ID).isNullOrBlank()) {
            mediaStatus = "Plane job continues · re-checks when app is active"
            mediaError = null
          } else {
            mediaError = e.toUserMessage()
            mediaStatus = "Failed after ${mediaElapsedSeconds}s · prompt kept for Retry"
            NotificationHelper.notifyMedia(
              appContext,
              title = "Video failed",
              body = mediaError ?: "Video failed",
              success = false,
            )
          }
        } finally {
          stopMediaTimer()
          mediaBusy = false
        }
      }
  }

  private suspend fun finishVideoJob(id: String, modelId: String, prompt: String) {
    val job =
      withContext(Dispatchers.IO) {
        client.waitForJob(id, timeoutMs = ControlPlaneClient.JOB_POLL_TIMEOUT_MS)
      }
    if (!job.isTerminal) throw PrismError.Server("Job still running on the plane")
    if (!job.isSuccess) {
      clearPendingVideoJob()
      throw PrismError.Server(job.error?.message ?: job.error?.code ?: "Video job failed")
    }
    val videoUrl = job.result?.video
    if (videoUrl.isNullOrBlank()) {
      clearPendingVideoJob()
      throw PrismError.Server("Empty video in job result")
    }
    withContext(Dispatchers.IO) { client.waitForMediaReady(videoUrl) }
    clearPendingVideoJob()
    applyVideoResult(videoUrl, job.result?.model ?: modelId, prompt)
  }

  private fun applyVideoResult(videoUrl: String?, modelId: String, prompt: String) {
    lastVideoUrl = videoUrl
    mediaStatus = "Video ready · ${mediaElapsedSeconds}s"
    pushMediaHistory(
      MediaHistoryItem(
        kind = MediaKind.Video,
        model = modelId,
        prompt = prompt.ifEmpty { "(image-only)" },
        videoUrl = videoUrl,
      ),
    )
    NotificationHelper.notifyMedia(
      appContext,
      title = "Video ready",
      body = "$modelId finished in ${mediaElapsedSeconds}s",
      success = true,
    )
    refreshAccount()
  }

  private fun clearPendingVideoJob() {
    secrets.set(SecretStoreKeys.PENDING_VIDEO_JOB_ID, null)
    secrets.set(SecretStoreKeys.PENDING_VIDEO_JOB_MODEL, null)
  }

  fun retryLastVideo() {
    if (videoPrompt.trim().isEmpty() && videoImageRef.trim().isEmpty()) return
    generateVideo()
  }

  fun cancelMedia() {
    mediaJob?.cancel()
    mediaJob = null
    // Explicit cancel drops tracking (job may still finish server-side).
    clearPendingVideoJob()
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

  fun clearMediaHistory() {
    mediaHistory.clear()
  }

  /**
   * Attach a photo (JPEG/PNG bytes as data URL) to the next chat send.
   * Cap 3 images / ~3 MiB each after re-compress.
   */
  fun attachChatImageBytes(bytes: ByteArray, maxBytes: Int = 3 * 1024 * 1024) {
    if (draftImageDataUrls.size >= 3) {
      errorMessage = "At most 3 images per message."
      return
    }
    var jpeg = bytes
    if (jpeg.size > maxBytes) {
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      if (bmp == null) {
        errorMessage = "Image is too large (max ~3 MB)."
        return
      }
      jpeg =
        ByteArrayOutputStream().use { bos ->
          var q = 80
          while (q >= 40) {
            bos.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            if (bos.size() <= maxBytes) break
            q -= 15
          }
          bos.toByteArray()
        }
      if (!bmp.isRecycled) bmp.recycle()
      if (jpeg.size > maxBytes) {
        errorMessage = "Image is too large (max ~3 MB after compress)."
        return
      }
    }
    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    draftImageDataUrls.add("data:image/jpeg;base64,$b64")
  }

  fun removeDraftImage(at: Int) {
    if (at in draftImageDataUrls.indices) draftImageDataUrls.removeAt(at)
  }

  fun clearDraftImages() {
    draftImageDataUrls.clear()
  }

  /** Pull playground server conversation list into local sessions (playground only). */
  fun syncPlaygroundConversations() {
    if (backend != BackendKind.Playground || !playgroundAuthenticated) {
      serverSyncMessage = "Sign in to the playground to sync cloud chats."
      return
    }
    viewModelScope.launch {
      serverSyncBusy = true
      serverSyncMessage = null
      try {
        val remote =
          withContext(Dispatchers.IO) {
            playground.listConversations()
          }
        var imported = 0
        for (item in remote.take(40)) {
          val cid = item.conversationId
          if (sessions.any { it.conversationId == cid }) continue
          val detail =
            withContext(Dispatchers.IO) {
              playground.getConversation(cid)
            }
          if (!detail.error.isNullOrBlank()) continue
          val newTurns = mutableListOf<ChatTurn>()
          for (row in detail.turns.orEmpty()) {
            val userIn = row.userInput.orEmpty()
            val out = row.resolvedOutput.orEmpty()
            if (userIn.isNotEmpty()) {
              newTurns.add(ChatTurn(role = ChatTurn.Role.User, text = userIn))
            }
            if (out.isNotEmpty()) {
              newTurns.add(
                ChatTurn(
                  role = ChatTurn.Role.Assistant,
                  text = out,
                  modelId = row.model,
                ),
              )
            }
          }
          val rawTitle = item.firstInput.orEmpty()
          val title =
            when {
              rawTitle.isEmpty() -> "Cloud chat"
              rawTitle.length <= 48 -> rawTitle
              else -> rawTitle.take(45) + "..."
            }
          sessions.add(
            ChatSession(
              title = title,
              turns = newTurns,
              conversationId = cid,
              selectedModelId = item.latestModel,
              compact = detail.compact,
            ),
          )
          imported += 1
        }
        sessions.sortByDescending { it.updatedAtMs }
        trimSessions()
        saveSessionsToDisk()
        serverSyncMessage =
          if (imported == 0) {
            "Already up to date with playground (${remote.size} cloud chats)."
          } else {
            "Imported $imported chat(s) from playground."
          }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        serverSyncMessage = e.toUserMessage()
      } finally {
        serverSyncBusy = false
      }
    }
  }

  /** Export all local sessions as JSON bytes (share / backup). */
  fun exportSessionsJson(): ByteArray {
    persistCurrentSession()
    return sessionStore.exportJson(sessions.toList(), currentSessionId)
  }

  /** Decode without applying (import confirmation UX). */
  fun previewImportSessionsJson(data: ByteArray): ChatImportPreview =
    sessionStore.previewFromJson(data)

  /**
   * Import sessions from JSON.
   * [replace] false = merge by id (file wins); true = discard local list first.
   */
  fun importSessionsJson(data: ByteArray, replace: Boolean = false) {
    try {
      persistCurrentSession()
      val next =
        if (replace) {
          sessionStore.replaceFromJson(data)
        } else {
          sessionStore.mergeFromJson(sessions.toList(), data)
        }
      sessions.clear()
      sessions.addAll(next)
      ensureCurrentSession()
      saveSessionsToDisk()
      banner =
        if (replace) {
          "Replaced local chats (${next.size} total)."
        } else {
          "Merged chats (${next.size} total)."
        }
    } catch (e: Exception) {
      errorMessage = e.message ?: "Could not import sessions"
    }
  }

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
          val imgs = turn.imageDataUrls.orEmpty()
          if (turn.text.isNotEmpty() || imgs.isNotEmpty()) {
            out.add(
              ControlPlaneChatMessage(
                role = "user",
                content = turn.text.ifEmpty { " " },
                imageDataUrls = imgs.takeIf { it.isNotEmpty() },
              ),
            )
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
  private var musicPlayer: android.media.MediaPlayer? = null
  private var speechJob: Job? = null
  private var musicJob: Job? = null
  var isSpeechPlaying by mutableStateOf(false)
    private set
  var isMusicPlaying by mutableStateOf(false)
    private set

  val canSpeakText: Boolean
    get() =
      canUseMediaDoors && speechModels.any { it.spendable != false } && !speechBusy

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
    stopSpeechPlayback()
    speechJob?.cancel()
    speechJob =
      viewModelScope.launch {
        speechBusy = true
        speechError = null
        speechStatus = "Generating speech… (plane job after accept)"
        try {
          val res =
            withContext(Dispatchers.IO) {
              client.generateSpeech(model = modelId, input = input, async = true)
            }
          if (res.isAsyncAccept) {
            val id = res.id!!
            secrets.set(SecretStoreKeys.PENDING_SPEECH_JOB_ID, id)
            secrets.set(SecretStoreKeys.PENDING_SPEECH_JOB_MODEL, modelId)
            speechStatus = "Plane job ${id.take(12)}…"
            finishSpeechJob(id, modelId)
          } else {
            clearPendingSpeechJob()
            applySpeechResult(res.audioBase64, res.format ?: "mp3", modelId)
          }
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) {
            if (!secrets.get(SecretStoreKeys.PENDING_SPEECH_JOB_ID).isNullOrBlank()) {
              speechStatus = "Plane job continues · re-checks when active"
              speechError = null
            }
            return@launch
          }
          autoPlaySpeechAfterGenerate = false
          handleAuthError(e)
          if (!secrets.get(SecretStoreKeys.PENDING_SPEECH_JOB_ID).isNullOrBlank()) {
            speechStatus = "Plane job continues · re-checks when active"
            speechError = null
          } else {
            speechError = e.toUserMessage()
            speechStatus = null
          }
        } finally {
          speechBusy = false
        }
      }
  }

  private suspend fun finishSpeechJob(id: String, modelId: String) {
    val job =
      withContext(Dispatchers.IO) {
        client.waitForJob(
          id,
          pollIntervalMs = 3_000,
          timeoutMs = ControlPlaneClient.SPEECH_JOB_POLL_TIMEOUT_MS,
        )
      }
    if (!job.isTerminal) throw PrismError.Server("Job still running on the plane")
    if (!job.isSuccess) {
      clearPendingSpeechJob()
      throw PrismError.Server(job.error?.message ?: job.error?.code ?: "Speech job failed")
    }
    val b64 = job.result?.audioBase64 ?: job.result?.audio
    val fmt = job.result?.format ?: "mp3"
    // audio may be data URL or raw base64
    val payload =
      when {
        b64.isNullOrBlank() -> null
        b64.startsWith("http") -> null // stream URL only; store as status
        else -> b64
      }
    if (payload == null && job.result?.audio?.startsWith("http") == true) {
      clearPendingSpeechJob()
      throw PrismError.Server("Speech job returned URL only; open not wired")
    }
    if (payload.isNullOrBlank()) {
      clearPendingSpeechJob()
      throw PrismError.Server("Empty speech in job result")
    }
    clearPendingSpeechJob()
    applySpeechResult(payload, fmt, job.result?.model ?: modelId)
  }

  private fun applySpeechResult(audioBase64: String?, format: String, modelId: String) {
    lastSpeechBase64 = audioBase64
    lastSpeechFormat = format
    speechStatus = "Speech ready · $modelId"
    refreshAccount()
    if (autoPlaySpeechAfterGenerate) {
      autoPlaySpeechAfterGenerate = false
      playLastSpeech()
    }
  }

  private fun clearPendingSpeechJob() {
    secrets.set(SecretStoreKeys.PENDING_SPEECH_JOB_ID, null)
    secrets.set(SecretStoreKeys.PENDING_SPEECH_JOB_MODEL, null)
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
    if (isSpeechPlaying) {
      stopSpeechPlayback()
      return
    }
    try {
      stopMusicPlayback()
      speechPlayer?.release()
      speechPlayer =
        MediaUtils.playAudioBase64(appContext, b64, lastSpeechFormat ?: "mp3")?.also { p ->
          p.setOnCompletionListener {
            isSpeechPlaying = false
            speechStatus = "Speech ready"
          }
        }
      isSpeechPlaying = speechPlayer != null
      speechStatus = if (isSpeechPlaying) "Playing…" else speechStatus
    } catch (e: Exception) {
      speechError = e.message ?: "Playback failed"
      isSpeechPlaying = false
    }
  }

  fun stopSpeechPlayback() {
    try {
      speechPlayer?.stop()
    } catch (_: Exception) {
    }
    try {
      speechPlayer?.release()
    } catch (_: Exception) {
    }
    speechPlayer = null
    isSpeechPlaying = false
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

  /**
   * Live STT via plane WebSocket (`GET /v1/stt/stream`, Bearer pcp_).
   * Streams linear16 PCM @ 16 kHz; partials in [liveSttPartial]; stop(commit=true) → draft.
   */
  fun startLiveStt() {
    if (!hasDeviceKey || liveSttRunning || speechBusy || backend != BackendKind.ControlPlane) {
      if (backend != BackendKind.ControlPlane) {
        speechError = "Live STT needs the control plane."
        errorMessage = "Live STT needs the control plane."
      }
      return
    }
    if (!isNetworkSatisfied) {
      speechError = "No network connection. Reconnect and try again."
      errorMessage = "No network connection."
      return
    }
    stopLiveStt(commit = false)
    speechError = null
    liveSttFinals.clear()
    liveSttPartial = ""
    liveSttStatus = "Connecting…"
    speechStatus = "Live STT connecting…"
    val session =
      LiveSttSession(
        client = client,
        onPartial = { t ->
          viewModelScope.launch {
            liveSttPartial = t
            liveSttStatus = "Listening…"
            speechStatus = "Listening…"
          }
        },
        onFinal = { t ->
          viewModelScope.launch {
            val trimmed = t.trim()
            if (trimmed.isNotEmpty()) liveSttFinals.add(trimmed)
            liveSttPartial = ""
            liveSttStatus = "Listening…"
            speechStatus = "Live final"
          }
        },
        onError = { msg ->
          viewModelScope.launch {
            speechError = msg
            errorMessage = msg
            speechStatus = null
            liveSttStatus = null
            liveSttRunning = false
          }
        },
        onClosed = {
          viewModelScope.launch {
            liveSttRunning = false
            if (liveSttStatus == "Listening…" || liveSttStatus == "Connecting…") {
              liveSttStatus = "Stopped"
            }
          }
        },
      )
    liveSttSession = session
    liveSttRunning = true
    session.start()
  }

  /**
   * Stop live STT. When [commit] is true, append finals + partial into the chat draft.
   */
  fun stopLiveStt(commit: Boolean = true) {
    liveSttSession?.stop()
    liveSttSession = null
    val partial = liveSttPartial.trim()
    if (commit) {
      val pieces = liveSttFinals.toList() + listOfNotNull(partial.takeIf { it.isNotEmpty() })
      val t = pieces.joinToString(" ").trim()
      if (t.isNotEmpty()) {
        lastTranscript = t
        draft = if (draft.isBlank()) t else "$draft $t"
        banner = "Live transcript added to draft"
      }
    }
    liveSttFinals.clear()
    liveSttPartial = ""
    liveSttRunning = false
    liveSttStatus = null
    if (speechStatus?.startsWith("Live") == true || speechStatus == "Listening…") {
      speechStatus = "Live STT stopped"
    }
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
    stopMusicPlayback()
    musicJob?.cancel()
    musicJob =
      viewModelScope.launch {
        musicBusy = true
        musicError = null
        musicStatus =
          "Generating $modelId · often 2-4 min (up to ~5 with lyrics). " +
            "Job continues on the plane after accept (lock OK)."
        NotificationHelper.ensureChannels(appContext)
        try {
          val res =
            withContext(Dispatchers.IO) {
              client.generateMusic(
                model = modelId,
                prompt = prompt,
                lyrics = musicLyrics.trim().ifEmpty { null },
                async = true,
              )
            }
          if (res.isAsyncAccept) {
            val id = res.id!!
            secrets.set(SecretStoreKeys.PENDING_MUSIC_JOB_ID, id)
            secrets.set(SecretStoreKeys.PENDING_MUSIC_JOB_MODEL, modelId)
            musicStatus = "Plane job ${id.take(12)}… · runs on plane (lock OK)"
            finishMusicJob(id, modelId)
          } else {
            clearPendingMusicJob()
            applyMusicResult(res, modelId)
          }
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) {
            if (!secrets.get(SecretStoreKeys.PENDING_MUSIC_JOB_ID).isNullOrBlank()) {
              musicStatus = "Plane job continues · re-checks when app is active"
              musicError = null
            } else {
              musicError = "Cancelled"
              musicStatus = null
            }
            return@launch
          }
          handleAuthError(e)
          if (!secrets.get(SecretStoreKeys.PENDING_MUSIC_JOB_ID).isNullOrBlank()) {
            musicStatus = "Plane job continues · re-checks when app is active"
            musicError = null
          } else {
            musicError = e.toUserMessage()
            musicStatus = null
            NotificationHelper.notifyMedia(
              appContext,
              title = "Music failed",
              body = musicError ?: "Music failed",
              success = false,
            )
          }
        } finally {
          musicBusy = false
        }
      }
  }

  fun cancelMusic() {
    musicJob?.cancel()
    musicJob = null
    clearPendingMusicJob()
    musicBusy = false
    musicStatus = "Cancelled"
    musicError = "Cancelled"
  }

  private suspend fun finishMusicJob(id: String, modelId: String) {
    val job =
      withContext(Dispatchers.IO) {
        client.waitForJob(id, timeoutMs = ControlPlaneClient.JOB_POLL_TIMEOUT_MS)
      }
    if (!job.isTerminal) throw PrismError.Server("Job still running on the plane")
    if (!job.isSuccess) {
      clearPendingMusicJob()
      throw PrismError.Server(job.error?.message ?: job.error?.code ?: "Music job failed")
    }
    clearPendingMusicJob()
    val audio = job.result?.audio
    lastMusicUrl = audio?.takeIf { it.startsWith("http") }
    lastMusicBase64 =
      audio?.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }
        ?: job.result?.audioBase64
    musicStatus = "Music ready · ${job.result?.model ?: modelId}"
    NotificationHelper.notifyMedia(
      appContext,
      title = "Music ready",
      body = musicStatus ?: "Music ready",
      success = true,
    )
    refreshAccount()
  }

  private fun applyMusicResult(res: org.skyphusion.prism.MusicGenerationResponse, modelId: String) {
    lastMusicUrl = res.audioUrl
    lastMusicBase64 =
      res.audio?.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }
    musicStatus = "Music ready · ${res.model ?: modelId}"
    NotificationHelper.notifyMedia(
      appContext,
      title = "Music ready",
      body = musicStatus ?: "Music ready",
      success = true,
    )
    refreshAccount()
  }

  private fun clearPendingMusicJob() {
    secrets.set(SecretStoreKeys.PENDING_MUSIC_JOB_ID, null)
    secrets.set(SecretStoreKeys.PENDING_MUSIC_JOB_MODEL, null)
  }

  fun playLastMusic() {
    if (isMusicPlaying) {
      stopMusicPlayback()
      return
    }
    val b64 = lastMusicBase64
    if (b64 != null) {
      try {
        stopSpeechPlayback()
        musicPlayer?.release()
        musicPlayer =
          MediaUtils.playAudioBase64(appContext, b64, "mp3")?.also { p ->
            p.setOnCompletionListener {
              isMusicPlaying = false
              musicStatus = musicStatus?.removePrefix("Playing… · ") ?: "Music ready"
            }
          }
        isMusicPlaying = musicPlayer != null
        if (isMusicPlaying) musicStatus = "Playing…"
      } catch (e: Exception) {
        musicError = e.message ?: "Playback failed"
        isMusicPlaying = false
      }
      return
    }
    musicError = "No inline audio to play (open URL if available)."
  }

  fun stopMusicPlayback() {
    try {
      musicPlayer?.stop()
    } catch (_: Exception) {
    }
    try {
      musicPlayer?.release()
    } catch (_: Exception) {
    }
    musicPlayer = null
    isMusicPlaying = false
  }

  /**
   * Always re-query plane for any pending Workflow job.
   * Does not gate on busy flags (iOS forceSyncPendingJobs).
   */
  fun forceSyncPendingJobs() {
    if (!hasDeviceKey) return
    viewModelScope.launch {
      secrets.get(SecretStoreKeys.PENDING_MUSIC_JOB_ID)?.takeIf { it.isNotBlank() }?.let { id ->
        syncOnePendingMusicJob(id)
      }
      secrets.get(SecretStoreKeys.PENDING_VIDEO_JOB_ID)?.takeIf { it.isNotBlank() }?.let { id ->
        syncOnePendingVideoJob(id)
      }
      secrets.get(SecretStoreKeys.PENDING_SPEECH_JOB_ID)?.takeIf { it.isNotBlank() }?.let { id ->
        syncOnePendingSpeechJob(id)
      }
    }
  }

  private suspend fun syncOnePendingMusicJob(id: String) {
    val model =
      secrets.get(SecretStoreKeys.PENDING_MUSIC_JOB_MODEL) ?: "music"
    try {
      val job = withContext(Dispatchers.IO) { client.getJob(id) }
      if (job.isTerminal) {
        musicJob?.cancel()
        musicBusy = true
        try {
          if (job.isSuccess) {
            clearPendingMusicJob()
            val audio = job.result?.audio
            lastMusicUrl = audio?.takeIf { it.startsWith("http") }
            lastMusicBase64 =
              audio?.takeIf { !it.startsWith("http") } ?: job.result?.audioBase64
            musicStatus = "Music ready · ${job.result?.model ?: model}"
            musicError = null
            NotificationHelper.notifyMedia(appContext, "Music ready", musicStatus!!, true)
            refreshAccount()
          } else {
            clearPendingMusicJob()
            musicError = job.error?.message ?: job.error?.code ?: "Music job failed"
            musicStatus = "Failed"
            NotificationHelper.notifyMedia(appContext, "Music failed", musicError!!, false)
          }
        } finally {
          musicBusy = false
        }
        return
      }
      // Still running: restart poll
      musicJob?.cancel()
      musicBusy = true
      musicError = null
      musicStatus = "Plane job ${id.take(12)}… · still running"
      musicJob =
        viewModelScope.launch {
          try {
            finishMusicJob(id, model)
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
              musicStatus = "Plane job continues · re-checks when active"
            } else if (!secrets.get(SecretStoreKeys.PENDING_MUSIC_JOB_ID).isNullOrBlank()) {
              musicStatus = "Plane job continues · re-checks when active"
            } else {
              musicError = e.toUserMessage()
              musicStatus = "Failed"
            }
          } finally {
            musicBusy = false
          }
        }
    } catch (_: Exception) {
      musicStatus = "Plane job ${id.take(12)}… · re-check pending"
    }
  }

  private suspend fun syncOnePendingVideoJob(id: String) {
    val model = secrets.get(SecretStoreKeys.PENDING_VIDEO_JOB_MODEL) ?: "video"
    val prompt = videoPrompt.trim()
    try {
      val job = withContext(Dispatchers.IO) { client.getJob(id) }
      if (job.isTerminal) {
        mediaJob?.cancel()
        mediaBusy = true
        startMediaTimer()
        try {
          if (job.isSuccess) {
            val videoUrl = job.result?.video
            if (!videoUrl.isNullOrBlank()) {
              withContext(Dispatchers.IO) { client.waitForMediaReady(videoUrl) }
              clearPendingVideoJob()
              applyVideoResult(videoUrl, job.result?.model ?: model, prompt)
              mediaError = null
            } else {
              clearPendingVideoJob()
              mediaError = "Empty video in job result"
              mediaStatus = "Failed"
            }
          } else {
            clearPendingVideoJob()
            mediaError = job.error?.message ?: job.error?.code ?: "Video job failed"
            mediaStatus = "Failed"
            NotificationHelper.notifyMedia(appContext, "Video failed", mediaError!!, false)
          }
        } finally {
          stopMediaTimer()
          mediaBusy = false
        }
        return
      }
      mediaJob?.cancel()
      mediaBusy = true
      mediaError = null
      mediaStatus = "Plane job ${id.take(12)}… · still running"
      startMediaTimer()
      mediaJob =
        viewModelScope.launch {
          try {
            finishVideoJob(id, model, prompt)
          } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException &&
              secrets.get(SecretStoreKeys.PENDING_VIDEO_JOB_ID).isNullOrBlank()
            ) {
              mediaError = e.toUserMessage()
              mediaStatus = "Failed"
            } else {
              mediaStatus = "Plane job continues · re-checks when active"
              mediaError = null
            }
          } finally {
            stopMediaTimer()
            mediaBusy = false
          }
        }
    } catch (_: Exception) {
      mediaStatus = "Plane job ${id.take(12)}… · re-check pending"
    }
  }

  private suspend fun syncOnePendingSpeechJob(id: String) {
    val model = secrets.get(SecretStoreKeys.PENDING_SPEECH_JOB_MODEL) ?: "speech"
    try {
      val job = withContext(Dispatchers.IO) { client.getJob(id) }
      if (job.isTerminal) {
        speechJob?.cancel()
        speechBusy = true
        try {
          if (job.isSuccess) {
            clearPendingSpeechJob()
            val b64 = job.result?.audioBase64 ?: job.result?.audio
            if (!b64.isNullOrBlank() && !b64.startsWith("http")) {
              applySpeechResult(b64, job.result?.format ?: "mp3", job.result?.model ?: model)
              speechError = null
            } else {
              speechError = "Empty speech in job result"
              speechStatus = "Failed"
            }
          } else {
            clearPendingSpeechJob()
            speechError = job.error?.message ?: job.error?.code ?: "Speech job failed"
            speechStatus = "Failed"
          }
        } finally {
          speechBusy = false
        }
        return
      }
      speechJob?.cancel()
      speechBusy = true
      speechError = null
      speechStatus = "Plane job ${id.take(12)}… · still running"
      speechJob =
        viewModelScope.launch {
          try {
            finishSpeechJob(id, model)
          } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException &&
              secrets.get(SecretStoreKeys.PENDING_SPEECH_JOB_ID).isNullOrBlank()
            ) {
              speechError = e.toUserMessage()
              speechStatus = "Failed"
            } else {
              speechStatus = "Plane job continues · re-checks when active"
              speechError = null
            }
          } finally {
            speechBusy = false
          }
        }
    } catch (_: Exception) {
      speechStatus = "Plane job ${id.take(12)}… · re-check pending"
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

    /** Empty-state chips; full self-contained prompts (never trailing blanks). */
    val starterPrompts: List<String> =
      listOf(
        "In plain language, explain how HTTPS keeps web traffic private.",
        "Summarize the tradeoffs between SQL and document databases in three short bullets.",
        "Write a two-sentence product blurb for a prepaid AI playground aimed at indie developers.",
        "List five practical steps to debug a REST API that returns 502 only under load.",
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
