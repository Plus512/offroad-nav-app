package com.navigationgps.desert.track

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxExporter {

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Builds a standard GPX 1.1 XML document string for the given track and sequential points.
     */
    fun buildGpxString(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        sb.append("""<gpx version="1.1" creator="Al-Dalilah Desert Navigator" xmlns="http://www.topografix.com/GPX/1/1">""").append("\n")

        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(track.title)).append("</name>\n")
        if (!track.description.isNullOrEmpty()) {
            sb.append("    <desc>").append(escapeXml(track.description)).append("</desc>\n")
        }
        sb.append("    <time>").append(isoDateFormat.format(Date(track.startTimeMillis))).append("</time>\n")
        sb.append("  </metadata>\n")

        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(track.title)).append("</name>\n")
        if (!track.description.isNullOrEmpty()) {
            sb.append("    <desc>").append(escapeXml(track.description)).append("</desc>\n")
        }
        sb.append("    <trkseg>\n")

        for (pt in points) {
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n", pt.latitude, pt.longitude))
            if (pt.altitude != null) {
                sb.append(String.format(Locale.US, "        <ele>%.1f</ele>\n", pt.altitude))
            }
            sb.append("        <time>").append(isoDateFormat.format(Date(pt.timestampMillis))).append("</time>\n")
            if (pt.speedMps != null) {
                sb.append(String.format(Locale.US, "        <speed>%.2f</speed>\n", pt.speedMps))
            }
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return sb.toString()
    }

    /**
     * Writes GPX string to a file in cacheDir/tracks/ and launches the Android Share Sheet.
     */
    fun shareGpx(context: Context, track: TrackEntity, points: List<TrackPointEntity>) {
        val gpxContent = buildGpxString(track, points)

        val tracksDir = File(context.cacheDir, "tracks").apply { if (!exists()) mkdirs() }
        val safeName = track.title.replace(Regex("[^a-zA-Z0-9ء-ي_\\-]"), "_").take(40)
        val gpxFile = File(tracksDir, "${safeName}_${track.id}.gpx")

        FileWriter(gpxFile).use { writer ->
            writer.write(gpxContent)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gpxFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            putExtra(Intent.EXTRA_TEXT, "مسار GPS: ${track.title} (${String.format(Locale.US, "%.1f كم", track.totalDistanceMeters / 1000.0)})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "مشاركة المسار بصيغة GPX")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
