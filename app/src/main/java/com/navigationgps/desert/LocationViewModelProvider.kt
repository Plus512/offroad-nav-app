package com.navigationgps.desert

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

object LocationViewModelProvider {
    fun get(owner: ViewModelStoreOwner): SharedLocationViewModel {
        return ViewModelProvider(owner)[SharedLocationViewModel::class.java]
    }
}
