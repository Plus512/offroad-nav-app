package com.alruwaili.garminalbarr.network

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: SignalPointEntity): Long

    @Delete
    suspend fun delete(point: SignalPointEntity)

    @Query("DELETE FROM signal_points WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE signal_points SET custom_label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String?)

    @Query("SELECT * FROM signal_points ORDER BY captured_at_millis DESC")
    fun getAllFlow(): Flow<List<SignalPointEntity>>

    @Query("SELECT * FROM signal_points ORDER BY captured_at_millis DESC")
    suspend fun getAll(): List<SignalPointEntity>

    @Query("SELECT COUNT(*) FROM signal_points")
    suspend fun getCount(): Int

    @Query("SELECT * FROM signal_points WHERE id = :id")
    suspend fun getById(id: Long): SignalPointEntity?
}
