package com.navigationgps.desert.map

import android.graphics.Color
import android.graphics.DashPathEffect
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class OsmdroidCompassOverlay(private val mapView: MapView) {

    private var bearingLine: Polyline? = null

    fun showBearingLine(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        hideBearingLine()
        val poly = Polyline()
        poly.outlinePaint.color = Color.parseColor("#D4A017")
        poly.outlinePaint.strokeWidth = 6f
        poly.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        poly.setPoints(listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon)))
        mapView.overlays.add(poly)
        mapView.invalidate()
        bearingLine = poly
    }

    fun hideBearingLine() {
        bearingLine?.let { mapView.overlays.remove(it) }
        mapView.invalidate()
        bearingLine = null
    }
}
