package com.navigationgps.desert.map.routing

data class LatLng(val latitude: Double, val longitude: Double)

enum class CarRouteMode {
    CLOSEST_PAVED,
    SHORTEST_TOTAL
}

interface OfflineRouter {
    suspend fun calculateRoute(
        origin: LatLng,
        destination: LatLng,
        mode: CarRouteMode = CarRouteMode.CLOSEST_PAVED
    ): RouteResult

    data class RouteResult(
        val polyline: List<LatLng>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val instructions: List<RouteInstruction>,
        val leg1Direct: List<LatLng> = emptyList(),
        val leg2Track: List<LatLng> = emptyList(),
        val leg3Direct: List<LatLng> = emptyList(),
        val isFallbackDirect: Boolean = false,
        val warningMessage: String? = null
    )

    data class RouteInstruction(
        val text: String,
        val distanceMeters: Double,
        val maneuver: ManeuverType
    )

    enum class ManeuverType {
        TURN_LEFT,
        TURN_RIGHT,
        STRAIGHT,
        U_TURN,
        ARRIVE
    }
}
