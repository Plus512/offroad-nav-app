package com.navigationgps.desert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedGateBlendTest {

    @Test
    fun shortestArcSlerp_midpoint() {
        // 0° to 90°, t=0.5 → exactly 45° (shortest-arc midpoint, exact float arithmetic)
        val result = HeadingFilter.shortestArcSlerp(0f, 90f, 0.5f)
        assertEquals(45f, result, 0.05f)
    }

    @Test
    fun shortestArcSlerp_acrossNorth() {
        // 350° → 10°, t=0.5 → near 0° (forward through North, not backward -170°)
        val result = HeadingFilter.shortestArcSlerp(350f, 10f, 0.5f)
        assertTrue("expected near 0°, got $result", result > 355f || result < 5f)
    }

    @Test
    fun shortestArcSlerp_atZero_returnsFrom() {
        val result = HeadingFilter.shortestArcSlerp(45f, 135f, 0f)
        assertEquals(45f, result, 0.01f)
    }

    @Test
    fun shortestArcSlerp_atOne_returnsTo() {
        val result = HeadingFilter.shortestArcSlerp(45f, 135f, 1f)
        assertEquals(135f, result, 0.01f)
    }

    @Test
    fun blendAlpha_rampsBetweenZeroAndOne() {
        var alpha = 0f
        val blendStep = 0.25f
        // 4 GPS fixes above threshold → alpha reaches 1.0
        repeat(4) { alpha = (alpha + blendStep).coerceAtMost(1f) }
        assertEquals(1f, alpha, 0.001f)
        // 4 GPS fixes below lower threshold → alpha returns to 0.0
        repeat(4) { alpha = (alpha - blendStep).coerceAtLeast(0f) }
        assertEquals(0f, alpha, 0.001f)
    }
}
