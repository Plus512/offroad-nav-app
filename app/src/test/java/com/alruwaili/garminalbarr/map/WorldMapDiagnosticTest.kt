package com.alruwaili.garminalbarr.map

import org.junit.Test
import org.mapsforge.core.model.Tile as MapsforgeTile
import org.mapsforge.map.reader.MapFile
import java.io.File

class WorldMapDiagnosticTest {

    private fun tileForLon(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return Math.floor((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun tileForLat(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat)
        return Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    @Test
    fun testWorldMapData() {
        val worldMapFile = File("src/main/assets/maps/world.map")
        println("=== TESTING world.map ===")
        println("File absolute path: ${worldMapFile.absolutePath}")
        println("File exists: ${worldMapFile.exists()}, length: ${worldMapFile.length()}")
        if (!worldMapFile.exists()) return

        val mapFile = MapFile(worldMapFile)
        val info = mapFile.mapFileInfo
        println("MapFileInfo: boundingBox=${info.boundingBox}")
        println("MapFileInfo: zoomLevelMin=${info.zoomLevelMin}, zoomLevelMax=${info.zoomLevelMax}, startZoomLevel=${info.startZoomLevel}, startPosition=${info.startPosition}, fileVersion=${info.fileVersion}")

        val lat = 24.7
        val lon = 46.7

        val allUniqueTags = mutableSetOf<String>()

        for (zoom in 0..10) {
            val tileX = tileForLon(lon, zoom)
            val tileY = tileForLat(lat, zoom)
            val tile = MapsforgeTile(tileX, tileY, zoom.toByte(), 256)
            val supported = mapFile.supportsTile(tile)
            println("--- Zoom $zoom (tileX=$tileX, tileY=$tileY, supported=$supported) ---")

            try {
                val result = mapFile.readMapData(tile)
                if (result == null) {
                    println("  Zoom $zoom: readMapData returned null")
                } else {
                    val ways = result.ways ?: emptyList()
                    val pois = result.pointOfInterests ?: emptyList()
                    println("  Zoom $zoom: waysCount=${ways.size}, poisCount=${pois.size}, isWater=${result.isWater}")

                    for (w in ways) {
                        w.tags?.forEach { allUniqueTags.add("${it.key}=${it.value}") }
                    }
                    for (p in pois) {
                        p.tags?.forEach { allUniqueTags.add("[POI] ${it.key}=${it.value}") }
                    }

                    for ((i, way) in ways.take(3).withIndex()) {
                        val tagsStr = way.tags?.joinToString("; ") { "${it.key}=${it.value}" } ?: "no tags"
                        println("    Way[$i] (layer=${way.layer}): $tagsStr")
                    }

                    for ((i, poi) in pois.take(3).withIndex()) {
                        val tagsStr = poi.tags?.joinToString("; ") { "${it.key}=${it.value}" } ?: "no tags"
                        println("    POI[$i] (layer=${poi.layer}): $tagsStr")
                    }
                }
            } catch (e: Exception) {
                println("  Zoom $zoom: readMapData threw exception: ${e.message}")
            }
        }

        println("=== ALL UNIQUE TAGS FOUND IN TEST TILES ===")
        allUniqueTags.forEach { println("TAG: $it") }

        mapFile.close()
    }
}
