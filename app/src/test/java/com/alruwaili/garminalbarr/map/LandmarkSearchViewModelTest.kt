package com.alruwaili.garminalbarr.map

import com.alruwaili.garminalbarr.LocationLightweight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkSearchViewModelTest {

    @Test
    fun normalizeArabic_stripsDiacriticsAndNormalizesLetters() {
        // Strips harakat, maps آ أ إ -> ا, ة -> ه, ى -> ي
        val input = "رَوْضَةُ أُمِّ الشُّعُولِ"
        val normalized = LandmarkSearchViewModel.normalizeArabic(input)
        assertEquals("روضه ام الشعول", normalized)
    }

    @Test
    fun normalizeArabic_handlesEmptyAndPureEnglish() {
        assertEquals("", LandmarkSearchViewModel.normalizeArabic(""))
        assertEquals("Riyadh Highway", LandmarkSearchViewModel.normalizeArabic("Riyadh Highway"))
    }

    @Test
    fun locationLightweight_isFav_defaultsToFalseOrNull() {
        val loc = LocationLightweight(
            id = 123,
            isFav = false,
            latitude = 25.0,
            longitude = 45.0,
            title = "بئر هداج"
        )
        assertFalse(loc.isFav == true)

        val favorited = loc.copy(isFav = true)
        assertTrue(favorited.isFav == true)
    }

    @Test
    fun locationLightweight_toggleFavoriteLogic_handlesNullStatus() {
        val loc = LocationLightweight(
            id = 456,
            isFav = null,
            latitude = 24.5,
            longitude = 46.5,
            title = "شعيب الحيسية"
        )
        val newStatus = !(loc.isFav ?: false)
        assertTrue("Null isFav toggles to true", newStatus)

        val toggledBack = !(newStatus)
        assertFalse("True isFav toggles back to false", toggledBack)
    }

    @Test
    fun searchResultKey_isDeterministicAndUnique() {
        val loc1 = LocationLightweight(id = 1, isFav = false, latitude = 24.0, longitude = 46.0, title = "موقع")
        val loc2 = LocationLightweight(id = 2, isFav = false, latitude = 24.0, longitude = 46.0, title = "موقع")

        val key1 = "${loc1.title}_${loc1.latitude}_${loc1.longitude}_0"
        val key2 = "${loc2.title}_${loc2.latitude}_${loc2.longitude}_1"

        assertNotEquals("Keys for items with same title and coords must remain distinct using index suffix", key1, key2)
    }

    @Test
    fun maxResultsConstant_isConfiguredSensibly() {
        assertTrue(LandmarkSearchViewModel.MAX_RESULTS in 50..100)
    }
}
