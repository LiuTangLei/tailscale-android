// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.MagicHeaderRange
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AwgSettingsViewModel : IpnViewModel() {

    // Current AWG config loaded from prefs
    private val _currentConfig = MutableStateFlow<AmneziaWGPrefs?>(null)
    val currentConfig: StateFlow<AmneziaWGPrefs?> = _currentConfig

    // Manual field values
    val jc = MutableStateFlow("")
    val jMin = MutableStateFlow("")
    val jMax = MutableStateFlow("")
    val s1 = MutableStateFlow("")
    val s2 = MutableStateFlow("")
    val s3 = MutableStateFlow("")
    val s4 = MutableStateFlow("")
    val i1 = MutableStateFlow("")
    val i2 = MutableStateFlow("")
    val i3 = MutableStateFlow("")
    val i4 = MutableStateFlow("")
    val i5 = MutableStateFlow("")
    val h1Min = MutableStateFlow("")
    val h1Max = MutableStateFlow("")
    val h2Min = MutableStateFlow("")
    val h2Max = MutableStateFlow("")
    val h3Min = MutableStateFlow("")
    val h3Max = MutableStateFlow("")
    val h4Min = MutableStateFlow("")
    val h4Max = MutableStateFlow("")

    // JSON input
    val jsonInput = MutableStateFlow("")

    // Status message for toast
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadCurrentConfig()
    }

    fun loadCurrentConfig() {
        val client = Client(viewModelScope)
        client.getLocalPrefs { result ->
            result.onSuccess { prefs ->
                val config = prefs.AmneziaWG
                _currentConfig.value = config
                App.get().getAppScopedViewModel().setLocalAwgConfigured(config?.hasNonDefaultValues() == true)
                config?.let { populateFieldsFromConfig(it) }
            }.onFailure { error ->
                TSLog.e(TAG, "Failed to load AWG config: ${error.message}")
            }
        }
    }

    private fun populateFieldsFromConfig(config: AmneziaWGPrefs) {
        jc.value = config.JC?.takeIf { it != 0 }?.toString() ?: ""
        jMin.value = config.JMin?.takeIf { it != 0 }?.toString() ?: ""
        jMax.value = config.JMax?.takeIf { it != 0 }?.toString() ?: ""
        s1.value = config.S1?.takeIf { it != 0 }?.toString() ?: ""
        s2.value = config.S2?.takeIf { it != 0 }?.toString() ?: ""
        s3.value = config.S3?.takeIf { it != 0 }?.toString() ?: ""
        s4.value = config.S4?.takeIf { it != 0 }?.toString() ?: ""
        i1.value = config.I1 ?: ""
        i2.value = config.I2 ?: ""
        i3.value = config.I3 ?: ""
        i4.value = config.I4 ?: ""
        i5.value = config.I5 ?: ""
        h1Min.value = config.H1?.min?.toString() ?: ""
        h1Max.value = config.H1?.max?.toString() ?: ""
        h2Min.value = config.H2?.min?.toString() ?: ""
        h2Max.value = config.H2?.max?.toString() ?: ""
        h3Min.value = config.H3?.min?.toString() ?: ""
        h3Max.value = config.H3?.max?.toString() ?: ""
        h4Min.value = config.H4?.min?.toString() ?: ""
        h4Max.value = config.H4?.max?.toString() ?: ""
    }

    fun applyJsonConfig() {
        val json = jsonInput.value.trim()
        if (json.isEmpty()) {
            _statusMessage.value = "JSON input is empty"
            return
        }
        try {
            val decoder = Json { ignoreUnknownKeys = true }
            val config = decoder.decodeFromString<AmneziaWGPrefs>(json)
            applyConfig(config)
        } catch (e: Exception) {
            _statusMessage.value = "Invalid JSON: ${e.message}"
        }
    }

    fun applyManualConfig() {
        val config = buildConfigFromFields()
        applyConfig(config)
    }

    fun clearFields() {
        jc.value = ""
        jMin.value = ""
        jMax.value = ""
        s1.value = ""
        s2.value = ""
        s3.value = ""
        s4.value = ""
        i1.value = ""
        i2.value = ""
        i3.value = ""
        i4.value = ""
        i5.value = ""
        h1Min.value = ""
        h1Max.value = ""
        h2Min.value = ""
        h2Max.value = ""
        h3Min.value = ""
        h3Max.value = ""
        h4Min.value = ""
        h4Max.value = ""
        jsonInput.value = ""
    }

    private fun buildConfigFromFields(): AmneziaWGPrefs {
        fun parseHeaderRange(minStr: String, maxStr: String): MagicHeaderRange? {
            val min = minStr.trim().toLongOrNull()
            val max = maxStr.trim().toLongOrNull()
            return if (min != null || max != null) MagicHeaderRange(min, max) else null
        }

        return AmneziaWGPrefs(
            JC = jc.value.trim().toIntOrNull(),
            JMin = jMin.value.trim().toIntOrNull(),
            JMax = jMax.value.trim().toIntOrNull(),
            S1 = s1.value.trim().toIntOrNull(),
            S2 = s2.value.trim().toIntOrNull(),
            S3 = s3.value.trim().toIntOrNull(),
            S4 = s4.value.trim().toIntOrNull(),
            I1 = i1.value.trim().ifEmpty { null },
            I2 = i2.value.trim().ifEmpty { null },
            I3 = i3.value.trim().ifEmpty { null },
            I4 = i4.value.trim().ifEmpty { null },
            I5 = i5.value.trim().ifEmpty { null },
            H1 = parseHeaderRange(h1Min.value, h1Max.value),
            H2 = parseHeaderRange(h2Min.value, h2Max.value),
            H3 = parseHeaderRange(h3Min.value, h3Max.value),
            H4 = parseHeaderRange(h4Min.value, h4Max.value),
        )
    }

    private fun applyConfig(config: AmneziaWGPrefs) {
        _isLoading.value = true
        val maskedPrefs = Ipn.MaskedPrefs()
        maskedPrefs.AmneziaWG = config

        val client = Client(viewModelScope)
        client.editPrefs(maskedPrefs) { result ->
            result.onSuccess {
                TSLog.d(TAG, "AWG config applied successfully")
                _currentConfig.value = config
                App.get().getAppScopedViewModel().setLocalAwgConfigured(config.hasNonDefaultValues())
                _statusMessage.value = if (config.hasNonDefaultValues()) {
                    "AWG config applied successfully"
                } else {
                    "AWG config cleared"
                }
                loadCurrentConfig()
                autoReconnect()
            }.onFailure { error ->
                TSLog.e(TAG, "Failed to apply AWG config: ${error.message}")
                _statusMessage.value = "Failed to apply AWG config: ${error.message}"
                _isLoading.value = false
            }
        }
    }

    private fun autoReconnect() {
        viewModelScope.launch {
            try {
                TSLog.d(TAG, "Restarting VPN for AWG config change")
                restartVPN()
                TSLog.d(TAG, "VPN restart requested for AWG config change")
            } catch (e: Exception) {
                TSLog.e(TAG, "Auto-reconnect failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    companion object {
        private const val TAG = "AwgSettingsViewModel"
    }
}
