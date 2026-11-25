package com.alruwaili.garminalbarr.map

import com.alruwaili.garminalbarr.LocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UserSavedLocationsTest {

    @Test
    fun testLocationEntityManualFlagDefaultAndSet() {
        val defaultEntity = LocationEntity(
            title = "روضة خريم",
            latitude = 25.385,
            longitude = 47.287
        )
        assertFalse("Default isManual must be false", defaultEntity.isManual ?: false)

        val manualEntity = defaultEntity.copy(isManual = true)
        assertTrue("Manual saved location must have isManual = true", manualEntity.isManual ?: false)
    }

    @Test
    fun testSavedLocationsDeduplication() {
        val roomLocation = LocationEntity(
            id = 1,
            title = "مخيمي في الصمان",
            latitude = 26.123456,
            longitude = 47.654321,
            isManual = true
        )
        val storageLocation = LocationEntity(
            id = 0,
            title = "مخيمي في الصمان",
            latitude = 26.123459, // slightly different precision, rounds to same 5 decimals
            longitude = 47.654320,
            isManual = true
        )
        val anotherLocation = LocationEntity(
            id = 2,
            title = "فيضة التنهات",
            latitude = 26.01234,
            longitude = 46.54321,
            isManual = true
        )

        val combinedList = listOf(roomLocation, storageLocation, anotherLocation)
        val distinct = combinedList.distinctBy { loc ->
            val latStr = String.format(Locale.US, "%.5f", loc.latitude ?: 0.0)
            val lonStr = String.format(Locale.US, "%.5f", loc.longitude ?: 0.0)
            "${loc.title}_${latStr}_${lonStr}"
        }

        assertEquals("Duplicates with same title and 5-decimal coordinates should be merged", 2, distinct.size)
        assertEquals("First occurrence title should be preserved", "مخيمي في الصمان", distinct[0].title)
        assertEquals("Another location should be preserved", "فيضة التنهات", distinct[1].title)
    }

    @Test
    fun testPoiDetailsCreationFromSavedLocation() {
        val savedEntity = LocationEntity(
            id = 42,
            title = "البيت",
            latitude = 24.7136,
            longitude = 46.6753,
            isManual = true
        )

        val poiDetails = PoiDetails(
            name = savedEntity.title ?: "موقع محفوظ",
            category = "user_location",
            latitude = savedEntity.latitude ?: 0.0,
            longitude = savedEntity.longitude ?: 0.0
        )

        assertEquals("البيت", poiDetails.name)
        assertEquals("user_location", poiDetails.category)
        assertEquals(24.7136, poiDetails.latitude, 1e-6)
        assertEquals(46.6753, poiDetails.longitude, 1e-6)
    }

    @Test
    fun testSaveLocationNameFallback() {
        val lat = 24.81234
        val lon = 46.95678
        val defaultCoords = "${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lon)}"

        // When user leaves name blank, fallback to formatted coordinates
        val blankInput = "   "
        val resolvedBlankName = blankInput.trim().ifEmpty { defaultCoords }
        assertEquals(defaultCoords, resolvedBlankName)

        // When user enters custom name, use trimmed custom name
        val customInput = "  نقطة تجمع 1  "
        val resolvedCustomName = customInput.trim().ifEmpty { defaultCoords }
        assertEquals("نقطة تجمع 1", resolvedCustomName)
    }

    @Test
    fun testLocationDeletionMatching() {
        val list = mutableListOf(
            LocationEntity(title = "موقعي 1", latitude = 24.1, longitude = 46.1, isManual = true),
            LocationEntity(title = "موقعي 2", latitude = 24.2, longitude = 46.2, isManual = true)
        )
        val toDelete = LocationEntity(title = "موقعي 1", latitude = 24.1, longitude = 46.1, isManual = true)
        val removed = list.removeAll {
            it.title == toDelete.title && it.latitude == toDelete.latitude && it.longitude == toDelete.longitude
        }
        assertTrue("Item should be removed from list", removed)
        assertEquals(1, list.size)
        assertEquals("موقعي 2", list[0].title)
    }

    @Test
    fun testUserLocationMarkerTitleTruncation() {
        // Null and blank titles should fall back to default
        assertEquals("موقع محفوظ", UserLocationMarker.truncateTitle(null))
        assertEquals("موقع محفوظ", UserLocationMarker.truncateTitle(""))
        assertEquals("موقع محفوظ", UserLocationMarker.truncateTitle("    "))

        // Titles within MAX_CHARS (18) should remain intact (trimmed)
        val shortTitle = "  مخيم الصمان  "
        assertEquals("مخيم الصمان", UserLocationMarker.truncateTitle(shortTitle))

        val exact18Chars = "123456789012345678"
        assertEquals(18, exact18Chars.length)
        assertEquals(exact18Chars, UserLocationMarker.truncateTitle(exact18Chars))

        // Titles exceeding MAX_CHARS should be cleanly truncated with an ellipsis
        val longArabicTitle = "مخيم روضة خريم بالقرب من البوابة الشمالية"
        val truncated = UserLocationMarker.truncateTitle(longArabicTitle, maxChars = 18)
        assertEquals(18, truncated.length)
        assertTrue("Truncated string should end with ellipsis", truncated.endsWith("…"))
        assertEquals("مخيم روضة خريم با…", truncated)

        // Trailing whitespace before ellipsis should be trimmed
        val titleWithSpaceAtCut = "مخيم روضة خريم 12345"
        // maxChars = 16 -> take(15) is "مخيم روضة خريم "
        val truncatedTrimmed = UserLocationMarker.truncateTitle(titleWithSpaceAtCut, maxChars = 16)
        assertEquals("مخيم روضة خريم…", truncatedTrimmed)
    }

    @Test
    fun testUserLocationMarkerTextMetrics() {
        val textWidth = 100f
        val fontAscent = -24f
        val fontDescent = 6f

        val metrics = UserLocationMarker.computeTextMetrics(
            textWidth = textWidth,
            fontAscent = fontAscent,
            fontDescent = fontDescent
        )

        assertEquals(100f, metrics.textWidth, 1e-4f)
        assertEquals(30f, metrics.textHeight, 1e-4f)
        assertEquals(24f, metrics.baselineOffset, 1e-4f)
    }

    @Test
    fun testUserLocationMarkerTypographyConstants() {
        assertEquals(0xFF1A1A1A.toInt(), UserLocationMarker.TEXT_FILL_COLOR)
        assertEquals(0xFFFFFFFF.toInt(), UserLocationMarker.TEXT_HALO_COLOR)
        assertEquals(12.5f, UserLocationMarker.TEXT_SIZE_DP, 1e-4f)
        assertEquals(2.8f, UserLocationMarker.TEXT_HALO_WIDTH_DP, 1e-4f)
        assertEquals(16f, UserLocationMarker.TOUCH_SLOP_DP, 1e-4f)
    }

    @Test
    fun testUserLocationMarkerZoomThresholdAndContinuousScaling() {
        assertEquals(12.0, UserLocationMarker.POI_MIN_ZOOM, 1e-6)

        // Threshold entry at zoom 12.0: scale must be exactly 1.0 (base size 12.5dp)
        assertEquals(1.0, UserLocationMarker.zoomScaleFor(12.0), 1e-4)

        // Mid-point zoom levels scale monotonically and continuously
        assertEquals(1.07, UserLocationMarker.zoomScaleFor(13.0), 1e-4)
        assertEquals(1.175, UserLocationMarker.zoomScaleFor(14.5), 1e-4)
        assertEquals(1.21, UserLocationMarker.zoomScaleFor(15.0), 1e-4)

        // Upper bound at zoom 17.0: scale is 1.35 (16.875dp)
        assertEquals(1.35, UserLocationMarker.zoomScaleFor(17.0), 1e-4)

        // Clamping behavior beyond bounds
        assertEquals(1.0, UserLocationMarker.zoomScaleFor(10.0), 1e-4)
        assertEquals(1.35, UserLocationMarker.zoomScaleFor(19.0), 1e-4)

        // Verify zero-jump continuity across integer boundaries (no 2x-to-1x drops)
        val justBefore13 = UserLocationMarker.zoomScaleFor(12.999)
        val justAfter13 = UserLocationMarker.zoomScaleFor(13.001)
        val jump = Math.abs(justAfter13 - justBefore13)
        assertTrue("Scaling curve must be smooth and continuous with no integer snaps (jump=$jump)", jump < 0.001)
        assertTrue("Scaling must be strictly non-decreasing", justAfter13 >= justBefore13)
    }
}
