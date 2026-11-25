package com.alruwaili.garminalbarr.map.routing

import android.content.Context
import android.util.Log
import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import btools.router.BRouterVoiceHintHelper
import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.alruwaili.garminalbarr.map.MapFileManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Embedded offline BRouter engine implementation.
 *
 * Hardware Constraint Guarantees (Infinix X623 / 2GB RAM / MT6761):
 * - Thread-isolated via Dispatchers.Default.limitedParallelism(1) to avoid CPU contention with Mapsforge.
 * - Memory-constrained via rc.memoryclass = 32.
 * - Enforces single route calculation (max-routes = 1).
 * - Safe 15s execution timeout.
 * - Explicit OutOfMemoryError recovery degrading to direct geodesic line navigation.
 *
 * Performance: RoutingContext (expensive profile/lookups parsing) is built once and cached.
 * Only lightweight RoutingEngine instances are created per-route.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BRouterOfflineEngine(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) : OfflineRouter {

    companion object {
        private const val TAG = "BRouterOfflineEngine"
        private const val TIMEOUT_MS = 6_000L
        private const val MEMORY_CLASS = 32
        private const val OFF_ROAD_SNAP_THRESHOLD_METERS = 50.0
        private const val OFF_ROAD_SPEED_MS = 5.55 // ~20 km/h off-road crawling
        private const val DIRECT_CRUISE_SPEED_MS = 16.66 // ~60 km/h average desert direct speed
    }

    /**
     * Cached RoutingContext — built once from profile + lookups, reused for every route.
     * The profile parsing, metadata reading, and expression context setup are the dominant
     * cost in BRouter initialization (~100-300ms on low-end hardware). Since the desert-car.brf
     * profile and lookups.dat never change at runtime, rebuilding per-call is pure waste.
     */
    @Volatile
    private var cachedRoutingContext: RoutingContext? = null

    @Volatile
    private var cachedShortestRoutingContext: RoutingContext? = null

    @Volatile
    private var cachedShortestWayValidator: BExpressionContextWay? = null

    init {
        ensureAssetsExtracted()
        buildAndCacheRoutingContext()
    }

    override suspend fun calculateRoute(
        origin: LatLng,
        destination: LatLng,
        mode: CarRouteMode
    ): OfflineRouter.RouteResult = withContext(dispatcher) {
        val directDist = calculateDistanceMeters(origin, destination)
        if (directDist < 10.0) {
            return@withContext OfflineRouter.RouteResult(
                polyline = listOf(origin, destination),
                distanceMeters = directDist,
                durationSeconds = directDist / DIRECT_CRUISE_SPEED_MS,
                instructions = listOf(
                    OfflineRouter.RouteInstruction(
                        text = "أنت في موقع الهدف",
                        distanceMeters = directDist,
                        maneuver = OfflineRouter.ManeuverType.ARRIVE
                    )
                ),
                isFallbackDirect = false
            )
        }

        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                if (mode == CarRouteMode.SHORTEST_TOTAL) {
                    executeGlobalShortestSearch(origin, destination)
                } else {
                    executeBRouterSearch(origin, destination, mode)
                }
            }

            if (result != null) {
                result
            } else {
                Log.w(TAG, "BRouter calculation timed out after ${TIMEOUT_MS}ms, falling back to direct geodesic route")
                fallbackDirectRoute(origin, destination, "لا توجد دروب سيارات قريبة، استخدم المسار المباشر")
            }
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "BRouter OutOfMemoryError encountered on target device. Reclaiming heap and falling back to geodesic line.", oom)
            System.gc()
            fallbackDirectRoute(origin, destination, "تم تفعيل الخط المباشر لتوفير ذاكرة الجهاز")
        } catch (e: Throwable) {
            Log.e(TAG, "BRouter route execution failed [${e.javaClass.name}]: ${e.message}. Falling back to direct geodesic route.", e)
            val userWarning = when (e) {
                is ClassNotFoundException, is NoSuchMethodError, is NoSuchFieldError ->
                    "خطأ في إعداد محرك المسارات، يرجى تحديث التطبيق"
                is OutOfMemoryError ->
                    "تم تفعيل الخط المباشر لتوفير ذاكرة الجهاز"
                is IllegalArgumentException ->
                    "خطأ في بيانات المسارات، تم استخدام الخط المباشر (${e.message ?: "غير معروف"})"
                else ->
                    "حدث خطأ في حساب المسار (${e.javaClass.simpleName})، تم الخط المباشر"
            }
            fallbackDirectRoute(origin, destination, userWarning)
        }
    }

    private fun getRoutingContext(mode: CarRouteMode): RoutingContext? {
        val brouterDir = getBrouterWorkDir()
        val lookupsFile = File(brouterDir, "lookups.dat")
        return when (mode) {
            CarRouteMode.CLOSEST_PAVED -> {
                val profileFile = File(brouterDir, "desert-car.brf")
                if (!lookupsFile.exists() || !profileFile.exists()) {
                    ensureAssetsExtracted()
                    cachedRoutingContext = null
                }
                cachedRoutingContext ?: run {
                    val fresh = buildRoutingContextSafe(brouterDir, lookupsFile, profileFile)
                    cachedRoutingContext = fresh
                    fresh
                }
            }
            CarRouteMode.SHORTEST_TOTAL -> {
                val profileFile = File(brouterDir, "desert-car-shortest.brf")
                if (!lookupsFile.exists() || !profileFile.exists()) {
                    ensureAssetsExtracted()
                    cachedShortestRoutingContext = null
                }
                cachedShortestRoutingContext ?: run {
                    val fresh = buildRoutingContextSafe(brouterDir, lookupsFile, profileFile)
                    cachedShortestRoutingContext = fresh
                    fresh
                }
            }
        }
    }

    /**
     * Dedicated, thread-safe cached way expression context for candidate road extraction.
     * Unlike RoutingContext.expctxWay (which BRouter's RoutingEngine releases and wipes to null
     * via ProfileCache.releaseProfile() upon engine completion), this validator remains permanently
     * in memory and is decoupled from BRouter's execution lifecycle.
     */
    private fun getShortestWayValidator(): BExpressionContextWay? {
        cachedShortestWayValidator?.let { return it }
        val brouterDir = getBrouterWorkDir()
        val lookupsFile = File(brouterDir, "lookups.dat")
        val profileFile = File(brouterDir, "desert-car-shortest.brf")
        if (!lookupsFile.exists() || !profileFile.exists()) {
            ensureAssetsExtracted()
        }
        return try {
            val meta = BExpressionMetaData()
            val validator = BExpressionContextWay(meta)
            meta.readMetaData(lookupsFile)
            validator.parseFile(profileFile, "global")
            cachedShortestWayValidator = validator
            validator
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating shortest way validator: ${e.message}", e)
            null
        }
    }

    /**
     * العقل الصحراوي الذكي ("أقرب طريق معبّد"): يعيد المسافة بالأمتار من [point] إلى
     * أقرب نقطة على شبكة الطرق المحلية (.rd5) الصالحة للسيارات، أو null إذا لم تتوفر
     * بيانات طرق محمّلة لهذا النطاق على الجهاز. يعيد استخدام نفس آلية
     * [CandidateRoadExtractor] المستخدمة داخليًا في وضع "أقصر مسار إجمالي"، دون أي
     * اتصال إنترنت.
     */
    fun findNearestRoadDistanceMeters(point: LatLng): Double? {
        val segmentDir = MapFileManager.getRoutingSegmentsDir(context)
        if (!segmentDir.exists() || (segmentDir.listFiles()?.none { it.extension == "rd5" && it.length() > 0 } == true)) {
            return null
        }
        val wayValidator = getShortestWayValidator() ?: return null
        val candidates = CandidateRoadExtractor.findCandidates(
            context = context,
            queryPoint = point,
            wayValidator = wayValidator,
            segmentDir = segmentDir,
            maxCandidates = 1
        )
        return candidates.minByOrNull { it.distanceMeters }?.distanceMeters
    }

    private fun executeGlobalShortestSearch(
        origin: LatLng,
        destination: LatLng
    ): OfflineRouter.RouteResult {
        val rc = getRoutingContext(CarRouteMode.SHORTEST_TOTAL)
            ?: return fallbackDirectRoute(origin, destination, "خطأ في تهيئة محرك المسارات — تم الخط المباشر")

        val segmentDir = MapFileManager.getRoutingSegmentsDir(context)
        if (!segmentDir.exists() || (segmentDir.listFiles()?.none { it.extension == "rd5" && it.length() > 0 } == true)) {
            Log.i(TAG, "No .rd5 segments found on device, using direct geodesic navigation")
            return fallbackDirectRoute(origin, destination, "يرجى تحميل مسارات السيارة والبر من مدير التحميل")
        }

        val wayValidator = getShortestWayValidator()
        if (wayValidator == null) {
            Log.w(TAG, "Way validator unavailable; falling back to standard search")
            return executeBRouterSearch(origin, destination, CarRouteMode.SHORTEST_TOTAL)
        }

        val entryCandidates = CandidateRoadExtractor.findCandidates(
            context = context,
            queryPoint = origin,
            wayValidator = wayValidator,
            segmentDir = segmentDir
        )
        val exitCandidates = CandidateRoadExtractor.findCandidates(
            context = context,
            queryPoint = destination,
            wayValidator = wayValidator,
            segmentDir = segmentDir
        )

        Log.i(TAG, "Shortest total candidate search: ${entryCandidates.size} entry candidates, ${exitCandidates.size} exit candidates")

        if (entryCandidates.isEmpty() || exitCandidates.isEmpty()) {
            Log.i(TAG, "CandidateRoadExtractor found no road candidates in range; falling back to standard BRouter search")
            return executeBRouterSearch(origin, destination, CarRouteMode.SHORTEST_TOTAL)
        }

        val candidatePairs = CandidateRoadExtractor.buildCandidatePairs(
            origin = origin,
            destination = destination,
            entryCandidates = entryCandidates,
            exitCandidates = exitCandidates
        )

        if (candidatePairs.isEmpty()) {
            return executeBRouterSearch(origin, destination, CarRouteMode.SHORTEST_TOTAL)
        }

        var bestResult: OfflineRouter.RouteResult? = null
        var bestTotalDistanceMeters = Double.MAX_VALUE
        val pairTimeoutMs = (TIMEOUT_MS / 2).coerceAtLeast(2_500L)

        for (pair in candidatePairs) {
            // Branch-and-bound pruning:
            // Since pairs are sorted by Euclidean Lower Bound:
            // LB = Dist(Origin, Entry) + Dist_Euclid(Entry, Exit) + Dist(Exit, Dest)
            // If LB >= bestTotalDistanceMeters, no future pair can possibly beat our best!
            if (pair.lowerBoundMeters >= bestTotalDistanceMeters) {
                Log.d(TAG, "Pruning pair (LB=${pair.lowerBoundMeters.roundToInt()}m >= best=${bestTotalDistanceMeters.roundToInt()}m)")
                break
            }

            val fromIl = encodeToILatLon(pair.entry.point.latitude, pair.entry.point.longitude)
            val toIl = encodeToILatLon(pair.exit.point.latitude, pair.exit.point.longitude)

            val fromNode = OsmNodeNamed().apply {
                ilat = fromIl.first
                ilon = fromIl.second
                name = "from"
            }

            val toNode = OsmNodeNamed().apply {
                ilat = toIl.first
                ilon = toIl.second
                name = "to"
            }

            val routeResult = runBRouterEngine(
                fromNode = fromNode,
                toNode = toNode,
                segmentDir = segmentDir,
                rc = rc,
                timeoutMs = pairTimeoutMs,
                origin = origin,
                destination = destination,
                attemptLabel = "shortest-candidate"
            )

            if (routeResult != null) {
                Log.i(TAG, "Candidate pair evaluated: totalDist=${routeResult.distanceMeters.roundToInt()}m vs currentBest=${bestTotalDistanceMeters.roundToInt()}m")
                if (routeResult.distanceMeters < bestTotalDistanceMeters) {
                    bestTotalDistanceMeters = routeResult.distanceMeters
                    bestResult = routeResult
                }
            }
        }

        if (bestResult != null) {
            return bestResult
        }

        Log.w(TAG, "All candidate pairs failed; falling back to standard BRouter search")
        return executeBRouterSearch(origin, destination, CarRouteMode.SHORTEST_TOTAL)
    }

    private fun executeBRouterSearch(
        origin: LatLng,
        destination: LatLng,
        mode: CarRouteMode
    ): OfflineRouter.RouteResult {
        val brouterDir = getBrouterWorkDir()
        val lookupsFile = File(brouterDir, "lookups.dat")
        val profileFile = File(brouterDir, if (mode == CarRouteMode.SHORTEST_TOTAL) "desert-car-shortest.brf" else "desert-car.brf")

        val rc = getRoutingContext(mode)
            ?: return fallbackDirectRoute(origin, destination, "خطأ في تهيئة محرك المسارات — تم الخط المباشر")

        // Encode waypoints as BRouter integer microdegrees
        val fromIl = encodeToILatLon(origin.latitude, origin.longitude)
        val toIl = encodeToILatLon(destination.latitude, destination.longitude)

        val fromNode = OsmNodeNamed().apply {
            ilat = fromIl.first
            ilon = fromIl.second
            name = "from"
        }

        val toNode = OsmNodeNamed().apply {
            ilat = toIl.first
            ilon = toIl.second
            name = "to"
        }

        val segmentDir = MapFileManager.getRoutingSegmentsDir(context)
        if (!segmentDir.exists() || (segmentDir.listFiles()?.none { it.extension == "rd5" && it.length() > 0 } == true)) {
            Log.i(TAG, "No .rd5 segments found on device, using direct geodesic navigation")
            return fallbackDirectRoute(origin, destination, "يرجى تحميل مسارات السيارة والبر من مدير التحميل")
        }

        // Diagnostic logging — let BRouter attempt routing even if intermediate segments are missing;
        // BRouter handles missing segments internally by degrading to beeline.
        val originSeg = MapFileManager.getSegmentNameForCoordinate(origin.latitude, origin.longitude)
        val destSeg = MapFileManager.getSegmentNameForCoordinate(destination.latitude, destination.longitude)
        val originSegExists = MapFileManager.isSegmentDownloaded(context, originSeg)
        val destSegExists = MapFileManager.isSegmentDownloaded(context, destSeg)

        Log.i(TAG, buildString {
            append("BRouter pre-flight:\n")
            append("  Origin: lat=${origin.latitude}, lon=${origin.longitude} -> ilat=${fromIl.first}, ilon=${fromIl.second}\n")
            append("  Dest:   lat=${destination.latitude}, lon=${destination.longitude} -> ilat=${toIl.first}, ilon=${toIl.second}\n")
            append("  originSeg=$originSeg(exists=$originSegExists)\n")
            append("  destSeg=$destSeg(exists=$destSegExists)\n")
            append("  segmentDir=${segmentDir.absolutePath}\n")
            append("  files=${segmentDir.listFiles()?.map { "${it.name}(${it.length()}B)" }}\n")
            append("  lookupsFile=${lookupsFile.absolutePath}(exists=${lookupsFile.exists()}, size=${lookupsFile.length()}B)\n")
            append("  profileFile=${profileFile.absolutePath}(exists=${profileFile.exists()}, size=${profileFile.length()}B)\n")
            append("  rc.waypointCatchingRange=${rc.waypointCatchingRange}\n")
            append("  rc.buildBeelineOnRange=${rc.buildBeelineOnRange}\n")
            append("  rc.useDynamicDistance=${rc.useDynamicDistance}\n")
        })

        // Single attempt with 6s timeout
        val result = runBRouterEngine(
            fromNode, toNode, segmentDir, rc, TIMEOUT_MS, origin, destination, "offline"
        )
        if (result != null) return result

        Log.w(TAG, "BRouter attempt failed to find track, falling back to direct geodesic route")
        return fallbackDirectRoute(origin, destination, "لا توجد دروب سيارات قريبة، استخدم المسار المباشر")
    }

    private fun runBRouterEngine(
        fromNode: OsmNodeNamed,
        toNode: OsmNodeNamed,
        segmentDir: File,
        rc: RoutingContext,
        timeoutMs: Long,
        origin: LatLng,
        destination: LatLng,
        attemptLabel: String
    ): OfflineRouter.RouteResult? {
        val engine = try {
            RoutingEngine(null, null, segmentDir, listOf(fromNode, toNode), rc)
        } catch (e: Exception) {
            Log.e(TAG, "BRouter $attemptLabel constructor failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
        try {
            engine.doRun(timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "BRouter $attemptLabel threw ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }

        val track: OsmTrack? = engine.foundTrack
        if (track == null || track.nodes == null || track.nodes.isEmpty()) {
            val engineError = engine.errorMessage ?: "unknown"
            val linksProcessed = try {
                val field = engine.javaClass.getDeclaredField("linksProcessed")
                field.isAccessible = true
                field.getInt(engine)
            } catch (_: Exception) { "N/A" }
            Log.w(TAG, "BRouter $attemptLabel: no track. errorMessage=$engineError, " +
                "linksProcessed=$linksProcessed")
            return null
        }

        Log.i(TAG, "BRouter $attemptLabel: found track with ${track.nodes.size} nodes, " +
            "distance=${track.distance}m, duration=${track.totalSeconds}s")

        // Convert OsmPathElement nodes to LatLng
        val rawTrackPoints = track.nodes.map { node ->
            val lat = (node.getILat() - 90_000_000) / 1_000_000.0
            val lon = (node.getILon() - 180_000_000) / 1_000_000.0
            LatLng(lat, lon)
        }
        val trackPoints = simplifyTrack(rawTrackPoints, 50.0)

        Log.i(TAG, "Track simplified: ${rawTrackPoints.size} → ${trackPoints.size} points")

        // 3-Leg Off-road Snap Concatenation
        val snapStart = trackPoints.first()
        val snapEnd = trackPoints.last()

        val startSnapDist = calculateDistanceMeters(origin, snapStart)
        val endSnapDist = calculateDistanceMeters(snapEnd, destination)

        val leg1 = if (startSnapDist > OFF_ROAD_SNAP_THRESHOLD_METERS) {
            listOf(origin, snapStart)
        } else {
            emptyList()
        }

        val leg3 = if (endSnapDist > OFF_ROAD_SNAP_THRESHOLD_METERS) {
            listOf(snapEnd, destination)
        } else {
            emptyList()
        }

        val fullPolyline = mutableListOf<LatLng>()
        if (leg1.isNotEmpty()) {
            fullPolyline.add(origin)
        }
        fullPolyline.addAll(trackPoints)
        if (leg3.isNotEmpty()) {
            fullPolyline.add(destination)
        }

        val totalDistMeters = (if (leg1.isNotEmpty()) startSnapDist else 0.0) +
                track.distance.toDouble() +
                (if (leg3.isNotEmpty()) endSnapDist else 0.0)

        // Detour sanity guard: if BRouter snapped to a distant network routing >6x direct geodesic distance,
        // reject track so we fall back cleanly to direct navigation.
        val directDist = calculateDistanceMeters(origin, destination)
        if (directDist > 1000.0 && totalDistMeters > directDist * 6.0) {
            Log.w(TAG, "BRouter $attemptLabel: track is an extreme detour (${totalDistMeters}m vs direct ${directDist}m > 6x). Rejecting track.")
            return null
        }

        val totalSecs = (if (leg1.isNotEmpty()) startSnapDist / OFF_ROAD_SPEED_MS else 0.0) +
                track.totalSeconds.toDouble() +
                (if (leg3.isNotEmpty()) endSnapDist / OFF_ROAD_SPEED_MS else 0.0)

        val instructions = mutableListOf<OfflineRouter.RouteInstruction>()

        if (leg1.isNotEmpty()) {
            instructions.add(
                OfflineRouter.RouteInstruction(
                    text = "توجه نحو أقرب مسار بري (${startSnapDist.roundToInt()} م)",
                    distanceMeters = startSnapDist,
                    maneuver = OfflineRouter.ManeuverType.STRAIGHT
                )
            )
        }

        val hints = BRouterVoiceHintHelper.extractVoiceHints(track)
        for (hint in hints) {
            val maneuver = when (hint.maneuver) {
                BRouterVoiceHintHelper.NormalizedManeuver.TURN_LEFT -> OfflineRouter.ManeuverType.TURN_LEFT
                BRouterVoiceHintHelper.NormalizedManeuver.TURN_RIGHT -> OfflineRouter.ManeuverType.TURN_RIGHT
                BRouterVoiceHintHelper.NormalizedManeuver.U_TURN -> OfflineRouter.ManeuverType.U_TURN
                BRouterVoiceHintHelper.NormalizedManeuver.ARRIVE -> OfflineRouter.ManeuverType.ARRIVE
                BRouterVoiceHintHelper.NormalizedManeuver.STRAIGHT, null -> OfflineRouter.ManeuverType.STRAIGHT
            }
            val text = translateInstructionToArabic(maneuver)
            instructions.add(
                OfflineRouter.RouteInstruction(
                    text = text,
                    distanceMeters = hint.distanceToNext,
                    maneuver = maneuver
                )
            )
        }

        if (leg3.isNotEmpty()) {
            instructions.add(
                OfflineRouter.RouteInstruction(
                    text = "اتجه برياً مباشرة نحو الهدف (${endSnapDist.roundToInt()} م)",
                    distanceMeters = endSnapDist,
                    maneuver = OfflineRouter.ManeuverType.ARRIVE
                )
            )
        }

        return OfflineRouter.RouteResult(
            polyline = fullPolyline,
            distanceMeters = totalDistMeters,
            durationSeconds = totalSecs,
            instructions = instructions,
            leg1Direct = leg1,
            leg2Track = trackPoints,
            leg3Direct = leg3,
            isFallbackDirect = false
        )
    }

    /**
     * Pre-build and cache the RoutingContext at construction time.
     * Profile parsing, metadata reading, expression context setup, readGlobalConfig(),
     * and initModel() are the dominant cost (~100-300ms on low-end hardware).
     * Building once at init eliminates this from every subsequent route calculation.
     */
    private fun buildAndCacheRoutingContext() {
        try {
            val brouterDir = getBrouterWorkDir()
            val lookupsFile = File(brouterDir, "lookups.dat")
            val profileFile = File(brouterDir, "desert-car.brf")
            cachedRoutingContext = buildRoutingContextSafe(brouterDir, lookupsFile, profileFile)
        } catch (e: Exception) {
            Log.w(TAG, "Pre-build RoutingContext failed (will retry on first route): ${e.message}")
        }
    }

    /**
     * Build RoutingContext with resilient error handling.
     * On first-time corruption (e.g. incomplete asset extraction), deletes
     * and re-extracts profile/lookups from APK assets, then retries once.
     * Invalidates [cachedRoutingContext] on corruption so the next call rebuilds fresh.
     * @return initialized RoutingContext, or null if both attempts fail.
     */
    private fun buildRoutingContextSafe(
        brouterDir: File,
        lookupsFile: File,
        profileFile: File
    ): RoutingContext? {
        // First attempt
        try {
            return buildRoutingContext(brouterDir, lookupsFile, profileFile)
        } catch (e: Exception) {
            Log.w(TAG, "BRouter context first attempt failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Delete possibly-corrupted assets and re-extract — invalidate cache so next call rebuilds
        cachedRoutingContext = null
        cachedShortestRoutingContext = null
        cachedShortestWayValidator = null
        try {
            Log.i(TAG, "Re-extracting BRouter assets after init failure")
            lookupsFile.delete()
            profileFile.delete()
            MapFileManager.ensureBrouterAssets(context)
        } catch (_: Exception) {}

        // Retry with fresh assets
        return try {
            buildRoutingContext(brouterDir, lookupsFile, profileFile)
        } catch (e: Exception) {
            Log.e(TAG, "BRouter context retry also failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun buildRoutingContext(
        brouterDir: File,
        lookupsFile: File,
        profileFile: File
    ): RoutingContext {
        val rc = RoutingContext().apply {
            localFunction = profileFile.absolutePath
            memoryclass = MEMORY_CLASS
            carMode = true
        }

        // CRITICAL ORDER: contexts MUST be created BEFORE readMetaData().
        // readMetaData() dispatches lookup values to registered listeners,
        // so the contexts must already be registered as listeners first.
        val meta = BExpressionMetaData()
        val expctxWay = BExpressionContextWay(meta)
        val expctxNode = BExpressionContextNode(meta)

        meta.readMetaData(lookupsFile)

        expctxWay.parseFile(profileFile, "global")
        expctxNode.parseFile(profileFile, "global")

        rc.expctxWay = expctxWay
        rc.expctxNode = expctxNode

        // readGlobalConfig() and initModel() read profile-defined values from expctxWay/expctxNode.
        // Must happen BEFORE we set our custom desert overrides.
        rc.readGlobalConfig()
        rc.initModel()

        // Now override with desert-specific values AFTER readGlobalConfig() has loaded defaults.
        rc.waypointCatchingRange = 5000.0 // 5km — tuned for desert track snapping without huge search space
        rc.buildBeelineOnRange = true
        rc.useDynamicDistance = true

        return rc
    }

    private fun encodeToILatLon(lat: Double, lon: Double): Pair<Int, Int> {
        val ilat = ((lat + 90.0) * 1e6).roundToInt()
        val ilon = ((lon + 180.0) * 1e6).roundToInt()
        return ilat to ilon
    }

    private fun fallbackDirectRoute(
        origin: LatLng,
        destination: LatLng,
        warning: String?
    ): OfflineRouter.RouteResult {
        val dist = calculateDistanceMeters(origin, destination)
        return OfflineRouter.RouteResult(
            polyline = listOf(origin, destination),
            distanceMeters = dist,
            durationSeconds = dist / DIRECT_CRUISE_SPEED_MS,
            instructions = listOf(
                OfflineRouter.RouteInstruction(
                    text = "اتجه مباشرة نحو الهدف بالبوصلة (${dist.roundToInt()} م)",
                    distanceMeters = dist,
                    maneuver = OfflineRouter.ManeuverType.STRAIGHT
                )
            ),
            leg1Direct = listOf(origin, destination),
            leg2Track = emptyList(),
            leg3Direct = emptyList(),
            isFallbackDirect = true,
            warningMessage = warning
        )
    }

    private fun translateInstructionToArabic(maneuver: OfflineRouter.ManeuverType): String {
        return when (maneuver) {
            OfflineRouter.ManeuverType.TURN_LEFT -> "انعطف يساراً"
            OfflineRouter.ManeuverType.TURN_RIGHT -> "انعطف يميناً"
            OfflineRouter.ManeuverType.U_TURN -> "قم بالدوران للخلف"
            OfflineRouter.ManeuverType.ARRIVE -> "لقد وصلت إلى وجهتك"
            OfflineRouter.ManeuverType.STRAIGHT -> "تابع السير للأمام"
        }
    }

    private fun getBrouterWorkDir(): File {
        return MapFileManager.getBrouterBaseDir(context)
    }

    private fun ensureAssetsExtracted() {
        MapFileManager.ensureBrouterAssets(context)
    }

    private fun calculateDistanceMeters(p1: LatLng, p2: LatLng): Double {
        val earthRadius = 6371000.0
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Douglas-Peucker line simplification — recursively removes points that
     * lie within [toleranceMeters] of the line between their neighbors.
     * Keeps first + last point always. Produces far fewer points than a naive
     * distance filter on winding desert tracks.
     */
    private fun simplifyTrack(points: List<LatLng>, toleranceMeters: Double): List<LatLng> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true
        dpSimplify(points, 0, points.size - 1, toleranceMeters, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun dpSimplify(
        points: List<LatLng>,
        start: Int,
        end: Int,
        tolerance: Double,
        keep: BooleanArray
    ) {
        if (end - start < 2) return
        var maxDist = 0.0
        var maxIdx = start
        val p1 = points[start]
        val p2 = points[end]
        for (i in (start + 1) until end) {
            val d = perpendicularDistance(points[i], p1, p2)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        if (maxDist > tolerance) {
            keep[maxIdx] = true
            dpSimplify(points, start, maxIdx, tolerance, keep)
            dpSimplify(points, maxIdx, end, tolerance, keep)
        }
    }

    private fun perpendicularDistance(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Double {
        val dx = lineEnd.longitude - lineStart.longitude
        val dy = lineEnd.latitude - lineStart.latitude
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return calculateDistanceMeters(point, lineStart)
        val t = ((point.longitude - lineStart.longitude) * dx + (point.latitude - lineStart.latitude) * dy) / lenSq
        val clamped = t.coerceIn(0.0, 1.0)
        val projLat = lineStart.latitude + clamped * dy
        val projLon = lineStart.longitude + clamped * dx
        return calculateDistanceMeters(point, LatLng(projLat, projLon))
    }
}
