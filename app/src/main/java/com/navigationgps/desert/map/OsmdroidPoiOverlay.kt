package com.navigationgps.desert.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import com.navigationgps.desert.LocationLightweight
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class OsmdroidPoiOverlay(
    private val onPoiTapped: (PoiDetails) -> Unit,
    private val onEmptyAreaTapped: ((GeoPoint) -> Unit)? = null
) : Overlay() {

    companion object {
        private const val TAP_TOLERANCE_DP = 30f
        // Video spec: DB + tile POIs appear together at zoom >= 12 (~5.9 km scale).
        private const val POI_MIN_ZOOM = 12.0
        // Softened fill (Option H): tile glyphs read lighter via bitmap filtering,
        // so DB uses #6B5B3E instead of #5A4A2A for tone parity. Halo stays pure white.
        private const val TEXT_FILL_COLOR = 0xFF6B5B3E.toInt()
        private const val TEXT_HALO_COLOR = 0xFFFFFFFF.toInt()
        private const val TEXT_GAP_DP = 8.0f
        // Video spec: bold with white stroke/halo; size 10 matches tile POI caption.
        private const val TEXT_SIZE_DP = 10f
        private const val TEXT_HALO_WIDTH_DP = 2f
        private const val TRI_HALF_DP = 5f
        private const val TRI_HEIGHT_DP = 8f
        // Anti-clutter: minimum on-screen separation between DB labels (density-scaled).
        // First-seen entry wins; crowded neighbours are skipped for a clean desert view.
        private const val MIN_LABEL_SEPARATION_DP = 48f

        /**
         * Bitmap over-zoom factor of the raster tile layer at [zoom]: tiles are baked
         * at integer zooms and bitmap-scaled by 2^(zoom - floor(zoom)) in [1, 2).
         * DB metrics multiply by this so labels stay in lockstep with tile glyphs
         * (both snap together when fresh integer-zoom tiles arrive).
         */
        fun zoomScaleFor(zoom: Double): Double = 2.0.pow(zoom - floor(zoom))
    }

    private var visiblePois: List<PoiEntry> = emptyList()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_FILL_COLOR
        textSize = 40f // will be scaled by density in configure()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_HALO_COLOR
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val triFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_FILL_COLOR
        style = Paint.Style.FILL
    }
    private val triHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_HALO_COLOR
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val fontMetrics = Paint.FontMetrics()
    private var configured = false
    private var density = 1f
    private var lastScale = -1.0
    /**
     * Option B (coverage audit: 100% .map/.db parity): false suppresses the
     * duplicate canvas labels + triangle markers while keeping touch
     * hit-testing ([keptEntries], [onSingleTapConfirmed]) fully active, so taps
     * still open the bottom sheet with exact DB landmark data.
     */
    var labelsVisible: Boolean = false

    private data class PoiEntry(
        val poi: LocationLightweight,
        val category: String
    )

    fun configure(density: Float) {
        if (configured) return
        this.density = density
        applyScale(1.0)
        configured = true
    }

    /** Resize all DB metrics for [scale]; refreshes font metrics only on real change. */
    private fun applyScale(scale: Double) {
        if (abs(scale - lastScale) < 1e-3) return
        lastScale = scale
        val textSizePx = (TEXT_SIZE_DP * density * scale).toFloat()
        textPaint.textSize = textSizePx
        textPaint.getFontMetrics(fontMetrics)
        haloPaint.textSize = textSizePx
        haloPaint.strokeWidth = (TEXT_HALO_WIDTH_DP * density * scale).toFloat()
        triHaloPaint.strokeWidth = (TEXT_HALO_WIDTH_DP * density * scale).toFloat()
    }

    fun updatePois(pois: List<LocationLightweight>) {
        visiblePois = pois.mapNotNull { poi ->
            val title = poi.title ?: return@mapNotNull null
            PoiEntry(poi, classifyPoi(title))
        }
    }

    fun clearPois() {
        visiblePois = emptyList()
    }

    /**
     * Viewport-culled entries with anti-clutter thinning applied: greedy pass in DB
     * order, keeping an entry only if no already-kept label sits within
     * [MIN_LABEL_SEPARATION_DP]. Shared by [draw] and [onSingleTapConfirmed] so a
     * hidden-by-clutter label can neither render nor steal taps.
     */
    private fun keptEntries(mapView: MapView): List<Pair<PoiEntry, Point>> {
        val projection = mapView.projection
        val mapBounds = mapView.boundingBox
        // Separation scales with the tile bitmap so layout stays proportional at any zoom.
        val minSepPx = MIN_LABEL_SEPARATION_DP * density * zoomScaleFor(mapView.zoomLevelDouble)
        val kept = ArrayList<Pair<PoiEntry, Point>>()
        for (entry in visiblePois) {
            val lat = entry.poi.latitude ?: continue
            val lon = entry.poi.longitude ?: continue
            if (!mapBounds.contains(lat, lon)) continue
            val point = projection.toPixels(GeoPoint(lat, lon), null)
            var clash = false
            for ((_, kp) in kept) {
                val dx = (kp.x - point.x).toFloat()
                val dy = (kp.y - point.y).toFloat()
                if (dx * dx + dy * dy < minSepPx * minSepPx) {
                    clash = true
                    break
                }
            }
            if (!clash) kept.add(entry to point)
        }
        return kept
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !labelsVisible) return
        val zoom = mapView.zoomLevelDouble
        if (zoom < POI_MIN_ZOOM) return

        // Lockstep with the tile bitmap over-zoom so DB glyphs match tile glyphs
        // at every fractional zoom (both snap together on integer-zoom tile swap).
        val scale = zoomScaleFor(zoom)
        applyScale(scale)
        val eff = (density * scale).toFloat()

        val lineHeight = fontMetrics.bottom - fontMetrics.top
        val gapPx = TEXT_GAP_DP * eff

        for ((entry, point) in keptEntries(mapView)) {
            val px = point.x.toFloat()
            val py = point.y.toFloat()
            val text = entry.poi.title ?: continue

            // Stack DB text directly below the OSM tile text (which is centered at py)
            // OSM label height spans [py - lineHeight/2, py + lineHeight/2]
            // DB label top starts at py + lineHeight/2 + gapPx
            val textY = py + (lineHeight / 2f) + gapPx - fontMetrics.ascent

            // Triangle peak marker (△) safely above the top (tile) label:
            // base sits gapPx above the tile-text top edge derived from font metrics,
            // so it can never overlap or intersect glyphs at any density.
            val triBaseY = py - (lineHeight / 2f) - gapPx
            val triHalf = TRI_HALF_DP * eff
            val triTopY = triBaseY - TRI_HEIGHT_DP * eff
            val triPath = Path().apply {
                moveTo(px, triTopY)
                lineTo(px - triHalf, triBaseY)
                lineTo(px + triHalf, triBaseY)
                close()
            }
            canvas.drawPath(triPath, triHaloPaint)
            canvas.drawPath(triPath, triFillPaint)

            // Text label — video spec: bold, #5A4A2A, white halo (unified tile + DB style)
            canvas.drawText(text, px, textY, haloPaint)
            canvas.drawText(text, px, textY, textPaint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val tolerance = TAP_TOLERANCE_DP * mapView.resources.displayMetrics.density
        val tapGeo = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        val zoom = mapView.zoomLevelDouble

        if (zoom < POI_MIN_ZOOM) {
            onEmptyAreaTapped?.invoke(tapGeo)
            return onEmptyAreaTapped != null
        }

        var closest: PoiEntry? = null
        var closestDist = Double.MAX_VALUE

        // Same anti-clutter set as draw(): hidden labels stay untappable.
        for ((entry, screenPoi) in keptEntries(mapView)) {

            val dx = screenPoi.x - e.x
            val dy = screenPoi.y - e.y
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
            if (dist < tolerance && dist < closestDist) {
                closestDist = dist
                closest = entry
            }
        }

        if (closest != null) {
            onPoiTapped(
                PoiDetails(
                    name = closest.poi.title ?: "",
                    category = closest.category,
                    latitude = closest.poi.latitude ?: 0.0,
                    longitude = closest.poi.longitude ?: 0.0
                )
            )
            return true
        }

        onEmptyAreaTapped?.invoke(tapGeo)
        return onEmptyAreaTapped != null
    }

    private fun classifyPoi(title: String): String {
        if (title.startsWith("\u062C\u0628\u0644")) return "mountain"
        if (title.startsWith("\u0628\u0626\u0631")) return "well"
        if (title.startsWith("\u0648\u0627\u062F\u064A")) return "valley"
        if (title.startsWith("\u0631\u0648\u0636\u0629")) return "meadow"
        if (title.startsWith("\u0637\u0639\u0633")) return "dune"
        if (title.startsWith("\u0642\u0644\u0645\u0629")) return "qalma"
        if (title.startsWith("\u0634\u0639\u064A\u0628")) return "ravine"
        if (title.contains("\u0646\u0641\u0648\u062F")) return "dune"
        if (title.startsWith("\u0645\u0633\u062C\u062F")) return "mosque"
        if (title.startsWith("\u0645\u062D\u0637\u0629")) return "station"
        if (title.startsWith("\u0628\u064A\u062A")) return "house"
        if (title.startsWith("\u0642\u0631\u064A")) return "village"
        if (title.startsWith("\u0642\u0627\u0639")) return "plain"
        if (title.startsWith("\u0641\u064A\u0636")) return "spring"
        if (title.startsWith("\u0645\u0631\u0643\u0632")) return "center"
        return "poi"
    }
}
