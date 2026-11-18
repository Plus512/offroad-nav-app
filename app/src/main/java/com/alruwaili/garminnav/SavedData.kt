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
 * كل مسار يُخزَّن بسجل ملخّص واحد في tracks.txt (البداية/النهاية/المسافة)، بالإضافة
 * إلى ملف منفصل يحمل كل نقاط المسار الفعلية (خط طول/عرض لكل نقطة GPS التُقطت أثناء
 * التسجيل) باسم مبني من وقت البداية — هذا ما يسمح برسم خط المسار لاحقاً عند استعراضه.
 *
 * فكرة الشاشتين («النقاط المحفوظة» و«المسارات») مأخوذة من شاشتي MyLocationsActivity
 * وTracks في تطبيق قارمن، لكن بتخزين وشيفرة مكتوبين من الصفر خصيصاً لهذا التطبيق.
 */
object SavedData {

    private fun pointsFile(ctx: Context) = File(ctx.filesDir, "saved_points.txt")
    private fun tracksFile(ctx: Context) = File(ctx.filesDir, "tracks.txt")
    private fun trackPointsFile(ctx: Context, startMs: Long) = File(ctx.filesDir, "track_points_$startMs.txt")

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

    /** يحفظ ملخّص المسار، وإن توفّرت نقاط فعلية (خط سير GPS) يحفظها في ملف منفصل لعرضها لاحقاً. */
    @Synchronized
    fun addTrack(ctx: Context, startMs: Long, endMs: Long, distanceM: Double, points: List<Pair<Int, Int>>) {
        tracksFile(ctx).appendText("$startMs|$endMs|$distanceM\n")
        if (points.isNotEmpty()) {
            val sb = StringBuilder()
            for ((lon, lat) in points) {
                sb.append(lon).append(',').append(lat).append('\n')
            }
            trackPointsFile(ctx, startMs).writeText(sb.toString())
        }
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

    /** يقرأ نقاط مسار محدَّد (بوحدات قارمن الخام) لرسمه على الخريطة؛ قائمة فارغة إن لم تُحفظ نقاط له. */
    @Synchronized
    fun loadTrackPoints(ctx: Context, startMs: Long): List<Pair<Int, Int>> {
        val f = trackPointsFile(ctx, startMs)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size != 2) return@mapNotNull null
            try {
                parts[0].toInt() to parts[1].toInt()
            } catch (e: Exception) {
                null
            }
        }
    }
}
