package com.alruwaili.garminalbarr.map

import android.graphics.DashPathEffect
import android.graphics.Paint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OsmdroidRouteOverlay(private val mapView: MapView) {

    companion object {
        private const val DIRECT_ROUTE_COLOR_INT = 0xFFE53935.toInt()
        private const val CAR_TRACK_BORDER_COLOR_INT = 0xFF1A1209.toInt()
        private const val CAR_TRACK_CORE_COLOR_INT = 0xFFFF6D00.toInt()
        private const val OFF_ROAD_DASH_COLOR_INT = 0xFFFFB300.toInt()
        private const val REJOIN_LEG_COLOR_INT = 0xFF00E5FF.toInt()

        /**
         * Slerp between [p1] and [p2]. [numPoints] is clamped to 2..200
         * (0/negative would divide by zero and yield NaN GeoPoints).
         * @throws IllegalArgumentException on out-of-range coordinates.
         */
        fun interpolateGeodesic(p1: GeoPoint, p2: GeoPoint, numPoints: Int = 50): List<GeoPoint> {
            require(p1.latitude in -90.0..90.0 && p2.latitude in -90.0..90.0) {
                "latitude out of range: ${p1.latitude}, ${p2.latitude}"
            }
            require(p1.longitude in -180.0..180.0 && p2.longitude in -180.0..180.0) {
                "longitude out of range: ${p1.longitude}, ${p2.longitude}"
            }
            val n = numPoints.coerceIn(2, 200)
            val lat1 = Math.toRadians(p1.latitude)
            val lon1 = Math.toRadians(p1.longitude)
            val lat2 = Math.toRadians(p2.latitude)
            val lon2 = Math.toRadians(p2.longitude)

            val dLat = lat2 - lat1
            val dLon = lon2 - lon1
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            val d = 2 * atan2(sqrt(a), sqrt(1 - a))

            if (d < 1e-6) return listOf(p1, p2)

            val points = mutableListOf<GeoPoint>()
            for (i in 0..n) {
                val f = i.toDouble() / n
                val aFrac = sin((1 - f) * d) / sin(d)
                val bFrac = sin(f * d) / sin(d)

                val x = aFrac * cos(lat1) * cos(lon1) + bFrac * cos(lat2) * cos(lon2)
                val y = aFrac * cos(lat1) * sin(lon1) + bFrac * cos(lat2) * sin(lon2)
                val z = aFrac * sin(lat1) + bFrac * sin(lat2)

                val latInterp = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
                val lonInterp = Math.toDegrees(atan2(y, x))
                points.add(GeoPoint(latInterp, lonInterp))
            }
            return points
        }
    }

    private val activeOverlays = mutableListOf<Overlay>()
    private var rejoinPolyline: Polyline? = null
    private val primaryRoutePoints = mutableListOf<GeoPoint>()

    fun getPrimaryRoutePoints(): List<GeoPoint> = primaryRoutePoints

    fun showRoute(points: List<GeoPoint>) {
        if (points.size < 2) return
        hideRoute()

        val allRoutePoints = mutableListOf<GeoPoint>()
        for (i in 0 until points.size - 1) {
            val segment = interpolateGeodesic(points[i], points[i + 1], numPoints = 30)
            if (i > 0 && segment.isNotEmpty()) {
                allRoutePoints.addAll(segment.drop(1))
            } else {
                allRoutePoints.addAll(segment)
            }
        }

        primaryRoutePoints.clear()
        primaryRoutePoints.addAll(allRoutePoints)

        val poly = Polyline(mapView).apply {
            isGeodesic = true
            outlinePaint.color = DIRECT_ROUTE_COLOR_INT
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            setPoints(allRoutePoints)
        }
        mapView.overlays.add(poly)
        activeOverlays.add(poly)

        mapView.invalidate()
    }

    fun showRoute(startPoint: GeoPoint, destPoint: GeoPoint) {
        showRoute(listOf(startPoint, destPoint))
    }

    /**
     * Live straight line: re-anchors the existing direct line at the user's
     * current position so it always points from the user to the target.
     * Updates the polyline in place (keeps overlay order / POI taps intact).
     */
    fun updateDirectRoute(startPoint: GeoPoint, destPoint: GeoPoint) {
        val poly = activeOverlays.singleOrNull() as? Polyline
        if (poly == null) {
            showRoute(startPoint, destPoint)
            return
        }
        val pts = interpolateGeodesic(startPoint, destPoint, numPoints = 30)
        if (pts.size < 2) return
        clearRejoinLeg()
        primaryRoutePoints.clear()
        primaryRoutePoints.addAll(pts)
        poly.setPoints(pts)
        mapView.invalidate()
    }

    /**
     * Renders 3-leg hybrid desert navigation:
     * - Leg 1 (Off-road departure to track snap): Dashed amber line
     * - Leg 2 (BRouter OSM car/track route): Solid high-contrast Burnt Orange with dark border
     * - Leg 3 (Track exit to off-road POI): Dashed amber line
     */
    fun showCarRoute(
        leg1Direct: List<GeoPoint>,
        leg2Track: List<GeoPoint>,
        leg3Direct: List<GeoPoint>
    ) {
        hideRoute()

        if (leg2Track.isEmpty()) {
            if (leg1Direct.size >= 2) {
                showRoute(leg1Direct)
            }
            return
        }

        primaryRoutePoints.clear()
        if (leg1Direct.isNotEmpty()) primaryRoutePoints.addAll(leg1Direct)
        if (leg2Track.isNotEmpty()) primaryRoutePoints.addAll(leg2Track)
        if (leg3Direct.isNotEmpty()) primaryRoutePoints.addAll(leg3Direct)

        if (leg1Direct.size >= 2) {
            val leg1Poly = Polyline(mapView).apply {
                isGeodesic = true
                outlinePaint.color = OFF_ROAD_DASH_COLOR_INT
                outlinePaint.strokeWidth = 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(25f, 15f), 0f)
                outlinePaint.isAntiAlias = true
                setPoints(leg1Direct)
            }
            mapView.overlays.add(leg1Poly)
            activeOverlays.add(leg1Poly)
        }

        if (leg2Track.size >= 2) {
            val borderPoly = Polyline(mapView).apply {
                outlinePaint.color = CAR_TRACK_BORDER_COLOR_INT
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                setPoints(leg2Track)
            }
            mapView.overlays.add(borderPoly)
            activeOverlays.add(borderPoly)

            val trackPoly = Polyline(mapView).apply {
                outlinePaint.color = CAR_TRACK_CORE_COLOR_INT
                outlinePaint.strokeWidth = 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                setPoints(leg2Track)
            }
            mapView.overlays.add(trackPoly)
            activeOverlays.add(trackPoly)
        }

        if (leg3Direct.size >= 2) {
            val leg3Poly = Polyline(mapView).apply {
                isGeodesic = true
                outlinePaint.color = OFF_ROAD_DASH_COLOR_INT
                outlinePaint.strokeWidth = 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(25f, 15f), 0f)
                outlinePaint.isAntiAlias = true
                setPoints(leg3Direct)
            }
            mapView.overlays.add(leg3Poly)
            activeOverlays.add(leg3Poly)
        }

        mapView.invalidate()
    }

    /**
     * Recycles a single instance of rejoinPolyline for off-route rejoin guidance.
     * High-contrast Cyan dashed polyline connecting current vehicle GPS to the closest route waypoint.
     */
    fun updateRejoinLeg(currentLocation: GeoPoint, targetWaypoint: GeoPoint) {
        var poly = rejoinPolyline
        if (poly == null) {
            poly = Polyline(mapView).apply {
                outlinePaint.color = REJOIN_LEG_COLOR_INT
                outlinePaint.strokeWidth = 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
                outlinePaint.isAntiAlias = true
            }
            rejoinPolyline = poly
        }
        poly.setPoints(listOf(currentLocation, targetWaypoint))
        if (!mapView.overlays.contains(poly)) {
            val idx = mapView.overlays.indexOfFirst { it is OsmdroidPoiOverlay }
            if (idx >= 0) {
                mapView.overlays.add(idx, poly)
            } else {
                mapView.overlays.add(0, poly)
            }
        }
        poly.isEnabled = true
        mapView.invalidate()
    }

    fun clearRejoinLeg() {
        val poly = rejoinPolyline ?: return
        poly.isEnabled = false
        poly.setPoints(emptyList())
        mapView.overlays.remove(poly)
        mapView.invalidate()
    }

    fun hideRoute() {
        clearRejoinLeg()
        primaryRoutePoints.clear()
        for (overlay in activeOverlays) {
            mapView.overlays.remove(overlay)
        }
        activeOverlays.clear()
    }
}
