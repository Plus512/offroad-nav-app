package com.alruwaili.garminalbarr.map

import com.alruwaili.garminalbarr.map.ui.TargetCompassMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMiniTargetCompassTest {

    @Test
    fun relativeBearing_alignedNorth_returnsZero() {
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 0f, deviceAzimuth = 0f)
        assertEquals(0f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_alignedAnyAngle_returnsZero() {
        val angles = listOf(45f, 90f, 180f, 270f, 315f)
        for (a in angles) {
            val rel = TargetCompassMath.computeRelativeBearing(targetBearing = a, deviceAzimuth = a)
            assertEquals(0f, rel, 0.001f)
        }
    }

    @Test
    fun relativeBearing_targetEast_deviceNorth_pointsRight90() {
        // Target is East (90°), Phone points North (0°) -> Needle should rotate 90° clockwise (right)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 90f, deviceAzimuth = 0f)
        assertEquals(90f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_targetNorth_deviceEast_pointsLeft270() {
        // Target is North (0°), Phone points East (90°) -> Needle should point 270° clockwise (left)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 0f, deviceAzimuth = 90f)
        assertEquals(270f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_targetSouth_deviceNorth_pointsDown180() {
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 180f, deviceAzimuth = 0f)
        assertEquals(180f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_crossingNorthBoundary_clockwise() {
        // Target is 10° (NNE), Device heading is 350° (NNW).
        // The forward clockwise angle is 20°
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 10f, deviceAzimuth = 350f)
        assertEquals(20f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_crossingNorthBoundary_counterClockwise() {
        // Target is 350° (NNW), Device heading is 10° (NNE).
        // Relative angle is 350 - 10 = 340° (or -20°)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 350f, deviceAzimuth = 10f)
        assertEquals(340f, rel, 0.001f)
    }

    @Test
    fun relativeBearing_handlesNaNAndInfinityGracefully() {
        assertEquals(0f, TargetCompassMath.computeRelativeBearing(Float.NaN, 45f), 0.001f)
        assertEquals(0f, TargetCompassMath.computeRelativeBearing(45f, Float.NaN), 0.001f)
        assertEquals(0f, TargetCompassMath.computeRelativeBearing(Float.POSITIVE_INFINITY, 0f), 0.001f)
        assertEquals(0f, TargetCompassMath.computeRelativeBearing(0f, Float.NEGATIVE_INFINITY), 0.001f)
    }

    @Test
    fun relativeBearing_alwaysInNormalizedRange() {
        val testBearings = listOf(-100f, -10f, 0f, 45f, 180f, 359f, 360f, 400f, 720f)
        val testAzimuths = listOf(-50f, 0f, 90f, 270f, 350f, 365f, 800f)

        for (b in testBearings) {
            for (az in testAzimuths) {
                val rel = TargetCompassMath.computeRelativeBearing(b, az)
                assertTrue("Angle $rel should be >= 0.0", rel >= 0f)
                assertTrue("Angle $rel should be < 360.0", rel < 360f)
            }
        }
    }

    @Test
    fun relativeBearing_withGeodesicNavigationMath() {
        // Riyadh to Al-Dahna
        val fromLat = 24.7136
        val fromLon = 46.6753
        val toLat = 25.5000
        val toLon = 47.5000

        val targetBearing = NavigationMath.bearingDeg(fromLat, fromLon, toLat, toLon)
        assertTrue(targetBearing in 40f..50f) // North-East

        // If phone heading matches target bearing, relative angle must be 0° (straight ahead)
        val relFacingTarget = TargetCompassMath.computeRelativeBearing(targetBearing, targetBearing)
        assertEquals(0f, relFacingTarget, 0.001f)

        // If phone is facing North (0°), relative angle equals targetBearing
        val relFacingNorth = TargetCompassMath.computeRelativeBearing(targetBearing, 0f)
        assertEquals(targetBearing, relFacingNorth, 0.001f)
    }

    @Test
    fun targetPointer_strictDirectionSpec_userFacingTarget_pointsUp() {
        // Target is East (100°), User faces East (100°) -> points straight UP (0°)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 100f, deviceAzimuth = 100f)
        assertEquals(0f, rel, 0.001f)
    }

    @Test
    fun targetPointer_strictDirectionSpec_targetToRight_pointsRight() {
        // Target is 120°, User faces 30° -> Target is 90° to the right -> points RIGHT (90°)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 120f, deviceAzimuth = 30f)
        assertEquals(90f, rel, 0.001f)
    }

    @Test
    fun targetPointer_strictDirectionSpec_targetToLeft_pointsLeft() {
        // Target is 120°, User faces 210° -> Target is 90° to the left -> points LEFT (270°)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 120f, deviceAzimuth = 210f)
        assertEquals(270f, rel, 0.001f)
    }

    @Test
    fun targetPointer_strictDirectionSpec_targetBehind_pointsDown() {
        // Target is 120°, User faces 300° -> Target is behind user -> points DOWN (180°)
        val rel = TargetCompassMath.computeRelativeBearing(targetBearing = 120f, deviceAzimuth = 300f)
        assertEquals(180f, rel, 0.001f)
    }

    @Test
    fun targetPointer_coldStart_originDestFallback_neverDefaultsToZero() {
        // Cold-start scenario: User position saved in originSlot, destination in destSlot
        val originLat = 24.7136
        val originLon = 46.6753
        val destLat = 29.98316
        val destLon = 35.71145

        val targetBearing = NavigationMath.bearingDeg(originLat, originLon, destLat, destLon)
        assertTrue("Geodesic bearing must be finite", targetBearing.isFinite())
        assertTrue("Geodesic bearing must not be 0.0 for distinct non-due-north points", targetBearing > 290f && targetBearing < 315f)

        // When phone heading is 0° (North), pointer points toward targetBearing (NW)
        val relHeadingNorth = TargetCompassMath.computeRelativeBearing(targetBearing, 0f)
        assertEquals(targetBearing, relHeadingNorth, 0.001f)

        // When user turns to face NW (heading = targetBearing), pointer points straight UP (0°)
        val relFacingTarget = TargetCompassMath.computeRelativeBearing(targetBearing, targetBearing)
        assertEquals(0f, relFacingTarget, 0.001f)
    }
}
