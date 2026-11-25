package com.alruwaili.garminalbarr.map.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alruwaili.garminalbarr.map.NavigationMath
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint

private val CompassBg = Color(0xFF1F1A16)
private val CompassGold = Color(0xFFDDA01E)
private val CompassTextPrimary = Color(0xFFFFFFFF)
private val CompassTextSecondary = Color(0xFFA89F91)
private val CompassRing = Color(0xFF42372E)
private val CompassTick = Color(0xFF756A5E)

/**
 * Target selected for full-screen compass guidance.
 *
 * @param slot destination waypoint.
 * @param userLat user latitude snapshot at open time (static-bearing fallback).
 * @param userLon user longitude snapshot at open time (static-bearing fallback).
 */
data class CompassTarget(
    val slot: RouteSlot,
    val userLat: Double,
    val userLon: Double
)

/**
 * Edge-to-edge car-mount compass overlay. Reads the SHARED sensor pipeline
 * ([azimuthState] @ ~15 Hz, [bearingState] @ 1 Hz) — creates no sensors.
 *
 * - Rose rotates `-azimuth` so True North stays earth-aligned.
 * - Gold needle rotates `relative = bearing − azimuth` (shortest arc);
 *   0 = facing target = straight UP. Compose positive rotation is clockwise,
 *   so NO sign inversion: east target (rel=+90) points right.
 */
