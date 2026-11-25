package com.alruwaili.garminalbarr.map

import kotlin.math.abs

/**
 * Feature flag for zero-risk rollout of the hyper-smooth heading pipeline.
 *
 * - `true` (default): adaptive EMA + tilt remap + VSync-gated render (§2-§4).
 * - `false`: legacy static-lerp path in [SmoothLocationOverlay] for field rollback.
 *
 * Backed by a volatile runtime switch so it can be flipped from settings /
 * remote-config without a hotfix. A future step may back this with
 * `BuildConfig.HEADING_V2`, but the runtime default must stay `true`.
 */
object HeadingFlags {
    @Volatile
    var HEADING_V2_ENABLED: Boolean = true
}

/**
 * Adaptive EMA heading filter: near-zero damping at high angular velocity,
 * frozen at rest.
 *
 * Pure Kotlin — unit-test without Robolectric. All angles in degrees [0,360).
 *
 * Physical behavior:
 * - Fast turn (omega > [w1DegPerSec]): alpha -> 1, 1:1 tracking in 1-2 samples.
 * - Stationary (omega < [w0DegPerSec] and |diff| < [deadbandStable]): frozen,
 *   returns null so the caller skips invalidate/notify (zero jitter).
 * - Slow drift: alpha in [alphaMin, 1] via smoothstep S-curve.
 *
 * Design note: velocity-based smoothstep is kept intentionally instead of a
 * position-based 3-level alpha (0.85/0.35/0.08) — it gives zero lag on fast
 * turns at any sensor rate (dt-normalized) while staying rock-solid on
 * micro-tremor. All smoothing runs along [shortestArcDelta] so 359° <-> 0°
 * crosses take the +2° forward arc, never a 358° reverse spin.
 */
class HeadingFilter(
    var deadbandStable: Float = 0.9f,
    var deadbandHyst: Float = 0.35f,
    var alphaMin: Float = 0.06f,
    var w0DegPerSec: Float = 8f,
    var w1DegPerSec: Float = 90f,
    var snapThreshold: Float = 25f,
    var dtRefSec: Float = 0.02f,
) {
    var current: Float = 0f
        private set
    var frozen: Boolean = true
        private set

    /** 1.0 normal, 0.6 low accuracy, 0.35 unreliable (near metal). */
    var accuracyScale: Float = 1f

    fun snapTo(deg: Float) {
        current = ((deg % 360f) + 360f) % 360f
        // Start frozen at rest so stationary tremor is swallowed; any real turn
        // (|diff| > deadband + hyst) unfreezes immediately on the next sample.
        frozen = true
    }

    /**
     * @return new filtered azimuth, or null if frozen
     * (caller must skip invalidate/notify).
     */
    fun update(targetDeg: Float, dtSec: Float): Float? {
        val dt = dtSec.coerceIn(0.008f, 0.10f)
        val diff = shortestArcDelta(targetDeg, current)
        val absDiff = abs(diff)
        val omega = absDiff / dt // deg/s

        if (frozen) {
            if (absDiff < deadbandStable + deadbandHyst) return null
            frozen = false
        } else {
            if (absDiff < deadbandStable && omega < w0DegPerSec) {
                frozen = true
                return null
            }
        }
        if (absDiff >= snapThreshold / accuracyScale.coerceAtLeast(0.35f)) {
            // Scaled snap: 25° when clean, ~42° at low accuracy, ~71° when
            // unreliable — magnetic spikes near metal must not snap the arrow.
            current = ((targetDeg % 360f) + 360f) % 360f
            return current
        }
        var alpha = alphaMin + (1f - alphaMin) * smoothstep(w0DegPerSec, w1DegPerSec, omega)
        alpha *= accuracyScale
        // dt-normalize: keep feel constant across 16 Hz .. 100 Hz sensor rates
        val refSteps = dt / dtRefSec
        alpha = 1f - Math.pow((1f - alpha).toDouble(), refSteps.toDouble()).toFloat()
        alpha = alpha.coerceIn(alphaMin * accuracyScale, 1f)

        current = ((current + diff * alpha) % 360f + 360f) % 360f
        return current
    }

    companion object {
        /**
         * Signed shortest-arc difference in [-180, 180).
         * Positive = clockwise toward target. Antipodal returns -180 (CCW).
         *
         * Param order is (target, current) — i.e. `shortestArcDelta(1, 359) = +2`
         * (forward through North). Equivalent to `((to - from + 540) % 360) - 180`.
         */
        fun shortestArcDelta(target: Float, current: Float): Float =
            ((target - current + 540f) % 360f) - 180f

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            if (x <= e0) return 0f
            if (x >= e1) return 1f
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /**
         * Shortest-arc linear interpolation between two angles.
         * t=0 → from, t=1 → to, always traversing the shorter arc.
         */
        fun shortestArcSlerp(from: Float, to: Float, t: Float): Float {
            val diff = shortestArcDelta(to, from)
            return ((from + diff * t) % 360f + 360f) % 360f
        }
    }
}
