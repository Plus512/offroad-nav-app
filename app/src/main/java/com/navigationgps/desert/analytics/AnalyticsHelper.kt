package com.navigationgps.desert.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Centralized Firebase Analytics event logger for funnel & business metric tracking.
 */
object AnalyticsHelper {

    private const val TAG = "AnalyticsHelper"
    private var firebaseAnalytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        try {
            firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)
            Log.d(TAG, "FirebaseAnalytics initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseAnalytics: ${e.message}", e)
        }
    }

    /**
     * Logs when a trial is created/started on the server.
     */
    fun logTrialStarted(deviceId: String, source: String) {
        val bundle = Bundle().apply {
            putString("device_id", deviceId)
            putString("source", source)
            putLong("timestamp_ms", System.currentTimeMillis())
        }
        firebaseAnalytics?.logEvent("trial_started", bundle)
        Log.d(TAG, "Logged event: trial_started ($source)")
    }

    /**
     * Logs when a user submits a VIP activation request.
     */
    fun logVipRequested(countryCode: String, deviceModel: String) {
        val bundle = Bundle().apply {
            putString("country_code", countryCode)
            putString("device_model", deviceModel)
            putLong("timestamp_ms", System.currentTimeMillis())
        }
        firebaseAnalytics?.logEvent("vip_requested", bundle)
        Log.d(TAG, "Logged event: vip_requested ($countryCode)")
    }

    /**
     * Logs when a purchase is successfully made and acknowledged.
     */
    fun logPurchaseSuccess(productId: String, purchaseToken: String) {
        val bundle = Bundle().apply {
            putString("product_id", productId)
            putString("purchase_token", purchaseToken)
            putLong("timestamp_ms", System.currentTimeMillis())
        }
        firebaseAnalytics?.logEvent("purchase_success", bundle)
        Log.d(TAG, "Logged event: purchase_success ($productId)")
    }

    /**
     * Logs when a paywall or subscription screen is viewed.
     */
    fun logPaywallImpression(screenName: String) {
        val bundle = Bundle().apply {
            putString("screen_name", screenName)
        }
        firebaseAnalytics?.logEvent("paywall_impression", bundle)
        Log.d(TAG, "Logged event: paywall_impression ($screenName)")
    }
}
