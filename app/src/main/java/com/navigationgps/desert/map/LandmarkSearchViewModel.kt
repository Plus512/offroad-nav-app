package com.navigationgps.desert.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.navigationgps.desert.CacheState
import com.navigationgps.desert.LocationDao
import com.navigationgps.desert.LocationEntity
import com.navigationgps.desert.LocationLightweight
import com.navigationgps.desert.RoomDBHelper
import com.navigationgps.desert.SharedLocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel powering the in-map landmark search overlay.
 *
 * Architecture: in-memory cache search with pre-normalized index (zero DB round-trips).
 * The global [SharedLocationViewModel.globalLocationsCache] is loaded at app start by
 * [com.navigationgps.desert.MyApplication] and holds ~314k [LocationLightweight] records.
 *
 * Performance Optimizations:
 * 1. Pre-normalized search index: When [CacheState.Ready] arrives, a background job builds
 *    a list of [SearchEntry] objects once. Subsequent user keystrokes search directly against
 *    pre-normalized strings without regex evaluation or per-item allocations.
 * 2. Non-allocating Arabic normalizer: [normalizeArabic] processes characters sequentially
 *    using a single [StringBuilder] pass, eliminating regex compilation and intermediate String
 *    allocations.
 * 3. Early-exit search with cooperative cancellation: Checks [ensureActive] periodically and
 *    terminates as soon as [MAX_RESULTS] items are found, ensuring UI remains strictly 60+ FPS.
 */
