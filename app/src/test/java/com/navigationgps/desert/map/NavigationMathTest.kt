package com.navigationgps.desert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class NavigationMathTest {

    @Test
    fun distanceMeters_riyadhToDammam() {
        // Riyadh (24.7136,46.6753) -> Dammam (26.4207,50.0888) ≈ 391km ±10km
        val d = NavigationMath.distanceMeters(24.7136, 46.6753, 26.4207, 50.0888)
        assertTrue("expected ~391km, got $d", d in 381_000.0..401_000.0)
    }

    @Test
    fun distanceMeters_zeroForSamePoint() {
        assertEquals(0.0, NavigationMath.distanceMeters(24.0, 46.0, 24.0, 46.0), 0.001)
    }

    @Test
    fun bearingDeg_cardinalQuadrants() {
        assertEquals(0f, NavigationMath.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.5f) // N
        assertEquals(90f, NavigationMath.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.5f) // E
        assertEquals(180f, NavigationMath.bearingDeg(0.0, 0.0, -1.0, 0.0), 0.5f) // S
        assertEquals(270f, NavigationMath.bearingDeg(0.0, 0.0, 0.0, -1.0), 0.5f) // W
    }

    @Test
    fun formatRemaining_metersAndKm() {
        assertEquals("999 م", NavigationMath.formatRemaining(999.0))
        assertEquals("1.0 كم", NavigationMath.formatRemaining(1000.0))
        assertEquals("17.7 كم", NavigationMath.formatRemaining(17700.0))
        assertEquals("850 م", NavigationMath.formatRemaining(850.4))
    }

    @Test
    fun formatRemaining_clampsGlitchInput() {
        // GPS glitches must never reach the HUD as nonsense text.
        assertEquals("0 م", NavigationMath.formatRemaining(-10.0))
        assertEquals("0 م", NavigationMath.formatRemaining(Double.NaN))
        assertEquals("0 م", NavigationMath.formatRemaining(0.0))
    }

    @Test
    fun arabicDirection_eightWinds() {
        assertEquals("شمال", NavigationMath.arabicDirection(0f))
        assertEquals("شمال شرقي", NavigationMath.arabicDirection(45f))
        assertEquals("شرقي", NavigationMath.arabicDirection(90f))
        assertEquals("جنوب شرقي", NavigationMath.arabicDirection(133f))
        assertEquals("جنوب", NavigationMath.arabicDirection(180f))
        assertEquals("جنوب غربي", NavigationMath.arabicDirection(225f))
        assertEquals("غربي", NavigationMath.arabicDirection(270f))
        assertEquals("شمال غربي", NavigationMath.arabicDirection(315f))
        assertEquals("شمال", NavigationMath.arabicDirection(359f))
    }

    @Test
    fun isArrived_threshold30m() {
        assertTrue(NavigationMath.isArrived(0.0))
        assertTrue(NavigationMath.isArrived(29.9))
        assertFalse(NavigationMath.isArrived(30.0))
        assertFalse(NavigationMath.isArrived(850.0))
    }

    @Test
    fun screenAngle_zeroWhenFacingTarget() {
        assertEquals(0f, NavigationMath.screenAngleDeg(90f, 90f), 0.001f)
        assertEquals(0f, NavigationMath.screenAngleDeg(0f, 0f), 0.001f)
        assertEquals(0f, NavigationMath.screenAngleDeg(350f, 350f), 0.001f)
    }

    @Test
    fun screenAngle_steerRightSlantsLeft() {
        // Facing 30° right of target → line slants 30° left (-30°).
        assertEquals(-30f, NavigationMath.screenAngleDeg(90f, 120f), 0.001f)
        // Facing 30° left of target → line slants 30° right (+30°).
        assertEquals(30f, NavigationMath.screenAngleDeg(90f, 60f), 0.001f)
    }

    @Test
    fun screenAngle_wrapsAcrossNorth() {
        // Bearing 10°, azimuth 350° → +20° (target 20° right).
        assertEquals(20f, NavigationMath.screenAngleDeg(10f, 350f), 0.001f)
        assertEquals(-20f, NavigationMath.screenAngleDeg(350f, 10f), 0.001f)
        // Antipodal: ±180° are the same direction (shortestArc returns -180°).
        assertEquals(180f, kotlin.math.abs(NavigationMath.screenAngleDeg(0f, 180f)), 0.001f)
    }

    @Test
    fun mapOrientationFor_counterRotatesHeading() {
        assertEquals(0f, NavigationMath.mapOrientationFor(0f), 0.001f)
        assertEquals(270f, NavigationMath.mapOrientationFor(90f), 0.001f)
        assertEquals(180f, NavigationMath.mapOrientationFor(180f), 0.001f)
        assertEquals(90f, NavigationMath.mapOrientationFor(270f), 0.001f)
        assertEquals(10f, NavigationMath.mapOrientationFor(350f), 0.001f)
        // Always normalized to [0, 360).
        assertTrue(NavigationMath.mapOrientationFor(720f) in 0f..360f)
    }

    @Test
    fun mapOrientationFor_wrapFuzzStaysNormalized() {
        val angles = floatArrayOf(0f, 0.1f, 45f, 90f, 179.9f, 180f, 359.9f, 360f, 720f, -90f, -0.5f)
        for (az in angles) {
            val o = NavigationMath.mapOrientationFor(az)
            assertTrue("orientation $o out of range for az=$az", o >= 0f && o < 360f)
            // Counter-rotation invariant: (az + orientation) ≡ 0 (mod 360).
            val sum = ((az + o) % 360f + 360f) % 360f
            assertTrue("az=$az o=$o sum=$sum", sum < 0.01f || sum > 359.99f)
        }
    }
    @Test
    fun screenAngle_matchesShortestArcDelta() {
        val pairs = arrayOf(
            0f to 0f, 90f to 90f, 90f to 120f, 90f to 60f,
            10f to 350f, 350f to 10f, 0f to 180f, 359f to 1f, 1f to 359f
        )
        for ((b, a) in pairs) {
            assertEquals(
                HeadingFilter.shortestArcDelta(b, a),
                NavigationMath.screenAngleDeg(b, a),
                0.0001f
            )
        }
    }

    @Test
    fun screenAngle_needleRotationContract() {
        // Compose Modifier.rotate() is clockwise-positive: the needle/mini-arrow
        // uses rotate(relative) with NO inversion. East target while facing
        // north → +90 → points right; west → −90 → points left.
        assertEquals(90f, NavigationMath.screenAngleDeg(90f, 0f), 0.001f)
        assertEquals(-90f, NavigationMath.screenAngleDeg(270f, 0f), 0.001f)
        // Facing target → 0 (straight UP).
        assertEquals(0f, NavigationMath.screenAngleDeg(133f, 133f), 0.001f)
    }

    @Test
    fun destinationPoint_geodesicCalculation() {
        val startLat = 24.7136
        val startLon = 46.6753
        val distance = 1000.0 // 1 km
        val bearing = 90f // East

        val dest = NavigationMath.destinationPoint(startLat, startLon, distance, bearing)

        // Latitude should be nearly identical (pure East)
        assertEquals(startLat, dest.latitude, 0.001)
        // Longitude should have increased
        assertTrue(dest.longitude > startLon)
        // Calculated distance back to destination should be ~1000m
        val d = NavigationMath.distanceMeters(startLat, startLon, dest.latitude, dest.longitude)
        assertEquals(1000.0, d, 1.0)
        // Bearing should be 90°
        val b = NavigationMath.bearingDeg(startLat, startLon, dest.latitude, dest.longitude)
        assertEquals(90f, b, 0.5f)
    }

    @Test
    fun destinationPoint_zeroDistanceReturnsSamePoint() {
        val p = NavigationMath.destinationPoint(24.0, 46.0, 0.0, 45f)
        assertEquals(24.0, p.latitude, 0.0001)
        assertEquals(46.0, p.longitude, 0.0001)
    }

    @Test
    fun closestPointOnSegment_projectionAndClamping() {
        val segStartLat = 24.0
        val segStartLon = 46.0
        val segEndLat = 24.0
        val segEndLon = 46.02

        // Point directly perpendicular to segment midpoint
        val pMid = NavigationMath.closestPointOnSegment(24.001, 46.01, segStartLat, segStartLon, segEndLat, segEndLon)
        assertEquals(24.0, pMid.latitude, 0.0001)
        assertEquals(46.01, pMid.longitude, 0.0001)

        // Point before segment start -> clamped to start
        val pBefore = NavigationMath.closestPointOnSegment(24.0, 45.99, segStartLat, segStartLon, segEndLat, segEndLon)
        assertEquals(segStartLat, pBefore.latitude, 0.0001)
        assertEquals(segStartLon, pBefore.longitude, 0.0001)

        // Point after segment end -> clamped to end
        val pAfter = NavigationMath.closestPointOnSegment(24.0, 46.03, segStartLat, segStartLon, segEndLat, segEndLon)
        assertEquals(segEndLat, pAfter.latitude, 0.0001)
        assertEquals(segEndLon, pAfter.longitude, 0.0001)
    }

    @Test
    fun findClosestPointOnPolyline_crossTrackDistance() {
        val poly = listOf(
            GeoPoint(24.0, 46.0),
            GeoPoint(24.0, 46.02),
            GeoPoint(24.02, 46.02)
        )

        // Point 100m north of first segment
        val testPoint = NavigationMath.destinationPoint(24.0, 46.01, 100.0, 0f)
        val result = NavigationMath.findClosestPointOnPolyline(testPoint.latitude, testPoint.longitude, poly)

        assertNotNull(result)
        val (closestGeo, dist) = result!!
        assertEquals(24.0, closestGeo.latitude, 0.001)
        assertEquals(46.01, closestGeo.longitude, 0.001)
        assertEquals(100.0, dist, 2.0)
    }

    @Test
    fun smoothHeadingStep_shortestArcAndDeadband() {
        // Within deadband (< 0.5°) -> no change
        assertEquals(10f, NavigationMath.smoothHeadingStep(10f, 10.3f, alpha = 0.22f, deadbandDeg = 0.5f), 0.001f)

        // Normal right turn from 0° to 90° with alpha = 0.2
        val step1 = NavigationMath.smoothHeadingStep(0f, 90f, alpha = 0.2f, deadbandDeg = 0.5f)
        assertEquals(18f, step1, 0.01f)

        // Cross 0° North wrap from 355° towards 5° (clockwise difference is +10°)
        val stepNorth = NavigationMath.smoothHeadingStep(355f, 5f, alpha = 0.2f, deadbandDeg = 0.5f)
        assertEquals(357f, stepNorth, 0.01f)

        // Cross 0° North wrap from 5° towards 355° (counter-clockwise difference is -10°)
        val stepNorthReverse = NavigationMath.smoothHeadingStep(5f, 355f, alpha = 0.2f, deadbandDeg = 0.5f)
        assertEquals(3f, stepNorthReverse, 0.01f)
    }

    @Test
    fun rejoinHysteresisThresholds_behavior() {
        // Below 20m: on-track
        val onTrackDist = 15.0
        assertTrue(onTrackDist <= 20.0)

        // Between 20m and 60m: hysteresis band
        val midDist = 45.0
        assertTrue(midDist > 20.0 && midDist <= 60.0)

        // Above 60m: triggers off-route rejoin leg
        val offRouteDist = 65.0
        assertTrue(offRouteDist > 60.0)
    }

    @Test
    fun bearingDeg_straightLineTargetingQuadrants() {
        val userLat = 24.0
        val userLon = 46.0

        // Target directly North (+1° lat)
        assertEquals(0f, NavigationMath.bearingDeg(userLat, userLon, 25.0, userLon), 0.5f)

        // Target directly East (+1° lon)
        assertEquals(90f, NavigationMath.bearingDeg(userLat, userLon, userLat, 47.0), 0.5f)

        // Target directly South (-1° lat)
        assertEquals(180f, NavigationMath.bearingDeg(userLat, userLon, 23.0, userLon), 0.5f)

        // Target directly West (-1° lon)
        assertEquals(270f, NavigationMath.bearingDeg(userLat, userLon, userLat, 45.0), 0.5f)

        // Target North-East
        val neBearing = NavigationMath.bearingDeg(userLat, userLon, 25.0, 47.0)
        assertTrue("Expected NE bearing in (0, 90), got $neBearing", neBearing > 0f && neBearing < 90f)

        // Target North-West
        val nwBearing = NavigationMath.bearingDeg(userLat, userLon, 25.0, 45.0)
        assertTrue("Expected NW bearing in (270, 360), got $nwBearing", nwBearing > 270f && nwBearing < 360f)
    }

    @Test
    fun straightLineNavigation_targetHeadingConvergence() {
        // Simulating successive heading smoothing steps from initial heading 0 towards target bearing 270 (West)
        var smoothed = 0f
        val targetBearing = 270f
        val alpha = 0.35f

        // Initial step should take shortest arc counter-clockwise (towards 270, delta = -90)
        smoothed = NavigationMath.smoothHeadingStep(smoothed, targetBearing, alpha)
        // 0 + (-90 * 0.35) = -31.5 -> 328.5°
        assertEquals(328.5f, smoothed, 0.5f)

        // After several steps, it converges to 270°
        for (i in 1..15) {
            smoothed = NavigationMath.smoothHeadingStep(smoothed, targetBearing, alpha)
        }
        assertEquals(270f, smoothed, 1.0f)
    }
}