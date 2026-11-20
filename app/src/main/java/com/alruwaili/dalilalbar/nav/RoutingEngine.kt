package com.alruwaili.dalilalbar.nav

import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLng

enum class NavMode { STRAIGHT, CAR }

/** قطعة واحدة من المسار المرسوم على الخريطة، بتصنيفها: "paved" (معبّد) أو "track" (ردمية) أو "straight" (خط مستقيم). */
data class RouteSegment(val points: List<LatLng>, val cls: String)

/**
 * النتيجة النهائية لحساب المسار، جاهزة للعرض على الخريطة.
 * @param fallbackReason نص عربي يوضّح سبب التراجع لخط مستقيم (جزئياً أو كلياً)، أو null إن لم يحدث أي تراجع.
 */
class RouteOutcome(
    val segments: List<RouteSegment>,
    val totalDistanceMeters: Double,
    val usedTrack: Boolean,
    val fallbackReason: String?
)

/**
 * محرّك المسارات: يحسب المسافة والمسار بين موقع المستخدم ونقطة مختارة على الخريطة.
 *
 * منطق وضع "سيارة" (مطابق تماماً لما طُلب): يحاول أولاً إيجاد مسار عبر الطرق المعبّدة فقط من بيانات
 * OpenStreetMap الحقيقية (عبر Overpass API). فإن لم يوجد مسار معبّد متصل، يحاول مرة ثانية بإضافة
 * الدروب/الردمية (highway=track) إلى الشبكة. فإن لم يتصل المسار حتى مع الدروب (لا توجد بيانات طرق
 * قريبة كافية، أو خطأ شبكة، أو لا اتصال فعلي بين النقطتين في بيانات OSM)، يُعرض خط مستقيم كحل أخير
 * — مع توضيح صريح للمستخدم بسبب التراجع، دون أي تخمين أو اختلاق لبيانات طرق غير موجودة فعلياً.
 */
object RoutingEngine {
    private const val MAX_SNAP_DISTANCE_METERS = 6000.0
    private const val BBOX_PADDING_RATIO = 0.25
    private const val MIN_PAD_DEGREES = 0.01
    private const val MAX_BBOX_SIDE_DEGREES = 1.6 // ≈ حد أقصى عملي لحجم استعلام Overpass الواحد

    private val mainHandler = Handler(Looper.getMainLooper())

    fun computeRoute(user: LatLng, destination: LatLng, mode: NavMode, callback: (RouteOutcome) -> Unit) {
        val straightDistance = GeoMath.distanceMeters(user, destination)

        if (mode == NavMode.STRAIGHT) {
            callback(
                RouteOutcome(
                    segments = listOf(RouteSegment(listOf(user, destination), "straight")),
                    totalDistanceMeters = straightDistance,
                    usedTrack = false,
                    fallbackReason = null
                )
            )
            return
        }

        Thread {
            val outcome = try {
                computeCarRoute(user, destination, straightDistance)
            } catch (e: Exception) {
                straightFallback(user, destination, straightDistance, "خطأ غير متوقع: ${e.message ?: e.javaClass.simpleName}")
            }
            mainHandler.post { callback(outcome) }
        }.start()
    }

    private fun straightFallback(user: LatLng, destination: LatLng, distance: Double, reason: String): RouteOutcome =
        RouteOutcome(
            segments = listOf(RouteSegment(listOf(user, destination), "straight")),
            totalDistanceMeters = distance,
            usedTrack = false,
            fallbackReason = reason
        )

