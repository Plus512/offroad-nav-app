package com.navigationgps.desert.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEntityAndMigrationTest {

    @Test
    fun testTrackEntityCreationAndDefaults() {
        val now = System.currentTimeMillis()
        val track = TrackEntity(
            title = "مسار الصمان",
            description = "رحلة صيد وتخييم",
            startTimeMillis = now - 3600000L,
            endTimeMillis = now,
            totalDistanceMeters = 24500.0,
            durationSeconds = 3600L,
            maxSpeedMps = 22.5f,
            avgSpeedMps = 6.8f,
            minAltitudeMeters = 420.0,
            maxAltitudeMeters = 590.0,
            pointsCount = 450
        )

        assertEquals("مسار الصمان", track.title)
        assertEquals("#E53935", track.colorHex) // default high-vis red
        assertTrue(track.isVisibleOnMap)
        assertEquals(24500.0, track.totalDistanceMeters, 0.001)
        assertEquals(450, track.pointsCount)
    }

    @Test
    fun testTrackPointEntitySequence() {
        val now = System.currentTimeMillis()
        val p1 = TrackPointEntity(
            trackId = 1L,
            seqIndex = 0,
            latitude = 25.12345,
            longitude = 46.54321,
            altitude = 550.0,
            speedMps = 12.0f,
            bearingDeg = 45f,
            timestampMillis = now
        )
        val p2 = TrackPointEntity(
            trackId = 1L,
            seqIndex = 1,
            latitude = 25.12400,
            longitude = 46.54380,
            altitude = 552.0,
            speedMps = 14.0f,
            bearingDeg = 48f,
            timestampMillis = now + 5000L
        )

        assertEquals(0, p1.seqIndex)
        assertEquals(1, p2.seqIndex)
        assertEquals(1L, p1.trackId)
        assertEquals(p1.trackId, p2.trackId)
    }

    @Test
    fun testTrackColorVariants() {
        val colors = listOf(
            "#E53935", // Red
            "#FFB300", // Gold
            "#00E5FF", // Cyan
            "#00E676", // Green
            "#FF6D00", // Orange
            "#D500F9"  // Purple
        )
        for (color in colors) {
            val track = TrackEntity(
                title = "Test",
                colorHex = color,
                startTimeMillis = 0L,
                endTimeMillis = 1000L,
                totalDistanceMeters = 100.0,
                durationSeconds = 10L
            )
            assertEquals(color, track.colorHex)
        }
    }
}