@Composable
fun TargetCompassOverlay(
    target: CompassTarget,
    azimuthState: State<Float>,
    bearingState: State<Float>,
    activeDest: RouteSlot?,
    remainingMeters: Double?,
    /** Live GPS fix sampled at 1 Hz so the fallback bearing tracks walking. */
    locationProvider: () -> GeoPoint?,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    var liveFix by remember(target) { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(target) {
        while (true) {
            try {
                liveFix = locationProvider()
            } catch (_: Exception) {
            }
            delay(1000L)
        }
    }

    // Live 1 Hz bearing is valid only when the overlay target IS the active
    // navigation destination; otherwise fall back to user-fix → target,
    // refreshed at 1 Hz above instead of frozen at open time.
    val liveBearing by bearingState
    val fallbackOrigin = liveFix?.let { it.latitude to it.longitude }
        ?: (target.userLat to target.userLon)
    val bearing = remember(target, activeDest, liveBearing, fallbackOrigin) {
        val matchesActive = activeDest != null &&
            NavigationMath.distanceMeters(
                activeDest.latitude, activeDest.longitude,
                target.slot.latitude, target.slot.longitude
            ) < 50.0
        if (matchesActive && liveBearing.isFinite()) {
            liveBearing
        } else {
            NavigationMath.bearingDeg(
                fallbackOrigin.first, fallbackOrigin.second,
                target.slot.latitude, target.slot.longitude
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompassBg)
            .padding(16.dp)
    ) {
        CompassTopBar(target = target, onClose = onClose)
        Spacer(modifier = Modifier.height(8.dp))
        CompassDial(
            azimuthState = azimuthState,
            bearing = bearing,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        CompassBottomHud(
            bearing = bearing,
            azimuthState = azimuthState,
            remainingMeters = remainingMeters
        )
    }
}

@Composable
private fun CompassTopBar(target: CompassTarget, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "إغلاق البوصلة",
                tint = CompassTextPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.slot.title,
                color = CompassTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(
                    Locale.US, "%.5f, %.5f",
                    target.slot.latitude, target.slot.longitude
                ),
                color = CompassTextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Isolated 15 Hz leaf: the ONLY composable reading [azimuthState] here.
 * Rose is earth-aligned (rotate −az); needle rotates by [bearing] relative to dial North.
 * The net angle on screen is (bearing − az), exactly matching CompassActivity.
 */
@Composable
private fun CompassDial(
    azimuthState: State<Float>,
    bearing: Float,
    modifier: Modifier = Modifier
) {
    val az by azimuthState
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1f)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f
            val center = Offset(cx, cy)

            // Earth-aligned rose (rotates by -az).
            rotate(-az, pivot = center) {
                drawCircle(color = CompassRing, radius = r, style = Stroke(width = 3.dp.toPx()))
                drawCircle(
                    color = CompassRing,
                    radius = r * 0.78f,
                    style = Stroke(width = 1.dp.toPx())
                )
                for (deg in 0 until 360 step 15) {
                    val rad = Math.toRadians(deg.toDouble())
                    val isCardinal = deg % 90 == 0
                    val startR = if (isCardinal) r * 0.78f else r * 0.86f
                    drawLine(
                        color = if (deg == 0) CompassGold else CompassTick,
                        start = Offset(
                            cx + (startR * sin(rad)).toFloat(),
                            cy - (startR * cos(rad)).toFloat()
                        ),
                        end = Offset(
                            cx + (r * 0.95f * sin(rad)).toFloat(),
                            cy - (r * 0.95f * cos(rad)).toFloat()
                        ),
                        strokeWidth = if (isCardinal) 4.dp.toPx() else 2.dp.toPx()
                    )
                }
                drawCardinalLabels(measurer, center, r * 0.64f)

                // Target needle inside the dial: rotates by bearing (targetBearing) relative to dial North.
                // Net screen angle: targetBearing - phoneAzimuth.
                rotate(bearing, pivot = center) {
                    val nose = r * 0.72f
                    val wing = r * 0.16f
                    val tail = r * 0.30f
                    val needle = Path().apply {
                        moveTo(cx, cy - nose)
                        lineTo(cx + wing, cy + tail * 0.4f)
                        lineTo(cx, cy + tail * 0.05f)
                        lineTo(cx - wing, cy + tail * 0.4f)
                        close()
                    }
                    drawPath(path = needle, color = CompassGold)
                }
            }

            drawCircle(color = Color.White, radius = r * 0.055f, center = center)
            drawCircle(color = CompassGold, radius = r * 0.028f, center = center)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCardinalLabels(
    measurer: TextMeasurer,
    center: Offset,
    radius: Float
) {
    val labels = listOf("شمال" to 0, "شرق" to 90, "جنوب" to 180, "غرب" to 270)
    for ((text, deg) in labels) {
        val rad = Math.toRadians(deg.toDouble())
        val style = androidx.compose.ui.text.TextStyle(
            color = if (deg == 0) CompassGold else CompassTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        val layout = measurer.measure(text, style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                center.x + (radius * sin(rad)).toFloat() - layout.size.width / 2f,
                center.y - (radius * cos(rad)).toFloat() - layout.size.height / 2f
            )
        )
    }
}

@Composable
private fun CompassBottomHud(
    bearing: Float,
    azimuthState: State<Float>,
    remainingMeters: Double?
) {
    val normBearing = ((bearing % 360f) + 360f) % 360f
    val distanceText = when {
        remainingMeters == null -> "جاري تحديد المسافة…"
        NavigationMath.isArrived(remainingMeters) -> "وصلت لوجهتك"
        else -> NavigationMath.formatRemaining(remainingMeters)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = distanceText,
            color = CompassGold,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "الهدف: ${normBearing.toInt()}° ${NavigationMath.arabicDirection(normBearing)}",
            color = CompassTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        HudHeading(azimuthState = azimuthState)
    }
}

/** Isolated 15 Hz leaf for the live heading readout. */
@Composable
private fun HudHeading(azimuthState: State<Float>) {
    val az by azimuthState
    val normAz = ((az % 360f) + 360f) % 360f
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Canvas triangle (points UP at 0): clockwise-positive rotation,
        // east heading (+90) points right. No inversion.
        Canvas(
            modifier = Modifier
                .size(20.dp)
                .rotate(normAz)
        ) {
            val w = size.width
            val h = size.height
            drawPath(
                path = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(w, h)
                    lineTo(w / 2f, h * 0.72f)
                    lineTo(0f, h)
                    close()
                },
                color = CompassGold
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "اتجاهك: ${normAz.toInt()}° ${NavigationMath.arabicDirection(normAz)}",
            color = CompassTextSecondary,
            fontSize = 14.sp
        )
    }
}

/**
 * Gold chevron pointing UP at zero rotation; rotate clockwise-positive by the
 * caller ([relative] = target bearing − azimuth, shortest arc).
 * Exact mini replica of SmoothLocationOverlay.drawNavigationArrow():
 * - Drop shadow (offset down)
 * - Gold fill #D4A017
 * - Crisp 2dp white outline
 * - Central GPS anchor dot (white outer + #7A5A00 inner)
 * Canvas-drawn so it never depends on the extended material-icons set.
 */
@Composable
internal fun TargetArrowMark(
    relative: Float,
    modifier: Modifier = Modifier,
    tint: Color = CompassGold,
    showShadow: Boolean = true
) {
    val goldFill = Color(0xFFD4A017)
    val shadowColor = Color(0x44000000)
    val dotCore = Color(0xFF7A5A00)
    val density = LocalDensity.current.density

    Canvas(modifier = modifier.rotate(relative)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.5f

        // Chevron geometry matching SmoothLocationOverlay
        val nose = r
        val wing = r * 0.72f
        val tail = r * 0.32f
        val chevron = Path().apply {
            moveTo(cx, cy - nose)
            lineTo(cx + wing, cy + tail * 0.68f)
            lineTo(cx, cy + tail)
            lineTo(cx - wing, cy + tail * 0.68f)
            close()
        }

        // 1. Drop shadow (offset down by 2.5dp equivalent) — skipped in mini mode
        if (showShadow) {
            drawContext.canvas.save()
            drawContext.canvas.translate(0f, 2.5f * density)
            drawPath(path = chevron, color = shadowColor)
            drawContext.canvas.restore()
        }

        // 2. Gold fill
        drawPath(path = chevron, color = goldFill)

        // 3. White stroke outline (2dp)
        drawPath(
            path = chevron,
            color = Color.White,
            style = Stroke(width = 2f * density, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )

        // 4. Central GPS anchor dot
        drawCircle(color = Color.White, radius = r * 0.19f, center = Offset(cx, cy))
        drawCircle(color = dotCore, radius = r * 0.10f, center = Offset(cx, cy))
    }
}
