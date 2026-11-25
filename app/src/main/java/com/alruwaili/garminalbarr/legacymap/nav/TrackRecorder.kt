package com.alruwaili.garminalbarr.legacymap.nav

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

    /** ملخّص مسار أُوقِف تسجيله للتو (بانتظار قرار المستخدم: حفظ باسم أو حذف)، وليس ملفاً مكتوباً بعد. */
    class PendingTrack internal constructor(
        internal val fixes: List<LatLng>,
        internal val altitudes: List<Double>,
        internal val times: List<Long>,
        val pointCount: Int,
        val distanceMeters: Double,
        val durationMs: Long
    )

    fun start() {
        points = ArrayList()
    }

    fun addFix(location: Location) {
        points?.add(RecordedFix(LatLng(location.latitude, location.longitude), location.altitude, location.time))
    }

    /** يوقف التسجيل فوراً ويُرجع ملخّص المسار (المسافة/المدة/عدد النقاط) دون كتابة أي ملف — الكتابة
     * الفعلية تنتظر قرار المستخدم الصريح بالحفظ (باسم) أو الحذف، كما طُلب. null إن لم يكن هناك تسجيل جارٍ. */
    fun stop(): PendingTrack? {
        val recorded = points ?: return null
        points = null
        if (recorded.isEmpty()) return PendingTrack(emptyList(), emptyList(), emptyList(), 0, 0.0, 0L)

        var dist = 0.0
        for (i in 1 until recorded.size) {
            dist += GeoMath.distanceMeters(recorded[i - 1].latLng, recorded[i].latLng)
        }
        val duration = recorded.last().timeMs - recorded.first().timeMs
        return PendingTrack(
            fixes = recorded.map { it.latLng },
            altitudes = recorded.map { it.altitudeMeters },
            times = recorded.map { it.timeMs },
            pointCount = recorded.size,
            distanceMeters = dist,
            durationMs = duration
        )
    }

    /** يكتب مسارًا معلَّقاً إلى ملف GPX قياسي باسم اختاره المستخدم؛ يُرجع الملف الناتج. */
    fun saveAs(pending: PendingTrack, displayName: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "tracks")
        if (!dir.exists()) dir.mkdirs()
        val safeName = displayName.ifBlank {
            "مسار_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }
        val fileName = safeName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".gpx"
        val file = File(dir, fileName)

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"الدليلة 2\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(safeName).append("</name><trkseg>\n")
        for (i in pending.fixes.indices) {
            val p = pending.fixes[i]
            sb.append("    <trkpt lat=\"").append(p.latitude)
                .append("\" lon=\"").append(p.longitude).append("\">\n")
            sb.append("      <ele>").append(pending.altitudes[i]).append("</ele>\n")
            sb.append("      <time>").append(isoFormat.format(Date(pending.times[i]))).append("</time>\n")
            sb.append("    </trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        file.writeText(sb.toString())
        return file
    }
}
