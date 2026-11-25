package com.alruwaili.garminalbarr.map.download

data class MapRegion(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val boundingBox: BoundingBox,
    val sizeBytes: Long,
    val downloadUrl: String,
    val mapDownloadUrl: String = "",
    val version: Int,
    val hasDem: Boolean = true,
    val sha256: String? = null,
    val routingSegments: List<String> = emptyList(),
    val routingSizeBytes: Long = 0L,
    /** Size of the previous build (old host). Files of that size stay valid. */
    val legacySizeBytes: Long = 0L
)

data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east
}

enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    UPDATE_AVAILABLE
}
