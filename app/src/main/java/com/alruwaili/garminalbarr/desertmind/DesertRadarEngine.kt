package com.alruwaili.garminalbarr.desertmind

import com.alruwaili.garminalbarr.LocationLightweight
import com.alruwaili.garminalbarr.map.NavigationMath

/**
 * "الرادار الصحراوي" — Desert Radar.
 *
 * Pure, fully-offline query logic: given the user's current position and the app's
 * already-loaded saved-locations list, returns every saved location within a chosen
 * radius (5–50 km, per the user's spec), each carrying its distance and compass
 * bearing from the user so the UI (see [com.alruwaili.garminalbarr.desertmind.ui])
 * can place it on a radar-style polar display. No network, no new data source — the
 * same [LocationLightweight] table already used everywhere else in the app.
 */
object DesertRadarEngine {

    const val MIN_RADIUS_KM = 5
    const val MAX_RADIUS_KM = 50
    const val DEFAULT_RADIUS_KM = 20

    data class RadarTarget(
        val id: Int,
        val title: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        /** Compass bearing in degrees [0, 360) from the user to this target. */
        val bearingDeg: Float
    )

    /**
     * Returns every location within [radiusMeters] of (currentLat, currentLon), sorted
     * nearest-first (the order the radar sweep should logically prioritize revealing
     * them, though the sweep itself reveals strictly by bearing order as it rotates).
     */
    fun locationsWithinRadius(
        currentLat: Double,
        currentLon: Double,
        allLocations: List<LocationLightweight>,
        radiusMeters: Double
    ): List<RadarTarget> {
        val results = ArrayList<RadarTarget>()
        for (loc in allLocations) {
            val lat = loc.latitude ?: continue
            val lon = loc.longitude ?: continue
            val title = loc.title ?: continue
            val dist = NavigationMath.distanceMeters(currentLat, currentLon, lat, lon)
            if (dist <= radiusMeters) {
                val bearing = NavigationMath.bearingDeg(currentLat, currentLon, lat, lon)
                results.add(
                    RadarTarget(
                        id = loc.id,
                        title = title,
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = dist,
                        bearingDeg = ((bearing % 360f) + 360f) % 360f
                    )
                )
            }
        }
        return results.sortedBy { it.distanceMeters }
    }
}
