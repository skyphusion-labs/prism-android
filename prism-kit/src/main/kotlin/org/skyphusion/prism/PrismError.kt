package org.skyphusion.prism

/**
 * Failures from the Prism control plane or playground HTTP clients.
 * Match the spirit of iOS PrismKit.PrismError (portable, no platform deps).
 */
sealed class PrismError(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause) {
  class InvalidUrl(url: String) : PrismError("Invalid URL: $url")

  class HttpStatus(val code: Int, val bodyMessage: String?) :
    PrismError(bodyMessage?.let { "HTTP $code: $it" } ?: "HTTP $code")

  class Decoding(detail: String, cause: Throwable? = null) :
    PrismError("Decode failed: $detail", cause)

  class Transport(detail: String, cause: Throwable? = null) :
    PrismError(detail, cause)

  data object Unauthenticated : PrismError("Not authenticated")

  class Server(detail: String) : PrismError(detail)

  class ClientRevoked : PrismError("Client key revoked; re-enroll")
}
