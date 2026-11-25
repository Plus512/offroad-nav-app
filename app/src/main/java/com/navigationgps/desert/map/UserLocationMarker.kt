package com.navigationgps.desert.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.MotionEvent
import org.osmdroid.util.RectL
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Custom Osmdroid [Marker] that renders saved user locations directly onto the Canvas
 * as native text labels with exact visual parity to database POIs and raster tile captions.
 *
 * Features:
 * - Completely pin-free and badge-free: native black text (#1A1A1A) with a pure white (#FFFFFF) halo.
 * - Zoom visibility threshold: strictly hidden when zoom < 12.0 (matches native POI minimum zoom).
 * - Continuous zero-jump zoom scaling: scales smoothly from 12.5dp to 16.9dp across zoom 12 to 17.
 * - Screen-space billboarding: rotates counter to map orientation so text remains permanently upright.
 * - Custom projection and viewport culling: bypasses Osmdroid Marker's null-icon early returns.
 * - Oriented touch hit-testing: expands label bounds with 16dp touch slop for responsive tapping.
 */
open class UserLocationMarker(
    private val mapView: MapView,
    val locationTitle: String
) : Marker(mapView) {

    data class TextMetrics(
        val textWidth: Float,
        val textHeight: Float,
        val baselineOffset: Float
    )

    companion object {
        const val MAX_CHARS = 18
        const val DEFAULT_TITLE = "موقع محفوظ"
        const val POI_MIN_ZOOM = 12.0
        const val ZOOM_SCALE_MIN = 12.0
        const val ZOOM_SCALE_MAX = 17.0
        const val ZOOM_GROWTH_FACTOR = 0.35

        // Calibrated native vector styling matching Mapsforge terrain captions (desert_merged.xml)
        const val TEXT_FILL_COLOR = 0xFF1A1A1A.toInt() // Deep charcoal black for strong contrast
        const val TEXT_HALO_COLOR = 0xFFFFFFFF.toInt() // Pure white halo
        const val TEXT_SIZE_DP = 12.5f
        const val TEXT_HALO_WIDTH_DP = 2.8f
        const val TOUCH_SLOP_DP = 16f

        /**
         * Continuous, zero-jump monotonic scaling factor across zoom levels [12.0, 17.0].
         * Eliminates the 2x-to-1x snap drop at integer zoom boundaries while gently
         * scaling text size from 12.5dp (at zoom 12.0) to 16.9dp (at zoom 17.0+).
         */
        fun zoomScaleFor(zoom: Double): Double {
            val clamped = zoom.coerceIn(ZOOM_SCALE_MIN, ZOOM_SCALE_MAX)
            val progress = (clamped - ZOOM_SCALE_MIN) / (ZOOM_SCALE_MAX - ZOOM_SCALE_MIN)
            return 1.0 + (progress * ZOOM_GROWTH_FACTOR)
        }

        /**
         * Normalizes and truncates a user-provided location title.
         * Falls back to [DEFAULT_TITLE] if blank, and appends an ellipsis if longer than [maxChars].
         */
        fun truncateTitle(title: String?, maxChars: Int = MAX_CHARS): String {
            val trimmed = title?.trim()?.ifEmpty { DEFAULT_TITLE } ?: DEFAULT_TITLE
            return if (trimmed.length > maxChars) {
                trimmed.take(maxChars - 1).trimEnd() + "…"
            } else {
                trimmed
            }
        }

        /**
         * Computes text dimensions and baseline offset from measured font metrics.
         */
        fun computeTextMetrics(
            textWidth: Float,
            fontAscent: Float,
            fontDescent: Float
        ): TextMetrics {
            val textHeight = fontDescent - fontAscent
            val baselineOffset = -fontAscent
            return TextMetrics(textWidth, textHeight, baselineOffset)
        }

        // Shared static Paint instances to prevent per-frame object allocations during 60 FPS render loops
        private val textPaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_FILL_COLOR
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
        }

        private val haloPaint: Paint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_HALO_COLOR
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }
        }

        private val sharedFontMetrics = Paint.FontMetrics()
    }

    val displayTitle: String = truncateTitle(locationTitle)
    private var isTextDisplayed: Boolean = false

    // Reusable rects for zero allocations in draw() and hitTest()
    private val localTextRect = Rect()
    private val orientedTextRect = Rect()

    init {
        // Explicitly clear icon to ensure no pin drawable is retained or rendered
        icon = null
        setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
    }

    override fun isDisplayed(): Boolean = isTextDisplayed

    override fun draw(canvas: Canvas, pj: Projection) {
        val zoom = mapView.zoomLevelDouble
        if (zoom < POI_MIN_ZOOM || !isEnabled || displayTitle.isEmpty()) {
            isTextDisplayed = false
            return
        }

        // 1. Convert GeoPoint to screen pixels directly (bypassing Marker.java early return on icon == null)
        val pos = position ?: return
        pj.toPixels(pos, mPositionPixels)
        val px = mPositionPixels.x.toFloat()
        val py = mPositionPixels.y.toFloat()

        // 2. Dynamic scaling lockstep with raster tile over-zoom
        val scale = zoomScaleFor(zoom)
        val density = mapView.resources.displayMetrics.density
        val textSizePx = (TEXT_SIZE_DP * density * scale).toFloat()
        val haloWidthPx = (TEXT_HALO_WIDTH_DP * density * scale).toFloat()

        synchronized(textPaint) {
            if (abs(textPaint.textSize - textSizePx) > 0.05f) {
                textPaint.textSize = textSizePx
                haloPaint.textSize = textSizePx
                haloPaint.strokeWidth = haloWidthPx
                textPaint.getFontMetrics(sharedFontMetrics)
            }

            // 3. Screen-space rotation compensation: un-rotates map orientation so text is permanently upright
            val counterRotation = if (isFlat) -this.rotation else -pj.orientation - this.rotation

            // 4. Compute oriented touch bounding box with 16dp touch slop expansion
            val textWidth = textPaint.measureText(displayTitle)
            val textHeight = sharedFontMetrics.descent - sharedFontMetrics.ascent
            val touchSlop = TOUCH_SLOP_DP * density
            val halfW = (textWidth / 2f) + touchSlop
            val halfH = (textHeight / 2f) + touchSlop

            localTextRect.set(
                (px - halfW).toInt(),
                (py - halfH).toInt(),
                (px + halfW).toInt(),
                (py + halfH).toInt()
            )
            RectL.getBounds(localTextRect, mPositionPixels.x, mPositionPixels.y, counterRotation.toDouble(), orientedTextRect)

            // 5. Viewport culling: only draw if the oriented bounding box intersects visible canvas bounds
            isTextDisplayed = Rect.intersects(orientedTextRect, canvas.clipBounds)
            if (!isTextDisplayed) {
                return
            }

            val saveCount = canvas.save()
            canvas.rotate(counterRotation, px, py)

            // Center text vertically at py
            val baseline = py - (sharedFontMetrics.ascent + sharedFontMetrics.descent) / 2f

            // 6. Draw contrast halo outline, then text fill (matching native place caption rule)
            canvas.drawText(displayTitle, px, baseline, haloPaint)
            canvas.drawText(displayTitle, px, baseline, textPaint)

            canvas.restoreToCount(saveCount)
        }
    }

    override fun hitTest(event: MotionEvent, mapView: MapView): Boolean {
        // Bypasses super.hitTest() which returns false when icon == null
        if (mapView.zoomLevelDouble < POI_MIN_ZOOM || !isEnabled || !isTextDisplayed) return false
        return orientedTextRect.contains(event.x.toInt(), event.y.toInt())
    }
}
