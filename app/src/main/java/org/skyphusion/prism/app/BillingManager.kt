package org.skyphusion.prism.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.StoreProducts
import org.skyphusion.prism.prismUserFacingError
import kotlin.coroutines.resume

/**
 * Play Billing (consumable credit packs) + control-plane redeem.
 * Mirrors iOS StoreManager: load products → purchase → redeem → finish/consume.
 */
class BillingManager(
  context: Context,
  private val clientProvider: () -> ControlPlaneClient?,
  private val onRedeemed: suspend () -> Unit,
) : PurchasesUpdatedListener {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
  val products: StateFlow<List<ProductDetails>> = _products.asStateFlow()

  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy.asStateFlow()

  private val billingClient: BillingClient =
    BillingClient.newBuilder(appContext)
      .setListener(this)
      .enablePendingPurchases(
        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
      )
      .build()

  private var connected = false

  fun start() {
    if (billingClient.isReady) {
      connected = true
      scope.launch { queryProducts(); queryAndRedeemPending() }
      return
    }
    billingClient.startConnection(
      object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
          connected = result.responseCode == BillingClient.BillingResponseCode.OK
          if (connected) {
            scope.launch {
              queryProducts()
              queryAndRedeemPending()
            }
          } else {
            _error.value = "Billing setup failed (${result.responseCode}): ${result.debugMessage}"
          }
        }

        override fun onBillingServiceDisconnected() {
          connected = false
        }
      },
    )
  }

  fun end() {
    if (billingClient.isReady) billingClient.endConnection()
    connected = false
  }

  val sortedProducts: List<ProductDetails>
    get() =
      _products.value.sortedBy { details ->
        details.oneTimePurchaseOfferDetails?.priceAmountMicros ?: Long.MAX_VALUE
      }

  suspend fun queryProducts() {
    if (!connected && !billingClient.isReady) {
      _status.value = "Billing not ready"
      return
    }
    _busy.value = true
    _error.value = null
    try {
      val productList =
        StoreProducts.allCreditPacks.map { id ->
          QueryProductDetailsParams.Product.newBuilder()
            .setProductId(id)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        }
      val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
      val (result, details) =
        suspendCancellableCoroutine { cont ->
          billingClient.queryProductDetailsAsync(params) { br, list ->
            cont.resume(br to list)
          }
        }
      if (result.responseCode != BillingClient.BillingResponseCode.OK) {
        _error.value = "Product query failed (${result.responseCode}): ${result.debugMessage}"
        _products.value = emptyList()
      } else {
        _products.value = details
        _status.value =
          if (details.isEmpty()) {
            "No products returned. Create org.skyphusion.prism.credit.{5,20,50} in Play Console " +
              "(or use a license tester + published draft)."
          } else {
            "${details.size} credit pack(s) available"
          }
      }
    } finally {
      _busy.value = false
    }
  }

  fun launchPurchase(activity: Activity, product: ProductDetails) {
    val offer = product.oneTimePurchaseOfferDetails ?: run {
      _error.value = "Product has no one-time offer"
      return
    }
    val productParams =
      BillingFlowParams.ProductDetailsParams.newBuilder()
        .setProductDetails(product)
        .build()
    val flowParams =
      BillingFlowParams.newBuilder()
        .setProductDetailsParamsList(listOf(productParams))
        .build()
    _error.value = null
    _status.value = "Opening Play purchase…"
    val result = billingClient.launchBillingFlow(activity, flowParams)
    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
      _error.value = "Could not launch purchase (${result.responseCode}): ${result.debugMessage}"
    }
    // silence unused
    @Suppress("UNUSED_VARIABLE")
    val price = offer.formattedPrice
  }

  override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
    when (result.responseCode) {
      BillingClient.BillingResponseCode.OK -> {
        if (purchases.isNullOrEmpty()) {
          _status.value = "No purchases in update"
          return
        }
        scope.launch {
          for (p in purchases) handlePurchase(p)
        }
      }
      BillingClient.BillingResponseCode.USER_CANCELED -> {
        _status.value = "Purchase cancelled"
      }
      else -> {
        _error.value = "Purchase failed (${result.responseCode}): ${result.debugMessage}"
      }
    }
  }

  private suspend fun queryAndRedeemPending() {
    if (!billingClient.isReady) return
    val params =
      QueryPurchasesParams.newBuilder()
        .setProductType(BillingClient.ProductType.INAPP)
        .build()
    val (result, purchases) =
      suspendCancellableCoroutine { cont ->
        billingClient.queryPurchasesAsync(params) { br, list ->
          cont.resume(br to list)
        }
      }
    if (result.responseCode != BillingClient.BillingResponseCode.OK) return
    for (p in purchases) {
      if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
        handlePurchase(p)
      }
    }
  }

  private suspend fun handlePurchase(purchase: Purchase) {
    if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
    val productId = purchase.products.firstOrNull() ?: return
    val token = purchase.purchaseToken
    val plane = clientProvider()
    if (plane == null || plane.clientKey.isNullOrBlank()) {
      _error.value =
        "Purchase recorded but no device key. Import a pcp_ key, then reopen Settings to redeem."
      return
    }
    _busy.value = true
    _error.value = null
    _status.value = "Redeeming with control plane…"
    try {
      val res =
        withContext(Dispatchers.IO) {
          plane.redeemGooglePlay(purchaseToken = token, productId = productId)
        }
      // Consumable: consume so it can be bought again; also acknowledges.
      consume(purchase)
      val usd =
        res.creditGrantedMicroUsd?.let { String.format("$%.0f", it / 1_000_000.0) }
          ?: StoreProducts.creditUsd(productId)?.let { "$$it" }
          ?: productId
      _status.value =
        if (res.applied == true) {
          "Credit applied ($usd). Balance refresh…"
        } else {
          "Already redeemed. Balance refresh…"
        }
      onRedeemed()
    } catch (e: Exception) {
      // Do not consume on redeem failure — pending query will retry.
      _error.value = prismUserFacingError(e)
      _status.value = "Purchase verified by Play; credit apply failed. Will retry."
    } finally {
      _busy.value = false
    }
  }

  private suspend fun consume(purchase: Purchase) {
    if (!billingClient.isReady) return
    if (!purchase.isAcknowledged) {
      val ack =
        AcknowledgePurchaseParams.newBuilder()
          .setPurchaseToken(purchase.purchaseToken)
          .build()
      suspendCancellableCoroutine { cont ->
        billingClient.acknowledgePurchase(ack) { cont.resume(Unit) }
      }
    }
    val consume =
      ConsumeParams.newBuilder()
        .setPurchaseToken(purchase.purchaseToken)
        .build()
    suspendCancellableCoroutine { cont ->
      billingClient.consumeAsync(consume) { _, _ -> cont.resume(Unit) }
    }
  }
}
