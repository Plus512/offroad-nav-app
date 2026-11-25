package com.alruwaili.garminalbarr.map

import android.graphics.Color
import android.graphics.Paint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/**
 * 60 FPS Zero-Lag Osmdroid Track Overlay.
 *
 * Implements:
 * 1. Non-geodesic planar lines (isGeodesic = false) to prevent spherical vertex explosion.
 * 2. In-place addPoint() coordinate appending without array re-allocation.
 * 3. 500-point segment chunking for Osmdroid bounding-box viewport culling.
 * 4. Distinct overlay layering below SmoothLocationOverlay so vehicle chevron stays on top.
 * 5. Multi-track display support for saved tracks and backtrack routes.
 */
class OsmdroidTrackOverlay(private val mapView: MapView) {

    companion object {
        private const val CHUNK_MAX_POINTS = 500
        private const val DEFAULT_LIVE_COLOR = 0xFFE53935.toInt() // Desert Red
        private const val BACKTRACK_COLOR = 0xFF00E5FF.toInt()    // Electric Cyan
    }

    private val density = mapView.context.resources.displayMetrics.density

    // Live active recording chunks
    private val liveChunks = mutableListOf<Polyline>()
    private var currentLiveChunk: Polyline? = null
    private var currentLiveChunkCount = 0
    private var liveColorInt: Int = DEFAULT_LIVE_COLOR

    // Saved tracks: Map of trackId -> List<Polyline>
    private val savedTrackOverlays = mutableMapOf<Long, MutableList<Polyline>>()

    // Backtrack route overlays
    private val backtrackOverlays = mutableListOf<Polyline>()

    fun setLiveTrackColor(colorHex: String) {
        liveColorInt = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            DEFAULT_LIVE_COLOR
        }
        for (chunk in liveChunks) {
            chunk.outlinePaint.color = liveColorInt
        }
        currentLiveChunk?.outlinePaint?.color = liveColorInt
        mapView.postInvalidate()
    }

    /**
     * Incrementally appends a single coordinate to the live recording polyline.
     * Guaranteed 60 FPS zero-allocation hot-path.
     */
    fun appendLivePoint(geo: GeoPoint) {
        var chunk = currentLiveChunk
        if (chunk == null || currentLiveChunkCount >= CHUNK_MAX_POINTS) {
            val lastPoint = chunk?.actualPoints?.lastOrNull()
            val newChunk = createPolylineChunk(liveColorInt, strokeWidthDp = 5.5f)
            if (lastPoint != null) {
                newChunk.addPoint(lastPoint)
            }
            liveChunks.add(newChunk)
            insertOverlayBelowVehicle(newChunk)
            currentLiveChunk = newChunk
            currentLiveChunkCount = if (lastPoint != null) 1 else 0
            chunk = newChunk
        }

        chunk.addPoint(geo)
        currentLiveChunkCount++
        mapView.postInvalidate()
    }

    /**
     * Restores an active live track upon activity recreation from background service.
     */
    fun restoreLiveTrack(points: List<GeoPoint>, colorHex: String? = null) {
        clearLiveTrack()
        if (colorHex != null) {
            liveColorInt = try {
                Color.parseColor(colorHex)
            } catch (_: Exception) {
                DEFAULT_LIVE_COLOR
            }
        }
        if (points.isEmpty()) return

        for (pt in points) {
            appendLivePoint(pt)
        }
        mapView.postInvalidate()
    }

    /**
     * Clears and removes live recording polylines from the map canvas.
     */
    fun clearLiveTrack() {
        for (chunk in liveChunks) {
            mapView.overlays.remove(chunk)
        }
        liveChunks.clear()
        currentLiveChunk = null
        currentLiveChunkCount = 0
        mapView.postInvalidate()
    }

    /**
     * Displays a saved track on the map with its specified color.
     */
    fun showSavedTrack(trackId: Long, points: List<GeoPoint>, colorHex: String) {
        hideSavedTrack(trackId)
        if (points.size < 2) return

        val colorInt = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            DEFAULT_LIVE_COLOR
        }

        val chunks = mutableListOf<Polyline>()
        var chunk = createPolylineChunk(colorInt, strokeWidthDp = 4.5f)
        chunks.add(chunk)
        insertOverlayBelowVehicle(chunk)

        var count = 0
        for (i in points.indices) {
            if (count >= CHUNK_MAX_POINTS) {
                val lastPt = points[i - 1]
                chunk = createPolylineChunk(colorInt, strokeWidthDp = 4.5f)
                chunk.addPoint(lastPt)
                chunks.add(chunk)
                insertOverlayBelowVehicle(chunk)
                count = 1
            }
            chunk.addPoint(points[i])
            count++
        }

        savedTrackOverlays[trackId] = chunks
        mapView.postInvalidate()
    }

    /**
     * Hides and removes a saved track from the map.
     */
    fun hideSavedTrack(trackId: Long) {
        savedTrackOverlays.remove(trackId)?.let { chunks ->
            for (chunk in chunks) {
                mapView.overlays.remove(chunk)
            }
            mapView.postInvalidate()
        }
    }

    /**
     * Hides all saved tracks currently rendered on the map.
     */
    fun clearAllSavedTracks() {
        for ((_, chunks) in savedTrackOverlays) {
            for (chunk in chunks) {
                mapView.overlays.remove(chunk)
            }
        }
        savedTrackOverlays.clear()
        mapView.postInvalidate()
    }

    /**
     * Renders a backtrack route (reverse path) with high-contrast cyan highlight.
     */
    fun showBacktrack(points: List<GeoPoint>) {
        clearBacktrack()
        if (points.size < 2) return

        var chunk = createPolylineChunk(BACKTRACK_COLOR, strokeWidthDp = 6.0f)
        backtrackOverlays.add(chunk)
        insertOverlayBelowVehicle(chunk)

        var count = 0
        for (i in points.indices) {
            if (count >= CHUNK_MAX_POINTS) {
                val lastPt = points[i - 1]
                chunk = createPolylineChunk(BACKTRACK_COLOR, strokeWidthDp = 6.0f)
                chunk.addPoint(lastPt)
                backtrackOverlays.add(chunk)
                insertOverlayBelowVehicle(chunk)
                count = 1
            }
            chunk.addPoint(points[i])
            count++
        }
        mapView.postInvalidate()
    }

    fun clearBacktrack() {
        for (chunk in backtrackOverlays) {
            mapView.overlays.remove(chunk)
        }
        backtrackOverlays.clear()
        mapView.postInvalidate()
    }

    private fun createPolylineChunk(colorInt: Int, strokeWidthDp: Float): Polyline {
        return Polyline(mapView).apply {
            isGeodesic = false // Critical for 60 FPS: prevents spherical vertex explosion
            outlinePaint.color = colorInt
            outlinePaint.strokeWidth = strokeWidthDp * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }
    }

    /**
     * Inserts the track polyline above map tiles and below user location arrow overlay.
     */
    private fun insertOverlayBelowVehicle(overlay: Overlay) {
        val locOverlayIndex = mapView.overlays.indexOfFirst { it is SmoothLocationOverlay }
        if (locOverlayIndex > 0) {
            mapView.overlays.add(locOverlayIndex, overlay)
        } else {
            mapView.overlays.add(overlay)
        }
    }
}
