package com.alruwaili.garminalbarr.legacymap

import org.maplibre.android.geometry.LatLngBounds

/**
 * مناطق تحميل الخريطة دون انترنت.
 *
 * الحدود هنا مستطيلات جغرافية تقريبية (bounding box) لكل دولة/منطقة — وليست حدوداً سياسية دقيقة
 * (الحدود السياسية الفعلية غير مستطيلة الشكل). هذا يعني أن حافة المستطيل قد تتجاوز قليلاً إلى
 * دولة مجاورة أو لا تغطي بضع كيلومترات من أقصى حدود الدولة الحقيقية؛ الأثر الوحيد لذلك هو تحميل
 * بلاطات خريطة زائدة قليلاً أو نقص طفيف عند الحافة، وليس خطأً في موضع المواقع نفسها.
 *
 * السعودية مقسّمة إلى ثلاث مناطق (شمال/وسط/جنوب) بحسب خط العرض كما طلب المستخدم.
 */
enum class OfflineRegionDef(
    val id: String,
    val arabicName: String,
    /** اسم إنجليزي للعرض بجانب الاسم العربي، كما في شاشة «إدارة الخرائط» المرجعية (الدليلة بلس). */
    val englishName: String,
    /** رمز علم الدولة (محارف مؤشر إقليمي Unicode حقيقية لكل دولة، وليست صورة مُفبركة). */
    val flagEmoji: String,
    val bounds: LatLngBounds
) {
    JORDAN("jo", "الأردن", "Jordan", "🇯🇴", LatLngBounds.from(33.4, 39.3, 29.1, 34.9)),
    SAUDI_NORTH("sa_n", "السعودية — الشمال", "Saudi Arabia — North", "🇸🇦", LatLngBounds.from(32.3, 55.7, 26.0, 34.5)),
    SAUDI_CENTER("sa_c", "السعودية — الوسط", "Saudi Arabia — Center", "🇸🇦", LatLngBounds.from(26.0, 55.7, 21.0, 34.5)),
    SAUDI_SOUTH("sa_s", "السعودية — الجنوب", "Saudi Arabia — South", "🇸🇦", LatLngBounds.from(21.0, 55.7, 16.0, 34.5)),
    UAE("ae", "الإمارات", "United Arab Emirates", "🇦🇪", LatLngBounds.from(26.5, 56.5, 22.5, 51.0)),
    KUWAIT("kw", "الكويت", "Kuwait", "🇰🇼", LatLngBounds.from(30.1, 48.5, 28.5, 46.5)),
    QATAR("qa", "قطر", "Qatar", "🇶🇦", LatLngBounds.from(26.2, 51.7, 24.4, 50.6)),
    BAHRAIN("bh", "البحرين", "Bahrain", "🇧🇭", LatLngBounds.from(26.3, 50.8, 25.5, 50.3)),
    OMAN("om", "عُمان", "Oman", "🇴🇲", LatLngBounds.from(26.5, 59.9, 16.6, 51.8));

    companion object {
        fun byId(id: String): OfflineRegionDef? = entries.firstOrNull { it.id == id }

        /** المناطق التي تتقاطع مع مستطيل العرض الحالي للخريطة (تُستخدم عند أول تحديد موقع وعند كل توقّف تكبير/تحريك). */
        fun intersecting(visible: LatLngBounds): List<OfflineRegionDef> = entries.filter { boundsIntersect(it.bounds, visible) }

        /** المنطقة/المناطق التي تقع فيها نقطة إحداثية معيّنة (تُستخدم لتحديد الدولة التي يتواجد فيها المستخدم فعلياً حالياً). */
        fun containing(lat: Double, lon: Double): List<OfflineRegionDef> = entries.filter {
            lat <= it.bounds.latitudeNorth && lat >= it.bounds.latitudeSouth &&
                lon <= it.bounds.longitudeEast && lon >= it.bounds.longitudeWest
        }

        private fun boundsIntersect(a: LatLngBounds, b: LatLngBounds): Boolean {
            val latOverlap = a.latitudeSouth <= b.latitudeNorth && a.latitudeNorth >= b.latitudeSouth
            val lonOverlap = a.longitudeWest <= b.longitudeEast && a.longitudeEast >= b.longitudeWest
            return latOverlap && lonOverlap
        }
    }
}
