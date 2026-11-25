package com.navigationgps.desert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocationShareHelperTest {

    @Test
    fun formatShareText_withFullDetails_formatsCorrectly() {
        val text = LocationShareHelper.formatShareText(
            appName = "خرائط الصحراء الدليلة",
            title = "روضة خريم",
            latitude = 25.385556,
            longitude = 47.302778,
            packageName = "com.navigationgps.desert"
        )

        val expected = """
📍 تم إرسال موقع من تطبيق خرائط الصحراء الدليلة:
🏷️ الاسم: روضة خريم
🧭 الإحداثيات: 25.385556, 47.302778

📲 لتحميل التطبيق والملاحة في الصحراء:
https://play.google.com/store/apps/details?id=com.navigationgps.desert

🗺️ أو عرض الإحداثيات مباشرة:
https://www.google.com/maps?q=25.385556, 47.302778
""".trimIndent()

        assertEquals(expected, text)
    }

    @Test
    fun formatShareText_withNullOrBlankTitle_usesDefaultName() {
        val textNull = LocationShareHelper.formatShareText(
            appName = "خرائط الصحراء الدليلة",
            title = null,
            latitude = 24.713552,
            longitude = 46.675296,
            packageName = "com.navigationgps.desert"
        )
        assertTrue(textNull.contains("🏷️ الاسم: موقع محدد"))

        val textBlank = LocationShareHelper.formatShareText(
            appName = "خرائط الصحراء الدليلة",
            title = "   ",
            latitude = 24.713552,
            longitude = 46.675296,
            packageName = "com.navigationgps.desert"
        )
        assertTrue(textBlank.contains("🏷️ الاسم: موقع محدد"))
    }

    @Test
    fun formatShareText_trimsTitleWhitespace() {
        val text = LocationShareHelper.formatShareText(
            appName = "خرائط الصحراء الدليلة",
            title = "   جبل أجا   ",
            latitude = 27.5,
            longitude = 41.6,
            packageName = "com.navigationgps.desert"
        )
        assertTrue(text.contains("🏷️ الاسم: جبل أجا\n"))
    }

    @Test
    fun formatShareText_forcesLocaleUS_evenWithForeignDefaultLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            val textArabic = LocationShareHelper.formatShareText(
                appName = "خرائط الصحراء الدليلة",
                title = "نقطة تجربة",
                latitude = 24.123456,
                longitude = 46.654321,
                packageName = "com.navigationgps.desert"
            )
            // Must contain Western digits and dot separator, not Arabic numerals
            assertTrue(textArabic.contains("24.123456, 46.654321"))
            assertTrue(textArabic.contains("https://www.google.com/maps?q=24.123456, 46.654321"))

            Locale.setDefault(Locale.GERMANY)
            val textGerman = LocationShareHelper.formatShareText(
                appName = "خرائط الصحراء الدليلة",
                title = "نقطة تجربة",
                latitude = 24.123456,
                longitude = 46.654321,
                packageName = "com.navigationgps.desert"
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
            appName = "خرائط الصحراء الدليلة",
            title = "بئر ماء",
            latitude = 28.123400,
            longitude = 45.678900,
            packageName = "com.navigationgps.desert"
        )
        assertTrue(text.contains("https://play.google.com/store/apps/details?id=com.navigationgps.desert"))
        assertTrue(text.contains("https://www.google.com/maps?q=28.123400, 45.678900"))
    }
}
