package com.navigationgps.desert.map.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.navigationgps.desert.map.NavigationMath
import org.osmdroid.util.GeoPoint
import kotlin.math.min

object TargetCompassMath {
    /**
     * Calculates the relative angle [0, 360) for a target bearing relative
     * to the device heading azimuth.
     *
     * In Compose, positive rotationZ rotates clockwise.
     * When target bearing matches device heading, relative angle is 0° (points straight UP).
     */
    fun computeRelativeBearing(targetBearing: Float, deviceAzimuth: Float): Float {
        if (!targetBearing.isFinite() || !deviceAzimuth.isFinite()) return 0f
        return ((targetBearing - deviceAzimuth) % 360f + 360f) % 360f
    }
}

private val MiniCompassBg = Color(0xFF2C2013)
private val MiniCompassGold = Color(0xFFD4A017)
private val MiniCompassDotCore = Color(0xFF7A5A00)
private val MiniCompassShadow = Color(0x55000000)

/**
 * High-precision, zero-lag floating mini target compass widget.
 *
 * Performance Contract:
 * - Reads [azimuthState] and [bearingState] STRICTLY inside [Modifier.graphicsLayer].
 * - Bypasses both Composition and Layout phases: triggers 0 recompositions at 60Hz.
 * - Needle path is drawn into a cached RenderNode, rotating via hardware matrix on GPU.
 */
@Composable
fun FloatingMiniTargetCompass(
    azimuthState: State<Float>,
    bearingState: State<Float>,
    activeDestState: State<RouteSlot?>? = null,
    activeOriginState: State<RouteSlot?>? = null,
    locationProvider: (() -> GeoPoint?)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(50.dp)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(MiniCompassBg)
            .border(BorderStroke(1.5.dp, MiniCompassGold), CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = "فتح بوصلة الهدف",
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Dial tick marks: Device Forward indicator (top notch) & cardinal accents
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f

            // Device forward pip (12 o'clock notch)
            drawCircle(
                color = MiniCompassGold,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy - r + 4.dp.toPx())
            )
        }

        // Live Target Needle: 32dp chevron rotated on GPU via graphicsLayer
        val chevron = remember { Path() }
        Canvas(
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    val az = azimuthState.value
                    val b = bearingState.value
                    val effectiveBearing = if (b.isFinite()) {
                        b
                    } else {
                        val dest = activeDestState?.value
                        val origin = activeOriginState?.value
                        val loc = locationProvider?.invoke()
                        val fromLat = loc?.latitude ?: origin?.latitude
                        val fromLon = loc?.longitude ?: origin?.longitude
                        if (dest != null && fromLat != null && fromLon != null &&
                            (fromLat != 0.0 || fromLon != 0.0) &&
                            (dest.latitude != 0.0 || dest.longitude != 0.0)
                        ) {
                            NavigationMath.bearingDeg(
                                fromLat, fromLon,
                                dest.latitude, dest.longitude
                            )
                        } else {
                            Float.NaN
                        }
                    }
                    if (effectiveBearing.isFinite() && az.isFinite()) {
                        alpha = 1f
                        rotationZ = TargetCompassMath.computeRelativeBearing(effectiveBearing, az)
                    } else {
                        // Never default to 0°/North: hide needle when bearing cannot be determined
                        alpha = 0f
                    }
                }
        ) {
            val density = this.density
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) * 0.5f

            // Precision chevron geometry centered at (cx, cy)
            chevron.rewind()
            chevron.moveTo(cx, cy - r) // nose -> True North at rotation 0
            chevron.lineTo(cx + 0.72f * r, cy + 0.68f * r) // right wing
            chevron.lineTo(cx, cy + 0.32f * r) // recessed tail notch
            chevron.lineTo(cx - 0.72f * r, cy + 0.68f * r) // left wing
            chevron.close()

            // 1. Subtle drop shadow
            drawContext.canvas.save()
            drawContext.canvas.translate(0f, 2f * density)
            drawPath(path = chevron, color = MiniCompassShadow)
            drawContext.canvas.restore()

            // 2. Gold fill
            drawPath(path = chevron, color = MiniCompassGold)

            // 3. Crisp white stroke outline
            drawPath(
                path = chevron,
                color = Color.White,
                style = Stroke(
                    width = 1.75f * density,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round
                )
            )

            // 4. Central GPS anchor dot
            drawCircle(
                color = Color.White,
                radius = r * 0.20f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = MiniCompassDotCore,
                radius = r * 0.10f,
                center = Offset(cx, cy)
            )
        }
    }
}
