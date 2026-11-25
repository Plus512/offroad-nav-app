package com.alruwaili.garminalbarr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.google.android.gms.appset.AppSet
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages cloud sync of user-saved "My Locations" via Firestore.
 * Uses ANDROID_ID as the primary document key (same pattern as TrialRepository).
 *
 * Flow:
 *   1. On app start (after install): Check Firestore for existing locations
 *   2. If found: Merge with local (Firestore wins for conflicts)
 *   3. On every local change: Push to Firestore (debounced)
 *   4. Manual restore: User can trigger restore from UI
 *
 * Firestore document structure:
 *   Collection: "user_locations"
 *   Document ID: {ANDROID_ID}
 *   Fields: { android_id, app_set_id, locations[], last_updated, location_count }
 */
object LocationsSyncRepository {

    private const val TAG = "LocationsSyncRepo"
    private const val COLLECTION = "user_locations"
    private const val SYNC_TIMEOUT_MS = 8000L
    private const val RESTORE_TIMEOUT_MS = 5000L

    // ── Device Identifiers (reuse from TrialRepository pattern) ──

    private fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    private suspend fun getAppSetId(context: Context): String? {
        return try {
            val client = AppSet.getClient(context)
            val info = client.appSetIdInfo.await()
            info.id
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "App Set ID unavailable: ${e.message}")
            null
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Cloud → Local (Restore) ──

    /**
     * Checks Firestore for existing user locations.
     * Called on app start after install (when local prefs are empty).
     *
     * @return List of LocationEntity if found, null if no cloud data
     */
    suspend fun restoreFromCloud(context: Context): List<LocationEntity>? {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Offline — cannot restore from cloud")
            return null
        }

        return withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
            try {
                val androidId = getAndroidId(context)
                val doc = FirebaseFirestore.getInstance()
                    .collection(COLLECTION)
                    .document(androidId)
                    .get()
                    .await()

                if (!doc.exists()) {
                    Log.d(TAG, "No cloud locations found for this device")
                    return@withTimeoutOrNull null
                }

                val locationsArray = doc.get("locations") as? List<Map<String, Any>>
                if (locationsArray.isNullOrEmpty()) {
                    Log.d(TAG, "Cloud document exists but locations array is empty")
                    return@withTimeoutOrNull null
                }

                val locations = locationsArray.mapNotNull { map ->
                    try {
                        LocationEntity(
                            title = map["title"] as? String,
                            latitude = (map["latitude"] as? Number)?.toDouble(),
                            longitude = (map["longitude"] as? Number)?.toDouble(),
                            isFav = map["is_fav"] as? Boolean ?: false
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse location from cloud: ${e.message}")
                        null
                    }
                }

                Log.d(TAG, "Restored ${locations.size} locations from cloud")
                locations
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Cloud restore failed: ${e.message}")
                null
            }
        }
    }

    // ── Local → Cloud (Backup) ──

    /**
     * Pushes the current local locations list to Firestore.
     * Merges with existing cloud data to prevent data loss on fresh install.
     *
     * Flow:
     *   1. Fetch existing cloud document
     *   2. Parse existing cloud locations
     *   3. Merge: start with cloud locations, add local locations not in cloud
     *   4. Save merged list to Firestore
     *
     * @param context Application context
     * @param locations The current list of user-saved locations
     */
    suspend fun syncToCloud(context: Context, locations: List<LocationEntity>) {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Offline — will sync on next launch")
            return
        }

        withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            try {
                val androidId = getAndroidId(context)
                val appSetId = getAppSetId(context) ?: "unavailable"

                // Step 1: Fetch existing cloud locations
                val existingCloudLocations = fetchCloudLocations(androidId)

                // Step 2: Merge — start with cloud, add local items not in cloud
                val mergedList = mergeLocations(existingCloudLocations, locations)

                // Step 3: Save merged list to Firestore
                val locationsData = mergedList.map { loc ->
                    mapOf(
                        "title" to loc.title,
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "is_fav" to (loc.isFav ?: false)
                    )
                }

                val documentData = mapOf(
                    "android_id" to androidId,
                    "app_set_id" to appSetId,
                    "locations" to locationsData,
                    "last_updated" to FieldValue.serverTimestamp(),
                    "location_count" to mergedList.size
                )

                FirebaseFirestore.getInstance()
                    .collection(COLLECTION)
                    .document(androidId)
                    .set(documentData, SetOptions.merge())
                    .await()

                Log.d(TAG, "Synced ${mergedList.size} locations to cloud (local: ${locations.size}, cloud: ${existingCloudLocations.size}, merged: ${mergedList.size})")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Cloud sync failed: ${e.message}")
            }
        }
    }

    /**
     * Fetches existing cloud locations for this device.
     * Returns empty list if no cloud data exists.
     */
    private suspend fun fetchCloudLocations(androidId: String): List<LocationEntity> {
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(androidId)
                .get()
                .await()

            if (!doc.exists()) return emptyList()

            val locationsArray = doc.get("locations") as? List<Map<String, Any>>
            if (locationsArray.isNullOrEmpty()) return emptyList()

            locationsArray.mapNotNull { map ->
                try {
                    LocationEntity(
                        title = map["title"] as? String,
                        latitude = (map["latitude"] as? Number)?.toDouble(),
                        longitude = (map["longitude"] as? Number)?.toDouble(),
                        isFav = map["is_fav"] as? Boolean ?: false
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse cloud location: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch cloud locations: ${e.message}")
            emptyList()
        }
    }

    /**
     * Merges cloud and local location lists.
     * Cloud locations are preserved, local locations are added if not already in cloud.
     * Duplicate detection: same title + latitude + longitude.
     *
     * @param cloudLocations Existing locations from Firestore
     * @param localLocations Current locations from device
     * @return Merged list with no duplicates
     */
    private fun mergeLocations(
        cloudLocations: List<LocationEntity>,
        localLocations: List<LocationEntity>
    ): List<LocationEntity> {
        if (cloudLocations.isEmpty()) return localLocations
        if (localLocations.isEmpty()) return cloudLocations

        val merged = cloudLocations.toMutableList()

        for (local in localLocations) {
            val isDuplicate = cloudLocations.any { cloud ->
                cloud.title == local.title &&
                cloud.latitude == local.latitude &&
                cloud.longitude == local.longitude
            }
            if (!isDuplicate) {
                merged.add(local)
            }
        }

        return merged
    }

    /**
     * Replaces cloud data with local list (no merge).
     * Used when user removes a location and wants to delete from cloud too.
     * Local list is treated as source of truth.
     *
     * @param context Application context
     * @param locations The current local list to push to cloud
     */
    suspend fun replaceCloudFromLocal(context: Context, locations: List<LocationEntity>) {
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Offline — cannot replace cloud data")
            return
        }

        withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            try {
                val androidId = getAndroidId(context)
                val appSetId = getAppSetId(context) ?: "unavailable"

                val locationsData = locations.map { loc ->
                    mapOf(
                        "title" to loc.title,
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "is_fav" to (loc.isFav ?: false)
                    )
                }

                val documentData = mapOf(
                    "android_id" to androidId,
                    "app_set_id" to appSetId,
                    "locations" to locationsData,
                    "last_updated" to FieldValue.serverTimestamp(),
                    "location_count" to locations.size
                )

                FirebaseFirestore.getInstance()
                    .collection(COLLECTION)
                    .document(androidId)
                    .set(documentData, SetOptions.merge())
                    .await()

                Log.d(TAG, "Replaced cloud data with ${locations.size} local locations")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Cloud replace failed: ${e.message}")
            }
        }
    }

    /**
     * Deletes the cloud backup for this device.
     * Useful for testing or if user requests data deletion.
     */
    suspend fun deleteCloudBackup(context: Context) {
        if (!isNetworkAvailable(context)) return

        try {
            val androidId = getAndroidId(context)
            FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(androidId)
                .delete()
                .await()
            Log.d(TAG, "Cloud backup deleted")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to delete cloud backup: ${e.message}")
        }
    }
}
