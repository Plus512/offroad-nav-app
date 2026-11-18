package com.alruwaili.garminnav

import android.content.Context

/**
 * إعدادات التطبيق المحفوظة (SharedPreferences بسيطة، بلا قاعدة بيانات): تُعدَّل
 * من شاشة «الإعدادات» وتُقرأ فوراً من أي شاشة أخرى تحتاجها — وحدة المسافة تؤثر
 * على كل نص مسافة في التطبيق (شريط «إلى أين؟»، تفاصيل الموقع، قائمة المسارات)،
 * وبقية الإعدادات تُقرأ عند فتح الشاشة الرئيسية أو رسم الخريطة.
 */
object Prefs {
    private const val NAME = "app_settings"
    private const val KEY_UNIT_MILES = "unit_miles"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_ALWAYS_LABELS = "always_labels"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun unitMiles(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNIT_MILES, false)
    fun setUnitMiles(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_UNIT_MILES, v).apply() }

    fun keepScreenOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_KEEP_SCREEN_ON, v).apply() }

    fun alwaysLabels(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ALWAYS_LABELS, false)
    fun setAlwaysLabels(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_ALWAYS_LABELS, v).apply() }

    /** ينسّق مسافة بالأمتار حسب وحدة المستخدم المختارة (كم أو ميل)، مع اسم الوحدة ضمن النص. */
    fun formatDistance(ctx: Context, meters: Double): String {
        return if (unitMiles(ctx)) {
            String.format("%.2f ميل", meters / 1609.344)
        } else if (meters >= 1000.0) {
            String.format("%.2f كم", meters / 1000.0)
        } else {
            String.format("%.0f م", meters)
        }
    }
}
