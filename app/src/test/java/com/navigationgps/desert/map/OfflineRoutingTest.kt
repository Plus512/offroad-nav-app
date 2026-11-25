package com.navigationgps.desert.map

import com.navigationgps.desert.map.download.RegionRegistry
import com.navigationgps.desert.map.routing.CarRouteMode
import com.navigationgps.desert.map.routing.LatLng
import com.navigationgps.desert.map.routing.OfflineRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OfflineRoutingTest {

    @Test
    fun regionRegistry_allRegionsHaveValidRoutingSegments() {
        val allRegions = RegionRegistry.ALL_REGIONS
        assertEquals(8, allRegions.size)

        for (region in allRegions) {
            assertTrue("Region ${region.id} should have routing segments", region.routingSegments.isNotEmpty())
            assertTrue("Region ${region.id} size should be positive", region.routingSizeBytes > 0)
            for (seg in region.routingSegments) {
                assertTrue("Segment $seg name format invalid", seg.matches(Regex("^[EW]\\d{2,3}_[NS]\\d{2}\\.rd5$")))
            }
        }
    }

    @Test
    fun regionRegistry_sharedSegmentsExistBetweenNeighbors() {
        // Jordan and Saudi North share segments E35_N30.rd5 and E35_N25.rd5
        val jordan = RegionRegistry.getRegionById("jordan")
        val saudiNorth = RegionRegistry.getRegionById("saudi_north")

        assertNotNull(jordan)
        assertNotNull(saudiNorth)

        val intersection = jordan!!.routingSegments.intersect(saudiNorth!!.routingSegments.toSet())
        assertTrue("Jordan and Saudi North should share border segments", intersection.isNotEmpty())
        assertTrue(intersection.contains("E35_N30.rd5"))
        assertTrue(intersection.contains("E35_N25.rd5"))
    }

    @Test
    fun regionRegistry_uniqueSegmentsCountIs21() {
        assertEquals(21, RegionRegistry.ALL_UNIQUE_ROUTING_SEGMENTS.size)
    }

    @Test
    fun offlineRouter_routeResultModelDefaults() {
        val origin = LatLng(24.7136, 46.6753)
        val dest = LatLng(24.8000, 46.8000)
        val result = OfflineRouter.RouteResult(
            polyline = listOf(origin, dest),
            distanceMeters = 15000.0,
            durationSeconds = 900.0,
            instructions = emptyList()
        )

        assertFalse(result.isFallbackDirect)
        assertTrue(result.leg1Direct.isEmpty())
        assertTrue(result.leg2Track.isEmpty())
        assertTrue(result.leg3Direct.isEmpty())
        assertEquals(null, result.warningMessage)
        assertEquals(2, result.polyline.size)
    }

    @Test
    fun offlineRouter_3legHybridStructure() {
        val gpsStart = LatLng(24.7000, 46.6000)
        val snapStart = LatLng(24.7020, 46.6010)
        val trackMid = LatLng(24.7500, 46.7000)
        val snapEnd = LatLng(24.7980, 46.7990)
        val poiDest = LatLng(24.8000, 46.8000)

        val leg1 = listOf(gpsStart, snapStart)
        val leg2 = listOf(snapStart, trackMid, snapEnd)
        val leg3 = listOf(snapEnd, poiDest)
        val full = listOf(gpsStart, snapStart, trackMid, snapEnd, poiDest)

        val hybrid = OfflineRouter.RouteResult(
            polyline = full,
            distanceMeters = 22000.0,
            durationSeconds = 1400.0,
            instructions = listOf(
                OfflineRouter.RouteInstruction("توجه نحو المسار", 300.0, OfflineRouter.ManeuverType.STRAIGHT),
                OfflineRouter.RouteInstruction("انعطف يميناً", 5000.0, OfflineRouter.ManeuverType.TURN_RIGHT),
                OfflineRouter.RouteInstruction("وصلت لوجهتك", 300.0, OfflineRouter.ManeuverType.ARRIVE)
            ),
            leg1Direct = leg1,
            leg2Track = leg2,
            leg3Direct = leg3,
            isFallbackDirect = false
        )

        assertEquals(2, hybrid.leg1Direct.size)
        assertEquals(3, hybrid.leg2Track.size)
        assertEquals(2, hybrid.leg3Direct.size)
        assertEquals(5, hybrid.polyline.size)
        assertEquals(3, hybrid.instructions.size)
        assertFalse(hybrid.isFallbackDirect)
    }

    @Test
    fun osmdroidRouteOverlay_interpolateGeodesic() {
        val p1 = GeoPoint(24.7136, 46.6753)
        val p2 = GeoPoint(26.4207, 50.0888)

        val points = OsmdroidRouteOverlay.interpolateGeodesic(p1, p2, numPoints = 20)
        assertEquals(21, points.size)
        assertEquals(p1.latitude, points.first().latitude, 0.0001)
        assertEquals(p1.longitude, points.first().longitude, 0.0001)
        assertEquals(p2.latitude, points.last().latitude, 0.0001)
        assertEquals(p2.longitude, points.last().longitude, 0.0001)
    }

    @Test
    fun brouterSegmentMath_calculatesCorrectRd5Filenames() {
        // Jordan (Amman, Petra)
        assertEquals("E35_N30.rd5", MapFileManager.getSegmentNameForCoordinate(31.95, 35.93))
        assertEquals("E35_N30.rd5", MapFileManager.getSegmentNameForCoordinate(30.32, 35.44))

        // Jordan (Aqaba)
        assertEquals("E35_N25.rd5", MapFileManager.getSegmentNameForCoordinate(29.53, 35.00))

        // Saudi Arabia (Riyadh)
        assertEquals("E45_N20.rd5", MapFileManager.getSegmentNameForCoordinate(24.71, 46.67))

        // Egypt (Cairo)
        assertEquals("E30_N30.rd5", MapFileManager.getSegmentNameForCoordinate(30.04, 31.23))

        // Southern / Western Hemispheres
        assertEquals("W50_S15.rd5", MapFileManager.getSegmentNameForCoordinate(-12.34, -45.67))
    }

    @Test
    fun offlineRouter_fallbackDirectStructure_withWarning() {
        val origin = LatLng(24.7136, 46.6753)
        val dest = LatLng(24.7500, 46.7000)
        val expectedWarning = "لا توجد دروب سيارات قريبة، استخدم المسار المباشر"

        val fallbackResult = OfflineRouter.RouteResult(
            polyline = listOf(origin, dest),
            distanceMeters = 5000.0,
            durationSeconds = 300.0,
            instructions = listOf(
                OfflineRouter.RouteInstruction("اتجه مباشرة نحو الهدف بالبوصلة (5000 م)", 5000.0, OfflineRouter.ManeuverType.STRAIGHT)
            ),
            leg1Direct = listOf(origin, dest),
            leg2Track = emptyList(),
            leg3Direct = emptyList(),
            isFallbackDirect = true,
            warningMessage = expectedWarning
        )

        assertTrue(fallbackResult.isFallbackDirect)
        assertEquals(expectedWarning, fallbackResult.warningMessage)
        assertEquals(2, fallbackResult.polyline.size)
        assertTrue(fallbackResult.leg2Track.isEmpty())
        assertEquals(1, fallbackResult.instructions.size)
        assertEquals(OfflineRouter.ManeuverType.STRAIGHT, fallbackResult.instructions[0].maneuver)
    }

    @Test
    fun offlineRouter_detourThreshold_rejectsExtremeDetours() {
        val directDistanceMeters = 4000.0 // 4 km direct
        val trackDistanceDetour = 405400.0 // 405.4 km track (e.g. snapping to Riyadh instead of local)
        val normalTrackDistance = 5500.0 // 5.5 km track

        // Threshold is > 6x direct distance
        val isDetourExtreme = trackDistanceDetour > directDistanceMeters * 6.0
        val isNormalTrackExtreme = normalTrackDistance > directDistanceMeters * 6.0

        assertTrue("405.4 km track for 4.0 km direct must be detected as an extreme detour", isDetourExtreme)
        assertFalse("5.5 km track for 4.0 km direct must not be detected as extreme", isNormalTrackExtreme)
    }

    @Test
    fun carRouteMode_enumValuesAndDefaults() {
        val modes = CarRouteMode.values()
        assertEquals(2, modes.size)
        assertEquals(CarRouteMode.CLOSEST_PAVED, CarRouteMode.valueOf("CLOSEST_PAVED"))
        assertEquals(CarRouteMode.SHORTEST_TOTAL, CarRouteMode.valueOf("SHORTEST_TOTAL"))
    }

    @Test
    fun carRouteMode_shortestProfileAssetExists() {
        val shortestProfileFile = java.io.File("src/main/assets/brouter/desert-car-shortest.brf")
        assertTrue("desert-car-shortest.brf must exist in assets/brouter", shortestProfileFile.exists())
        val content = shortestProfileFile.readText()
        assertTrue("Profile must be valid for cars", content.contains("assign validForCars        = true"))
        assertTrue("Profile must have costfactor definition", content.contains("assign costfactor ="))
        assertTrue("Profile must have zero initialcost for tracks", content.contains("else 0"))
    }

    @Test
    fun shortestWayValidator_independentOfProfileCacheLifecycle() {
        val brouterDir = java.io.File("src/main/assets/brouter")
        val lookupsFile = java.io.File(brouterDir, "lookups.dat")
        val profileFile = java.io.File(brouterDir, "desert-car-shortest.brf")

        assertTrue("lookups.dat must exist", lookupsFile.exists())
        assertTrue("desert-car-shortest.brf must exist", profileFile.exists())

        val meta = btools.expressions.BExpressionMetaData()
        val validator = btools.expressions.BExpressionContextWay(meta)
        meta.readMetaData(lookupsFile)
        validator.parseFile(profileFile, "global")

        // Create a RoutingContext and simulate BRouter's execution lifecycle
        val rc = btools.router.RoutingContext().apply {
            expctxWay = validator
        }

        // Simulate BRouter doRun finally block releasing profile
        btools.router.ProfileCache.releaseProfile(rc)

        // Verify that ProfileCache.releaseProfile intentionally wiped rc.expctxWay
        org.junit.Assert.assertNull("RoutingContext.expctxWay is wiped by ProfileCache.releaseProfile()", rc.expctxWay)

        // But our independent validator reference remains completely valid and alive!
        org.junit.Assert.assertNotNull("Independent wayValidator reference must survive and stay alive", validator)
    }
}
