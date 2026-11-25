package com.navigationgps.desert.map.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

class DownloadStateManager(context: Context) {

    companion object {
        private const val TAG = "DownloadStateManager"
        private const val PREFS_NAME = "download_states"
        private const val KEY_STATES = "region_states"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private data class RegionState(
        @SerializedName("state") val state: DownloadState,
        @SerializedName("version") val version: Int = 1,
        @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
    )

    fun getState(regionId: String): DownloadState {
        val states = loadStates()
        return states[regionId]?.state ?: DownloadState.NOT_DOWNLOADED
    }

    fun setState(regionId: String, state: DownloadState) {
        val states = loadStates()
        val existing = states[regionId]
        states[regionId] = RegionState(
            state = state,
            version = existing?.version ?: 1,
            timestamp = System.currentTimeMillis()
        )
        saveStates(states)
    }

    /**
     * Clears a persisted DOWNLOADED flag whose backing file failed the integrity sweep,
     * so the download banner/manager offers the region for re-download.
     * Uses commit() for crash-consistency: apply() is async and can be lost if process dies
     * before flush, leaving a dangling DOWNLOADED flag for a deleted file.
     */
    fun invalidate(regionId: String) {
        val states = loadStates()
        if (states[regionId]?.state == DownloadState.DOWNLOADED) {
            states[regionId] = RegionState(
                state = DownloadState.NOT_DOWNLOADED,
                version = states[regionId]?.version ?: 1,
                timestamp = System.currentTimeMillis()
            )
            saveStates(states, sync = true)
            Log.w(TAG, "Invalidated persisted state for '$regionId' (file failed integrity check)")
        }
    }

    fun getDownloadedVersion(regionId: String): Int {
        val states = loadStates()
        return states[regionId]?.version ?: 0
    }

    fun getAllStates(): Map<String, DownloadState> {
        return loadStates().mapValues { it.value.state }
    }

    private fun loadStates(): MutableMap<String, RegionState> {
        val json = prefs.getString(KEY_STATES, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, RegionState>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse download states", e)
            mutableMapOf()
        }
    }

    private fun saveStates(states: Map<String, RegionState>, sync: Boolean = false) {
        val json = gson.toJson(states)
        val editor = prefs.edit().putString(KEY_STATES, json)
        if (sync) editor.commit() else editor.apply()
    }
}
