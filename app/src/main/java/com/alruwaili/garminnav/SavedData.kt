package com.alruwaili.garminnav

import android.content.Context
import java.io.File

/** نقطة محفوظة بالاسم مع إحداثيات قارمن الخام (نفس وحدات GarminMap) ووقت الحفظ. */
data class SavedPoint(val name: String, val lon: Int, val lat: Int, val timeMs: Long)

/** جلسة مسار مسجَّلة: بداية/نهاية/مسافة بالأمتار. */
data class TrackSession(val startMs: Long, val endMs: Long, val distanceM: Double)

/**
 * تخزين بسيط للنقاط المحفوظة والمسارات، بدون قاعدة بيانات: كل سجل بسطر نصي واحد
 * مفصول بـ"|" داخل ملف في تخزين التطبيق الداخلي (filesDir). يبقى موجوداً بين فتحات
 * التطبيق، ويُحذف فقط إذا حُذف التطبيق نفسه — وهذا مقبول لهذه البيانات، بخلاف ملف
 * الخريطة نفسه الذي يُحفظ في مجلد خارجي دائم عبر MapStorage.
 *
 * فكرة الشاشتين («النقاط المحفوظة» و«المسارات») مأخوذة من شاشتي MyLocationsActivity
 * وTracks في تطبيق قارمن، لكن بتخزين وشيفرة مكتوبين من الصفر خصيصاً لهذا التطبيق.
 */
object SavedData {

    private fun pointsFile(ctx: Context) = File(ctx.filesDir, "saved_points.txt")
    private fun tracksFile(ctx: Context) = File(ctx.filesDir, "tracks.txt")

    @Synchronized
    fun addPoint(ctx: Context, name: String, lon: Int, lat: Int) {
        val safeName = name.replace("|", " ").replace("\n", " ").trim().ifEmpty { "نقطة" }
        val line = "$safeName|$lon|$lat|${System.currentTimeMillis()}\n"
        pointsFile(ctx).appendText(line)
    }

    @Synchronized
    fun listPoints(ctx: Context): List<SavedPoint> {
        val f = pointsFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 4) return@mapNotNull null
            try {
                SavedPoint(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toLong())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timeMs }
    }

    @Synchronized
    fun deletePoint(ctx: Context, point: SavedPoint) {
        val f = pointsFile(ctx)
        if (!f.exists()) return
        val remaining = f.readLines().filterNot { line ->
            val parts = line.split("|")
            parts.size == 4 &&
                parts[0] == point.name &&
                parts[1] == point.lon.toString() &&
                parts[2] == point.lat.toString() &&
                parts[3] == point.timeMs.toString()
        }
        f.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n")
    }

    @Synchronized
    fun addTrack(ctx: Context, startMs: Long, endMs: Long, distanceM: Double) {
        val line = "$startMs|$endMs|$distanceM\n"
        tracksFile(ctx).appendText(line)
    }

    @Synchronized
    fun listTracks(ctx: Context): List<TrackSession> {
        val f = tracksFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) return@mapNotNull null
            try {
                TrackSession(parts[0].toLong(), parts[1].toLong(), parts[2].toDouble())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startMs }
    }
}
