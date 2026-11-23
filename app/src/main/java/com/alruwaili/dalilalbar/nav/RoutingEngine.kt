package com.alruwaili.dalilalbar.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    // نصف عرض "ممر" البحث عن الطرق الحقيقية بمحاذاة خط السير المستقيم بين الانطلاق والوجهة، عند
    // مسافات طويلة جداً يتعذّر فيها استعلام مربع كامل واحد. ١٦ كم (عرض كامل ٣٢ كم) كافٍ عملياً للعثور
    // على الطريق الفعلي الذي يتبعه السائق، مهما طالت المسافة (حتى مئات الكيلومترات)، دون استعلام
    // بحجم غير عملي.
    private const val CORRIDOR_HALF_WIDTH_METERS = 16000.0

    // نصف ضلع مربع بحث محلي إضافي حول كل من نقطتي الانطلاق والوجهة (وليس فقط داخل الممر المستقيم)،
    // لالتقاط أي طريق حقيقي قريب قد يقع جانبياً خارج الممر تماماً — كما في حالة وجهة تبعد عن أقرب
    // طريق بمقدار يصل إلى نحو ١٠٠ كم جانبياً، كما وصف المستخدم تحديداً. ١ درجة ≈ ١١٠ كم.
    private const val ENDPOINT_LOCAL_HALF_DEGREES = 1.0

    // حدّ أقصى معقول لمسافة "آخر ميل" المستقيمة من/إلى أقرب طريق حقيقي عند المسافات الطويلة، كي لا
    // يُقبل تسديد غير منطقي لطريق بعيد جداً فعلياً عن أي من النقطتين (وليس قيداً تعسفياً صغيراً كسابقه).
    private const val LONG_ROUTE_MAX_SNAP_METERS = 150000.0

    // سقف زمني إجمالي صارم لكامل عملية حساب مسار السيارة (كل استعلامات Overpass + بناء الرسم البياني
    // + Dijkstra مجتمعة)، لحل مشكلة الانتظار غير المحدود التي رصدها المستخدم فعلياً (17كم استغرق أكثر
    // من 3 دقائق، وقد يطول الانتظار عدة دقائق لمسارات 1000-2000كم). عند تجاوزه نتراجع فوراً لخط مستقيم.
    private const val OVERALL_DEADLINE_MS = 40_000L

    // حدّ مسافة قبل محاولة أي حساب مباشر عبر الطرق الحقيقية: المسافات الأكبر من هذا (مثل 1000-2000كم)
    // تُعرض كخط مستقيم فوراً دون أي استعلام شبكي، بدل انتظار قد يصل لعدة دقائق لنتيجة غير مضمونة أصلاً.
    private const val LONG_DISTANCE_LIVE_ROUTING_CUTOFF_METERS = 200_000.0

    private val mainHandler = Handler(Looper.getMainLooper())

    // يُضبط مرة واحدة من MainActivity.onCreate (applicationContext) — يُستخدم فقط لفحص وجود شبكة
    // طرق محمَّلة مسبقاً محلياً (زر "تحميل بيانات مسار السيارة" الجديد) قبل أي طلب Overpass مباشر. إن
    // بقي null (لم يُضبط لأي سبب) يستمر السلوك تماماً كالسابق: طلب Overpass المباشر فقط، دون أي تغيير.
    var appContext: Context? = null

    /** يحاول استخدام شبكة محمَّلة محلياً أولاً (بلا إنترنت)، فإن لم توجد يطلب Overpass المباشر كالمعتاد. */
    private fun fetchNetworkPreferCache(south: Double, west: Double, north: Double, east: Double): RoadNetwork {
        val ctx = appContext
        if (ctx != null) {
            val cached = RoadNetworkCache.loadCovering(ctx, south, west, north, east)
            if (cached != null && cached.ways.isNotEmpty()) return cached
        }
        return OverpassClient.fetchRoadNetwork(south, west, north, east)
    }

    /**
     * يُشغّل [block] (عملية قد تطول: بناء رسم بياني + Dijkstra) على خيط منفصل بحد زمني أقصى يساوي الوقت
     * المتبقي فعلياً من [deadlineAtMs]. إن انتهى الوقت أو تجاوزناه مسبقاً، يُعاد null فوراً دون انتظار
     * اكتمال العملية (نتخلّى عن نتيجتها، دون أن يعني هذا بالضرورة إيقاف الخيط الداخلي فوراً).
     */
    private fun <T> runWithDeadline(deadlineAtMs: Long, block: () -> T?): T? {
        val remaining = deadlineAtMs - System.currentTimeMillis()
        if (remaining <= 0L) return null
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(Callable { block() })
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

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

        if (straightDistance > LONG_DISTANCE_LIVE_ROUTING_CUTOFF_METERS) {
            // مسافة طويلة جداً (مثل 1000-2000كم): نعرض خطاً مستقيماً فوراً دون أي استعلام شبكي، بدل
            // انتظار قد يطول عدة دقائق لنتيجة غير مضمونة أصلاً، كما طلب المستخدم تحديداً.
            callback(
                straightFallback(
                    user, destination, straightDistance,
                    "المسافة (${GeoMath.formatDistance(straightDistance)}) تتجاوز حد البحث المباشر عبر الطرق الحقيقية " +
                        "(${GeoMath.formatDistance(LONG_DISTANCE_LIVE_ROUTING_CUTOFF_METERS)})، فعُرض خط مستقيم فوراً بدل انتظار قد يطول دقائق"
                )
            )
            return
        }

        Thread {
            val deadlineAtMs = System.currentTimeMillis() + OVERALL_DEADLINE_MS
            val outcome = try {
                computeCarRoute(user, destination, straightDistance, deadlineAtMs)
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

    private fun computeCarRoute(user: LatLng, destination: LatLng, straightDistance: Double, deadlineAtMs: Long): RouteOutcome {
        var south = minOf(user.latitude, destination.latitude)
        var north = maxOf(user.latitude, destination.latitude)
        var west = minOf(user.longitude, destination.longitude)
        var east = maxOf(user.longitude, destination.longitude)
        val latPad = maxOf(north - south, MIN_PAD_DEGREES) * BBOX_PADDING_RATIO
        val lonPad = maxOf(east - west, MIN_PAD_DEGREES) * BBOX_PADDING_RATIO
        south -= latPad; north += latPad; west -= lonPad; east += lonPad

        if ((north - south) > MAX_BBOX_SIDE_DEGREES || (east - west) > MAX_BBOX_SIDE_DEGREES) {
            // المسافة كبيرة جداً لاستعلام مربع كامل واحد. بدلاً من الاستسلام لخط مستقيم كامل فوراً،
            // نتتبّع الطريق الحقيقي الفعلي مهما طالت المسافة عبر استعلام "ممر" ضيّق بمحاذاة خط السير،
            // ولا نرسم خطاً مستقيماً إلا للفجوة الأخيرة غير المتصلة بطريق قرب الوجهة (أو قرب الانطلاق).
            return computeLongDistanceCarRoute(user, destination, straightDistance, deadlineAtMs)
        }

        val network = try {
            fetchNetworkPreferCache(south, west, north, east)
        } catch (e: OverpassClient.OverpassException) {
            return straightFallback(user, destination, straightDistance, e.message ?: "تعذّر جلب بيانات الطرق الحقيقية")
        }

        if (network.ways.isEmpty()) {
            return straightFallback(
                user, destination, straightDistance,
                "لا توجد بيانات طرق/دروب من خرائط الشارع المفتوح في هذه المنطقة"
            )
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "تجاوز الوقت المسموح لحساب المسار عبر الطرق الحقيقية (${OVERALL_DEADLINE_MS / 1000} ثانية)"
            )
        }

        // المحاولة الأولى: الطرق المعبّدة فقط. نُشغّل بناء الرسم البياني وDijkstra معاً بحد زمني صارم
        // لا يتجاوز الوقت المتبقي من المهلة الإجمالية، كي لا يتعلّق الحساب بلا حدود على رسم بياني ضخم.
        val pavedResult = runWithDeadline(deadlineAtMs) {
            tryRoute(RoadGraph.build(network, includeTrack = false), user, destination)
        }
        if (pavedResult != null) {
            return assemble(user, destination, pavedResult, straightDistance, reason = null)
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "تجاوز الوقت المسموح لحساب المسار عبر الطرق الحقيقية (${OVERALL_DEADLINE_MS / 1000} ثانية)"
            )
        }

        // المحاولة الثانية: إضافة الدروب/الردمية عند عدم اتصال المسار المعبّد.
        val mixedResult = runWithDeadline(deadlineAtMs) {
            tryRoute(RoadGraph.build(network, includeTrack = true), user, destination)
        }
        if (mixedResult != null) {
            return assemble(user, destination, mixedResult, straightDistance, reason = null)
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "تجاوز الوقت المسموح لحساب المسار عبر الطرق الحقيقية (${OVERALL_DEADLINE_MS / 1000} ثانية)"
            )
        }

        // المحاولة الثالثة (أخيرة): لا يتصل الطريق/الدرب بين النقطتين في بيانات OSM — خط مستقيم.
        return straightFallback(
            user, destination, straightDistance,
            "لا يتصل أي طريق معبّد أو درب حقيقي بين نقطة الانطلاق والوجهة في بيانات خرائط الشارع المفتوح"
        )
    }

    /**
     * عندما تكون المسافة بين نقطة الانطلاق والوجهة كبيرة جداً لاستعلام مربع واحد يغطيهما معاً، نتتبّع
     * الطريق الحقيقي الفعلي مهما طالت المسافة (حتى مئات الكيلومترات)، كما طلب المستخدم تحديداً: عبر
     * استعلام "ممر" ضيّق بمحاذاة خط السير المستقيم بين النقطتين (باستخدام مرشِّح poly في Overpass QL،
     * محقَّق من توثيقه الرسمي)، مع استعلامين محليين إضافيين حول كل من نقطتي الانطلاق والوجهة لالتقاط
     * أي طريق حقيقي قريب قد يقع جانبياً خارج الممر المستقيم تماماً. لا يُرسم خط مستقيم إلا للفجوة
     * الأخيرة غير المتصلة بأي طريق حقيقي (قرب الانطلاق و/أو قرب الوجهة) — وليس للمسافة الطويلة كلها.
     */
    private fun computeLongDistanceCarRoute(user: LatLng, destination: LatLng, straightDistance: Double, deadlineAtMs: Long): RouteOutcome {
        val tooFarReason = "المسافة بين نقطة الانطلاق والوجهة (${GeoMath.formatDistance(straightDistance)}) كبيرة جداً " +
            "لاستعلام مربع كامل واحد؛ استُخدم بحث بالممر مع الطريق الحقيقي بدلاً من ذلك"
        val timeoutSeconds = OVERALL_DEADLINE_MS / 1000

        val bearing = GeoMath.bearingDegrees(user, destination)
        val leftBearing = (bearing - 90.0 + 360.0) % 360.0
        val rightBearing = (bearing + 90.0) % 360.0
        val corridorPolygon = listOf(
            GeoMath.destinationPoint(user, leftBearing, CORRIDOR_HALF_WIDTH_METERS),
            GeoMath.destinationPoint(destination, leftBearing, CORRIDOR_HALF_WIDTH_METERS),
            GeoMath.destinationPoint(destination, rightBearing, CORRIDOR_HALF_WIDTH_METERS),
            GeoMath.destinationPoint(user, rightBearing, CORRIDOR_HALF_WIDTH_METERS)
        )

        val networks = ArrayList<RoadNetwork>()
        try {
            networks.add(OverpassClient.fetchRoadNetworkInPolygon(corridorPolygon))
        } catch (e: OverpassClient.OverpassException) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، وتعذّر جلب بيانات الممر (${e.message ?: "خطأ اتصال"})"
            )
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، وتجاوز الوقت المسموح ($timeoutSeconds ثانية) قبل إكمال البحث عن الطريق الحقيقي"
            )
        }

        fun localNetwork(center: LatLng): RoadNetwork? = try {
            fetchNetworkPreferCache(
                center.latitude - ENDPOINT_LOCAL_HALF_DEGREES,
                center.longitude - ENDPOINT_LOCAL_HALF_DEGREES,
                center.latitude + ENDPOINT_LOCAL_HALF_DEGREES,
                center.longitude + ENDPOINT_LOCAL_HALF_DEGREES
            )
        } catch (e: OverpassClient.OverpassException) {
            null // تعذّر الاستعلام المحلي الإضافي ليس سبباً كافياً لإفشال كل المحاولة؛ نكتفي بالممر
        }

        // نتخطّى الاستعلامين المحليين الإضافيين إن كانت المهلة قد أوشكت على الانتهاء أصلاً، بدل إهدار
        // الوقت المتبقي على استعلامات شبكية جديدة لن يبقى وقت لاستثمار نتيجتها بأي حال.
        if (System.currentTimeMillis() < deadlineAtMs) {
            localNetwork(user)?.let { networks.add(it) }
        }
        if (System.currentTimeMillis() < deadlineAtMs) {
            localNetwork(destination)?.let { networks.add(it) }
        }

        val network = OverpassClient.merge(networks)
        if (network.ways.isEmpty()) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، ولا توجد بيانات طرق/دروب حقيقية قريبة من نقطة الانطلاق أو الوجهة"
            )
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، وتجاوز الوقت المسموح ($timeoutSeconds ثانية)"
            )
        }

        val pavedResult = runWithDeadline(deadlineAtMs) {
            tryRoute(RoadGraph.build(network, includeTrack = false), user, destination, LONG_ROUTE_MAX_SNAP_METERS)
        }
        if (pavedResult != null) {
            return assemble(
                user, destination, pavedResult, straightDistance,
                reason = "$tooFarReason؛ عُثر على طريق معبّد حقيقي متصل عبر بحث بالممر"
            )
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، وتجاوز الوقت المسموح ($timeoutSeconds ثانية) أثناء البحث عن مسار معبّد"
            )
        }

        val mixedResult = runWithDeadline(deadlineAtMs) {
            tryRoute(RoadGraph.build(network, includeTrack = true), user, destination, LONG_ROUTE_MAX_SNAP_METERS)
        }
        if (mixedResult != null) {
            return assemble(
                user, destination, mixedResult, straightDistance,
                reason = "$tooFarReason؛ عُثر على طريق/درب حقيقي متصل عبر بحث بالممر"
            )
        }

        if (System.currentTimeMillis() >= deadlineAtMs) {
            return straightFallback(
                user, destination, straightDistance,
                "$tooFarReason، وتجاوز الوقت المسموح ($timeoutSeconds ثانية) أثناء البحث عن مسار/درب"
            )
        }

        return straightFallback(
            user, destination, straightDistance,
            "$tooFarReason، ولا يتصل أي طريق/درب حقيقي بين نقطة الانطلاق والوجهة حتى ضمن منطقة البحث الممتدة"
        )
    }

    private class SnappedRoute(val path: RoutePlanner.PathResult, val startSnapDist: Double, val endSnapDist: Double)

    private fun tryRoute(
        graph: RoadGraph, user: LatLng, destination: LatLng,
        maxSnapDistanceMeters: Double = MAX_SNAP_DISTANCE_METERS
    ): SnappedRoute? {
        val startSnap = graph.nearestNode(user) ?: return null
        val endSnap = graph.nearestNode(destination) ?: return null
        if (startSnap.second > maxSnapDistanceMeters || endSnap.second > maxSnapDistanceMeters) return null
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
