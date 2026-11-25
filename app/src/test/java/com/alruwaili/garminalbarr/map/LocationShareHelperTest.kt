package com.alruwaili.garminalbarr.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocationShareHelperTest {

    @Test
    fun formatShareText_withFullDetails_formatsCorrectly() {
        val text = LocationShareHelper.formatShareText(
            appName = "قارمن البر",
            title = "روضة خريم",
            latitude = 25.385556,
            longitude = 47.302778,
            packageName = "com.alruwaili.garminalbarr"
        )

        val expected = """
📍 تم إرسال موقع من تطبيق قارمن البر:
🏷️ الاسم: روضة خريم
🧭 الإحداثيات: 25.385556, 47.302778

📲 لتحميل التطبيق والملاحة في الصحراء:
https://play.google.com/store/apps/details?id=com.alruwaili.garminalbarr

🗺️ أو عرض الإحداثيات مباشرة:
https://www.google.com/maps?q=25.385556, 47.302778
""".trimIndent()

        assertEquals(expected, text)
    }

    @Test
    fun formatShareText_withNullOrBlankTitle_usesDefaultName() {
        val textNull = LocationShareHelper.formatShareText(
            appName = "قارمن البر",
            title = null,
            latitude = 24.713552,
            longitude = 46.675296,
            packageName = "com.alruwaili.garminalbarr"
        )
        assertTrue(textNull.contains("🏷️ الاسم: موقع محدد"))

        val textBlank = LocationShareHelper.formatShareText(
            appName = "قارمن البر",
            title = "   ",
            latitude = 24.713552,
            longitude = 46.675296,
            packageName = "com.alruwaili.garminalbarr"
        )
        assertTrue(textBlank.contains("🏷️ الاسم: موقع محدد"))
    }

    @Test
    fun formatShareText_trimsTitleWhitespace() {
        val text = LocationShareHelper.formatShareText(
            appName = "قارمن البر",
            title = "   جبل أجا   ",
            latitude = 27.5,
            longitude = 41.6,
            packageName = "com.alruwaili.garminalbarr"
        )
        assertTrue(text.contains("🏷️ الاسم: جبل أجا\n"))
    }

    @Test
    fun formatShareText_forcesLocaleUS_evenWithForeignDefaultLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            val textArabic = LocationShareHelper.formatShareText(
                appName = "قارمن البر",
                title = "نقطة تجربة",
                latitude = 24.123456,
                longitude = 46.654321,
                packageName = "com.alruwaili.garminalbarr"
            )
            // Must contain Western digits and dot separator, not Arabic numerals
            assertTrue(textArabic.contains("24.123456, 46.654321"))
            assertTrue(textArabic.contains("https://www.google.com/maps?q=24.123456, 46.654321"))

            Locale.setDefault(Locale.GERMANY)
            val textGerman = LocationShareHelper.formatShareText(
                appName = "قارمن البر",
                title = "نقطة تجربة",
                latitude = 24.123456,
                longitude = 46.654321,
                packageName = "com.alruwaili.garminalbarr"
            )
            // Must use '.' and not German ',' decimal separator
            assertTrue(textGerman.contains("24.123456, 46.654321"))
            assertTrue(textGerman.contains("https://www.google.com/maps?q=24.123456, 46.654321"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun formatShareText_includesBothStoreLinkAndDirectMapsLink() {
        val text = LocationShareHelper.formatShareText(
            appName = "قارمن البر",
            title = "بئر ماء",
            latitude = 28.123400,
            longitude = 45.678900,
            packageName = "com.alruwaili.garminalbarr"
        )
        assertTrue(text.contains("https://play.google.com/store/apps/details?id=com.alruwaili.garminalbarr"))
        assertTrue(text.contains("https://www.google.com/maps?q=28.123400, 45.678900"))
    }
}
