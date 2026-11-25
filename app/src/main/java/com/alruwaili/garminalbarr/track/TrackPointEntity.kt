package com.alruwaili.garminalbarr.track

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    indices = [
        Index(value = ["track_id", "seq_index"]),
        Index(value = ["track_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "seq_index") val seqIndex: Int,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "altitude") val altitude: Double? = null,
    @ColumnInfo(name = "speed_mps") val speedMps: Float? = null,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float? = null,
    @ColumnInfo(name = "timestamp_millis") val timestampMillis: Long
)
