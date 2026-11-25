package com.alruwaili.garminalbarr.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingClientWrapper(
    private val context: Context,
    private val subscriptionManager: SubscriptionManager
) {
    companion object {
        private const val TAG = "BillingWrapper"
        private const val MAX_RECONNECT_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 1000L
        val ACTIVE_SUBSCRIPTION_IDS = listOf(
            "com.alruwaili.garminalbarr.monthly",
            "com.alruwaili.garminalbarr.three_months",
            "com.alruwaili.garminalbarr.yearly"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var reconnectRetryCount = 0

    private val _connectionState = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _purchaseState = MutableStateFlow(false)
    val isPurchased: StateFlow<Boolean> = _purchaseState.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    handlePurchase(purchase)
                }
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                Log.d(TAG, "User cancelled purchase flow")
            } else {
                Log.e(TAG, "Purchase flow error: ${billingResult.debugMessage}")
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    @Volatile
    private var networkCallbackRegistered = false

    /**
     * يعيد محاولة الاتصال بـ Google Play عند رجوع الإنترنت (بعد رحلة طويلة بدون شبكة)،
     * بدل الاستسلام بعد 5 محاولات حتى يُعاد فتح التطبيق.
     */
    private fun registerNetworkReconnect() {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (!_connectionState.value) {
                        Log.d(TAG, "Network back — retrying BillingClient connection")
                        ensureConnected()
                    }
                }
            })
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }

    /** يبدأ اتصالاً جديداً إذا لم يكن متصلاً (يُستدعى عند رجوع الشبكة أو فتح صفحة الاشتراك). */
    fun ensureConnected() {
        if (_connectionState.value || billingClient.isReady) return
        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) return
        reconnectRetryCount = 0
        try {
            startConnection()
        } catch (e: Exception) {
            Log.w(TAG, "ensureConnected failed: ${e.message}")
        }
    }

    fun startConnection() {
        registerNetworkReconnect()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _connectionState.value = true
                    reconnectRetryCount = 0
                    Log.d(TAG, "BillingClient connected")
                    scope.launch {
                        queryActivePurchases()
                    }
                } else {
                    _connectionState.value = false
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                _connectionState.value = false
                Log.w(TAG, "BillingClient disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectRetryCount >= MAX_RECONNECT_RETRIES) {
            Log.w(TAG, "BillingClient max reconnect retries ($MAX_RECONNECT_RETRIES) reached — giving up")
            return
        }
        val delayMs = INITIAL_BACKOFF_MS * (1 shl reconnectRetryCount)
        reconnectRetryCount++
        Log.d(TAG, "BillingClient reconnect attempt $reconnectRetryCount/$MAX_RECONNECT_RETRIES in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (!_connectionState.value) {
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            _connectionState.value = true
                            reconnectRetryCount = 0
                            Log.d(TAG, "BillingClient reconnected successfully")
                            scope.launch {
                                queryActivePurchases()
                            }
                        } else {
                            Log.e(TAG, "BillingClient reconnect setup failed: ${billingResult.debugMessage}")
                            scheduleReconnect()
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        _connectionState.value = false
                        Log.w(TAG, "BillingClient disconnected again during reconnect")
                        scheduleReconnect()
                    }
                })
            }
        }
    }

    fun endConnection() {
        billingClient.endConnection()
        _connectionState.value = false
    }

    suspend fun queryActivePurchases(): Boolean {
        if (!_connectionState.value) {
            Log.w(TAG, "BillingClient not ready for purchase query")
            return false
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = billingClient.queryPurchasesAsync(params)

        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val activePurchase = result.purchasesList.find { purchase ->
                purchase.products.any { it in ACTIVE_SUBSCRIPTION_IDS } && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }

            if (activePurchase != null) {
                if (activePurchase.isAcknowledged) {
                    grantPurchaseAccess(activePurchase)
                } else {
                    grantPurchaseAccess(activePurchase)
                    scope.launch {
                        val success = acknowledgePurchase(activePurchase)
                        if (!success) {
                            Log.w(TAG, "Acknowledgement failed during queryActivePurchases — Google Play will retry automatically. Access retained.")
                        }
                    }
                }
                return true
            } else {
                subscriptionManager.isPurchased = false
                _purchaseState.value = false
                Log.d(TAG, "No active purchase found")
                subscriptionManager.updateVerificationTimestamp()
                return false
            }
        } else {
            Log.e(TAG, "queryPurchasesAsync failed: ${result.billingResult.debugMessage}")
            return false
        }
    }

    suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails>? {
        if (!_connectionState.value) {
            Log.e(TAG, "Cannot query product details — BillingClient not ready")
            return null
        }

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val detailsResult = billingClient.queryProductDetails(queryParams)
        return if (detailsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            detailsResult.productDetailsList
        } else {
            Log.e(TAG, "queryProductDetails failed: ${detailsResult.billingResult.debugMessage}")
            null
        }
    }

    suspend fun launchPurchaseFlow(activity: Activity, productId: String) {
        if (!_connectionState.value) {
            Log.e(TAG, "Cannot launch purchase — BillingClient not ready")
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val detailsResult = billingClient.queryProductDetails(queryParams)

        if (detailsResult.billingResult.responseCode != BillingClient.BillingResponseCode.OK || detailsResult.productDetailsList.isNullOrEmpty()) {
            Log.e(TAG, "Product details query failed: ${detailsResult.billingResult.debugMessage}")
            return
        }

        val productDetails = detailsResult.productDetailsList?.firstOrNull()
        if (productDetails == null) {
            Log.e(TAG, "Product details list is empty for product: $productId")
            return
        }
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken

        if (offerToken.isNullOrEmpty()) {
            Log.e(TAG, "No offer token found for product: $productId. Aborting purchase flow.")
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        withContext(Dispatchers.Main) {
            billingClient.launchBillingFlow(activity, billingFlowParams)
            Log.d(TAG, "Purchase flow launched")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.any { it in ACTIVE_SUBSCRIPTION_IDS }) return

        // Already acknowledged → safe to grant immediately
        if (purchase.isAcknowledged) {
            grantPurchaseAccess(purchase)
        } else {
            // Unacknowledged → optimistic grant, acknowledge, keep access on failure
            grantPurchaseAccess(purchase)
            scope.launch {
                val success = acknowledgePurchase(purchase)
                if (!success) {
                    Log.w(TAG, "Acknowledgement failed — Google Play will retry automatically. Access retained.")
                }
            }
        }
    }

    private fun grantPurchaseAccess(purchase: Purchase) {
        if (subscriptionManager.isTokenRevoked(purchase.purchaseToken)) {
            Log.w(TAG, "Purchase token revoked by server — access not granted")
            subscriptionManager.isPurchased = false
            _purchaseState.value = false
            return
        }
        subscriptionManager.isPurchased = true
        subscriptionManager.purchaseToken = purchase.purchaseToken
        // لا نمدد مدة السماح إلا بتحقق فعلي مع الإنترنت (نتيجة Play المخزنة أوفلاين قد تكون منتهية)
        if (TrialRepository.isNetworkAvailable(context) || subscriptionManager.getLastVerifiedEpoch() <= 0L) {
            subscriptionManager.updateVerificationTimestamp()
        }
        _purchaseState.value = true
        OrderReporter.report(context, purchase)
        com.alruwaili.garminalbarr.analytics.AnalyticsHelper.logPurchaseSuccess(
            purchase.products.firstOrNull() ?: "unknown",
            purchase.purchaseToken
        )
        Log.d(TAG, "Purchase access granted: ${purchase.products}")
    }

    private suspend fun acknowledgePurchase(purchase: Purchase): Boolean {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        val result = billingClient.acknowledgePurchase(params)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Purchase acknowledged successfully")
            true
        } else {
            Log.e(TAG, "Acknowledge failed: ${result.debugMessage}")
            false
        }
    }
}