    private fun computeCarRoute(user: LatLng, destination: LatLng, straightDistance: Double): RouteOutcome {
        var south = minOf(user.latitude, destination.latitude)
        var north = maxOf(user.latitude, destination.latitude)
        var west = minOf(user.longitude, destination.longitude)
        var east = maxOf(user.longitude, destination.longitude)
        val latPad = maxOf(north - south, MIN_PAD_DEGREES) * BBOX_PADDING_RATIO
        val lonPad = maxOf(east - west, MIN_PAD_DEGREES) * BBOX_PADDING_RATIO
        south -= latPad; north += latPad; west -= lonPad; east += lonPad

        if ((north - south) > MAX_BBOX_SIDE_DEGREES || (east - west) > MAX_BBOX_SIDE_DEGREES) {
            return straightFallback(
                user, destination, straightDistance,
                "المسافة بين نقطة الانطلاق والوجهة كبيرة جداً لاستعلام طرق حقيقي واحد"
            )
        }

        val network = try {
            OverpassClient.fetchRoadNetwork(south, west, north, east)
        } catch (e: OverpassClient.OverpassException) {
            return straightFallback(user, destination, straightDistance, e.message ?: "تعذّر جلب بيانات الطرق الحقيقية")
        }

        if (network.ways.isEmpty()) {
            return straightFallback(
                user, destination, straightDistance,
                "لا توجد بيانات طرق/دروب من خرائط الشارع المفتوح في هذه المنطقة"
            )
        }

        // المحاولة الأولى: الطرق المعبّدة فقط.
        val pavedGraph = RoadGraph.build(network, includeTrack = false)
        val pavedResult = tryRoute(pavedGraph, user, destination)
        if (pavedResult != null) {
            return assemble(user, destination, pavedResult, straightDistance, reason = null)
        }

        // المحاولة الثانية: إضافة الدروب/الردمية عند عدم اتصال المسار المعبّد.
        val mixedGraph = RoadGraph.build(network, includeTrack = true)
        val mixedResult = tryRoute(mixedGraph, user, destination)
        if (mixedResult != null) {
            return assemble(user, destination, mixedResult, straightDistance, reason = null)
        }

        // المحاولة الثالثة (أخيرة): لا يتصل الطريق/الدرب بين النقطتين في بيانات OSM — خط مستقيم.
        return straightFallback(
            user, destination, straightDistance,
            "لا يتصل أي طريق معبّد أو درب حقيقي بين نقطة الانطلاق والوجهة في بيانات خرائط الشارع المفتوح"
        )
    }

    private class SnappedRoute(val path: RoutePlanner.PathResult, val startSnapDist: Double, val endSnapDist: Double)

    private fun tryRoute(graph: RoadGraph, user: LatLng, destination: LatLng): SnappedRoute? {
        val startSnap = graph.nearestNode(user) ?: return null
        val endSnap = graph.nearestNode(destination) ?: return null
        if (startSnap.second > MAX_SNAP_DISTANCE_METERS || endSnap.second > MAX_SNAP_DISTANCE_METERS) return null
        val path = RoutePlanner.shortestPath(graph, startSnap.first, endSnap.first) ?: return null
        return SnappedRoute(path, startSnap.second, endSnap.second)
    }

    private fun assemble(
        user: LatLng, destination: LatLng, snapped: SnappedRoute, straightDistance: Double, reason: String?
    ): RouteOutcome {
        val segments = ArrayList<RouteSegment>()
        var total = 0.0

        val routePoints = snapped.path.points
        if (routePoints.isEmpty()) {
            return straightFallback(user, destination, straightDistance, reason ?: "تعذّر بناء مسار صالح")
        }

        if (snapped.startSnapDist > 15.0) {
            segments.add(RouteSegment(listOf(user, routePoints.first()), "straight"))
            total += snapped.startSnapDist
        }

        // تجميع القطع المتتالية من نفس النوع (معبّد/درب) في عنصر رسم واحد لتبسيط الطبقة.
        var runStartIdx = 0
        var runClass: RoadClass? = null
        for (i in snapped.path.edgeClasses.indices) {
            val cls = snapped.path.edgeClasses[i]
            if (runClass == null) {
                runClass = cls
            } else if (cls != runClass) {
                segments.add(
                    RouteSegment(routePoints.subList(runStartIdx, i + 1), classLabel(runClass))
                )
                runStartIdx = i
                runClass = cls
            }
        }
        if (runClass != null) {
            segments.add(RouteSegment(routePoints.subList(runStartIdx, routePoints.size), classLabel(runClass)))
        }
        total += snapped.path.distanceMeters

        if (snapped.endSnapDist > 15.0) {
            segments.add(RouteSegment(listOf(routePoints.last(), destination), "straight"))
            total += snapped.endSnapDist
        }

        val usedTrack = snapped.path.edgeClasses.any { it == RoadClass.TRACK }
        return RouteOutcome(segments, total, usedTrack, reason)
    }

    private fun classLabel(cls: RoadClass): String = if (cls == RoadClass.TRACK) "track" else "paved"
}
