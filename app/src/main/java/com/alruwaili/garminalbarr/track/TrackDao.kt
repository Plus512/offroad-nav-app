package com.alruwaili.garminalbarr.track

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: Long)

    @Query("SELECT * FROM tracks ORDER BY start_time_millis DESC")
    fun getAllTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY start_time_millis DESC")
    suspend fun getAllTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrackById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE is_visible_on_map = 1 ORDER BY start_time_millis DESC")
    suspend fun getVisibleTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE is_visible_on_map = 1 ORDER BY start_time_millis DESC")
    fun getVisibleTracksFlow(): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET is_visible_on_map = :isVisible WHERE id = :trackId")
    suspend fun setTrackVisibility(trackId: Long, isVisible: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY seq_index ASC")
    suspend fun getTrackPoints(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY seq_index ASC")
    fun getTrackPointsFlow(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("DELETE FROM track_points WHERE track_id = :trackId")
    suspend fun deletePointsForTrack(trackId: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTracksCount(): Int
}
