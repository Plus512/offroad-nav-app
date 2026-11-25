package com.alruwaili.garminalbarr.map.routing

import android.content.Context
import android.util.Log
import btools.codec.DataBuffers
import btools.codec.WaypointMatcher
import btools.expressions.BExpressionContextWay
import btools.mapaccess.OsmFile
import btools.mapaccess.PhysicalFile
import com.alruwaili.garminalbarr.map.MapFileManager
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extracts car-accessible candidate road segments near off-road desert coordinates
 * directly from BRouter's .rd5 routing segment files.
 *
 * Used by [CarRouteMode.SHORTEST_TOTAL] to overcome BRouter's local Euclidean snapping
 * defect, discovering intermediate road branches (such as the middle road in field test
 * image_37b8bd.jpg) instead of blindly snapping to distant highway loops.
 */
object CandidateRoadExtractor {

    private const val TAG = "CandidateRoadExtractor"
    const val DEFAULT_SEARCH_RADIUS_METERS = 12_000.0 // 12 km search radius in desert
    const val DEFAULT_MIN_SEPARATION_METERS = 250.0 // 250m allows capturing distinct intermediate road branches
    const val DEFAULT_MAX_CANDIDATES = 5
    const val ON_ROAD_THRESHOLD_METERS = 50.0

    data class RoadCandidate(
        val point: LatLng,
        val distanceMeters: Double,
        val node1: LatLng,
        val node2: LatLng
    )

    data class CandidatePair(
        val entry: RoadCandidate,
        val exit: RoadCandidate,
        val lowerBoundMeters: Double
    )

