package com.alruwaili.garminalbarr.map

import android.content.Context
import android.content.Intent

/**
 * Single owner of "open location in DesertMap": coordinate validation +
 * intent construction. Previously copy-pasted across FavoritesActivity,
 * LocationsFragment and MyLocationsFragment (which already drifted once).
 */
object MapNavigationHelper {

    /** Finite, in range, and not the (0,0) null-island placeholder. */
    fun isValidLatLon(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)

    fun goToDesertMap(context: Context, lat: Double, lon: Double, title: String?, openCompass: Boolean = false) {
        val intent = Intent(context, DesertMapActivity::class.java).apply {
            putExtra("latitude", lat)
            putExtra("longitude", lon)
            putExtra("title", title)
            putExtra("EXTRA_TARGET_LAT", lat)
            putExtra("EXTRA_TARGET_LON", lon)
            putExtra("EXTRA_TARGET_NAME", title)
            // openCompass: يفتح البوصلة الكاملة فورًا نحو هذا الموقع (مستخدم من سهم
            // الاتجاه في قوائم المفضلة/مواقعي)، بدل مجرد تمركز الخريطة عليه.
            putExtra("EXTRA_OPEN_COMPASS", openCompass)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }
}
