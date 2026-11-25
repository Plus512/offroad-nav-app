package com.navigationgps.desert

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationViewModel(application: Application): AndroidViewModel(application) {
    private val dao = RoomDBHelper.getInstance(application).locationDao
    private val _addedLocation = MutableLiveData<LocationEntity>()
    val addedLocation: LiveData<LocationEntity> get() = _addedLocation

    val favouriteLocations: LiveData<List<LocationEntity>> = dao.getFavoriteLocations()

    fun updateFavouriteStatus(locationId: Int, isFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateFavorite(locationId, isFavorite)
        }
    }

    fun insertLocation(location: LocationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(location)
            _addedLocation.postValue(location)
        }
    }

    fun deleteLocation(location: LocationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(location)
        }
    }

    fun toggleFavorite(location: LocationLightweight) {
        viewModelScope.launch(Dispatchers.IO) {
            val newStatus = !(location.isFav ?: false)
            dao.updateFavorite(location.id, newStatus)
            
            val app = getApplication<MyApplication>()
            val cacheState = app.sharedLocationViewModel.globalLocationsCache.value
            if (cacheState is CacheState.Ready) {
                val currentCache = cacheState.locations.toMutableList()
                val index = currentCache.indexOfFirst { it.id == location.id }
                if (index != -1) {
                    currentCache[index] = currentCache[index].copy(isFav = newStatus)
                    app.sharedLocationViewModel.initializeGlobalCache(currentCache)
                }
            }
        }
    }

    fun clearAllLocations() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }
}
