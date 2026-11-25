package com.navigationgps.desert

import android.content.Context
import com.google.gson.Gson

class MyLocationsStorage(context: Context) {
    private val prefs = context.getSharedPreferences("my_locations_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Callback for change notifications
    private var onChangeListener: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) {
        onChangeListener = listener
    }

    fun save(list: List<LocationEntity>) {
        val json = gson.toJson(list)
        prefs.edit().putString("my_locations_list", json).apply()
        onChangeListener?.invoke()
    }

    fun load(): MutableList<LocationEntity> {
        val json = prefs.getString("my_locations_list", null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, Array<LocationEntity>::class.java).toMutableList()
        } catch (e: Exception) {
            android.util.Log.e("MyLocationsStorage", "Failed to deserialize locations, clearing corrupted data", e)
            prefs.edit().remove("my_locations_list").apply()
            mutableListOf()
        }
    }

    fun isEmpty(): Boolean {
        return prefs.getString("my_locations_list", null) == null
    }
}
