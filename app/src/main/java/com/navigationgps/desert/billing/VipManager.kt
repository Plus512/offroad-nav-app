package com.navigationgps.desert.billing

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Manages VIP (subscription-free) access for specific devices.
 * Checks Firestore "trials" collection for a "vip_user" boolean field.
 *
 * Hybrid approach:
 *   - Online: Check Firestore, persist result to EncryptedSharedPreferences
 *   - Offline: Use persisted local flag (fail-open if previously verified)
 *   - Fresh install + offline: Deny VIP (fail-closed)
 *
 * Storage: Firestore collection "trials"
 * Document ID: ANDROID_ID
 * Field: vip_user (Boolean)
 */
object VipManager {

    private const val TAG = "VipManager"
    private const val COLLECTION = "trials"

    /**
     * Checks Firestore for VIP status of this device.
     * Uses hybrid approach: online check + persistent local flag.
     *
     * @param context Application context
     * @param subscriptionManager SubscriptionManager for persisting VIP status
     * @param forceRefresh If true, bypasses Firestore cache and fetches from server
     * @return true if device has VIP access, false otherwise
     */
    suspend fun checkVipStatus(
        context: Context,
        subscriptionManager: SubscriptionManager,
        forceRefresh: Boolean = false
    ): Boolean {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            // Use SERVER for authoritative check, DEFAULT for cache-permitting background refresh
            val source = if (forceRefresh) Source.SERVER else Source.DEFAULT

            val doc = FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(androidId)
                .get(source)
                .await()

            val isVip = doc.getBoolean("vip_user") == true

            if (isVip) {
                // Firestore confirms VIP -> persist locally for offline access
                subscriptionManager.setVipStatus(true)
                Log.d(TAG, "Device IS VIP (verified online): $androidId")
            } else {
                // Firestore says NOT VIP -> revoke local flag
                subscriptionManager.setVipStatus(false)
                Log.d(TAG, "Device is NOT VIP — revoked local flag: $androidId")
            }

            isVip
        } catch (e: Exception) {
            Log.e(TAG, "VIP check failed (network error?): ${e.message}")
            // Network failure: use persisted local flag (fail-open if previously verified)
            val localVip = subscriptionManager.hasVipAccess
            Log.d(TAG, "Using local VIP flag due to network failure: $localVip")
            localVip
        }
    }

    /**
     * Invalidates the cached VIP status.
     * Call this if you need to force a fresh Firestore check.
     */
    fun invalidateCache() {
        // No-op for now; Firestore SDK handles its own cache
    }

    /**
     * Starts a real-time Firestore snapshotListener on the trials document.
     * When vip_user becomes true, persists VIP status locally and calls onActivated.
     *
     * @param context Application context
     * @param subscriptionManager SubscriptionManager for persisting VIP status
     * @param onActivated Callback fired when vip_user is detected as true
     * @return ListenerRegistration — caller must call .remove() in onDestroy
     */
    fun listenForVipActivation(
        context: Context,
        subscriptionManager: SubscriptionManager,
        onActivated: () -> Unit,
        onRejected: () -> Unit
    ): ListenerRegistration {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        return FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(androidId)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.e(TAG, "VIP listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (doc != null) {
                    val isVip = doc.getBoolean("vip_user") == true
                    val vipRequestStatus = doc.getString("vip_request_status")

                    if (isVip) {
                        subscriptionManager.setVipStatus(true)
                        Log.d(TAG, "VIP activation detected via real-time listener: $androidId")
                        onActivated()
                    } else if (vipRequestStatus == "rejected") {
                        subscriptionManager.clearVipRequest()
                        Log.d(TAG, "VIP request rejected via real-time listener: $androidId")
                        onRejected()
                    }
                }
            }
    }
}
