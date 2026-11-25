package com.alruwaili.garminalbarr.map.download

object RegionRegistry {

    // Maps + routing hosted on GitHub releases (public repo aldlelah/desert-maps): free, no daily cap.
    private const val BASE_URL = "https://github.com/aldlelah/desert-maps/releases/download/maps-v1"
    const val ROUTING_BASE_URL = BASE_URL
    const val ROUTING_FALLBACK_URL = "https://brouter.de/brouter/segments4"

    const val WORLD_MAP_URL = "$BASE_URL/world.map"
    const val WORLD_MAP_FILENAME = "world.map"

    val ALL_REGIONS = listOf(
        MapRegion(
            id = "saudi_north",
            nameAr = "السعودية (الشمال)",
            nameEn = "Saudi North",
            boundingBox = BoundingBox(25.5, 34.5, 32.5, 55.7),
            sizeBytes = 121_419_218L,
            legacySizeBytes = 59_833_276L,
            downloadUrl = "$BASE_URL/saudi_north.map",
            mapDownloadUrl = "$BASE_URL/saudi_north.map",
            version = 1,
            routingSegments = listOf(
                "E30_N25.rd5", "E35_N25.rd5", "E40_N25.rd5", "E45_N25.rd5",
                "E50_N25.rd5", "E55_N25.rd5", "E30_N30.rd5", "E35_N30.rd5",
                "E40_N30.rd5", "E45_N30.rd5", "E50_N30.rd5"
            ),
            routingSizeBytes = 134_460_000L
        ),
        MapRegion(
            id = "saudi_south",
            nameAr = "السعودية (الجنوب)",
            nameEn = "Saudi South",
            boundingBox = BoundingBox(16.0, 34.5, 25.6, 51.0),
            sizeBytes = 65_543_884L,
            legacySizeBytes = 46_421_628L,
            downloadUrl = "$BASE_URL/saudi_south.map",
            mapDownloadUrl = "$BASE_URL/saudi_south.map",
            version = 1,
            routingSegments = listOf(
                "E35_N15.rd5", "E40_N15.rd5", "E45_N15.rd5", "E50_N15.rd5",
                "E35_N20.rd5", "E40_N20.rd5", "E45_N20.rd5", "E50_N20.rd5",
                "E35_N25.rd5", "E40_N25.rd5", "E45_N25.rd5", "E50_N25.rd5"
            ),
            routingSizeBytes = 55_020_000L
        ),
        MapRegion(
            id = "jordan",
            nameAr = "الأردن",
            nameEn = "Jordan",
            boundingBox = BoundingBox(29.1, 34.8, 33.4, 39.3),
            sizeBytes = 26_234_213L,
            legacySizeBytes = 29_982_149L,
            downloadUrl = "$BASE_URL/jordan.map",
            mapDownloadUrl = "$BASE_URL/jordan.map",
            version = 1,
            routingSegments = listOf("E30_N25.rd5", "E35_N25.rd5", "E30_N30.rd5", "E35_N30.rd5"),
            routingSizeBytes = 70_960_000L
        ),
        MapRegion(
            id = "uae",
            nameAr = "الإمارات",
            nameEn = "UAE",
            boundingBox = BoundingBox(22.6, 51.5, 26.1, 56.4),
            sizeBytes = 70_757_061L,
            legacySizeBytes = 53_497_664L,
            downloadUrl = "$BASE_URL/uae.map",
            mapDownloadUrl = "$BASE_URL/uae.map",
            version = 1,
            routingSegments = listOf("E50_N20.rd5", "E55_N20.rd5", "E50_N25.rd5", "E55_N25.rd5"),
            routingSizeBytes = 33_890_000L
        ),
        MapRegion(
            id = "oman",
            nameAr = "عُمان",
            nameEn = "Oman",
            boundingBox = BoundingBox(16.6, 52.0, 26.4, 59.9),
            sizeBytes = 86_812_717L,
            legacySizeBytes = 17_807_917L,
            downloadUrl = "$BASE_URL/oman.map",
            mapDownloadUrl = "$BASE_URL/oman.map",
            version = 1,
            routingSegments = listOf("E50_N15.rd5", "E55_N15.rd5", "E50_N20.rd5", "E55_N20.rd5", "E55_N25.rd5"),
            routingSizeBytes = 25_260_000L
        ),
        MapRegion(
            id = "qatar",
            nameAr = "قطر",
            nameEn = "Qatar",
            boundingBox = BoundingBox(24.5, 50.7, 26.2, 51.7),
            sizeBytes = 20_320_077L,
            legacySizeBytes = 17_099_039L,
            downloadUrl = "$BASE_URL/qatar.map",
            mapDownloadUrl = "$BASE_URL/qatar.map",
            version = 1,
            routingSegments = listOf("E50_N20.rd5", "E50_N25.rd5"),
            routingSizeBytes = 15_000_000L
        ),
        MapRegion(
            id = "kuwait",
            nameAr = "الكويت",
            nameEn = "Kuwait",
            boundingBox = BoundingBox(28.5, 46.5, 30.1, 48.5),
            sizeBytes = 15_091_655L,
            legacySizeBytes = 12_601_665L,
            downloadUrl = "$BASE_URL/kuwait.map",
            mapDownloadUrl = "$BASE_URL/kuwait.map",
            version = 1,
            routingSegments = listOf("E45_N25.rd5", "E45_N30.rd5"),
            routingSizeBytes = 18_030_000L
        ),
        MapRegion(
            id = "bahrain",
            nameAr = "البحرين",
            nameEn = "Bahrain",
            boundingBox = BoundingBox(25.5, 50.3, 26.4, 50.8),
            sizeBytes = 5_927_010L,
            legacySizeBytes = 4_643_620L,
            downloadUrl = "$BASE_URL/bahrain.map",
            mapDownloadUrl = "$BASE_URL/bahrain.map",
            version = 1,
            routingSegments = listOf("E50_N25.rd5"),
            routingSizeBytes = 10_860_000L
        )
    )

    val ALL_UNIQUE_ROUTING_SEGMENTS: List<String> =
        ALL_REGIONS.flatMap { it.routingSegments }.distinct().sorted()

    fun getSegmentsForRegion(regionId: String): List<String> =
        getRegionById(regionId)?.routingSegments ?: emptyList()

    fun findRegionForLocation(lat: Double, lon: Double): MapRegion? =
        ALL_REGIONS.filter { it.boundingBox.contains(lat, lon) }
            .minByOrNull { region ->
                val b = region.boundingBox
                (b.north - b.south) * (b.east - b.west)
            }

    fun getRegionById(id: String): MapRegion? =
        ALL_REGIONS.firstOrNull { it.id == id }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