    /**
     * Finds candidate road entry/exit points within [searchRadiusMeters] of [queryPoint].
     *
     * @param context Application context for segment directory resolution
     * @param queryPoint Target coordinates (Origin or Destination)
     * @param wayValidator Compiled car accessibility validator (from RoutingContext.expctxWay)
     * @param searchRadiusMeters Search radius in meters (default 12 km)
     * @param minSeparationMeters Minimum spatial distance between distinct road branches (default 2 km)
     * @param maxCandidates Maximum distinct candidates to return (default 3)
     * @return Ordered list of distinct road candidates, closest first
     */
    fun findCandidates(
        context: Context? = null,
        queryPoint: LatLng,
        wayValidator: BExpressionContextWay?,
        searchRadiusMeters: Double = DEFAULT_SEARCH_RADIUS_METERS,
        minSeparationMeters: Double = DEFAULT_MIN_SEPARATION_METERS,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        segmentDir: File? = null
    ): List<RoadCandidate> {
        if (wayValidator == null) {
            try {
                Log.w(TAG, "wayValidator is null; returning empty candidates")
            } catch (_: Throwable) {}
            return emptyList()
        }

        val baseDir = segmentDir ?: context?.let {
            try {
                MapFileManager.getRoutingSegmentsDir(it)
            } catch (_: Throwable) { null }
        } ?: return emptyList()

        val collector = RoadSegmentCollector(queryPoint, searchRadiusMeters)
        val dataBuffers = DataBuffers()

        // Microdegree conversions
        val ilat = ((queryPoint.latitude + 90.0) * 1e6).roundToInt()
        val ilon = ((queryPoint.longitude + 180.0) * 1e6).roundToInt()

        val latRad = Math.toRadians(queryPoint.latitude)
        val cosLat = cos(latRad).coerceAtLeast(0.01)

        val dLatMicro = (searchRadiusMeters * (1e6 / 111320.0)).roundToInt()
        val dLonMicro = (searchRadiusMeters * (1e6 / (111320.0 * cosLat))).roundToInt()

        val minILat = ilat - dLatMicro
        val maxILat = ilat + dLatMicro
        val minILon = ilon - dLonMicro
        val maxILon = ilon + dLonMicro

        val minLatDeg = minILat / 1_000_000
        val maxLatDeg = maxILat / 1_000_000
        val minLonDeg = minILon / 1_000_000
        val maxLonDeg = maxILon / 1_000_000

        for (latDeg in minLatDeg..maxLatDeg) {
            for (lonDeg in minLonDeg..maxLonDeg) {
                val degLat = (latDeg - 90).toDouble()
                val degLon = (lonDeg - 180).toDouble()

                val segName = MapFileManager.getSegmentNameForCoordinate(degLat, degLon)
                val segFile = File(baseDir, segName)
                if (!segFile.exists() || segFile.length() == 0L) {
                    continue
                }

                var raFile: PhysicalFile? = null
                try {
                    raFile = PhysicalFile(segFile, dataBuffers, -1, -1)
                    val osmFile = OsmFile(raFile, lonDeg, latDeg, dataBuffers)
                    if (!osmFile.hasData()) {
                        continue
                    }

                    val divisor = raFile.divisor
                    val cellSize = 1_000_000 / divisor
                    val minLonIdx = maxOf(lonDeg * divisor, minILon / cellSize)
                    val maxLonIdx = minOf(lonDeg * divisor + divisor - 1, maxILon / cellSize)
                    val minLatIdx = maxOf(latDeg * divisor, minILat / cellSize)
                    val maxLatIdx = minOf(latDeg * divisor + divisor - 1, maxILat / cellSize)

                    for (lonIdx in minLonIdx..maxLonIdx) {
                        for (latIdx in minLatIdx..maxLatIdx) {
                            try {
                                osmFile.createMicroCache(
                                    lonIdx,
                                    latIdx,
                                    dataBuffers,
                                    wayValidator,
                                    collector,
                                    true,
                                    null
                                )
                            } catch (e: Exception) {
                                try {
                                    Log.w(TAG, "Error decoding microcache ($lonIdx, $latIdx) in $segName: ${e.message}")
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    try {
                        Log.w(TAG, "Error reading .rd5 segment $segName: ${e.message}")
                    } catch (_: Throwable) {}
                } finally {
                    try {
                        raFile?.close()
                    } catch (_: Exception) {}
                }
            }
        }

        return clusterCandidates(collector.candidates, maxCandidates, minSeparationMeters)
    }

    /**
     * Builds and sorts candidate pairs by their Euclidean Lower Bound:
     * LB = Dist(Origin, Entry) + Dist_Euclidean(Entry, Exit) + Dist(Exit, Dest)
     *
     * Because actual road distance >= straight-line distance, pairs sorted by this
     * lower bound allow Branch-and-Bound pruning without executing wasteful BRouter searches.
     */
    fun buildCandidatePairs(
        origin: LatLng,
        destination: LatLng,
        entryCandidates: List<RoadCandidate>,
        exitCandidates: List<RoadCandidate>
    ): List<CandidatePair> {
        val pairs = mutableListOf<CandidatePair>()
        for (entry in entryCandidates) {
            for (exit in exitCandidates) {
                val entryToExitEuclidean = calculateDistanceMeters(entry.point, exit.point)
                val lowerBound = entry.distanceMeters + entryToExitEuclidean + exit.distanceMeters
                pairs.add(
                    CandidatePair(
                        entry = entry,
                        exit = exit,
                        lowerBoundMeters = lowerBound
                    )
                )
            }
        }
        return pairs.sortedBy { it.lowerBoundMeters }
    }

    /**
     * Clusters raw candidate points along road segments:
     * - Retains the closest point.
     * - Retains subsequent points only if separated by at least [minSeparationMeters]
     *   from all already selected candidates.
     * - Caps at [maxCount].
     */
    fun clusterCandidates(
        rawCandidates: List<RoadCandidate>,
        maxCount: Int,
        minSeparationMeters: Double
    ): List<RoadCandidate> {
        if (rawCandidates.isEmpty()) return emptyList()

        val sorted = rawCandidates.sortedBy { it.distanceMeters }
        val distinct = mutableListOf<RoadCandidate>()

        for (candidate in sorted) {
            // If closest candidate is within on-road threshold (<50m), vehicle/target is already
            // on an asphalt/track way — no desert approach search is required.
            if (distinct.isEmpty() && candidate.distanceMeters <= ON_ROAD_THRESHOLD_METERS) {
                return listOf(candidate)
            }

            val isSeparated = distinct.all {
                calculateDistanceMeters(candidate.point, it.point) >= minSeparationMeters
            }

            if (isSeparated) {
                distinct.add(candidate)
                if (distinct.size >= maxCount) break
            }
        }

        return distinct
    }

    private class RoadSegmentCollector(
        private val queryPoint: LatLng,
        private val maxRadiusMeters: Double
    ) : WaypointMatcher {
        val candidates = mutableListOf<RoadCandidate>()
        private var lonLast = 0
        private var latLast = 0
        private var lonTarget = 0
        private var latTarget = 0

        override fun start(
            ilonStart: Int,
            ilatStart: Int,
            ilonTarget: Int,
            ilatTarget: Int,
            useAsStartWay: Boolean
        ): Boolean {
            lonLast = ilonStart
            latLast = ilatStart
            this.lonTarget = ilonTarget
            this.latTarget = ilatTarget
            return true
        }

        override fun transferNode(ilon: Int, ilat: Int) {
            checkSegment(lonLast, latLast, ilon, ilat)
            lonLast = ilon
            latLast = ilat
        }

        override fun end() {
            checkSegment(lonLast, latLast, lonTarget, latTarget)
        }

        override fun hasMatch(lon: Int, lat: Int): Boolean = false

        private fun checkSegment(lon1: Int, lat1: Int, lon2: Int, lat2: Int) {
            if (lon1 == lon2 && lat1 == lat2) return

            val p1Lat = (lat1 - 90_000_000) / 1e6
            val p1Lon = (lon1 - 180_000_000) / 1e6
            val p2Lat = (lat2 - 90_000_000) / 1e6
            val p2Lon = (lon2 - 180_000_000) / 1e6

            val p1 = LatLng(p1Lat, p1Lon)
            val p2 = LatLng(p2Lat, p2Lon)

            val (proj, dist) = projectPointOnSegment(queryPoint, p1, p2)
            if (dist <= maxRadiusMeters) {
                candidates.add(
                    RoadCandidate(
                        point = proj,
                        distanceMeters = dist,
                        node1 = p1,
                        node2 = p2
                    )
                )
            }
        }
    }

    fun projectPointOnSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Pair<LatLng, Double> {
        val dx = lineEnd.longitude - lineStart.longitude
        val dy = lineEnd.latitude - lineStart.latitude
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) {
            return lineStart to calculateDistanceMeters(point, lineStart)
        }
        val t = ((point.longitude - lineStart.longitude) * dx + (point.latitude - lineStart.latitude) * dy) / lenSq
        val clamped = t.coerceIn(0.0, 1.0)
        val projLat = lineStart.latitude + clamped * dy
        val projLon = lineStart.longitude + clamped * dx
        val proj = LatLng(projLat, projLon)
        return proj to calculateDistanceMeters(point, proj)
    }

    fun calculateDistanceMeters(p1: LatLng, p2: LatLng): Double {
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
}
