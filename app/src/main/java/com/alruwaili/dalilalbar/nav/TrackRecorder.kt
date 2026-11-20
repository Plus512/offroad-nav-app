package com.alruwaili.dalilalbar.nav

import android.content.Context
import android.location.Location
import android.os.Environment
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تسجيل مسار حركة المستخدم الفعلي، من نقاط GPS حقيقية واردة فعلياً من الجهاز فقط (لا نقاط
 * مصطنعة أو مقدَّرة)، وحفظه عند الإيقاف كملف GPX قياسي (المعيار العالمي لملفات المسارات) داخل
 * مجلد ملفات التطبيق الخاص — لا يحتاج إذن تخزين إضافياً على أندرويد.
 */
class TrackRecorder(private val context: Context) {

    private var points: MutableList<RecordedFix>? = null

    val isRecording: Boolean get() = points != null

    private data class RecordedFix(val latLng: LatLng, val altitudeMeters: Double, val timeMs: Long)

    fun start() {
        points = ArrayList()
    }

    fun addFix(location: Location) {
        points?.add(RecordedFix(LatLng(location.latitude, location.longitude), location.altitude, location.time))
    }

    /** يوقف التسجيل ويكتب ملف GPX على القرص؛ يُرجع الملف الناتج، أو null إن لم يكن هناك تسجيل جارٍ أو لم تُسجَّل أي نقطة. */
    fun stopAndSave(): File? {
        val recorded = points ?: return null
        points = null
        if (recorded.isEmpty()) return null

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "tracks")
        if (!dir.exists()) dir.mkdirs()
        val fileName = "track_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gpx"
        val file = File(dir, fileName)

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"دليل البر\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(fileName).append("</name><trkseg>\n")
        for (fix in recorded) {
            sb.append("    <trkpt lat=\"").append(fix.latLng.latitude)
                .append("\" lon=\"").append(fix.latLng.longitude).append("\">\n")
            sb.append("      <ele>").append(fix.altitudeMeters).append("</ele>\n")
            sb.append("      <time>").append(isoFormat.format(Date(fix.timeMs))).append("</time>\n")
            sb.append("    </trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        file.writeText(sb.toString())
        return file
    }
}
