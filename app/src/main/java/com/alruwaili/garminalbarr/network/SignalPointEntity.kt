package com.alruwaili.garminalbarr.network

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single "نقطة شبكة" (signal point): a location where cellular signal was last
 * present, recorded automatically by [NetworkSpotterService] the moment signal is lost.
 *
 * The forced "نقطة شبكة" label is NOT stored in [customLabel] — it is composed at
 * display time in the UI (always "نقطة شبكة" + an optional user-given suffix), so the
 * forced prefix can never be removed or overwritten regardless of what the user names it.
 */
@Entity(tableName = "signal_points")
data class SignalPointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "point_number") val pointNumber: Int,
    @ColumnInfo(name = "custom_label") val customLabel: String? = null,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "captured_at_millis") val capturedAtMillis: Long
) {
    /** The name always shown for this point: forced "نقطة شبكة" prefix, mandatory even if a custom label is set. */
    val displayName: String
        get() = if (!customLabel.isNullOrBlank()) "نقطة شبكة - $customLabel" else "نقطة شبكة $pointNumber"
}
