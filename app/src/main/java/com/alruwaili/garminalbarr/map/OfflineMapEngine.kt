package com.alruwaili.garminalbarr.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alruwaili.garminalbarr.BuildConfig
import org.osmdroid.views.MapView
import org.osmdroid.util.GeoPoint
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import java.io.File
import java.io.FileOutputStream

/**
 * Wraps MapsForgeTileSource lifecycle. Scans for .map files (world.map base + regional),
 * creates the tile source, and refreshes after downloads without losing camera state.
 */
object OfflineMapEngine {

    private const val TAG = "OfflineMapEngine"
    private var currentTileSource: MapsForgeTileSource? = null
    private var currentMapFileKeys: List<String> = emptyList()

    /**
     * Scan all storage locations for .map files, create MapsForgeTileSource.
     * world.map loads first (base layer for zoom 1-7), then regional .map files on top.
     * Returns the tile source, or null if no .map files found.
     * Idempotent: if the same files are already loaded, returns the existing tile source.
     */
    fun init(context: Context): MapsForgeTileSource? {
        val mapFiles = findAllMapFiles(context)
        if (mapFiles.isEmpty()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No .map files found — map will show blank canvas")
            currentTileSource?.dispose()
            currentTileSource = null
            currentMapFileKeys = emptyList()
            return null
        }

        val newKeys = mapFiles.map { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (newKeys == currentMapFileKeys && currentTileSource != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Tile source already initialized with ${mapFiles.size} files, reusing")
            return currentTileSource
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Found ${mapFiles.size} map files: ${mapFiles.map { "${it.name}(${it.length()}B)" }}")
        }

        return try {
            var tileSource: MapsForgeTileSource? = null

            // NOTE: "mapsforge/default.xml" collides with the same entry shipped inside
            // the mapsforge-themes library JAR (duplicate-entry build failure), so the
            // client's desert-palette fork (bg #ECD9A6, locality zoom-min 12) lives at
            // client_default.xml. Do NOT overwrite with upstream default.xml — colors
            // will regress to stock (#F8F8F8).
            val desertTheme = try {
                AssetsRenderTheme(context.applicationContext.assets, "", "mapsforge/client_default.xml")
            } catch (e: Exception) {
                Log.e(TAG, "Custom desert theme failed, falling back to DEFAULT", e)
                InternalRenderTheme.DEFAULT
            }

            try {
                tileSource = MapsForgeTileSource.createFromFiles(
                    mapFiles.toTypedArray(),
                    desertTheme,
                    // Client-original reference build: mapsforge/default.xml + locations.map.
                    // Renamed to bust stale SqlTileWriter tiles rendered with older themes.
                    "Desert-ClientOriginal-v1",
                    "ar"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Desert theme failed, trying bundled client_osmarender", e)
                // Bundled client fallback first (desert-consistent); jar-internal
                // OSMARENDER as the zero-asset-I/O last resort.
                val fallbackTheme = try {
                    AssetsRenderTheme(context.applicationContext.assets, "", "mapsforge/client_osmarender.xml")
                } catch (e0: Exception) {
                    Log.e(TAG, "Bundled fallback theme failed, using jar-internal OSMARENDER", e0)
                    InternalRenderTheme.OSMARENDER
                }
                try {
                    tileSource = MapsForgeTileSource.createFromFiles(
                        mapFiles.toTypedArray(),
                        fallbackTheme,
                        "OfflineDesert-Ar-Fallback",
                        "ar"
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "CRITICAL: All themes failed!", e2)
                }
            }

            if (tileSource != null) {
                currentTileSource?.dispose()
                currentTileSource = tileSource
                currentMapFileKeys = newKeys
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "MapsForgeTileSource created successfully")
                    Log.d(TAG, "Tile source: minZoom=${tileSource.minimumZoomLevel} maxZoom=${tileSource.maximumZoomLevel} tileSize=${tileSource.tileSizePixels}")
                }
            }

            tileSource
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to create MapsForgeTileSource — map will be blank!", e)
            null
        }
    }

    /**
     * Call after a new .map file is downloaded. Preserves camera state.
     * Uses non-blocking Handler.postDelayed instead of Thread.sleep.
     */
    fun refreshAfterDownload(context: Context, mapView: MapView): Boolean {
        val center = mapView.mapCenter
        val lat = center?.latitude ?: 0.0
        val lon = center?.longitude ?: 0.0
        val zoom = mapView.zoomLevelDouble
        val zoomByte = zoom.toInt().coerceIn(0, 22)

        val tileSource = init(context) ?: return false

        val tileProvider = MapsForgeTileProvider(
            SimpleRegisterReceiver(context.applicationContext),
            tileSource,
            SqlTileWriter()
        )
        mapView.tileProvider = tileProvider
        if (BuildConfig.DEBUG) Log.d(TAG, "MapsForgeTileProvider set on MapView")
        
        if (lat != 0.0 || lon != 0.0) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mapView.controller.setZoom(zoomByte.toDouble())
                    mapView.controller.setCenter(GeoPoint(lat, lon))
                    mapView.invalidate()
                } catch (e: Exception) {
                    Log.e(TAG, "Post-delayed map refresh failed", e)
                }
            }, 500)
        }
        return true
    }

    fun getCurrentTileSource(): MapsForgeTileSource? = currentTileSource

    fun dispose() {
        currentTileSource?.dispose()
        currentTileSource = null
    }

    /**
     * Find all .map files. world.map first (base layer), then regional files sorted by name.
     */
    private fun findAllMapFiles(context: Context): List<File> {
        val allFiles = mutableListOf<File>()

        val searchDirs = listOfNotNull(
            File(context.filesDir, "maps"),
            // getExternalFilesDir() can return null (ejected/shared storage).
            context.getExternalFilesDir(null)?.let { File(it, "maps") }
        )

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val mapFiles = dir.listFiles { file ->
                    file.extension == "map" && file.length() > 0
                }
                if (mapFiles != null) {
                    allFiles.addAll(mapFiles)
                }
            }
        }

        try {
            val assetMapFiles = context.assets.list("maps") ?: emptyArray()
            for (assetName in assetMapFiles) {
                if (assetName.endsWith(".map")) {
                    val cacheDir = File(context.cacheDir, "maps")
                    cacheDir.mkdirs()
                    val cached = File(cacheDir, assetName)
                    if (!cached.exists() || cached.length() == 0L) {
                        context.assets.open("maps/$assetName").use { input ->
                            FileOutputStream(cached).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (cached.exists() && cached.length() > 0) {
                        allFiles.add(cached)
                    }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to list asset maps", e)
        }

        val deduped = allFiles.distinctBy { it.name }
        if (BuildConfig.DEBUG && deduped.size != allFiles.size) {
            Log.d(TAG, "Dedupe dropped ${allFiles.size - deduped.size} shadowed file(s); winners: ${deduped.map { it.name }}")
        }

        val worldMap = deduped.find { it.name.equals("world.map", ignoreCase = true) }
        val regionalMaps = deduped
            .filter { !it.name.equals("world.map", ignoreCase = true) }
            .sortedBy { it.name }

        val result = mutableListOf<File>()
        if (worldMap != null) result.add(worldMap)
        result.addAll(regionalMaps)
        return result
    }

}
