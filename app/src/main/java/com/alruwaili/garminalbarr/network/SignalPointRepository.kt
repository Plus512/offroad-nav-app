package com.alruwaili.garminalbarr.network

import android.content.Context
import com.alruwaili.garminalbarr.RoomDBHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SignalPointRepository(context: Context) {
    private val db = RoomDBHelper.getInstance(context)
    private val dao = db.signalPointDao

    val allSignalPoints: Flow<List<SignalPointEntity>> = dao.getAllFlow()

    suspend fun insert(point: SignalPointEntity): Long = withContext(Dispatchers.IO) {
        dao.insert(point)
    }

    suspend fun nextPointNumber(): Int = withContext(Dispatchers.IO) {
        dao.getCount() + 1
    }

    suspend fun rename(id: Long, label: String?) = withContext(Dispatchers.IO) {
        dao.updateLabel(id, label)
    }

    suspend fun delete(point: SignalPointEntity) = withContext(Dispatchers.IO) {
        dao.delete(point)
    }

    suspend fun getById(id: Long): SignalPointEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }
}
