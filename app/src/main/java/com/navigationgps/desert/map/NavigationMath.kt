package com.navigationgps.desert.map

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.osmdroid.util.GeoPoint

/**
 * Single source of truth for navigation geodesy + HUD formatting.
 *
 * Extracted from duplicated implementations in PoiBottomSheet
 * (calculateDistance / calculateBearing / getBearingDirection) and
 * SmoothLocationOverlay (bearingBetween) so the live HUD engine,
 * bottom sheet, and overlay all agree.
 *
 * Pure Kotlin — unit-testable without Robolectric.
 */
object NavigationMath {

    const val ARRIVAL_THRESHOLD_METERS = 30.0
    const val EQUATORIAL_METERS_PER_PIXEL_ZOOM_0 = 156543.03
    const val DRIVING_LOOKAHEAD_RATIO = 0.18

    /** Haversine distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Initial bearing in degrees [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = (lon2 - lon1) * PI / 180.0
        val lat1Rad = lat1 * PI / 180.0
        val lat2Rad = lat2 * PI / 180.0
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        var bearing = (atan2(y, x) * 180.0 / PI).toFloat()
        if (bearing < 0f) bearing += 360f
        return bearing
    }

    /** Signed shortest-arc angular difference: b1 - b2 in [-180, 180). */
    fun bearingDiff(b1: Float, b2: Float): Float =
        HeadingFilter.shortestArcDelta(b1, b2)

    /** Dynamic remaining-distance formatting: >=1000m -> "17.7 كم" else "850 م". */
    fun formatRemaining(meters: Double): String {
        // GPS glitches can yield negative/NaN — never show nonsense in the HUD.
        val m = when {
            meters.isNaN() -> 0.0
            meters < 0.0 -> 0.0
            else -> meters
        }
        return if (m >= 1000.0) {
            String.format(Locale.US, "%.1f كم", m / 1000.0)
        } else {
            String.format(Locale.US, "%.0f م", m)
        }
    }

    /** 8-wind Arabic direction label. */
    fun arabicDirection(bearingDeg: Float): String {
        val b = ((bearingDeg % 360f) + 360f) % 360f
        return when {
            b < 22.5f -> "شمال"
            b < 67.5f -> "شمال شرقي"
            b < 112.5f -> "شرقي"
            b < 157.5f -> "جنوب شرقي"
            b < 202.5f -> "جنوب"
            b < 247.5f -> "جنوب غربي"
            b < 292.5f -> "غربي"
            b < 337.5f -> "شمال غربي"
            else -> "شمال"
        }
    }

    fun isArrived(remainingMeters: Double): Boolean =
        remainingMeters < ARRIVAL_THRESHOLD_METERS

    /**
     * On-screen angle of the user→destination line in Heading-Up mode.
     *
     * θ_screen = Bearing_target − Azimuth_device, wrapped to (-180, 180].
     * 0 = line points straight UP; + = target is clockwise (right) of heading.
     *
     * Spec: facing target directly (az == bearing) → 0° (vertical UP).
     * Steering 30° right → line slants 30° left (-30°), and vice versa.
     */
    fun screenAngleDeg(bearingDeg: Float, azimuthDeg: Float): Float =
        HeadingFilter.shortestArcDelta(bearingDeg, azimuthDeg)

    /**
     * MapView orientation that keeps the physical world aligned in Heading-Up.
     *
     * osmdroid `setMapOrientation(deg)` rotates the map clockwise by `deg`,
     * so the map must counter-rotate the device heading: `-azimuth`.
     * Normalized to [0, 360).
     */
    fun mapOrientationFor(azimuthDeg: Float): Float =
        ((360f - azimuthDeg) % 360f + 360f) % 360f

