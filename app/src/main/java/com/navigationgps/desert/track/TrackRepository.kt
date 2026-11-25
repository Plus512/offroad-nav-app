package com.navigationgps.desert.track

import android.content.Context
import androidx.room.withTransaction
import com.navigationgps.desert.RoomDBHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TrackRepository(context: Context) {
    private val db = RoomDBHelper.getInstance(context)
    private val trackDao = db.trackDao

    val allTracks: Flow<List<TrackEntity>> = trackDao.getAllTracksFlow()
    val visibleTracks: Flow<List<TrackEntity>> = trackDao.getVisibleTracksFlow()

    suspend fun saveTrack(track: TrackEntity, points: List<TrackPointEntity>): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val trackId = trackDao.insertTrack(track)
            val indexedPoints = points.mapIndexed { idx, pt ->
                pt.copy(trackId = trackId, seqIndex = idx)
            }
            if (indexedPoints.isNotEmpty()) {
                trackDao.insertTrackPoints(indexedPoints)
            }
            trackId
        }
    }

    suspend fun insertPointsBatch(points: List<TrackPointEntity>) = withContext(Dispatchers.IO) {
        if (points.isNotEmpty()) {
            trackDao.insertTrackPoints(points)
        }
    }

    suspend fun getTrackById(trackId: Long): TrackEntity? = withContext(Dispatchers.IO) {
        trackDao.getTrackById(trackId)
    }

    suspend fun getTrack(trackId: Long): TrackEntity? = getTrackById(trackId)

    suspend fun getTrackPoints(trackId: Long): List<TrackPointEntity> = withContext(Dispatchers.IO) {
        trackDao.getTrackPoints(trackId)
    }

    fun getTrackPointsFlow(trackId: Long): Flow<List<TrackPointEntity>> {
        return trackDao.getTrackPointsFlow(trackId)
    }

    suspend fun setTrackVisibility(trackId: Long, isVisible: Boolean) = withContext(Dispatchers.IO) {
        trackDao.setTrackVisibility(trackId, isVisible)
    }

    suspend fun updateTrack(track: TrackEntity) = withContext(Dispatchers.IO) {
        trackDao.updateTrack(track)
    }

    suspend fun deleteTrack(trackId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            trackDao.deletePointsForTrack(trackId)
            trackDao.deleteTrackById(trackId)
        }
    }

    suspend fun getVisibleTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
        trackDao.getVisibleTracks()
    }
}
