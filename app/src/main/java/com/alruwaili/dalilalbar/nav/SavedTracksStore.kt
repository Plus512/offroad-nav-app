package com.alruwaili.dalilalbar.nav

import android.content.Context
import android.os.Environment
import org.maplibre.android.geometry.LatLng
import java.io.File

/**
 * يسرد ملفات GPX المسجَّلة فعلياً (TrackRecorder.saveAs) من القرص، ويقرأ نقاطها لعرضها كخط على
 * الخريطة عند تفعيل "إظهار". حالة الإظهار/الإخفاء لكل ملف تُحفظ في SharedPreferences بمفتاح اسم
 * الملف نفسه (لا حاجة لقاعدة بيانات منفصلة لعدد محدود من ملفات المسارات).
 */
class SavedTracksStore(private val context: Context) {

    data class SavedTrack(val file: File, val name: String, var visible: Boolean)

    private val prefs = context.getSharedPreferences("dalilalbar_saved_tracks", Context.MODE_PRIVATE)
    private fun visKey(fileName: String) = "visible_$fileName"

    private val tracksDir: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "tracks")

    fun list(): List<SavedTrack> {
        val dir = tracksDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                SavedTrack(f, f.nameWithoutExtension, prefs.getBoolean(visKey(f.name), false))
            } ?: emptyList()
    }

    fun setVisible(fileName: String, visible: Boolean) {
        prefs.edit().putBoolean(visKey(fileName), visible).apply()
    }

    fun delete(file: File) {
        prefs.edit().remove(visKey(file.name)).apply()
        file.delete()
    }

    /** يقرأ نقاط trkpt من ملف GPX قياسي (بنفس التنسيق الذي يكتبه TrackRecorder.saveAs بالضبط). */
    fun readPoints(file: File): List<LatLng> {
        val points = ArrayList<LatLng>()
        try {
            val text = file.readText()
            val regex = Regex("<trkpt lat=\"([^\"]+)\" lon=\"([^\"]+)\"")
            for (m in regex.findAll(text)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                points.add(LatLng(lat, lon))
            }
        } catch (e: Exception) {
            // ملف تالف أو غير قابل للقراءة: يُعامل كمسار بلا نقاط بدل تعطيل الشاشة كاملة.
        }
        return points
    }
}
