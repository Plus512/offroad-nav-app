package com.alruwaili.garminalbarr.map

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.alruwaili.garminalbarr.R
import java.util.Locale

object LocationShareHelper {

    const val DEFAULT_LOCATION_NAME = "موقع محدد"
    const val CHOOSER_TITLE = "مشاركة الموقع عبر"

    const val SHARE_TEMPLATE = "📍 تم إرسال موقع من تطبيق %1\$s:\n" +
        "🏷️ الاسم: %2\$s\n" +
        "🧭 الإحداثيات: %3\$s\n\n" +
        "📲 لتحميل التطبيق والملاحة في الصحراء:\n" +
        "https://play.google.com/store/apps/details?id=%4\$s\n\n" +
        "🗺️ أو عرض الإحداثيات مباشرة:\n" +
        "https://www.google.com/maps?q=%3\$s"

    /**
     * Pure formatting function for testability without Android Context dependencies.
     */
    fun formatShareText(
        appName: String,
        title: String?,
        latitude: Double,
        longitude: Double,
        packageName: String
    ): String {
        val cleanTitle = if (title.isNullOrBlank()) DEFAULT_LOCATION_NAME else title.trim()
        val coords = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        return String.format(Locale.US, SHARE_TEMPLATE, appName, cleanTitle, coords, packageName)
    }

    /**
     * Context-aware formatter resolving app name and package name dynamically.
     */
    fun formatShareText(
        context: Context,
        title: String?,
        latitude: Double,
        longitude: Double
    ): String {
        val appName = try {
            context.getString(R.string.app_name)
        } catch (_: Exception) {
            "قارمن البر"
        }
        return formatShareText(appName, title, latitude, longitude, context.packageName)
    }

    /**
     * Builds ACTION_SEND Intent with EXTRA_TEXT and chooser wrapper.
     */
    fun createShareIntent(
        context: Context,
        title: String?,
        latitude: Double,
        longitude: Double
    ): Intent {
        val shareText = formatShareText(context, title, latitude, longitude)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val chooser = Intent.createChooser(sendIntent, CHOOSER_TITLE)
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooser
    }

    /**
     * Launches the system chooser dialog to share location.
     */
    fun shareLocation(
        context: Context,
        title: String?,
        latitude: Double,
        longitude: Double
    ) {
        val chooser = createShareIntent(context, title, latitude, longitude)
        context.startActivity(chooser)
    }
}
