package com.navigationgps.desert

import androidx.room.ColumnInfo

data class LocationLightweight(
    @ColumnInfo(name = "_id") val id: Int = 0,
    @ColumnInfo(name = "is_fav") val isFav: Boolean? = false,
    val latitude: Double?,
    val longitude: Double?,
    val title: String?
)
