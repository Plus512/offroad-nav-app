package com.alruwaili.garminalbarr

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.alruwaili.garminalbarr.databinding.ActivityFavoritesBinding
import com.alruwaili.garminalbarr.billing.FeatureGatekeeper
import com.alruwaili.garminalbarr.map.LocationShareHelper
import com.alruwaili.garminalbarr.map.MapNavigationHelper

class FavoritesActivity : AppCompatActivity() {
    private lateinit var viewModel: LocationViewModel
    private lateinit var sharedLocationViewModel: SharedLocationViewModel
    private lateinit var adapter: LocationsAdapter
    private lateinit var binding: ActivityFavoritesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this).get(LocationViewModel::class.java)
        val app = application as MyApplication
        sharedLocationViewModel = app.sharedLocationViewModel

        adapter = LocationsAdapter(
            onFavoriteClick = { location -> toggleFavorite(location) },
            onItemClick = { location -> showOptionsDialog(location) }
        )
        sharedLocationViewModel.currentLocation.observe(this) { location ->
            if (location != null) {
                adapter.updateUserLocation(location.latitude, location.longitude)
                Log.d("FavoritesDebug", "User location updated inside FavoritesActivity")
            }
        }

        binding.favoritesList.layoutManager = LinearLayoutManager(this)
        binding.favoritesList.adapter = adapter

        viewModel.favouriteLocations.observe(this) { updateFavoritesList() }
        sharedLocationViewModel.manualLocations.observe(this) { updateFavoritesList() }
    }

    fun updateFavoritesList() {
        val dbFavs = viewModel.favouriteLocations.value ?: emptyList()
        val manualFavs = sharedLocationViewModel.manualLocations.value?.filter { it.isFav == true } ?: emptyList()

        val combined = (dbFavs + manualFavs)
            .distinctBy { Triple(it.title, it.latitude, it.longitude) }

        val combinedLightweight = combined.map { entity ->
            LocationLightweight(
                id = entity.id,
                isFav = entity.isFav,
                latitude = entity.latitude,
                longitude = entity.longitude,
                title = entity.title
            )
        }

        adapter.submitList(combinedLightweight)
        binding.emptyStateText.visibility = if (combinedLightweight.isEmpty()) View.VISIBLE else View.GONE
        binding.favoritesList.visibility = if (combinedLightweight.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleFavorite(location: LocationLightweight) {
        val manualList = sharedLocationViewModel.manualLocations.value ?: emptyList()
        val matchedManual = manualList.find { it.id == location.id }
        if (matchedManual != null) {
            sharedLocationViewModel.toggleFavoriteManual(matchedManual)
        } else {
            viewModel.toggleFavorite(location)
        }
        Toast.makeText(this, "تم تحديث حالة المفضلة", Toast.LENGTH_SHORT).show()
    }

    private fun showOptionsDialog(location: LocationLightweight) {
        val options = arrayOf("إزالة من المفضلة", "مشاركة", "الذهاب إلى")
        AlertDialog.Builder(this, R.style.CustomAlertDialogTheme)
            .setTitle(location.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavorite(location)
                    1 -> {
                        FeatureGatekeeper.guardAction(this) {
                            shareLocation(location)
                        }
                    }
                    2 -> {
                        FeatureGatekeeper.guardAction(this) {
                            goToLocation(location)
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun shareLocation(location: LocationLightweight) {
        val lat = location.latitude ?: return
        val lon = location.longitude ?: return
        LocationShareHelper.shareLocation(this, location.title, lat, lon)
    }

    private fun goToLocation(location: LocationLightweight) {
        val lat = location.latitude
        val lon = location.longitude
        if (!MapNavigationHelper.isValidLatLon(lat, lon)) {
            Toast.makeText(this, "إحداثيات غير صالحة", Toast.LENGTH_SHORT).show()
            return
        }
        MapNavigationHelper.goToDesertMap(this, lat!!, lon!!, location.title)
        finish()
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration)
        configuration.fontScale = 1.0f
        val context = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(context)
    }
}