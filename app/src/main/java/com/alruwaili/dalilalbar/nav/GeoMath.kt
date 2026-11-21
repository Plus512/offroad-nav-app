package com.alruwaili.dalilalbar.nav

import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * صيغ جيوديسية قياسية ومعروفة (المسافة الكروية بطريقة Haversine، والاتجاه الأولي بين نقطتين) —
 * رياضيات راسخة من علم الملاحة، وليست تخميناً أو قيماً مصطنعة.
 */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /** المسافة الفعلية بالأمتار بين نقطتين على سطح الأرض (الدائرة الكبرى). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val dPhi = Math.toRadians(b.latitude - a.latitude)
        val dLambda = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_METERS * c
    }

    /** الاتجاه الأولي (bearing) بالدرجات من ٠ إلى ٣٦٠، حيث ٠ = الشمال، من نقطة أ إلى نقطة ب. */
    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val dLambda = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /** يحوّل اتجاهاً بالدرجات إلى اسم جهة عربي مبسّط (شمال، شمال شرقي، شرق، ...). */
    fun compassLabelArabic(bearing: Double): String {
        val dirs = arrayOf(
            "شمال", "شمال شرقي", "شرق", "جنوب شرقي",
            "جنوب", "جنوب غربي", "غرب", "شمال غربي"
        )
        val idx = (((bearing % 360) + 360) % 360 / 45.0).let { Math.round(it).toInt() % 8 }
        return dirs[idx]
    }

    /**
     * نقطة الوصول عند السير مسافة معيّنة بالأمتار من نقطة بداية باتجاه (bearing) معيّن — الصيغة
     * الجيوديسية الكروية القياسية المعاكسة لصيغة bearingDegrees (معروفة في علم الملاحة، وليست تخميناً).
     * تُستخدم لبناء ضلعي "ممر" البحث عن الطرق الحقيقية بمحاذاة خط السير بين نقطتين بعيدتين جداً.
     */
    fun destinationPoint(start: LatLng, bearingDegrees: Double, distanceMeters: Double): LatLng {
        val delta = distanceMeters / EARTH_RADIUS_METERS
        val theta = Math.toRadians(bearingDegrees)
        val phi1 = Math.toRadians(start.latitude)
        val lambda1 = Math.toRadians(start.longitude)

        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        return LatLng(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }

    fun formatDistance(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format(Locale.US, "%.1f كم", meters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f م", meters)
        }
    }
}
