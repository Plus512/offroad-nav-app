package com.navigationgps.desert.map

import android.content.Context
import android.util.Log
import com.navigationgps.desert.map.download.DownloadStateManager
import com.navigationgps.desert.map.download.DownloadState
import com.navigationgps.desert.map.download.MapRegion
import com.navigationgps.desert.map.download.RegionRegistry
import com.navigationgps.desert.map.download.RegionRegistry.WORLD_MAP_FILENAME
import java.io.File

object MapFileManager {

    private const val TAG = "MapFileManager"
    private const val MAPS_DIR = "maps"

    fun getMapPath(context: Context, regionId: String): String {
        require(regionId.matches(Regex("[a-z0-9_\\-]+"))) { "invalid region id: $regionId" }
        val dir = File(context.filesDir, MAPS_DIR)
        return File(dir, "$regionId.map").absolutePath
    }

    fun isRegionDownloaded(context: Context, regionId: String): Boolean {
        val file = File(context.filesDir, "$MAPS_DIR/$regionId.map")
        return file.exists() && file.length() > 0
    }

    fun getDownloadedRegions(context: Context): List<String> {
        val dir = File(context.filesDir, MAPS_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".map") && it.length() > 0 }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun getDownloadedMapFiles(context: Context): List<File> {
        val dir = File(context.filesDir, MAPS_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".map") && it.length() > 0 }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun deleteRegion(context: Context, regionId: String): Boolean {
        require(regionId.matches(Regex("[a-z0-9_\\-]+"))) { "invalid region id: $regionId" }
        val file = File(context.filesDir, "$MAPS_DIR/$regionId.map")
        return file.exists() && file.delete()
    }

