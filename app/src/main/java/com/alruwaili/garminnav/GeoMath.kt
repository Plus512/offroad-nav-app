package com.alruwaili.garminnav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * دوال رياضية جغرافية بسيطة، شيفرة أصلية بالكامل: تحويل وحدات قارمن الخام
 * (raw = درجة × ٢^٢٣ ÷ ١٨٠، نفس الوحدة المستخدمة في كل مكان بالتطبيق) من/إلى
 * الدرجات الحقيقية، وحساب المسافة (صيغة هافرساين) والاتجاه بين نقطتين — تُستخدم
 * في شاشة «إلى أين؟» وتفاصيل الموقع وشريط المسافة المباشر.
 */
object GeoMath {
    private const val UNITS_PER_DEGREE = (1 shl 23) / 180.0
    private const val EARTH_RADIUS_M = 6371000.0

    fun rawToDeg(raw: Int): Double = raw / UNITS_PER_DEGREE

    fun degToRaw(deg: Double): Int = (deg * UNITS_PER_DEGREE).toInt()

    /** المسافة بالأمتار بين نقطتين بوحدات قارمن الخام (صيغة هافرساين). */
    fun distanceMeters(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
        val la1 = Math.toRadians(rawToDeg(lat1))
        val la2 = Math.toRadians(rawToDeg(lat2))
        val dLa = la2 - la1
        val dLo = Math.toRadians(rawToDeg(lon2) - rawToDeg(lon1))
        val a = sin(dLa / 2).pow(2) + cos(la1) * cos(la2) * sin(dLo / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** الاتجاه بالدرجات (٠..٣٦٠، ٠=شمال) من نقطة إلى أخرى بوحدات قارمن الخام. */
    fun bearingDegrees(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
        val la1 = Math.toRadians(rawToDeg(lat1))
        val la2 = Math.toRadians(rawToDeg(lat2))
        val dLo = Math.toRadians(rawToDeg(lon2) - rawToDeg(lon1))
        val y = sin(dLo) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLo)
        val brg = Math.toDegrees(atan2(y, x))
        return (brg + 360.0) % 360.0
    }

    /** يحوّل درجة اتجاه إلى اسم جهة مختصر بالعربية (ثماني جهات). */
    fun compassLabel(deg: Double): String {
        val dirs = arrayOf("شمال", "شمال شرقي", "شرق", "جنوب شرقي", "جنوب", "جنوب غربي", "غرب", "شمال غربي")
        val idx = (((deg % 360.0) + 360.0) % 360.0 / 45.0).roundToInt() % 8
        return dirs[idx]
    }
}
