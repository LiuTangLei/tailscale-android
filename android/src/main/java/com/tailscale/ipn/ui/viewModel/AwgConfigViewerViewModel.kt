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
import kotlinx.serialization.json.Json

class AwgConfigViewerViewModel : IpnViewModel() {

  private val _configJson = MutableStateFlow<String?>(null)
  val configJson: StateFlow<String?> = _configJson

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading

  private val _isClearing = MutableStateFlow(false)
  val isClearing: StateFlow<Boolean> = _isClearing

  private val _loadError = MutableStateFlow<String?>(null)
  val loadError: StateFlow<String?> = _loadError

  init {
    loadConfig()
  }

  fun loadConfig() {
    _isLoading.value = true
    val client = Client(viewModelScope)
    client.getLocalPrefs { result ->
      result
          .onSuccess { prefs ->
            _loadError.value = null
            val config = prefs.AmneziaWG
            if (config != null && config.hasNonDefaultValues()) {
              val json = Json {
                prettyPrint = true
                encodeDefaults = false
              }
              _configJson.value = json.encodeToString(AmneziaWGPrefs.serializer(), config)
            } else {
              _configJson.value = null
            }
          }
          .onFailure { error ->
            TSLog.e(TAG, "Failed to load AWG config: ${error.message}")
            _configJson.value = null
            _loadError.value = error.message ?: "LocalAPI request failed"
          }
      _isLoading.value = false
    }
  }

  fun clearAwgConfig(onCleared: () -> Unit, onError: (String) -> Unit) {
    _isClearing.value = true
    val maskedPrefs = Ipn.MaskedPrefs()
    maskedPrefs.AmneziaWG = AmneziaWGPrefs()

    val client = Client(viewModelScope)
    client.editPrefs(maskedPrefs) { result ->
      result
          .onSuccess {
            TSLog.d(TAG, "AWG config cleared successfully")
            _configJson.value = null
            App.get().getAppScopedViewModel().setLocalAwgConfigured(false)
            onCleared()
          }
          .onFailure { error ->
            TSLog.e(TAG, "Failed to clear AWG config: ${error.message}")
            onError(error.message ?: "LocalAPI request failed")
          }
      _isClearing.value = false
    }
  }

  companion object {
    private const val TAG = "AwgConfigViewerVM"
  }
}
