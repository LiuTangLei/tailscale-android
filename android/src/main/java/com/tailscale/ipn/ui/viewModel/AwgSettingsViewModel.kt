// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.AwgProfileGenerator
import com.tailscale.ipn.ui.model.AwgProfileVersion
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.MagicHeaderRange
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AwgSettingsViewModel : IpnViewModel() {
  private val appViewModel = App.get().getAppScopedViewModel()

  private val _currentConfig = MutableStateFlow<AmneziaWGPrefs?>(null)
  val currentConfig: StateFlow<AmneziaWGPrefs?> = _currentConfig

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

  val headerProtectionKey = MutableStateFlow("")
  val contentPaddingMin = MutableStateFlow("")
  val contentPaddingMax = MutableStateFlow("")
  val rekeyAfterMin = MutableStateFlow("")
  val rekeyAfterMax = MutableStateFlow("")
  val rekeyTimeoutMin = MutableStateFlow("")
  val rekeyTimeoutMax = MutableStateFlow("")
  val rejectAfterMin = MutableStateFlow("")
  val rejectAfterMax = MutableStateFlow("")
  val keepaliveTimeoutMin = MutableStateFlow("")
  val keepaliveTimeoutMax = MutableStateFlow("")
  val maxHandshakeAttemptsMin = MutableStateFlow("")
  val maxHandshakeAttemptsMax = MutableStateFlow("")

  val jsonInput = MutableStateFlow("")

  private val _statusMessage = MutableStateFlow<String?>(null)
  val statusMessage: StateFlow<String?> = _statusMessage

  val awgWriteInProgress: StateFlow<AwgWriteOperation?> = appViewModel.awgWriteInProgress
  val awgProfileMutationInProgress: StateFlow<AwgProfileMutationOperation?> =
      appViewModel.awgProfileMutationInProgress
  val awgProfileSyncReady: StateFlow<Boolean> = appViewModel.awgProfileSyncReady

  private val _generatedProfileVersion = MutableStateFlow<AwgProfileVersion?>(null)
  val generatedProfileVersion: StateFlow<AwgProfileVersion?> = _generatedProfileVersion

  private val json = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
    encodeDefaults = false
  }

  init {
    loadCurrentConfig()
  }

  fun loadCurrentConfig() {
    val prefsReadToken = appViewModel.beginAwgPrefsRead(AwgPrefsReader.SETTINGS)
    Client(viewModelScope).getLocalPrefs prefsRequest@{ result ->
      if (!appViewModel.isCurrentAwgPrefsRead(prefsReadToken)) return@prefsRequest
      result
          .onSuccess { prefs ->
            val config = prefs.AmneziaWG
            if (!appViewModel.commitLocalAwgStatus(
                prefsReadToken, config?.hasNonDefaultValues() == true)) {
              return@prefsRequest
            }
            _currentConfig.value = config
            config?.let(::populateFieldsFromConfig)
          }
          .onFailure { error ->
            TSLog.e(TAG, "Failed to load AWG config: ${error.message}")
            _statusMessage.value = "Failed to load AWG config: ${error.message}"
          }
    }
  }

  fun generateProfile(version: AwgProfileVersion) {
    val config = AwgProfileGenerator.generate(version)
    populateFieldsFromConfig(config)
    jsonInput.value = json.encodeToString(config)
    _generatedProfileVersion.value = version
    _statusMessage.value =
        "Generated ${config.versionLabel()}; apply the same profile to every peer"
  }

  private fun populateFieldsFromConfig(config: AmneziaWGPrefs) {
    jc.value = config.JC.nonZeroText()
    jMin.value = config.JMin.nonZeroText()
    jMax.value = config.JMax.nonZeroText()
    s1.value = config.S1.nonZeroText()
    s2.value = config.S2.nonZeroText()
    s3.value = config.S3.nonZeroText()
    s4.value = config.S4.nonZeroText()
    i1.value = config.I1.orEmpty()
    i2.value = config.I2.orEmpty()
    i3.value = config.I3.orEmpty()
    i4.value = config.I4.orEmpty()
    i5.value = config.I5.orEmpty()
    setRange(config.H1, h1Min, h1Max)
    setRange(config.H2, h2Min, h2Max)
    setRange(config.H3, h3Min, h3Max)
    setRange(config.H4, h4Min, h4Max)
    headerProtectionKey.value = config.HeaderProtectionKey.orEmpty()
    setRange(config.ContentPaddingAddition, contentPaddingMin, contentPaddingMax)
    setRange(config.RekeyAfterTime, rekeyAfterMin, rekeyAfterMax)
    setRange(config.RekeyTimeout, rekeyTimeoutMin, rekeyTimeoutMax)
    setRange(config.RejectAfterTime, rejectAfterMin, rejectAfterMax)
    setRange(config.KeepaliveTimeout, keepaliveTimeoutMin, keepaliveTimeoutMax)
    setRange(
        config.MaxHandshakeAttempts,
        maxHandshakeAttemptsMin,
        maxHandshakeAttemptsMax,
    )
  }

  fun applyJsonConfig() {
    val input = jsonInput.value.trim()
    if (input.isEmpty()) {
      _statusMessage.value = "JSON input is empty"
      return
    }
    runCatching { json.decodeFromString<AmneziaWGPrefs>(input) }
        .onSuccess(::applyConfig)
        .onFailure { _statusMessage.value = "Invalid AWG JSON: ${it.message}" }
  }

  fun applyManualConfig() {
    buildConfigFromFields().onSuccess(::applyConfig).onFailure {
      _statusMessage.value = it.message ?: "Invalid AWG configuration"
    }
  }

  fun clearFields() {
    listOf(
            jc,
            jMin,
            jMax,
            s1,
            s2,
            s3,
            s4,
            i1,
            i2,
            i3,
            i4,
            i5,
            h1Min,
            h1Max,
            h2Min,
            h2Max,
            h3Min,
            h3Max,
            h4Min,
            h4Max,
            headerProtectionKey,
            contentPaddingMin,
            contentPaddingMax,
            rekeyAfterMin,
            rekeyAfterMax,
            rekeyTimeoutMin,
            rekeyTimeoutMax,
            rejectAfterMin,
            rejectAfterMax,
            keepaliveTimeoutMin,
            keepaliveTimeoutMax,
            maxHandshakeAttemptsMin,
            maxHandshakeAttemptsMax,
            jsonInput,
        )
        .forEach { it.value = "" }
    _generatedProfileVersion.value = null
  }

  private fun buildConfigFromFields(): Result<AmneziaWGPrefs> = runCatching {
    AmneziaWGPrefs(
            JC = parseInt("JC", jc.value),
            JMin = parseInt("JMin", jMin.value),
            JMax = parseInt("JMax", jMax.value),
            S1 = parseInt("S1", s1.value),
            S2 = parseInt("S2", s2.value),
            S3 = parseInt("S3", s3.value),
            S4 = parseInt("S4", s4.value),
            I1 = i1.value.trim().ifEmpty { null },
            I2 = i2.value.trim().ifEmpty { null },
            I3 = i3.value.trim().ifEmpty { null },
            I4 = i4.value.trim().ifEmpty { null },
            I5 = i5.value.trim().ifEmpty { null },
            H1 = parseRange("H1", h1Min.value, h1Max.value),
            H2 = parseRange("H2", h2Min.value, h2Max.value),
            H3 = parseRange("H3", h3Min.value, h3Max.value),
            H4 = parseRange("H4", h4Min.value, h4Max.value),
            HeaderProtectionKey = headerProtectionKey.value.trim().ifEmpty { null },
            ContentPaddingAddition =
                parseRange(
                    "ContentPaddingAddition",
                    contentPaddingMin.value,
                    contentPaddingMax.value,
                ),
            RekeyAfterTime = parseRange("RekeyAfterTime", rekeyAfterMin.value, rekeyAfterMax.value),
            RekeyTimeout = parseRange("RekeyTimeout", rekeyTimeoutMin.value, rekeyTimeoutMax.value),
            RejectAfterTime =
                parseRange("RejectAfterTime", rejectAfterMin.value, rejectAfterMax.value),
            KeepaliveTimeout =
                parseRange(
                    "KeepaliveTimeout",
                    keepaliveTimeoutMin.value,
                    keepaliveTimeoutMax.value,
                ),
            MaxHandshakeAttempts =
                parseRange(
                    "MaxHandshakeAttempts",
                    maxHandshakeAttemptsMin.value,
                    maxHandshakeAttemptsMax.value,
                ),
        )
        .also { config -> config.validationError()?.let { throw IllegalArgumentException(it) } }
  }

  private fun applyConfig(config: AmneziaWGPrefs) {
    config.validationError()?.let {
      _statusMessage.value = it
      return
    }
    val writeOperation = appViewModel.tryBeginAwgWrite()
    if (writeOperation == null) {
      _statusMessage.value = "An account or AWG configuration update is already in progress"
      return
    }
    val maskedPrefs = Ipn.MaskedPrefs().also { it.AmneziaWG = config }
    Client(appViewModel.viewModelScope).editPrefs(maskedPrefs) { result ->
      if (!appViewModel.finishAwgWrite(
          operation = writeOperation,
          writeSucceeded = result.isSuccess,
          localAwgConfiguredOnSuccess = config.hasNonDefaultValues(),
      )) {
        return@editPrefs
      }
      if (!viewModelScope.isActive) return@editPrefs
      result
          .onSuccess {
            _currentConfig.value = config
            _generatedProfileVersion.value = null
            _statusMessage.value =
                if (config.hasNonDefaultValues()) {
                  "${config.versionLabel()} applied; peers must use matching S/H/key parameters"
                } else {
                  "AWG config cleared"
                }
            // EditPrefs reconfigures the live Go engine; restarting VpnService would add a needless
            // disconnect and can disrupt an in-place upgrade.
            loadCurrentConfig()
          }
          .onFailure { error ->
            TSLog.e(TAG, "Failed to apply AWG config: ${error.message}")
            _statusMessage.value = "Failed to apply AWG config: ${error.message}"
          }
    }
  }

  fun clearStatusMessage() {
    _statusMessage.value = null
  }

  private fun parseInt(name: String, input: String): Int? {
    if (input.isBlank()) return null
    return input.trim().toIntOrNull() ?: throw IllegalArgumentException("$name is too large")
  }

  private fun parseRange(name: String, minInput: String, maxInput: String): MagicHeaderRange? {
    if (minInput.isBlank() && maxInput.isBlank()) return null
    if (minInput.isBlank() || maxInput.isBlank()) {
      throw IllegalArgumentException("$name requires both min and max")
    }
    val min = minInput.trim().toLongOrNull() ?: throw IllegalArgumentException("Invalid $name min")
    val max = maxInput.trim().toLongOrNull() ?: throw IllegalArgumentException("Invalid $name max")
    return MagicHeaderRange(min, max)
  }

  private fun Int?.nonZeroText(): String = this?.takeIf { it != 0 }?.toString().orEmpty()

  private fun setRange(
      range: MagicHeaderRange?,
      minFlow: MutableStateFlow<String>,
      maxFlow: MutableStateFlow<String>,
  ) {
    minFlow.value = range?.min?.toString().orEmpty()
    maxFlow.value = range?.max?.toString().orEmpty()
  }

  companion object {
    private const val TAG = "AwgSettingsViewModel"
  }
}
