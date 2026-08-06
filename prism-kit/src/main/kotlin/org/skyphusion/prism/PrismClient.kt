package org.skyphusion.prism

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Client for the **Prism playground Worker** (`play.skyphusion.org` or self-host).
 *
 * Public mode (`AUTH_MODE=public`) uses an httpOnly session cookie
 * (`__Host-prism_session`). This client keeps an in-memory cookie jar.
 * Access mode (self-host behind CF Access) can pass headers via [defaultHeaders].
 */
class PrismClient(
  val http: HttpJson,
  private val cookieJar: MemoryCookieJar = MemoryCookieJar(),
  var defaultHeaders: Map<String, String> = emptyMap(),
) {
  companion object {
    const val PRODUCTION_BASE_URL: String = "https://play.skyphusion.org"
    /** Cookie name set by the playground Worker (`src/session.ts`). */
    const val SESSION_COOKIE_NAME: String = "__Host-prism_session"

    fun create(
      baseUrl: String = PRODUCTION_BASE_URL,
      cookieJar: MemoryCookieJar = MemoryCookieJar(),
      defaultHeaders: Map<String, String> = emptyMap(),
      client: OkHttpClient? = null,
    ): PrismClient {
      val ok =
        (client ?: HttpJson.defaultClient())
          .newBuilder()
          .cookieJar(cookieJar)
          .build()
      return PrismClient(
        http = HttpJson(baseUrl = baseUrl, client = ok),
        cookieJar = cookieJar,
        defaultHeaders = defaultHeaders,
      )
    }

    fun encodePathSegment(segment: String): String =
      URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
  }

  fun exportSessionToken(): String? = cookieJar.cookieValue(SESSION_COOKIE_NAME)

  fun restoreSessionToken(token: String): Boolean {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return false
    val url = http.root.toHttpUrlOrNull() ?: return false
    cookieJar.setCookie(
      Cookie.Builder()
        .name(SESSION_COOKIE_NAME)
        .value(trimmed)
        .path("/")
        .secure()
        .hostOnlyDomain(url.host)
        .build(),
    )
    return true
  }

  fun clearSession() {
    cookieJar.clear()
  }

  // --- Health / catalog ---

  fun health(): ControlPlaneHealth {
    val (body, _) =
      http.send<Unit?, ControlPlaneHealth>(
        "GET",
        "/health",
        body = null,
        headers = defaultHeaders,
      )
    return body
  }

  /** Boot probe: models + auth mode + session flag (no session required). */
  fun models(): PlaygroundModelsResponse {
    val (body, _) =
      http.send<Unit?, PlaygroundModelsResponse>(
        "GET",
        "/api/models",
        body = null,
        headers = defaultHeaders,
      )
    return body
  }

  // --- Auth (public mode) ---

  fun signup(username: String, password: String): AuthSuccess {
    val (res, _) =
      http.send<AuthCredentials, AuthSuccess>(
        "POST",
        "/api/auth/signup",
        body = AuthCredentials(username, password),
        headers = defaultHeaders,
        okStatuses = setOf(200, 201),
      )
    res.error?.takeIf { it.isNotBlank() }?.let { throw PrismError.Server(it) }
    return res
  }

  fun login(username: String, password: String): AuthSuccess {
    val (res, _) =
      http.send<AuthCredentials, AuthSuccess>(
        "POST",
        "/api/auth/login",
        body = AuthCredentials(username, password),
        headers = defaultHeaders,
      )
    res.error?.takeIf { it.isNotBlank() }?.let { throw PrismError.Server(it) }
    return res
  }

  fun logout() {
    http.send<Unit?, AuthLogoutResponse>(
      "POST",
      "/api/auth/logout",
      body = null,
      headers = defaultHeaders,
    )
    clearSession()
  }

  // --- Chat ---

  fun chat(body: PlaygroundChatRequest): PlaygroundChatResponse {
    val (res, _) =
      http.send<PlaygroundChatRequest, PlaygroundChatResponse>(
        "POST",
        "/api/chat",
        body = body,
        headers = defaultHeaders,
      )
    res.error?.takeIf { it.isNotBlank() }?.let { throw PrismError.Server(it) }
    return res
  }

  /**
   * Streaming chat via SSE (`POST /api/chat/stream`).
   * Buffers the full body then parses (reliable for tests and mobile).
   */
  fun chatStream(body: PlaygroundChatRequest): List<ChatStreamEvent> {
    val json = prismJsonEncode.encodeToString(body)
    val headers = defaultHeaders + mapOf("Accept" to "text/event-stream")
    val res =
      http.execute(
        "POST",
        "/api/chat/stream",
        bodyJson = json,
        headers = headers,
      )
    val text = res.body?.string().orEmpty()
    res.close()
    return SseParser.parseChatEvents(text)
  }

  fun chatStreamText(body: PlaygroundChatRequest): Pair<String, PlaygroundChatResponse?> {
    val events = chatStream(body)
    val parts = StringBuilder()
    var final: PlaygroundChatResponse? = null
    for (e in events) {
      when (e) {
        is ChatStreamEvent.Delta -> parts.append(e.text)
        is ChatStreamEvent.Done -> {
          if (!e.fullText.isNullOrEmpty()) {
            final = PlaygroundChatResponse(output = e.fullText)
          }
        }
        is ChatStreamEvent.Error -> throw PrismError.Server(e.message)
        is ChatStreamEvent.Unknown -> Unit
      }
    }
    val joined = parts.toString()
    val out = final?.output
    if (!out.isNullOrEmpty()) return (joined.ifEmpty { out }) to final
    return joined to final
  }

  // --- Conversation compact (playground v0.175.7) ---

  fun compactConversation(
    id: String,
    keepRecent: Int = ConversationCompact.DEFAULT_KEEP_RECENT,
    model: String? = null,
  ): ConversationCompactResponse {
    val encoded = encodePathSegment(id)
    val (res, _) =
      http.send<ConversationCompactRequest, ConversationCompactResponse>(
        "POST",
        "/api/conversations/$encoded/compact",
        body = ConversationCompactRequest(keepRecent = keepRecent, model = model),
        headers = defaultHeaders,
      )
    res.error?.takeIf { it.isNotBlank() }?.let { throw PrismError.Server(it) }
    if (res.compact == null) throw PrismError.Server("Compact returned no state")
    return res
  }

  fun clearConversationCompact(id: String): ConversationCompactClearResponse {
    val encoded = encodePathSegment(id)
    val (res, _) =
      http.send<Unit?, ConversationCompactClearResponse>(
        "DELETE",
        "/api/conversations/$encoded/compact",
        body = null,
        headers = defaultHeaders,
      )
    res.error?.takeIf { it.isNotBlank() }?.let { throw PrismError.Server(it) }
    return res
  }
}

/** Thread-safe in-memory [CookieJar] for playground session cookies. */
class MemoryCookieJar : CookieJar {
  private val store = ConcurrentHashMap<String, Cookie>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    for (c in cookies) {
      store[key(c)] = c
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> =
    store.values.filter { it.matches(url) }

  fun cookieValue(name: String): String? =
    store.values.firstOrNull { it.name == name }?.value

  fun setCookie(cookie: Cookie) {
    store[key(cookie)] = cookie
  }

  fun clear() {
    store.clear()
  }

  private fun key(c: Cookie): String = "${c.name}|${c.domain}|${c.path}"
}

@Serializable
internal data class AuthCredentials(
  val username: String,
  val password: String,
)

@Serializable
internal data class AuthLogoutResponse(
  val ok: Boolean? = null,
)
