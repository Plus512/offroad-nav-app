package com.navigationgps.desert.track

import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {

    @Test
    fun testBuildGpxStringContainsEssentialTags() {
        val now = 1700000000000L
        val track = TrackEntity(
            id = 1L,
            title = "مسار روضة التنهات",
            description = "رحلة ربيعية",
            startTimeMillis = now,
            endTimeMillis = now + 7200000L,
            totalDistanceMeters = 35000.0,
            durationSeconds = 7200L
        )

        val points = listOf(
            TrackPointEntity(
                trackId = 1L,
                seqIndex = 0,
                latitude = 26.01234,
                longitude = 46.54321,
                altitude = 580.5,
                speedMps = 15.0f,
                bearingDeg = 45f,
                timestampMillis = now
            ),
            TrackPointEntity(
                trackId = 1L,
                seqIndex = 1,
                latitude = 26.01500,
                longitude = 46.54600,
                altitude = 582.0,
                speedMps = 16.5f,
                bearingDeg = 50f,
                timestampMillis = now + 10000L
            )
        )

        val gpx = GpxExporter.buildGpxString(track, points)

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<name>مسار روضة التنهات</name>"))
        assertTrue(gpx.contains("<desc>رحلة ربيعية</desc>"))
        assertTrue(gpx.contains("<trkpt lat=\"26.0123400\" lon=\"46.5432100\">"))
        assertTrue(gpx.contains("<ele>580.5</ele>"))
        assertTrue(gpx.contains("<speed>15.00</speed>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertTrue(gpx.contains("</gpx>"))
    }
}
