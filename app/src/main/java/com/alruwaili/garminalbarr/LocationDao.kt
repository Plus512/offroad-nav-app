package com.alruwaili.garminalbarr

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao

interface LocationDao {

    @Query("DELETE FROM locations")
    suspend fun clearAll()


    @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insert(location: LocationEntity)

    @Delete
    suspend fun delete(location: LocationEntity)


    @Query("SELECT * FROM locations WHERE is_fav = 1")
    fun getFavoriteLocations(): LiveData<List<LocationEntity>>
    @Query("UPDATE locations SET is_fav = :isFavorite WHERE _id = :locationId")
     fun updateFavorite(locationId: Int, isFavorite: Boolean)
    @Query("SELECT * FROM locations")
    suspend fun getAllLocationsDirect(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE is_manual = 1")
    suspend fun getUserSavedLocations(): List<LocationEntity>

    @Query("DELETE FROM locations WHERE is_manual = 1 AND title = :title AND latitude = :lat AND longitude = :lon")
    suspend fun deleteUserSavedLocation(title: String, lat: Double, lon: Double)

    @Query("SELECT _id, is_fav, latitude, longitude, title FROM locations")
    suspend fun getLightweightLocationsDirect(): List<LocationLightweight>

    @Query("SELECT _id, is_fav, latitude, longitude, title FROM locations WHERE title LIKE '%' || :query || '%' LIMIT 50")
    suspend fun searchLocationsLightweight(query: String): List<LocationLightweight>

    @Query("SELECT _id, is_fav, latitude, longitude, title FROM locations WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon ORDER BY _id LIMIT 500")
    suspend fun getInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<LocationLightweight>

    @Query("SELECT _id, is_fav, latitude, longitude, title FROM locations WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon AND title LIKE '%' || :query || '%' ESCAPE '\\' LIMIT 200")
    suspend fun searchInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, query: String): List<LocationLightweight>

    @Query("""
        SELECT _id, is_fav, latitude, longitude, title FROM locations
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        ORDER BY _id
    """)
    suspend fun getDesertPoisInBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<LocationLightweight>
}