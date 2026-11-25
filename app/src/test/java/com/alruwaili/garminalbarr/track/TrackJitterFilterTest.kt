package com.alruwaili.garminalbarr.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackJitterFilterTest {

    private lateinit var filter: TrackJitterFilter

    @Before
    fun setUp() {
        filter = TrackJitterFilter()
    }

    private fun mockFix(
        lat: Double,
        lon: Double,
        alt: Double = 500.0,
        speed: Float = 10f,
        bearing: Float = 0f,
        accuracy: Float = 5f,
        time: Long = 1000000L
    ): RawGpsFix {
        return RawGpsFix(
            latitude = lat,
            longitude = lon,
            altitude = alt,
            speedMps = speed,
            bearingDeg = bearing,
            accuracyMeters = accuracy,
            timestampMillis = time
        )
    }

    @Test
    fun testFirstFixAlwaysAccepted() {
        val loc = mockFix(24.7136, 46.6753)
        val result = filter.filter(loc)
        assertTrue(result is FilterResult.Accept)
        val accepted = (result as FilterResult.Accept).point
        assertEquals(24.7136, accepted.latitude, 0.00001)
        assertEquals(46.6753, accepted.longitude, 0.00001)
    }

    @Test
    fun testPoorAccuracyRejected() {
        val first = mockFix(24.7136, 46.6753, accuracy = 10f, time = 1000L)
        filter.filter(first)

        val poorLoc = mockFix(24.7140, 46.6760, accuracy = 45f, time = 2000L) // 45m > 25m threshold
        val result = filter.filter(poorLoc)
        assertTrue("Fix with accuracy 45m should be rejected", result is FilterResult.Reject)
    }

    @Test
    fun testStationaryJitterDampened() {
        // Initial point
        val first = mockFix(24.713600, 46.675300, speed = 0f, time = 1000L)
        filter.filter(first)

        // GPS multipath jitter: vehicle stopped (speed = 0.2 m/s), displacement ~1.5 meters
        val jitterLoc = mockFix(24.713610, 46.675310, speed = 0.2f, time = 2000L)
        val result = filter.filter(jitterLoc)
        assertTrue("Sub-4m jitter while stopped should be classified as Stationary", result is FilterResult.Stationary)
    }

    @Test
    fun testMovingVehicleAccepted() {
        val first = mockFix(24.713600, 46.675300, speed = 12f, time = 1000L)
        filter.filter(first)

        // Vehicle moved 20 meters down the trail
        val movingLoc = mockFix(24.713800, 46.675300, speed = 12f, time = 3000L)
        val result = filter.filter(movingLoc)
        assertTrue("Moving vehicle >= 5m displacement should be accepted", result is FilterResult.Accept)
        val accepted = (result as FilterResult.Accept).point
        assertTrue(accepted.distanceDeltaMeters > 15.0)
    }

    @Test
    fun testSharpCurveDetected() {
        val first = mockFix(24.713600, 46.675300, speed = 8f, bearing = 0f, time = 1000L)
        filter.filter(first)

        // Small displacement (3.5m) but sharp bearing change (35°)
        val curveLoc = mockFix(24.713630, 46.675310, speed = 8f, bearing = 35f, time = 1500L)
        val result = filter.filter(curveLoc)
        assertTrue("Sharp turn >= 12 deg should be captured", result is FilterResult.Accept)
    }
}
