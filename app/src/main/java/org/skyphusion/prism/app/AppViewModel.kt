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

data class ChatTurn(
  val id: String = UUID.randomUUID().toString(),
  val role: Role,
  var text: String,
) {
  enum class Role { User, Assistant, System }
}

/**
 * Control-plane shell state: enroll, catalog, multi-turn chat (stream or not).
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
  var balance by mutableStateOf<String?>(null)
  var turns = mutableStateListOf<ChatTurn>()
    private set
  var draft by mutableStateOf("")
  var useStream by mutableStateOf(true)
  var isBusy by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<String?>(null)
  var banner by mutableStateOf("Control plane · $baseUrl")

  init {
    if (hasDeviceKey) {
      refreshAccount()
      refreshModels()
    }
  }

  fun enroll() {
    val token = enrollmentToken.trim()
    if (token.isEmpty()) {
      errorMessage = "Enrollment token required"
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
    val key = raw.trim()
    if (!key.startsWith("pcp_")) {
      errorMessage = "Key must start with pcp_"
      return
    }
    secrets.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, key)
    client.setClientKey(key)
    hasDeviceKey = true
    errorMessage = null
    banner = "Device key imported"
    refreshAccount()
    refreshModels()
  }

  fun clearDeviceKey() {
    secrets.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, null)
    client.setClientKey(null)
    hasDeviceKey = false
    models.clear()
    selectedModelId = null
    balance = null
    turns.clear()
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
        if (selectedModelId == null || list.none { it.id == selectedModelId }) {
          selectedModelId = list.firstOrNull { it.spendable != false && it.modality == "chat" }?.id
            ?: list.firstOrNull { it.spendable != false }?.id
            ?: list.firstOrNull()?.id
        }
      } catch (e: Exception) {
        handleAuthError(e)
        errorMessage = e.toUserMessage()
      } finally {
        isBusy = false
      }
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
  }

  fun send() {
    val text = draft.trim()
    val modelId = selectedModelId
    if (text.isEmpty() || modelId == null || isBusy) return
    val model = models.firstOrNull { it.id == modelId }
    if (model?.spendable == false) {
      errorMessage = "Model is not spendable on this plan"
      return
    }

    draft = ""
    turns.add(ChatTurn(role = ChatTurn.Role.User, text = text))
    val assistant = ChatTurn(role = ChatTurn.Role.Assistant, text = "")
    turns.add(assistant)
    val assistantIndex = turns.lastIndex

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
        handleAuthError(e)
        errorMessage = e.toUserMessage()
        if (turns.getOrNull(assistantIndex)?.text.isNullOrEmpty()) {
          turns.removeAt(assistantIndex)
        }
      } finally {
        isBusy = false
      }
    }
  }

  private fun handleAuthError(e: Exception) {
    if (e is PrismError.ClientRevoked || e is PrismError.Unauthenticated) {
      clearDeviceKey()
    }
  }

  private fun Exception.toUserMessage(): String =
    when (this) {
      is PrismError -> message ?: toString()
      else -> message ?: toString()
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
