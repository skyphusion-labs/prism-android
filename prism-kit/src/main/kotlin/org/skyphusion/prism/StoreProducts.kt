package org.skyphusion.prism

/**
 * Play Billing / App Store credit pack catalog.
 * Keep in lockstep with prism-ios StoreProducts and plane src/store-products.ts.
 *
 * Redeem: `POST /v1/store/redeem` with platform=google_play (plane 0.4.16+).
 */
object StoreProducts {
  const val PACKAGE_NAME: String = "org.skyphusion.prism"

  const val CREDIT_5: String = "org.skyphusion.prism.credit.5"
  const val CREDIT_20: String = "org.skyphusion.prism.credit.20"
  const val CREDIT_50: String = "org.skyphusion.prism.credit.50"

  val allCreditPacks: List<String> = listOf(CREDIT_5, CREDIT_20, CREDIT_50)

  data class CreditPack(
    val productId: String,
    val creditUsd: Int,
    val referenceName: String,
  )

  val packs: List<CreditPack> =
    listOf(
      CreditPack(CREDIT_5, 5, "Credit 5 USD"),
      CreditPack(CREDIT_20, 20, "Credit 20 USD"),
      CreditPack(CREDIT_50, 50, "Credit 50 USD"),
    )

  fun creditUsd(productId: String): Int? = packs.firstOrNull { it.productId == productId }?.creditUsd
}
