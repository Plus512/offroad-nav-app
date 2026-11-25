package com.navigationgps.desert

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.navigationgps.desert.LocationEntity
import com.navigationgps.desert.LocationLightweight
import com.navigationgps.desert.MyLocationsStorage
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class CacheState {
    object Loading : CacheState()
    data class Ready(val locations: List<LocationLightweight>) : CacheState()
    data class Error(val exception: Throwable) : CacheState()
}

data class LocationData(val latitude: Double, val longitude: Double, val title: String? = null)

class SharedLocationViewModel(application: Application) : AndroidViewModel(application) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationCallback: LocationCallback? = null

    private val _globalLocationsCache = MutableStateFlow<CacheState>(CacheState.Loading)
    val globalLocationsCache: StateFlow<CacheState> = _globalLocationsCache

    fun initializeGlobalCache(locations: List<LocationLightweight>) {
        _globalLocationsCache.value = CacheState.Ready(locations)
    }

    fun setCacheError(exception: Throwable) {
        _globalLocationsCache.value = CacheState.Error(exception)
    }

    val currentLocation = MutableLiveData<LocationData>()
    val myLocations = MutableLiveData<MutableList<LocationEntity>>(mutableListOf())
    val manualLocations = MutableLiveData<MutableList<LocationEntity>>(mutableListOf())

    fun updateLocation(lat: Double, lon: Double) {
        Log.d("SharedLocationDebug", "📍 updateLocation called with lat=$lat, lon=$lon")
        currentLocation.postValue(LocationData(lat, lon))
    }

    fun addMyLocation(location: LocationEntity) {
        val list = myLocations.value ?: mutableListOf()
        list.add(location)
        myLocations.value = list
        
        val lightweight = LocationLightweight(
            id = location.id,
            isFav = location.isFav,
            latitude = location.latitude,
            longitude = location.longitude,
            title = location.title
        )
        val state = _globalLocationsCache.value
        if (state is CacheState.Ready) {
            val updated = state.locations.toMutableList()
            updated.add(lightweight)
            _globalLocationsCache.value = CacheState.Ready(updated)
            Log.d("SharedVM", "➕ تمت إضافة موقع جديد للكاش (${location.title}) — إجمالي الآن ${updated.size}")
        }
    }

    fun addManualLocation(location: LocationEntity) {
        // Offload all disk/Gson I/O to Dispatchers.IO — never block Main.
        applicationScope.launch {
            val storage = MyLocationsStorage(getApplication())
            val list = try {
                storage.load().toMutableList()
            } catch (e: Exception) {
                Log.e("SharedVM", "❌ فشل تحميل المواقع: ${e.message}", e)
                mutableListOf()
            }

            val existingIndex = list.indexOfFirst {
                it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude
            }

            if (existingIndex != -1) {
                list[existingIndex] = location
                Log.d("SharedVM", "✅ تم تحديث الموقع الحالي (موجود مسبقاً): ${location.title}")
            } else {
                list.add(location)
                Log.d("SharedVM", "➕ تمت إضافة موقع جديد: ${location.title}")
            }

            manualLocations.postValue(list)

            try {
                storage.save(list)
                Log.d("SharedVM", "💾 تم حفظ المواقع بعد الإضافة/التحديث (${list.size})")
                // Trigger cloud sync (already IO)
                LocationsSyncRepository.syncToCloud(getApplication(), list)
            } catch (e: Exception) {
                Log.e("SharedVM", "❌ فشل حفظ المواقع: ${e.message}")
            }
        }
    }


    fun toggleFavoriteManual(location: LocationEntity) {
        val list = manualLocations.value ?: return
        val index = list.indexOf(location)
        if (index != -1) {
            list[index] = list[index].copy(isFav = !(list[index].isFav ?: false))
            manualLocations.postValue(list)
        }
    }
    fun updateManualFavoriteStatus(updated: LocationEntity) {
        // Disk I/O off Main — compute in-memory snapshot fast, persist on IO.
        val currentSnapshot = manualLocations.value?.toMutableList() ?: mutableListOf()
        val index = currentSnapshot.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            currentSnapshot[index] = updated
            Log.d("SharedVM", "✅ تم تحديث حالة المفضلة للموقع: ${updated.title}")
        } else {
            Log.d("SharedVM", "⚠️ الموقع غير موجود في القائمة، لن يتم تكراره: ${updated.title}")
        }
        manualLocations.value = currentSnapshot

        applicationScope.launch {
            try {
                val storage = MyLocationsStorage(getApplication())
                storage.save(currentSnapshot)
                LocationsSyncRepository.syncToCloud(getApplication(), currentSnapshot)
            } catch (e: Exception) {
                Log.e("SharedVM", "❌ فشل حفظ حالة المفضلة: ${e.message}")
            }
        }
    }

    fun loadManualLocations(loadedList: MutableList<LocationEntity>) {

        if (manualLocations.value.isNullOrEmpty()) {
            manualLocations.value = loadedList
            Log.d("SharedVM", "💾 تم تحميل المواقع اليدوية (${loadedList.size}) من التخزين إلى ViewModel")
        } else {

            Log.d("SharedVM", "⚠️ تجاهل تحميل المواقع اليدوية، الـ ViewModel يحتوي على بيانات مسبقًا.")
        }
    }



    fun toggleFavoritePersistent(location: LocationEntity) {
        // Fast in-memory toggle on caller thread, disk merge strictly on IO.
        val list = manualLocations.value?.toMutableList() ?: mutableListOf()
        val idx = list.indexOfFirst {
            it.title == location.title &&
                    it.latitude == location.latitude &&
                    it.longitude == location.longitude
        }

        if (idx != -1) {
            val updated = list[idx].copy(isFav = !(list[idx].isFav ?: false))
            list[idx] = updated
            Log.d("SharedVM", "⭐ toggleFavoritePersistent: updated manualLocations for ${updated.title}")
        } else {
            val added = location.copy(isFav = true)
            list.add(added)
            Log.d("SharedVM", "➕ toggleFavoritePersistent: added manual favorite ${added.title}")
        }
        manualLocations.value = list.toMutableList()

        val snapshot = list.toList()
        applicationScope.launch {
            try {
                val storage = MyLocationsStorage(getApplication())
                val savedList = storage.load().toMutableList()
                val savedIdx = savedList.indexOfFirst {
                    it.title == location.title &&
                            it.latitude == location.latitude &&
                            it.longitude == location.longitude
                }

                if (savedIdx != -1) {
                    savedList[savedIdx] = savedList[savedIdx].copy(isFav = snapshot.first {
                        it.title == savedList[savedIdx].title &&
                                it.latitude == savedList[savedIdx].latitude &&
                                it.longitude == savedList[savedIdx].longitude
                    }.isFav)
                } else {
                    val toAdd = snapshot.firstOrNull {
                        it.title == location.title &&
                                it.latitude == location.latitude &&
                                it.longitude == location.longitude
                    }
                    if (toAdd != null) savedList.add(toAdd)
                }

                storage.save(savedList)
                Log.d("SharedVM", "💾 toggleFavoritePersistent: saved changes to MyLocationsStorage (${savedList.size})")
                LocationsSyncRepository.syncToCloud(getApplication(), savedList)
            } catch (e: Exception) {
                Log.e("SharedVM", "Failed saving favorites: ${e.message}", e)
            }
        }
        val favStatus = snapshot.firstOrNull {
            it.title == location.title &&
                    it.latitude == location.latitude &&
                    it.longitude == location.longitude
        }?.isFav
        Log.d("FavoriteDebug", "🔁 ${location.title} → $favStatus")
    }

    fun refreshLocation(context: Context) {
        try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) bestLocation = l
            }
            bestLocation?.let {
                currentLocation.value = LocationData(it.latitude, it.longitude)
                Log.d("SharedVM", "📍 الموقع الحالي تم تحديثه: ${it.latitude}, ${it.longitude}")
            } ?: run {
                Log.d("SharedVM", "⚠️ لم يتم العثور على موقع محدث")
            }
        } catch (e: SecurityException) {
            Log.e("SharedVM", "🚫 فشل تحديث الموقع: ${e.message}")
        }
    }

    fun startLocationUpdates(context: Context) {
        if (locationCallback != null) return // Already running
        
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setWaitForAccurateLocation(true)
            .build()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                currentLocation.postValue(
                    LocationData(
                        title = "الموقع الحالي",
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                )

                val prefs = context.getSharedPreferences("user_location", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("lat", location.latitude.toString())
                    .putString("lon", location.longitude.toString())
                    .apply()
            }
        }
        locationCallback = callback
        fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    fun stopLocationUpdates(context: Context) {
        locationCallback?.let { callback ->
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient.removeLocationUpdates(callback)
                Log.d("SharedVM", "Location updates explicitly stopped.")
            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to remove location updates: ${e.message}")
            }
            locationCallback = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationCallback?.let { callback ->
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(getApplication())
                fusedClient.removeLocationUpdates(callback)
            } catch (e: Exception) {
                Log.e("SharedVM", "Failed to remove location updates on clear: ${e.message}")
            }
            locationCallback = null
        }
    }


}
