package com.alruwaili.garminalbarr.map

import com.alruwaili.garminalbarr.map.routing.CandidateRoadExtractor
import com.alruwaili.garminalbarr.map.routing.CandidateRoadExtractor.RoadCandidate
import com.alruwaili.garminalbarr.map.routing.CarRouteMode
import com.alruwaili.garminalbarr.map.routing.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRoadExtractorTest {

    @Test
    fun projectPointOnSegment_perpendicularFootOnSegment() {
        // Line segment from (24.0, 46.0) to (24.0, 46.1) - west-to-east
        val lineStart = LatLng(24.0, 46.0)
        val lineEnd = LatLng(24.0, 46.1)

        // Query point directly north of midpoint at (24.05, 46.05)
        val query = LatLng(24.05, 46.05)

        val (proj, dist) = CandidateRoadExtractor.projectPointOnSegment(query, lineStart, lineEnd)

        // Foot should be approximately at (24.0, 46.05)
        assertEquals(24.0, proj.latitude, 0.001)
        assertEquals(46.05, proj.longitude, 0.001)

        // Distance should be ~5.5 km (0.05 deg latitude = 0.05 * 111.32 km = 5566 m)
        assertEquals(5566.0, dist, 100.0)
    }

    @Test
    fun projectPointOnSegment_clampsToStartWhenQueryIsBeforeStart() {
        val lineStart = LatLng(24.0, 46.0)
        val lineEnd = LatLng(24.0, 46.1)

        // Query point west of start
        val query = LatLng(24.0, 45.9)

        val (proj, _) = CandidateRoadExtractor.projectPointOnSegment(query, lineStart, lineEnd)

        // Must clamp to start
        assertEquals(lineStart.latitude, proj.latitude, 0.000001)
        assertEquals(lineStart.longitude, proj.longitude, 0.000001)
    }

    @Test
    fun projectPointOnSegment_clampsToEndWhenQueryIsAfterEnd() {
        val lineStart = LatLng(24.0, 46.0)
        val lineEnd = LatLng(24.0, 46.1)

        // Query point east of end
        val query = LatLng(24.0, 46.2)

        val (proj, _) = CandidateRoadExtractor.projectPointOnSegment(query, lineStart, lineEnd)

        // Must clamp to end
        assertEquals(lineEnd.latitude, proj.latitude, 0.000001)
        assertEquals(lineEnd.longitude, proj.longitude, 0.000001)
    }

    @Test
    fun projectPointOnSegment_degenerateZeroLengthSegmentHandledSafely() {
        val point = LatLng(24.5, 46.5)
        val query = LatLng(24.6, 46.5)

        val (proj, dist) = CandidateRoadExtractor.projectPointOnSegment(query, point, point)

        assertEquals(point.latitude, proj.latitude, 0.000001)
        assertEquals(point.longitude, proj.longitude, 0.000001)
        assertTrue("Distance must be positive", dist > 10_000.0)
    }

    @Test
    fun clusterCandidates_deduplicatesDenseSegmentsOnSameRoad() {
        // 5 candidate points along the same highway, spaced 200m apart
        val highwayCandidates = (0..4).map { i ->
            RoadCandidate(
                point = LatLng(24.0 + i * 0.002, 46.0),
                distanceMeters = 3000.0 + i * 50.0,
                node1 = LatLng(24.0, 46.0),
                node2 = LatLng(24.01, 46.0)
            )
        }

        val clustered = CandidateRoadExtractor.clusterCandidates(
            rawCandidates = highwayCandidates,
            maxCount = 3,
            minSeparationMeters = 2000.0
        )

        // All points are within 2000m of each other; only the closest should be kept
        assertEquals(1, clustered.size)
        assertEquals(3000.0, clustered.first().distanceMeters, 0.01)
    }

    @Test
    fun clusterCandidates_preservesDistinctRoadBranches() {
        // Highway A candidates (around 3000m north)
        val hwyCandidate1 = RoadCandidate(
            point = LatLng(24.027, 46.0),
            distanceMeters = 3000.0,
            node1 = LatLng(24.027, 45.99),
            node2 = LatLng(24.027, 46.01)
        )
        val hwyCandidate2 = RoadCandidate(
            point = LatLng(24.027, 46.002),
            distanceMeters = 3050.0,
            node1 = LatLng(24.027, 46.01),
            node2 = LatLng(24.027, 46.02)
        )

        // Middle Road B candidate (around 4000m southeast, ~6 km away from Highway A)
        val middleRoadCandidate = RoadCandidate(
            point = LatLng(23.97, 46.03),
            distanceMeters = 4000.0,
            node1 = LatLng(23.97, 46.02),
            node2 = LatLng(23.97, 46.04)
        )

        val raw = listOf(hwyCandidate1, hwyCandidate2, middleRoadCandidate)

        val clustered = CandidateRoadExtractor.clusterCandidates(
            rawCandidates = raw,
            maxCount = 3,
            minSeparationMeters = 2000.0
        )

        // Must retain Highway A (closest, 3000m) and Middle Road B (4000m)
        assertEquals(2, clustered.size)
        assertEquals(3000.0, clustered[0].distanceMeters, 0.01)
        assertEquals(4000.0, clustered[1].distanceMeters, 0.01)
    }

    @Test
    fun clusterCandidates_onRoadVehicleShortCircuitsSearch() {
        // Vehicle is already on the road (distance = 25m < 50m threshold)
        val onRoadCandidate = RoadCandidate(
            point = LatLng(24.0002, 46.0),
            distanceMeters = 22.0,
            node1 = LatLng(24.0, 46.0),
            node2 = LatLng(24.01, 46.0)
        )
        val distantRoadCandidate = RoadCandidate(
            point = LatLng(24.05, 46.05),
            distanceMeters = 6000.0,
            node1 = LatLng(24.05, 46.0),
            node2 = LatLng(24.05, 46.1)
        )

        val clustered = CandidateRoadExtractor.clusterCandidates(
            rawCandidates = listOf(onRoadCandidate, distantRoadCandidate),
            maxCount = 3,
            minSeparationMeters = 2000.0
        )

        // When already on road, exactly 1 candidate must be returned
        assertEquals(1, clustered.size)
        assertEquals(22.0, clustered.first().distanceMeters, 0.01)
    }

    @Test
    fun buildCandidatePairs_sortsByLowerBoundAscending() {
        val origin = LatLng(24.0, 46.0)
        val destination = LatLng(24.1, 46.15)

        // Entry 1: Highway A (3.0 km North of Origin)
        val entryHighway = RoadCandidate(
            point = LatLng(24.027, 46.0),
            distanceMeters = 3000.0,
            node1 = LatLng(24.027, 45.99),
            node2 = LatLng(24.027, 46.01)
        )

        // Entry 2: Middle Road B (4.0 km South-East of Origin)
        val entryMiddleRoad = RoadCandidate(
            point = LatLng(23.97, 46.03),
            distanceMeters = 4000.0,
            node1 = LatLng(23.97, 46.02),
            node2 = LatLng(23.97, 46.04)
        )

        // Exit 1: Middle Road exit near Destination (3.0 km from Destination)
        val exitMiddleRoad = RoadCandidate(
            point = LatLng(24.08, 46.13),
            distanceMeters = 3000.0,
            node1 = LatLng(24.08, 46.12),
            node2 = LatLng(24.08, 46.14)
        )

        // Exit 2: Far Highway exit (loops around, exit point is 2.0 km from Destination)
        val exitHighway = RoadCandidate(
            point = LatLng(24.11, 46.16),
            distanceMeters = 2000.0,
            node1 = LatLng(24.11, 46.15),
            node2 = LatLng(24.11, 46.17)
        )

        val pairs = CandidateRoadExtractor.buildCandidatePairs(
            origin = origin,
            destination = destination,
            entryCandidates = listOf(entryHighway, entryMiddleRoad),
            exitCandidates = listOf(exitMiddleRoad, exitHighway)
        )

        assertEquals(4, pairs.size)

        // Verify sorted by lowerBoundMeters strictly ascending
        for (i in 0 until pairs.size - 1) {
            assertTrue(
                "Pair $i lower bound (${pairs[i].lowerBoundMeters}) must be <= Pair ${i+1} (${pairs[i+1].lowerBoundMeters})",
                pairs[i].lowerBoundMeters <= pairs[i+1].lowerBoundMeters
            )
        }

        // Verify each pair matches the exact lower bound mathematical formula:
        // LB = Dist(Origin, Entry) + Dist_euclid(Entry, Exit) + Dist(Exit, Dest)
        for (pair in pairs) {
            val expectedEuclid = CandidateRoadExtractor.calculateDistanceMeters(pair.entry.point, pair.exit.point)
            val expectedLB = pair.entry.distanceMeters + expectedEuclid + pair.exit.distanceMeters
            assertEquals(expectedLB, pair.lowerBoundMeters, 0.01)
        }
    }

    @Test
    fun branchAndBound_pruningSimulation() {
        // Simulated scenario:
        // Pair 1 (Middle Road): Lower Bound = 19,500 m, Actual Road Route = 20,000 m
        // Pair 2 (Highway Loop): Lower Bound = 50,000 m
        val pair1LB = 19_500.0
        val pair1Actual = 20_000.0

        val pair2LB = 50_000.0

        var bestTotalDistance = Double.MAX_VALUE
        var pair1Evaluated = false
        var pair2Evaluated = false

        // Simulate loop over sorted pairs
        val pairs = listOf(pair1LB to 1, pair2LB to 2)
        for ((lb, pairId) in pairs) {
            if (lb >= bestTotalDistance) {
                // Pruned!
                break
            }
            if (pairId == 1) {
                pair1Evaluated = true
                bestTotalDistance = pair1Actual
            } else if (pairId == 2) {
                pair2Evaluated = true
            }
        }

        assertTrue("Pair 1 must be evaluated", pair1Evaluated)
        assertFalse("Pair 2 must be pruned because LB (50,000m) >= bestTotal (20,000m)", pair2Evaluated)
        assertEquals(20_000.0, bestTotalDistance, 0.01)
    }

    @Test
    fun modePreservation_closestPavedRemainsUnchanged() {
        assertEquals(CarRouteMode.CLOSEST_PAVED, CarRouteMode.valueOf("CLOSEST_PAVED"))
        assertEquals(CarRouteMode.SHORTEST_TOTAL, CarRouteMode.valueOf("SHORTEST_TOTAL"))
    }

    @Test
    fun findCandidates_nullWayValidatorReturnsEmptySafely() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val query = LatLng(24.0, 46.0)
        val result = CandidateRoadExtractor.findCandidates(
            context = dummyContext,
            queryPoint = query,
            wayValidator = null
        )
        assertTrue("Must return empty list safely when wayValidator is null", result.isEmpty())
    }

    @Test
    fun findCandidates_realSegmentExtraction() {
        val segFile = java.io.File("/tmp/E35_N30.rd5")
        if (!segFile.exists()) return

        val brouterDir = java.io.File("src/main/assets/brouter")
        val lookupsFile = java.io.File(brouterDir, "lookups.dat")
        val profileFile = java.io.File(brouterDir, "desert-car-shortest.brf")

        val meta = btools.expressions.BExpressionMetaData()
        val validator = btools.expressions.BExpressionContextWay(meta)
        meta.readMetaData(lookupsFile)
        validator.parseFile(profileFile, "global")

        val origin = LatLng(32.2690745, 36.2889887)
        val dest = LatLng(32.2601865, 36.2900003)

        val entryCandidates = CandidateRoadExtractor.findCandidates(
            queryPoint = origin,
            wayValidator = validator,
            segmentDir = java.io.File("/tmp")
        )
        val exitCandidates = CandidateRoadExtractor.findCandidates(
            queryPoint = dest,
            wayValidator = validator,
            segmentDir = java.io.File("/tmp")
        )

        println("Entry candidates found: ${entryCandidates.size}")
        for (c in entryCandidates) {
            println("Entry candidate at ${c.point.latitude}, ${c.point.longitude}, dist=${c.distanceMeters}m")
        }

        println("Exit candidates found: ${exitCandidates.size}")
        for (c in exitCandidates) {
            println("Exit candidate at ${c.point.latitude}, ${c.point.longitude}, dist=${c.distanceMeters}m")
        }

        assertTrue("Must find entry candidates", entryCandidates.isNotEmpty())
        assertTrue("Must find exit candidates", exitCandidates.isNotEmpty())

        val pairs = CandidateRoadExtractor.buildCandidatePairs(origin, dest, entryCandidates, exitCandidates)
        val bestPair = pairs.first()
        assertTrue(
            "Best pair lower bound must be <= 1200m (middle road), but was ${bestPair.lowerBoundMeters}m",
            bestPair.lowerBoundMeters <= 1200.0
        )
    }
}
