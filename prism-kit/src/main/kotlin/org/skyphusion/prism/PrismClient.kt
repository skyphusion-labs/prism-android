package org.skyphusion.prism

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
 *
 * Compact API (Worker v0.175.7):
 * - `POST /api/conversations/:id/compact`
 * - `DELETE /api/conversations/:id/compact`
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

    /** Build a client with a shared [MemoryCookieJar] (production + tests). */
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

    /** Percent-encode a path segment (conversation ids are mostly unreserved). */
    fun encodePathSegment(segment: String): String =
      URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
  }

  /** Current session token from the jar (after login or restore), if any. */
  fun exportSessionToken(): String? = cookieJar.cookieValue(SESSION_COOKIE_NAME)

  /** Re-inject a previously stored session token into the jar. */
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

  /** Drop session cookies for this base URL. */
  fun clearSession() {
    cookieJar.clear()
  }

  // --- Conversation compact (playground v0.175.7) ---

  /**
   * Summarize older turns on the Worker; next chat injects the summary instead of full history.
   * UI transcript is unchanged.
   */
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

  /** Clear compact state so the next turn uses full raw history again. */
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

/**
 * Thread-safe in-memory [CookieJar] for playground session cookies.
 * Also exposes get/set helpers for session restore.
 */
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
