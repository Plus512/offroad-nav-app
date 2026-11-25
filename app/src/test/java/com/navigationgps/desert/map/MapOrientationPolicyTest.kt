package com.navigationgps.desert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOrientationPolicyTest {

    @Test
    fun testSpeedGatingThresholds() {
        // Speed threshold is strictly 1.5 m/s (5.4 km/h)
        val threshold = OsmdroidMapFragment.SPEED_DRIVING_THRESHOLD_MPS
        assertEquals(1.5f, threshold, 1e-4f)

        fun isDynamicRotationAllowed(speedMps: Float, hasSpeed: Boolean): Boolean {
            return hasSpeed && speedMps >= threshold
        }

        // Stationary or very slow drift -> freeze rotation
        assertFalse("0.0 m/s must freeze rotation", isDynamicRotationAllowed(0.0f, true))
        assertFalse("1.0 m/s must freeze rotation", isDynamicRotationAllowed(1.0f, true))
        assertFalse("1.49 m/s must freeze rotation", isDynamicRotationAllowed(1.49f, true))
        assertFalse("Missing speed must freeze rotation", isDynamicRotationAllowed(2.0f, false))

        // Moving at or above driving threshold -> allow dynamic rotation
        assertTrue("1.5 m/s must allow rotation", isDynamicRotationAllowed(1.5f, true))
        assertTrue("2.0 m/s must allow rotation", isDynamicRotationAllowed(2.0f, true))
        assertTrue("10.0 m/s must allow rotation", isDynamicRotationAllowed(10.0f, true))
    }

    @Test
    fun testOrientationModeTransitions() {
        var currentMode = OsmdroidMapFragment.MapOrientationMode.NORTH_UP

        fun toggle(mode: OsmdroidMapFragment.MapOrientationMode): OsmdroidMapFragment.MapOrientationMode {
            return if (mode == OsmdroidMapFragment.MapOrientationMode.NORTH_UP) {
                OsmdroidMapFragment.MapOrientationMode.COURSE_UP
            } else {
                OsmdroidMapFragment.MapOrientationMode.NORTH_UP
            }
        }

        // Default is North-Up
        assertEquals(OsmdroidMapFragment.MapOrientationMode.NORTH_UP, currentMode)

        // First tap: switches to Course-Up
        currentMode = toggle(currentMode)
        assertEquals(OsmdroidMapFragment.MapOrientationMode.COURSE_UP, currentMode)

        // Second tap: switches back to North-Up
        currentMode = toggle(currentMode)
        assertEquals(OsmdroidMapFragment.MapOrientationMode.NORTH_UP, currentMode)
    }

    @Test
    fun testStraightLineNavigationInitialOrientationGuarantee() {
        // When starting straight-line navigation ("مسار مستقيم"), mode must be forced to NORTH_UP
        val straightLineMode = OsmdroidMapFragment.MapOrientationMode.NORTH_UP
        val initialOrientation = if (straightLineMode == OsmdroidMapFragment.MapOrientationMode.COURSE_UP) {
            NavigationMath.mapOrientationFor(45f)
        } else {
            0f
        }
        assertEquals("Straight-line navigation must initialize strictly at 0° True North", 0f, initialOrientation, 1e-4f)
    }

    @Test
    fun testCourseUpMapOrientationCalculation() {
        // In Course-Up, mapOrientation counter-rotates travel bearing so vehicle points UP (12 o'clock)
        // Traveling North (0°) -> map rotation 0°
        assertEquals(0f, NavigationMath.mapOrientationFor(0f), 1e-4f)

        // Traveling East (90°) -> map rotates 270° (-90°)
        assertEquals(270f, NavigationMath.mapOrientationFor(90f), 1e-4f)

        // Traveling South (180°) -> map rotates 180° (-180°)
        assertEquals(180f, NavigationMath.mapOrientationFor(180f), 1e-4f)

        // Traveling West (270°) -> map rotates 90° (-270°)
        assertEquals(90f, NavigationMath.mapOrientationFor(270f), 1e-4f)
    }
}
