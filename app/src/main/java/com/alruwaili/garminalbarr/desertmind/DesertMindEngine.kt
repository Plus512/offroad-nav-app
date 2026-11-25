package com.alruwaili.garminalbarr.desertmind

import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.map.NavigationMath
import com.alruwaili.garminalbarr.map.PoiDetails
import com.alruwaili.garminalbarr.map.routing.BRouterOfflineEngine
import com.alruwaili.garminalbarr.map.routing.LatLng
import com.alruwaili.garminalbarr.map.routing.OfflineRouter
import com.alruwaili.garminalbarr.map.routing.CarRouteMode

/**
 * "العقل الصحراوي الذكي" — Smart Desert Mind.
 *
 * Deterministic, fully-offline query engine answering a fixed set of desert-navigation
 * questions by combining the app's own data sources directly — no network connection,
 * no AI/LLM call of any kind:
 *   - GPS (current [LocationData]-equivalent lat/lon, passed in by the caller)
 *   - the saved/POI locations table ([LocationLightweight], already loaded elsewhere
 *     in the app, e.g. via [com.alruwaili.garminalbarr.LocationDao])
 *   - the car-routing offline engine ([BRouterOfflineEngine]) for road-reachability
 *     questions
 *   - the active track recording's first point (trip origin), when available, for the
 *     "best direction to return" question
 *
 * This is query logic only; it owns no UI. [com.alruwaili.garminalbarr.desertmind.ui]
 * presents the fixed question buttons and renders [DesertMindAnswer.text].
 *
 * NOTE on category accuracy (disclosed deliberately, not guessed away): the "water
 * source" / "station" / "settlement" groupings below reuse the exact same title-prefix
 * classifier already used by the map's POI overlay
 * ([com.alruwaili.garminalbarr.map.OsmdroidPoiOverlay.classifyPoi]) — e.g. any saved
 * point whose title starts with "بئر" or "فيض" counts as a water source, any title
 * starting with "محطة" counts as a station (this does not itself distinguish a fuel
 * station from any other kind of station), and "قرية"/"مركز" count as a settlement.
 * There is no separate category field in the locations database, so this is a
 * heuristic over existing titles, not a guaranteed classification — the same
 * limitation that already exists for the POI map labels today.
 */
object DesertMindEngine {

    /** Title-prefix categories that may appear in [classifyPoiTitle]'s result. */
    private val WATER_CATEGORIES = setOf("well", "spring", "water_generic", "pond")
    private val STATION_CATEGORIES = setOf("station")
    private val SETTLEMENT_CATEGORIES = setOf("village", "center", "house", "town")
    private val CAIRN_CATEGORIES = setOf("cairn")
    private val RADMIYA_CATEGORIES = setOf("radmiya")
    private val TOWER_CATEGORIES = setOf("tower")

    data class DesertMindAnswer(
        val text: String,
        /** Set when the answer names one specific place the user can act on (go-to). */
        val targetLat: Double? = null,
        val targetLon: Double? = null,
        val targetTitle: String? = null
    )

