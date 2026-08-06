package org.skyphusion.prism

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Thin JSON HTTP helper for the control plane.
 * Inject [OkHttpClient] in tests (MockWebServer).
 */
class HttpJson(
  baseUrl: String,
  val client: OkHttpClient = defaultClient(),
  val json: Json = prismJson,
) {
  val root: String = baseUrl.trimEnd('/')

  init {
    require(baseUrl.isNotBlank()) { "baseUrl required" }
  }

  fun url(path: String): String {
    val p = if (path.startsWith("/")) path else "/$path"
    return root + p
  }

  fun request(
    method: String,
    path: String,
    bodyJson: String? = null,
    bearer: String? = null,
    headers: Map<String, String> = emptyMap(),
  ): Request {
    val builder =
      Request.Builder()
        .url(url(path))
        .header("Accept", "application/json")
    for ((k, v) in headers) builder.header(k, v)
    if (bearer != null) builder.header("Authorization", "Bearer $bearer")

    val m = method.uppercase()
    when {
      m == "GET" || m == "HEAD" -> builder.method(m, null)
      bodyJson != null -> {
        builder.header("Content-Type", JSON_MEDIA.toString())
        builder.method(m, bodyJson.toRequestBody(JSON_MEDIA))
      }
      else -> builder.method(m, ByteArray(0).toRequestBody(null))
    }
    return builder.build()
  }

  fun execute(
    method: String,
    path: String,
    bodyJson: String? = null,
    bearer: String? = null,
    okStatuses: Set<Int> = (200..299).toSet(),
  ): Response {
    val req = request(method, path, bodyJson, bearer)
    val res =
      try {
        client.newCall(req).execute()
      } catch (e: IOException) {
        throw PrismError.Transport("Transport failed: ${e.message}", e)
      }
    if (res.code !in okStatuses) {
      val raw = res.body?.string().orEmpty()
      res.close()
      throw mapHttpError(res.code, raw)
    }
    return res
  }

  inline fun <reified B, reified T> send(
    method: String,
    path: String,
    body: B? = null,
    bearer: String? = null,
    okStatuses: Set<Int> = (200..299).toSet(),
  ): Pair<T, Map<String, List<String>>> {
    val bodyJson =
      if (body == null || method.uppercase() == "GET" || method.uppercase() == "HEAD") {
        null
      } else {
        // Use encodeDefaults=true so optional contract flags (stream, platform) ship on the wire.
        prismJsonEncode.encodeToString(body)
      }
    val res = execute(method, path, bodyJson, bearer, okStatuses)
    val raw = res.body?.string().orEmpty()
    val headers = res.headers.toMultimap()
    res.close()
    return try {
      json.decodeFromString<T>(raw) to headers
    } catch (e: Exception) {
      throw PrismError.Decoding(e.message ?: e.toString(), e)
    }
  }

  companion object {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Above plane non-chat ceiling (180s); iOS uses 200s. */
    fun nonChatClient(): OkHttpClient =
      defaultClient()
        .newBuilder()
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun mapHttpError(code: Int, raw: String): PrismError {
      val envelope =
        try {
          prismJson.decodeFromString<ErrorEnvelope>(raw)
        } catch (_: Exception) {
          null
        }
      val errCode = envelope?.error?.code ?: envelope?.code
      val message =
        envelope?.error?.message
          ?: envelope?.message
          ?: raw.takeIf { it.isNotBlank() }?.take(500)

      if (code == 401) {
        if (errCode == "client_revoked") return PrismError.ClientRevoked()
        return PrismError.Unauthenticated
      }
      // Prefer API error code in the message so prismUserFacingError can branch.
      val labeled =
        if (!errCode.isNullOrBlank() && !message.isNullOrBlank() && !message.contains(errCode)) {
          "$errCode: $message"
        } else {
          message
        }
      return PrismError.HttpStatus(code, labeled)
    }
  }
}