    /**
     * Destination point given start coordinate, distance in meters, and initial bearing in degrees.
     * Exact spherical forward geodesic formula (Earth radius = 6371000m).
     */
    fun destinationPoint(
        startLat: Double,
        startLon: Double,
        distanceMeters: Double,
        bearingDeg: Float
    ): GeoPoint {
        if (distanceMeters <= 0.0) return GeoPoint(startLat, startLon)
        val r = 6371000.0
        val d = distanceMeters / r
        val brng = Math.toRadians((((bearingDeg % 360f) + 360f) % 360f).toDouble())
        val lat1 = Math.toRadians(startLat)
        val lon1 = Math.toRadians(startLon)

        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))

        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Finds the closest point on segment A -> B to the given point P (lat, lon).
     * Uses equirectangular Cartesian projection centered at segment mid-latitude.
     * Clamps projection scalar t to [0.0, 1.0].
     */
    fun closestPointOnSegment(
        lat: Double,
        lon: Double,
        segStartLat: Double,
        segStartLon: Double,
        segEndLat: Double,
        segEndLon: Double
    ): GeoPoint {
        val midLatRad = Math.toRadians((segStartLat + segEndLat) / 2.0)
        val cosMid = cos(midLatRad)
        val metersPerDegLat = 111319.5
        val metersPerDegLon = metersPerDegLat * cosMid

        val dx = (segEndLon - segStartLon) * metersPerDegLon
        val dy = (segEndLat - segStartLat) * metersPerDegLat
        val lenSq = dx * dx + dy * dy

        if (lenSq < 1e-6) {
            return GeoPoint(segStartLat, segStartLon)
        }

        val px = (lon - segStartLon) * metersPerDegLon
        val py = (lat - segStartLat) * metersPerDegLat

        val t = (px * dx + py * dy) / lenSq
        val tClamped = t.coerceIn(0.0, 1.0)

        val projLat = segStartLat + tClamped * (segEndLat - segStartLat)
        val projLon = segStartLon + tClamped * (segEndLon - segStartLon)
        return GeoPoint(projLat, projLon)
    }

    /**
     * Finds the closest point on a polyline to point P (lat, lon) along with
     * the cross-track distance in meters.
     * Uses zero intermediate object allocations in the loop for 60fps / 1Hz performance.
     * Returns Pair(closestGeoPoint, crossTrackDistanceMeters) or null if points.size < 2.
     */
    fun findClosestPointOnPolyline(
        lat: Double,
        lon: Double,
        points: List<GeoPoint>
    ): Pair<GeoPoint, Double>? {
        if (points.size < 2) return null
        var bestLat = 0.0
        var bestLon = 0.0
        var minDist = Double.MAX_VALUE
        val metersPerDegLat = 111319.5

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val sLat = p1.latitude
            val sLon = p1.longitude
            val eLat = p2.latitude
            val eLon = p2.longitude

            val midLatRad = Math.toRadians((sLat + eLat) / 2.0)
            val metersPerDegLon = metersPerDegLat * cos(midLatRad)

            val dx = (eLon - sLon) * metersPerDegLon
            val dy = (eLat - sLat) * metersPerDegLat
            val lenSq = dx * dx + dy * dy

            val tClamped = if (lenSq < 1e-6) {
                0.0
            } else {
                val px = (lon - sLon) * metersPerDegLon
                val py = (lat - sLat) * metersPerDegLat
                ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
            }

            val projLat = sLat + tClamped * (eLat - sLat)
            val projLon = sLon + tClamped * (eLon - sLon)
            val dist = distanceMeters(lat, lon, projLat, projLon)

            if (dist < minDist) {
                minDist = dist
                bestLat = projLat
                bestLon = projLon
            }
        }

        return if (minDist != Double.MAX_VALUE) Pair(GeoPoint(bestLat, bestLon), minDist) else null
    }

    /**
     * Exponential moving average step for dynamic heading updates with shortest-arc wrapping.
     * Wraps difference to (-180, 180] and applies deadband to prevent micro-jitter.
     */
    fun smoothHeadingStep(
        currentHeading: Float,
        targetHeading: Float,
        alpha: Float = 0.22f,
        deadbandDeg: Float = 0.5f
    ): Float {
        val diff = ((targetHeading - currentHeading + 540f) % 360f) - 180f
        if (kotlin.math.abs(diff) < deadbandDeg) return currentHeading
        return ((currentHeading + diff * alpha) % 360f + 360f) % 360f
    }
}
