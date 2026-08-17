/*
 * Aurora Store
 *  Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 *
 *  Aurora Store is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  Aurora Store is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Aurora Store.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.aurora.store.viewmodel.all

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.store.data.providers.AppRequest
import com.aurora.store.data.providers.CatalogApp
import com.aurora.store.data.providers.RequestProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val requestProvider: RequestProvider
) : ViewModel() {

    private val _requests = MutableStateFlow<List<AppRequest>>(emptyList())
    val requests: StateFlow<List<AppRequest>> = _requests.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _catalog = MutableStateFlow<List<CatalogApp>>(emptyList())
    val catalog: StateFlow<List<CatalogApp>> = _catalog.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _catalog.value = requestProvider.fetchCatalog()
            _requests.value = requestProvider.fetchMyRequests()
            _loading.value = false
        }
    }

    fun submit(appName: String, pkg: String, note: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = requestProvider.submitRequest(appName, pkg, note)
            if (success) refresh()
            onResult(success)
        }
    }
}
