package com.navigationgps.desert.billing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.google.android.gms.appset.AppSet
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages trial persistence via Firestore using dual-key identification.
 *
 * Identity strategy (dual-key lookup):
 *  - Primary key:   ANDROID_ID  (persists across reinstall, same signing key, no permissions)
 *  - Secondary key: App Set ID  (persists across reinstall, requires GMS, scoped per developer)
 *  - Storage:       Firestore   (server-side, tamper-proof, survives everything)
 *
 * Lookup order:
 *  1. Direct document get by ANDROID_ID (fast, no query needed)
 *  2. If not found → query by app_set_id field (catches factory reset case)
 *  If EITHER key matches a record with trial_used=true → trial already used.
 *
 * Firestore document structure:
 *   Collection: "trials"
 *   Document ID: {ANDROID_ID}
 *   Fields: { android_id: String, app_set_id: String, trial_used: Boolean,
 *             trial_start: Timestamp, device_model: String }
 */
object TrialRepository {

    private const val TAG = "TrialRepository"
    private const val COLLECTION = "trials"
    private const val APP_SET_ID_TIMEOUT_MS = 5000L
    private const val CREATE_TRIAL_TIMEOUT_MS = 8000L

    internal fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Device Identifiers ──

    /**
     * Returns ANDROID_ID — stable across uninstall/reinstall (Android 8+).
     * Scoped to: device + user profile + signing key.
     * Resets on: factory reset or signing key change.
     * Requires: nothing (no permissions, no GMS, no internet).
     */
    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    /**
     * Returns App Set ID — stable across reinstall.
     * Scoped to: developer account (all apps from same developer share it).
     * Resets on: factory reset, or if all apps from developer are uninstalled.
     * Requires: Google Play Services.
     * Returns null if GMS is unavailable.
     */
    suspend fun getAppSetId(context: Context): String? {
        return withTimeoutOrNull(APP_SET_ID_TIMEOUT_MS) {
            try {
                val client = AppSet.getClient(context)
                val info = client.appSetIdInfo.await()
                info.id
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "App Set ID unavailable (no GMS?): ${e.message}")
                null
            }
        }
    }

    // ── Firestore Operations ──

    /**
     * Dual-key lookup: checks Firestore for an existing trial record.
     * Uses Source.SERVER only for fresh install/reinstall decisions.
     *
     * Step 1: Direct document lookup by ANDROID_ID (fast — single doc read).
     * Step 2: If not found, query by app_set_id field (catches factory reset).
     *
     * Returns explicit StartupResult — never null for both "document absent" and "lookup failed".
     */
    suspend fun getRemoteTrialStart(context: Context): StartupResult {
        val db = FirebaseFirestore.getInstance()

        // Use Source.SERVER for authoritative fresh-install lookup
        val source = Source.SERVER

        // ── Step 1: Direct lookup by ANDROID_ID ──
        try {
            val deviceId = getAndroidId(context)
            val doc = db.collection(COLLECTION)
                .document(deviceId)
                .get(source)
                .await()

            if (doc.exists() && doc.getBoolean("trial_used") == true) {
                val timestamp = doc.getTimestamp("trial_start")
                val vipUser = doc.getBoolean("vip_user") == true
                val vipRequestStatus = doc.getString("vip_request_status")
                Log.d(TAG, "Trial found securely via ($source) by ANDROID_ID: $deviceId")
                return StartupResult.ExistingAccess(
                    trialStartEpoch = timestamp?.toDate()?.time,
                    isVipUser = vipUser,
                    vipRequestStatus = vipRequestStatus,
                    purchaseRevoked = doc.getBoolean("purchase_revoked") == true
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Firestore primary lookup failed via $source: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            return StartupResult.ServerFailure
        }

        // ── Step 2: Fallback query by App Set ID ──
        // If App Set ID is unavailable or the query fails, we cannot determine
        // whether this is a new device or a reinstall. Return ServerUnavailable
        // rather than failing open with NewDevice (which would grant a new trial).
        val appSetId = try {
            getAppSetId(context)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "App Set ID retrieval failed: ${e.message}")
            null
        }

        if (appSetId == null) {
            Log.w(TAG, "App Set ID unavailable — cannot perform dual-key lookup")
            return StartupResult.ServerUnavailable
        }

        try {
            val querySnapshot = db.collection(COLLECTION)
                .whereEqualTo("app_set_id", appSetId)
                .whereEqualTo("trial_used", true)
                .limit(1)
                .get(source)
                .await()

            if (querySnapshot.documents.isNotEmpty()) {
                val matchedDoc = querySnapshot.documents[0]
                val timestamp = matchedDoc.getTimestamp("trial_start")
                val vipUser = matchedDoc.getBoolean("vip_user") == true
                val vipRequestStatus = matchedDoc.getString("vip_request_status")
                Log.d(TAG, "Trial found securely via ($source) by App Set ID: $appSetId")
                return StartupResult.ExistingAccess(
                    trialStartEpoch = timestamp?.toDate()?.time,
                    isVipUser = vipUser,
                    vipRequestStatus = vipRequestStatus,
                    purchaseRevoked = matchedDoc.getBoolean("purchase_revoked") == true
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Firestore App Set ID query failed via $source: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            return StartupResult.ServerFailure
        }

        Log.d(TAG, "No trial records found in ($source) for this device.")
        return StartupResult.NewDevice
    }

    /**
     * Server-acknowledged trial creation.
     * Returns explicit TrialCreationResult distinguishing new creation from existing discovery.
     *
     * Flow:
     * 1. Recheck document with Source.SERVER
     * 2. If exists → return Existing with its immutable server timestamp
     * 3. If not exists → create atomically, then re-read to get server timestamp
     * 4. If conflict/rejected/timed out → return Failed
     */
    suspend fun createRemoteTrial(context: Context): TrialCreationResult {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Device offline. Skipping remote trial creation.")
            return TrialCreationResult.Failed
        }

        return withTimeoutOrNull(CREATE_TRIAL_TIMEOUT_MS) {
            try {
                val deviceId = getAndroidId(context)
                val appSetId = getAppSetId(context) ?: "unavailable"

                // Step 1: Recheck with Source.SERVER
                val db = FirebaseFirestore.getInstance()
                val existingDoc = db.collection(COLLECTION)
                    .document(deviceId)
                    .get(Source.SERVER)
                    .await()

                if (existingDoc.exists() && existingDoc.getBoolean("trial_used") == true) {
                    // Step 2: Document exists — return Existing (do not assume active)
                    val serverTimestamp = existingDoc.getTimestamp("trial_start")
                    val timestampEpoch = serverTimestamp?.toDate()?.time
                    if (timestampEpoch == null) {
                        Log.e(TAG, "Existing trial document has no trial_start timestamp — cannot verify timing")
                        return@withTimeoutOrNull TrialCreationResult.Failed
                    }
                    Log.d(TAG, "Trial already exists on server — returning Existing: $timestampEpoch")
                    return@withTimeoutOrNull TrialCreationResult.Existing(timestampEpoch)
                }

                // Step 3: Document doesn't exist — create atomically
                val trialData = mapOf(
                    "android_id" to deviceId,
                    "app_set_id" to appSetId,
                    "trial_used" to true,
                    "trial_start" to FieldValue.serverTimestamp(),
                    "device_model" to android.os.Build.MODEL
                )

                db.collection(COLLECTION)
                    .document(deviceId)
                    .set(trialData, SetOptions.merge())
                    .await()

                // Step 4: Re-read to get the server-assigned timestamp
                val createdDoc = db.collection(COLLECTION)
                    .document(deviceId)
                    .get(Source.SERVER)
                    .await()

                val createdTimestamp = createdDoc.getTimestamp("trial_start")
                val createdEpoch = createdTimestamp?.toDate()?.time
                if (createdEpoch == null) {
                    Log.e(TAG, "Created trial document has no trial_start timestamp — server may not have applied FieldValue.serverTimestamp()")
                    return@withTimeoutOrNull TrialCreationResult.Failed
                }
                Log.d(TAG, "Trial created on server — returning Created: $createdEpoch")
                return@withTimeoutOrNull TrialCreationResult.Created(createdEpoch)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Firestore trial creation failed: ${e.message}")
                FirebaseCrashlytics.getInstance().recordException(e)
                return@withTimeoutOrNull TrialCreationResult.Failed
            }
        } ?: TrialCreationResult.Failed
    }
}
