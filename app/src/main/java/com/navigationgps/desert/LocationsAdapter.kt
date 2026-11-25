package com.navigationgps.desert

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.navigationgps.desert.databinding.ItemLocationBinding

class LocationsAdapter(
    private val onFavoriteClick: (LocationLightweight) -> Unit,
    private val onItemClick: (LocationLightweight) -> Unit
) : ListAdapter<LocationLightweight, LocationsAdapter.LocationViewHolder>(DiffCallback) {

    private var userLat: Double? = null
    private var userLon: Double? = null

    fun updateUserLocation(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        val size = currentList.size
        if (size > 0) {
            notifyItemRangeChanged(0, size, PAYLOAD_LOCATION_UPDATE)
        }
    }

    inner class LocationViewHolder(private val binding: ItemLocationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(location: LocationLightweight) {
            binding.title.text = location.title

            val lat = location.latitude ?: 0.0
            val lon = location.longitude ?: 0.0
            val uLat = userLat
            val uLon = userLon
            val distanceKm = if (uLat != null && uLon != null) {
                calculateDistance(uLat, uLon, lat, lon)
            } else {
                0.0
            }
            binding.distanceText.text = String.format(java.util.Locale.US, "المسافة: %.2f كم", distanceKm)

            binding.favoriteIcon.setImageResource(
                if (location.isFav == true) R.drawable.favred else R.drawable.favorite_border
            )
            binding.favoriteIcon.setOnClickListener { onFavoriteClick(location) }
            binding.root.setOnClickListener { onItemClick(location) }
        }

        fun updateDistanceOnly(location: LocationLightweight) {
            val lat = location.latitude ?: 0.0
            val lon = location.longitude ?: 0.0
            val uLat = userLat
            val uLon = userLon
            val distanceKm = if (uLat != null && uLon != null) {
                calculateDistance(uLat, uLon, lat, lon)
            } else {
                0.0
            }
            binding.distanceText.text = String.format(java.util.Locale.US, "المسافة: %.2f كم", distanceKm)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationViewHolder {
        val binding = ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LocationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LocationViewHolder, position: Int) {
        val location = getItem(position)
        holder.bind(location)
    }

    override fun onBindViewHolder(holder: LocationViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.any { it == PAYLOAD_LOCATION_UPDATE }) {
            holder.updateDistanceOnly(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    fun updateFavoriteStatus(updatedLocation: LocationLightweight) {
        val currentList = currentList.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedLocation.id }
        if (index != -1) {
            currentList[index] = updatedLocation
            submitList(currentList.toList())
        }
    }

    companion object {
        private const val PAYLOAD_LOCATION_UPDATE = "payload_location_update"

        val DiffCallback = object : DiffUtil.ItemCallback<LocationLightweight>() {
            override fun areItemsTheSame(oldItem: LocationLightweight, newItem: LocationLightweight) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: LocationLightweight, newItem: LocationLightweight) = oldItem == newItem
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0] / 1000.0
    }
}
