package com.navigationgps.desert

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "locations",
    indices = [Index(value = ["title"]), Index(value = ["latitude", "longitude"])]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    @SerializedName("id") val id: Int=0,
    @SerializedName("country") val country: String?=null,
    @ColumnInfo(name = "is_fav")
    @SerializedName("isFav") val isFav: Boolean?=false,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("title") val title: String?,
    @ColumnInfo(name = "is_manual")
    @SerializedName("isManual") val isManual: Boolean? = false
)