    fun getStorageUsedBytes(context: Context): Long {
        val dir = File(context.filesDir, MAPS_DIR)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun validateDownloadedRegions(
        context: Context,
        stateManager: DownloadStateManager? = null
    ): List<String> {
        val valid = mutableListOf<String>()
        val dir = File(context.filesDir, MAPS_DIR)
        if (!dir.exists()) return valid

        for (region in RegionRegistry.ALL_REGIONS) {
            val file = File(dir, "${region.id}.map")
            when {
                !file.exists() -> { /* never downloaded */ }
                region.sizeBytes > 0 && !isWithinTolerance(file.length(), region.sizeBytes) &&
                    !(region.legacySizeBytes > 0 && isWithinTolerance(file.length(), region.legacySizeBytes)) -> {
                    Log.w(TAG, "Integrity check FAILED for ${region.id}.map (on disk ${file.length()}B, expected ${region.sizeBytes}B) — deleting corrupt file")
                    file.delete()
                    stateManager?.invalidate(region.id)
                }
                else -> {
                    Log.d(TAG, "Integrity OK for ${region.id}.map (${file.length()}B)")
                    valid.add(region.id)
                }
            }
        }
        return valid
    }

    fun getWorldMapPath(context: Context): File {
        return File(context.filesDir, "$MAPS_DIR/$WORLD_MAP_FILENAME")
    }

    fun isWorldMapDownloaded(context: Context): Boolean {
        val file = getWorldMapPath(context)
        return file.exists() && file.length() > 0
    }

    // ======================== BRouter Routing Data Management ========================

    private const val BROUTER_DIR = "brouter"
    private const val SEGMENTS_DIR = "segments4"

    fun getBrouterBaseDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, BROUTER_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRoutingSegmentsDir(context: Context): File {
        val dir = File(getBrouterBaseDir(context), SEGMENTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSegmentFile(context: Context, segmentName: String): File {
        return File(getRoutingSegmentsDir(context), segmentName)
    }

    fun getSegmentNameForCoordinate(lat: Double, lon: Double): String {
        val lonFloor = Math.floor(lon / 5.0).toInt() * 5
        val latFloor = Math.floor(lat / 5.0).toInt() * 5
        val lonPart = if (lonFloor >= 0) "E$lonFloor" else "W${-lonFloor}"
        val latPart = if (latFloor >= 0) "N$latFloor" else "S${-latFloor}"
        return "${lonPart}_${latPart}.rd5"
    }

    fun areRouteSegmentsDownloaded(
        context: Context,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): Boolean {
        val originSeg = getSegmentNameForCoordinate(originLat, originLon)
        val destSeg = getSegmentNameForCoordinate(destLat, destLon)
        return isSegmentDownloaded(context, originSeg) && isSegmentDownloaded(context, destSeg)
    }

    fun ensureBrouterAssets(context: Context) {
        try {
            val baseDir = getBrouterBaseDir(context)
            copyAssetFileIfMissing(context, "brouter/lookups.dat", File(baseDir, "lookups.dat"))
            copyAssetFileIfMissing(context, "brouter/desert-car.brf", File(baseDir, "desert-car.brf"))
            copyAssetFileIfMissing(context, "brouter/desert-car-shortest.brf", File(baseDir, "desert-car-shortest.brf"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed ensuring BRouter assets: ${e.message}", e)
        }
    }

    private fun copyAssetFileIfMissing(context: Context, assetPath: String, targetFile: File) {
        if (targetFile.exists() && targetFile.length() > 0) return
        context.assets.open(assetPath).use { input ->
            java.io.FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Extracted asset $assetPath to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
    }

    fun isSegmentDownloaded(context: Context, segmentName: String): Boolean {
        val file = getSegmentFile(context, segmentName)
        return file.exists() && file.length() > 0
    }

    fun getDownloadedSegments(context: Context): List<String> {
        val dir = getRoutingSegmentsDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".rd5") && it.length() > 0 }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun isCountryRoutingDownloaded(context: Context, region: MapRegion): Boolean {
        if (region.routingSegments.isEmpty()) return false
        return region.routingSegments.all { isSegmentDownloaded(context, it) }
    }

    fun isCountryRoutingDownloaded(context: Context, regionId: String): Boolean {
        val region = RegionRegistry.getRegionById(regionId) ?: return false
        return isCountryRoutingDownloaded(context, region)
    }

    fun getCountryRoutingDownloadedCount(context: Context, region: MapRegion): Int {
        return region.routingSegments.count { isSegmentDownloaded(context, it) }
    }

    fun getCountryRoutingDownloadedCount(context: Context, regionId: String): Int {
        val region = RegionRegistry.getRegionById(regionId) ?: return 0
        return getCountryRoutingDownloadedCount(context, region)
    }

    fun getRoutingStorageUsedBytes(context: Context): Long {
        val dir = getRoutingSegmentsDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".rd5") }.sumOf { it.length() }
    }

    fun getTotalStorageUsedBytes(context: Context): Long {
        return getStorageUsedBytes(context) + getRoutingStorageUsedBytes(context)
    }

    fun deleteSegment(context: Context, segmentName: String): Boolean {
        val file = getSegmentFile(context, segmentName)
        return file.exists() && file.delete()
    }

    /**
     * Deletes segments for [region], preserving segments that are shared
     * with another region whose routing data is currently installed.
     */
    fun deleteCountryRouting(context: Context, region: MapRegion): Boolean {
        val otherRegions = RegionRegistry.ALL_REGIONS.filter { it.id != region.id }
        var allSuccess = true
        for (seg in region.routingSegments) {
            val sharedWithOtherActive = otherRegions.any { other ->
                isCountryRoutingDownloaded(context, other) && other.routingSegments.contains(seg)
            }
            if (!sharedWithOtherActive) {
                val deleted = deleteSegment(context, seg)
                if (!deleted && isSegmentDownloaded(context, seg)) {
                    allSuccess = false
                }
            } else {
                Log.d(TAG, "Preserving shared segment $seg (still used by another installed region)")
            }
        }
        return allSuccess
    }

    fun deleteCountryRouting(context: Context, regionId: String): Boolean {
        val region = RegionRegistry.getRegionById(regionId) ?: return false
        return deleteCountryRouting(context, region)
    }

    private fun isWithinTolerance(actual: Long, expected: Long, tolerancePercent: Double = 0.05): Boolean {
        if (expected == 0L) return actual == 0L
        val diff = kotlin.math.abs(actual - expected)
        val maxDiff = (expected * tolerancePercent).toLong()
        return diff <= maxDiff
    }
}
