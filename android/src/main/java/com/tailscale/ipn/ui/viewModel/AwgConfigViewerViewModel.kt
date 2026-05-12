// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AwgConfigViewerViewModel : IpnViewModel() {

    private val _configJson = MutableStateFlow<String?>(null)
    val configJson: StateFlow<String?> = _configJson

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = _isClearing

    init {
        loadConfig()
    }

    private fun loadConfig() {
        _isLoading.value = true
        val client = Client(viewModelScope)
        client.getLocalPrefs { result ->
            result.onSuccess { prefs ->
                val config = prefs.AmneziaWG
                if (config != null && config.hasNonDefaultValues()) {
                    val json = Json { prettyPrint = true; encodeDefaults = false }
                    _configJson.value = json.encodeToString(AmneziaWGPrefs.serializer(), config)
                } else {
                    _configJson.value = null
                }
            }.onFailure { error ->
                TSLog.e(TAG, "Failed to load AWG config: ${error.message}")
                _configJson.value = null
            }
            _isLoading.value = false
        }
    }

    fun clearAwgConfig(onCleared: () -> Unit) {
        _isClearing.value = true
        val maskedPrefs = Ipn.MaskedPrefs()
        maskedPrefs.AmneziaWG = AmneziaWGPrefs()

        val client = Client(viewModelScope)
        client.editPrefs(maskedPrefs) { result ->
            result.onSuccess {
                TSLog.d(TAG, "AWG config cleared successfully")
                _configJson.value = null
                App.get().getAppScopedViewModel().setLocalAwgConfigured(false)
                viewModelScope.launch {
                    try {
                        restartVPN()
                    } catch (e: Exception) {
                        TSLog.e(TAG, "VPN restart after clear failed: ${e.message}")
                    }
                }
                onCleared()
            }.onFailure { error ->
                TSLog.e(TAG, "Failed to clear AWG config: ${error.message}")
            }
            _isClearing.value = false
        }
    }

    companion object {
        private const val TAG = "AwgConfigViewerVM"
    }
}
