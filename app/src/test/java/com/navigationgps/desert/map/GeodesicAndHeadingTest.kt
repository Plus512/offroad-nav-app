package com.navigationgps.desert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesicAndHeadingTest {

    @Test
    fun testShortestArcAngularDifference() {
        // Single source of truth — production formula in HeadingFilter.
        // Turning across 0° (North) from 350° to 10° should be +20°, not -340°
        val diffNorthCross = HeadingFilter.shortestArcDelta(10f, 350f)
        assertEquals(20f, diffNorthCross, 0.01f)

        // Turning across 0° (North) from 10° to 350° should be -20°, not +340°
        val diffNorthCrossReverse = HeadingFilter.shortestArcDelta(350f, 10f)
        assertEquals(-20f, diffNorthCrossReverse, 0.01f)

        // Normal 90 deg turn
        val diffRight = HeadingFilter.shortestArcDelta(90f, 0f)
        assertEquals(90f, diffRight, 0.01f)
    }

    @Test
    fun adaptiveFilter_freezesBelowDeadband() {
        val f = HeadingFilter()
        f.snapTo(0f)
        repeat(50) { assertNull(f.update(0.4f, 0.02f)) } // tremor swallowed
    }

    @Test
    fun adaptiveFilter_tracksFastTurn() {
        val f = HeadingFilter()
        f.snapTo(0f)
        // 90° step → snap path on the FIRST sample (crisp, no tail).
        val first = f.update(90f, 0.02f)
        assertNotNull(first)
        assertEquals(90f, first!!, 0.5f)
        // Target now stationary → subsequent samples correctly freeze (null).
        assertNull(f.update(90f, 0.02f))
    }

    @Test
    fun adaptiveFilter_shortestArcAcrossNorth() {
        val f = HeadingFilter()
        f.snapTo(350f)
        val out = f.update(10f, 0.02f)!! // +20° diff, high omega → large step
        assertTrue(out > 350f || out < 30f) // wrapped forward, never backward through 180°
    }

    @Test
    fun smoothstep_endpoints() {
        assertEquals(0f, HeadingFilter.smoothstep(8f, 90f, 0f), 0.0001f)
        assertEquals(1f, HeadingFilter.smoothstep(8f, 90f, 200f), 0.0001f)
    }

    @Test
    fun adaptiveFilter_hysteresisRequiresLargerExit() {
        val f = HeadingFilter()
        f.snapTo(0f)
        // Enter frozen state with a small tremor sample.
        assertNull(f.update(0.2f, 0.02f))
        // Inside hysteresis band (0.9 + 0.35 = 1.25) → stays frozen.
        assertNull(f.update(1.0f, 0.02f))
        // Beyond hysteresis band → unfreezes and returns a value.
        assertNotNull(f.update(5.0f, 0.02f))
    }

    @Test
    fun shortestArcDelta_matchesLegacyFormula() {
        assertEquals(20f, HeadingFilter.shortestArcDelta(10f, 350f), 0.01f)
        assertEquals(-20f, HeadingFilter.shortestArcDelta(350f, 10f), 0.01f)
        assertEquals(-1f, HeadingFilter.shortestArcDelta(359f, 0f), 0.01f)
        // 359° -> 1° is a +2° forward step, never a -358° reverse spin.
        assertEquals(2f, HeadingFilter.shortestArcDelta(1f, 359f), 0.01f)
    }

    @Test
    fun adaptiveFilter_northWrapSequenceAdvancesForward() {
        // Regression: 350° -> 355° -> 0° -> 5° must advance +15° forward
        // through North, never spin ~345° backwards.
        val f = HeadingFilter()
        f.snapTo(350f)
        var prev = 350f
        val targets = listOf(355f, 0f, 5f)
        for (target in targets) {
            val out = f.update(target, 0.02f)
            assertNotNull("filter froze on real $prev° -> $target° step", out)
            val step = HeadingFilter.shortestArcDelta(out!!, prev)
            // Forward progress, small arc — a wrap bug would show as ~-355°.
            assertTrue("backward spin $prev° -> $target° (got $prev° -> $out°)", step > -30f && step < 30f)
            assertTrue("expected forward motion $prev° -> $target° (step=$step)", step > 0f)
            prev = out
        }
        // End near 5° (wrapped domain): shortest arc from start is +15°.
        // Tight tolerance: alpha=1 at this omega means exact tracking.
        val total = HeadingFilter.shortestArcDelta(prev, 350f)
        assertEquals(15f, total, 1.0f)
    }

    @Test
    fun zoomScaleFor_clampedOverviewToCloseUp() {
        // 75% at country overview (z5) → 125% at trail close-up (z19).
        assertEquals(0.75f, SmoothLocationOverlay.zoomScaleFor(5.0), 0.001f)
        assertEquals(1.25f, SmoothLocationOverlay.zoomScaleFor(19.0), 0.001f)
        // Base operating zoom 15 → ~1.107.
        assertEquals(1.107f, SmoothLocationOverlay.zoomScaleFor(15.0), 0.005f)
        // Clamped outside the design range.
        assertEquals(0.75f, SmoothLocationOverlay.zoomScaleFor(0.0), 0.001f)
        assertEquals(1.25f, SmoothLocationOverlay.zoomScaleFor(25.0), 0.001f)
        // Monotonic growth across the range.
        var prev = 0f
        for (z in 5..19) {
            val s = SmoothLocationOverlay.zoomScaleFor(z.toDouble())
            assertTrue("non-monotonic at zoom $z: $s <= $prev", s >= prev)
            prev = s
        }
    }

    @Test
    fun adaptiveFilter_northWrapSlowStepMovesForward() {
        // Partial-alpha case: with dt=0.10 a 5° step gives omega=50°/s,
        // inside (w0,w1) so 0<alpha<1. A naive lerp would sweep backwards;
        // the filter must stay on the forward arc.
        val f = HeadingFilter()
        f.snapTo(355f)
        val out = f.update(0f, 0.10f)!!
        val step = HeadingFilter.shortestArcDelta(out, 355f)
        assertTrue("backward sweep: step=$step", step > 0f && step < 5.5f)
        assertTrue("must stay on forward arc 355->360, got $out", out > 355f || out < 10f)
    }

    @Test
    fun adaptiveFilter_northWrapReverseDirection() {
        // Counter-clockwise: 5° -> 0° -> 355° -> 350° must go -15°,
        // never +345° forward.
        val f = HeadingFilter()
        f.snapTo(5f)
        var prev = 5f
        for (target in listOf(0f, 355f, 350f)) {
            val out = f.update(target, 0.02f)!!
            val step = HeadingFilter.shortestArcDelta(out, prev)
            assertTrue("CCW expected, step=$step ($prev° -> $out°)", step < 0f && step > -30f)
            prev = out
        }
        assertEquals(-15f, HeadingFilter.shortestArcDelta(prev, 5f), 1.0f)
    }

    @Test
    fun testGeodesicInterpolationEndpoints() {
        val start = org.osmdroid.util.GeoPoint(24.7136, 46.6753)
        val dest = org.osmdroid.util.GeoPoint(26.4207, 50.0888)

        val points = OsmdroidRouteOverlay.interpolateGeodesic(start, dest, numPoints = 50)
        assertEquals(51, points.size)
        assertEquals(start.latitude, points.first().latitude, 0.001)
        assertEquals(start.longitude, points.first().longitude, 0.001)
        assertEquals(dest.latitude, points.last().latitude, 0.001)
        assertEquals(dest.longitude, points.last().longitude, 0.001)
    }

    @Test
    fun interpolateGeodesic_clampsNumPoints() {
        val start = org.osmdroid.util.GeoPoint(24.0, 46.0)
        val dest = org.osmdroid.util.GeoPoint(25.0, 47.0)
        // 0/negative would divide by zero → clamped to 2 (3 points), never NaN.
        for (n in listOf(0, -5, 1)) {
            val pts = OsmdroidRouteOverlay.interpolateGeodesic(start, dest, numPoints = n)
            assertEquals(3, pts.size)
            pts.forEach {
                assertTrue(it.latitude.isFinite())
                assertTrue(it.longitude.isFinite())
            }
        }
        // Upper clamp: 201 points max.
        assertEquals(201, OsmdroidRouteOverlay.interpolateGeodesic(start, dest, numPoints = 10000).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun interpolateGeodesic_rejectsOutOfRangeLat() {
        OsmdroidRouteOverlay.interpolateGeodesic(
            org.osmdroid.util.GeoPoint(91.0, 46.0),
            org.osmdroid.util.GeoPoint(25.0, 47.0)
        )
    }

    @Test
    fun interpolateGeodesic_antimeridianStaysFinite() {
        val pts = OsmdroidRouteOverlay.interpolateGeodesic(
            org.osmdroid.util.GeoPoint(24.0, 179.0),
            org.osmdroid.util.GeoPoint(24.0, -179.0),
            numPoints = 10
        )
        assertEquals(11, pts.size)
        pts.forEach {
            assertTrue(it.latitude.isFinite())
            assertTrue(it.longitude.isFinite())
            assertTrue(it.longitude in -180.0..180.0)
        }
    }
}