@OptIn(FlowPreview::class)
class LandmarkSearchViewModel(
    private val sharedViewModel: SharedLocationViewModel,
    private val dao: LocationDao = RoomDBHelper.getInstance(sharedViewModel.getApplication()).locationDao
) : ViewModel() {

    private data class SearchEntry(
        val location: LocationLightweight,
        val normalizedTitle: String
    )

    private val _query       = MutableStateFlow("")
    private val _results     = MutableStateFlow<List<LocationLightweight>>(emptyList())
    private val _isSearching = MutableStateFlow(false)
    private val _cacheReady  = MutableStateFlow(false)

    val results:     StateFlow<List<LocationLightweight>> = _results
    val isSearching: StateFlow<Boolean>                   = _isSearching
    val cacheReady:  StateFlow<Boolean>                   = _cacheReady

    private var searchJob: Job? = null

    @Volatile
    private var searchIndex: List<SearchEntry> = emptyList()

    init {
        // Build pre-normalized search index once in background when cache is ready
        viewModelScope.launch {
            sharedViewModel.globalLocationsCache.collect { state ->
                if (state is CacheState.Ready) {
                    val entries = withContext(Dispatchers.Default) {
                        state.locations.map { loc ->
                            SearchEntry(
                                location = loc,
                                normalizedTitle = normalizeArabic(loc.title ?: "")
                            )
                        }
                    }
                    searchIndex = entries
                    _cacheReady.value = true
                } else {
                    _cacheReady.value = false
                }
            }
        }

        // Debounced search pipeline — fires 200 ms after the last keystroke
        viewModelScope.launch {
            _query
                .debounce(200)           // prevent firing on every character
                .distinctUntilChanged()  // skip duplicate consecutive values
                .collect { q -> performSearch(q) }
        }
    }

    /** Called by the UI on every text-field change. */
    fun onQueryChanged(q: String) {
        _query.value = q
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _results.value     = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            val normalizedQuery = normalizeArabic(trimmed)
            val index = searchIndex

            val found = withContext(Dispatchers.Default) {
                val matches = ArrayList<LocationLightweight>(MAX_RESULTS)
                for (i in 0 until index.size) {
                    if ((i and 511) == 0) {
                        ensureActive()
                    }
                    val entry = index[i]
                    if (entry.normalizedTitle.contains(normalizedQuery, ignoreCase = true)) {
                        matches.add(entry.location)
                        if (matches.size >= MAX_RESULTS) {
                            break
                        }
                    }
                }
                matches
            }

            _results.value = found
            _isSearching.value = false
        }
    }

    /**
     * Toggles favorite status for a landmark.
     * 100% FREE for all users (ungated).
     * Dispatches optimistic in-memory updates immediately, then persists to Room DB on Dispatchers.IO.
     */
    fun toggleFavorite(location: LocationLightweight) {
        val newStatus = !(location.isFav ?: false)

        // 1. Optimistic UI update on currently displayed results
        val currentResults = _results.value.toMutableList()
        val rIdx = currentResults.indexOfFirst {
            (location.id > 0 && it.id == location.id) ||
            (it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude)
        }
        if (rIdx != -1) {
            currentResults[rIdx] = currentResults[rIdx].copy(isFav = newStatus)
            _results.value = currentResults
        }

        // 2. In-memory update on search index so future searches or query updates retain state
        val currentSearchIndex = searchIndex
        val idx = currentSearchIndex.indexOfFirst {
            (location.id > 0 && it.location.id == location.id) ||
            (it.location.title == location.title && it.location.latitude == location.latitude && it.location.longitude == location.longitude)
        }
        if (idx != -1) {
            val updatedIndex = currentSearchIndex.toMutableList()
            val oldEntry = updatedIndex[idx]
            updatedIndex[idx] = oldEntry.copy(location = oldEntry.location.copy(isFav = newStatus))
            searchIndex = updatedIndex
        }

        // 3. Update global locations cache in SharedLocationViewModel
        val cacheState = sharedViewModel.globalLocationsCache.value
        if (cacheState is CacheState.Ready) {
            val cacheList = cacheState.locations.toMutableList()
            val cIdx = cacheList.indexOfFirst {
                (location.id > 0 && it.id == location.id) ||
                (it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude)
            }
            if (cIdx != -1) {
                cacheList[cIdx] = cacheList[cIdx].copy(isFav = newStatus)
                sharedViewModel.initializeGlobalCache(cacheList)
            }
        }

        // 4. Update manualLocations in SharedLocationViewModel if it's a manual location
        val manualList = sharedViewModel.manualLocations.value?.toList() ?: emptyList()
        val manualMatch = manualList.find {
            (location.id > 0 && it.id == location.id) ||
            (it.title == location.title && it.latitude == location.latitude && it.longitude == location.longitude)
        }
        if (manualMatch != null) {
            sharedViewModel.toggleFavoriteManual(manualMatch)
        }

        // 5. Persist to Room database on Dispatchers.IO
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (location.id > 0) {
                    dao.updateFavorite(location.id, newStatus)
                } else {
                    dao.insert(
                        LocationEntity(
                            id = 0,
                            title = location.title,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            isFav = newStatus
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("LandmarkSearchVM", "Failed to update favorite in Room DB", e)
            }
        }
    }

    class Factory(
        private val sharedViewModel: SharedLocationViewModel,
        private val dao: LocationDao? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LandmarkSearchViewModel::class.java)) {
                return if (dao != null) {
                    LandmarkSearchViewModel(sharedViewModel, dao) as T
                } else {
                    LandmarkSearchViewModel(sharedViewModel) as T
                }
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        /** Maximum items displayed in the search results list. */
        const val MAX_RESULTS = 80

        /**
         * Normalizes Arabic text in a single pass without regex compilation.
         *
         * Transformations:
         * 1. Strips diacritics (harakat U+064B–U+065F), superscript alef (U+0670), tatweel (U+0640).
         * 2. Unifies all Alef variants to bare Alef: آ (U+0622), أ (U+0623), إ (U+0625), ٱ (U+0671) -> ا (U+0627).
         * 3. Taa Marbuta -> Haa: ة (U+0629) -> ه (U+0647).
         * 4. Alef Maksura -> Yaa: ى (U+0649) -> ي (U+064A).
         */
        fun normalizeArabic(text: String): String {
            if (text.isEmpty()) return text
            val sb = java.lang.StringBuilder(text.length)
            for (i in 0 until text.length) {
                val ch = text[i]
                when (ch) {
                    in '\u064B'..'\u065F', '\u0670', '\u0640' -> { /* strip */ }
                    '\u0622', '\u0623', '\u0625', '\u0671' -> sb.append('\u0627')
                    '\u0629' -> sb.append('\u0647')
                    '\u0649' -> sb.append('\u064A')
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