    // ---------------------------------------------------------------------
    // أين أنا بالضبط؟
    // ---------------------------------------------------------------------
    fun whereAmI(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer {
        val nearest = nearestLocation(currentLat, currentLon, allLocations, categories = null)
        val coordsLine = "الإحداثيات: %.5f، %.5f".format(currentLat, currentLon)
        if (nearest == null) {
            return DesertMindAnswer(text = "$coordsLine\nلا توجد نقاط محفوظة قريبة لتحديد المكان نسبةً إليها.")
        }
        val dist = NavigationMath.distanceMeters(currentLat, currentLon, nearest.first.latitude!!, nearest.first.longitude!!)
        val dir = NavigationMath.arabicDirection(
            NavigationMath.bearingDeg(currentLat, currentLon, nearest.first.latitude!!, nearest.first.longitude!!)
        )
        return DesertMindAnswer(
            text = "$coordsLine\nأقرب نقطة معروفة: ${nearest.first.title ?: "بدون اسم"}، على بعد ${NavigationMath.formatRemaining(dist)} باتجاه $dir.",
            targetLat = nearest.first.latitude,
            targetLon = nearest.first.longitude,
            targetTitle = nearest.first.title
        )
    }

    // ---------------------------------------------------------------------
    // أقرب مورد ماء (بئر / فيض / مي / مويه / غدير)
    // ---------------------------------------------------------------------
    fun nearestWaterSource(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, WATER_CATEGORIES,
        notFoundText = "لم يتم العثور على بئر أو فيض أو مصدر مياه أو غدير محفوظ ضمن قاعدة المواقع الحالية.",
        label = "أقرب مصدر مياه (بئر/فيض/مي/غدير)"
    )

    // ---------------------------------------------------------------------
    // أقرب رجم (علامة حجرية إرشادية تقليدية في الصحراء)
    // ---------------------------------------------------------------------
    fun nearestCairn(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, CAIRN_CATEGORIES,
        notFoundText = "لم يتم العثور على رجم محفوظ ضمن قاعدة المواقع الحالية.",
        label = "أقرب رجم"
    )

    // ---------------------------------------------------------------------
    // أقرب ردمية
    // ---------------------------------------------------------------------
    fun nearestRadmiya(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, RADMIYA_CATEGORIES,
        notFoundText = "لم يتم العثور على ردمية محفوظة ضمن قاعدة المواقع الحالية.",
        label = "أقرب ردمية"
    )

    // ---------------------------------------------------------------------
    // أقرب برج اتصال — يعمل فقط إن وُجدت أبراج محفوظة يدويًا بأسماء تبدأ بـ"برج"؛
    // لا يوجد في التطبيق أي مصدر بيانات مخصص لأبراج الاتصال (انظر التنويه أعلى الملف).
    // ---------------------------------------------------------------------
    fun nearestTower(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, TOWER_CATEGORIES,
        notFoundText = "لا توجد أبراج اتصال محفوظة ضمن قاعدة المواقع الحالية — التطبيق لا يملك مصدر بيانات مخصص لأبراج الاتصال، وهذا البحث يعمل فقط على مواقع محفوظة يدويًا بأسماء تبدأ بـ\"برج\".",
        label = "أقرب برج اتصال"
    )

    // ---------------------------------------------------------------------
    // أقرب محطة (وقود أو غيرها — انظر تنويه الدقة أعلى الملف)
    // ---------------------------------------------------------------------
    fun nearestStation(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, STATION_CATEGORIES,
        notFoundText = "لم يتم العثور على محطة محفوظة ضمن قاعدة المواقع الحالية.",
        label = "أقرب محطة"
    )

    // ---------------------------------------------------------------------
    // أقرب تجمع سكاني (قرية / مركز / بيت)
    // ---------------------------------------------------------------------
    fun nearestSettlement(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>
    ): DesertMindAnswer = nearestOfGroup(
        currentLat, currentLon, allLocations, SETTLEMENT_CATEGORIES,
        notFoundText = "لم يتم العثور على تجمع سكاني (قرية/مركز/بيت) محفوظ ضمن قاعدة المواقع الحالية.",
        label = "أقرب تجمع سكاني"
    )

    private fun nearestOfGroup(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>,
        categories: Set<String>,
        notFoundText: String,
        label: String
    ): DesertMindAnswer {
        val nearest = nearestLocation(currentLat, currentLon, allLocations, categories)
            ?: return DesertMindAnswer(text = notFoundText)
        val (loc, dist) = nearest
        val dir = NavigationMath.arabicDirection(
            NavigationMath.bearingDeg(currentLat, currentLon, loc.latitude!!, loc.longitude!!)
        )
        return DesertMindAnswer(
            text = "$label: ${loc.title ?: "بدون اسم"}\nالمسافة: ${NavigationMath.formatRemaining(dist)}\nالاتجاه: $dir",
            targetLat = loc.latitude,
            targetLon = loc.longitude,
            targetTitle = loc.title
        )
    }

    /** Returns the nearest location (optionally filtered by [classifyPoiTitle] membership) and its distance in meters. */
    private fun nearestLocation(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>,
        categories: Set<String>?
    ): Pair<LocationLightweight, Double>? {
        var best: LocationLightweight? = null
        var bestDist = Double.MAX_VALUE
        for (loc in allLocations) {
            val lat = loc.latitude ?: continue
            val lon = loc.longitude ?: continue
            val title = loc.title ?: continue
            if (categories != null && classifyPoiTitle(title) !in categories) continue
            val d = NavigationMath.distanceMeters(currentLat, currentLon, lat, lon)
            if (d < bestDist) {
                bestDist = d
                best = loc
            }
        }
        return best?.let { it to bestDist }
    }

    // ---------------------------------------------------------------------
    // أقرب طريق معبّد / صالح للسيارات (مسافة فقط — دون إنترنت، عبر شبكة BRouter المحلية)
    // ---------------------------------------------------------------------
    fun nearestDrivableRoad(
        currentLat: Double,
        currentLon: Double,
        brouterEngine: BRouterOfflineEngine?
    ): DesertMindAnswer {
        if (brouterEngine == null) {
            return DesertMindAnswer(text = "محرك المسارات غير جاهز بعد، يرجى المحاولة مرة أخرى بعد قليل.")
        }
        val distance = brouterEngine.findNearestRoadDistanceMeters(LatLng(currentLat, currentLon))
            ?: return DesertMindAnswer(text = "لا تتوفر بيانات طرق (.rd5) محمّلة على الجهاز لهذا النطاق — يرجى تحميلها من مدير التحميل أولاً.")
        return DesertMindAnswer(text = "أقرب طريق صالح للسيارات على بعد ${NavigationMath.formatRemaining(distance)} تقريبًا (حسب بيانات الطرق المحمّلة دون إنترنت).")
    }

    // ---------------------------------------------------------------------
    // هل أصل بسيارتي إلى هذا الموقع؟ (يتطلب موقعًا محددًا على الخريطة حاليًا)
    // ---------------------------------------------------------------------
    suspend fun canReachByCar(
        currentLat: Double,
        currentLon: Double,
        destination: PoiDetails,
        brouterEngine: BRouterOfflineEngine?
    ): DesertMindAnswer {
        if (brouterEngine == null) {
            return DesertMindAnswer(text = "محرك المسارات غير جاهز بعد، يرجى المحاولة مرة أخرى بعد قليل.")
        }
        val result = try {
            brouterEngine.calculateRoute(
                LatLng(currentLat, currentLon),
                LatLng(destination.latitude, destination.longitude),
                CarRouteMode.CLOSEST_PAVED
            )
        } catch (e: Exception) {
            return DesertMindAnswer(text = "تعذّر حساب المسار إلى \"${destination.name}\": ${e.message ?: "خطأ غير معروف"}.")
        }
        return if (result.isFallbackDirect) {
            DesertMindAnswer(
                text = "لم يُعثر على مسار سيارة معروف إلى \"${destination.name}\" ضمن بيانات الطرق المحمّلة — سيُعرض خط مباشر فقط (المسافة الجوية ${NavigationMath.formatRemaining(result.distanceMeters)}), وقد لا يكون الوصول بسيارة عادية مضمونًا."
            )
        } else {
            DesertMindAnswer(
                text = "نعم، يوجد مسار سيارة إلى \"${destination.name}\" ضمن بيانات الطرق المحمّلة — المسافة التقريبية ${NavigationMath.formatRemaining(result.distanceMeters)}.",
                targetLat = destination.latitude,
                targetLon = destination.longitude,
                targetTitle = destination.name
            )
        }
    }

    // ---------------------------------------------------------------------
    // أرشدني إلى هذا الموقع (يتطلب موقعًا محددًا على الخريطة حاليًا — الملاحة الفعلية
    // ينفذها المتصل عبر مسار "اذهب إلى" الموجود أصلًا؛ هذه الدالة تجهّز نص التأكيد فقط)
    // ---------------------------------------------------------------------
    fun guideMeAnswer(destination: PoiDetails): DesertMindAnswer {
        return DesertMindAnswer(
            text = "جارٍ بدء الإرشاد إلى \"${destination.name}\".",
            targetLat = destination.latitude,
            targetLon = destination.longitude,
            targetTitle = destination.name
        )
    }

    // ---------------------------------------------------------------------
    // أفضل اتجاه للعودة (يتطلب تسجيل رحلة نشط — نقطة بداية الرحلة الحالية)
    // ---------------------------------------------------------------------
    fun bestReturnDirection(
        currentLat: Double,
        currentLon: Double,
        tripStartLat: Double?,
        tripStartLon: Double?
    ): DesertMindAnswer {
        if (tripStartLat == null || tripStartLon == null) {
            return DesertMindAnswer(text = "هذه الميزة تحتاج إلى تسجيل رحلة نشط حاليًا (ابدأ تسجيل المسار أولاً) لمعرفة نقطة الانطلاق.")
        }
        val dist = NavigationMath.distanceMeters(currentLat, currentLon, tripStartLat, tripStartLon)
        val dir = NavigationMath.arabicDirection(
            NavigationMath.bearingDeg(currentLat, currentLon, tripStartLat, tripStartLon)
        )
        return DesertMindAnswer(
            text = "نقطة انطلاق الرحلة الحالية على بعد ${NavigationMath.formatRemaining(dist)} باتجاه $dir.",
            targetLat = tripStartLat,
            targetLon = tripStartLon,
            targetTitle = "نقطة انطلاق الرحلة"
        )
    }

    /**
     * Exact same title-prefix classification rules as
     * [com.alruwaili.garminalbarr.map.OsmdroidPoiOverlay.classifyPoi] (duplicated here
     * rather than shared, so this new feature cannot change the POI map's own labels
     * or risk breaking its already-verified behavior).
     */
    private fun classifyPoiTitle(title: String): String {
        if (title.startsWith("جبل")) return "mountain"
        if (title.startsWith("بئر")) return "well"
        if (title.startsWith("وادي")) return "valley"
        if (title.startsWith("روضة")) return "meadow"
        if (title.startsWith("طعس")) return "dune"
        if (title.startsWith("قلمة")) return "qalma"
        if (title.startsWith("شعيب")) return "ravine"
        if (title.contains("نفود")) return "dune"
        if (title.startsWith("مسجد")) return "mosque"
        if (title.startsWith("محطة")) return "station"
        if (title.startsWith("بيت")) return "house"
        if (title.startsWith("قري")) return "village"
        if (title.startsWith("قاع")) return "plain"
        if (title.startsWith("فيض")) return "spring"
        if (title.startsWith("مركز")) return "center"
        // --- فئات إضافية (توسيع 2026-11-25 بطلب المستخدم — نفس أسلوب البادئة الحرفية) ---
        if (title.startsWith("غدير")) return "pond"
        if (title.startsWith("مويه") || title.startsWith("مياه")) return "water_generic"
        if (title.startsWith("رجم")) return "cairn"
        if (title.startsWith("ردمية")) return "radmiya"
        if (title.startsWith("برج")) return "tower"
        if (title.startsWith("بلدة")) return "town"
        return "poi"
    }

    // ---------------------------------------------------------------------
    // أين أتجه؟ (اتجاه البوصلة الحالي للجهاز، بدون GPS/تسجيل مسار)
    // ---------------------------------------------------------------------
    fun whereAmIHeading(azimuthDeg: Float): DesertMindAnswer {
        val dir = NavigationMath.arabicDirection(azimuthDeg)
        return DesertMindAnswer(text = "اتجاهك الحالي (بحسب البوصلة): $dir (%.0f°).".format(azimuthDeg))
    }
}
