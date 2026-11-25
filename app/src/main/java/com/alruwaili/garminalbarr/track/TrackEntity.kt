package com.alruwaili.garminalbarr.track

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "color_hex") val colorHex: String = "#E53935",
    @ColumnInfo(name = "start_time_millis") val startTimeMillis: Long,
    @ColumnInfo(name = "end_time_millis") val endTimeMillis: Long,
    @ColumnInfo(name = "total_distance_meters") val totalDistanceMeters: Double,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long,
    @ColumnInfo(name = "max_speed_mps") val maxSpeedMps: Float = 0f,
    @ColumnInfo(name = "avg_speed_mps") val avgSpeedMps: Float = 0f,
    @ColumnInfo(name = "min_altitude_meters") val minAltitudeMeters: Double? = null,
    @ColumnInfo(name = "max_altitude_meters") val maxAltitudeMeters: Double? = null,
    @ColumnInfo(name = "is_visible_on_map") val isVisibleOnMap: Boolean = true,
    @ColumnInfo(name = "points_count") val pointsCount: Int = 0,
    @ColumnInfo(name = "encoded_polyline") val encodedPolyline: String? = null
)
