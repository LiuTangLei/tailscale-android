// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsNav(
    val onNavigateToBugReport: () -> Unit,
    val onNavigateToAbout: () -> Unit,
    val onNavigateToDNSSettings: () -> Unit,
    val onNavigateToSplitTunneling: () -> Unit,
    val onNavigateToTailnetLock: () -> Unit,
    val onNavigateToSubnetRouting: () -> Unit,
    val onNavigateToMDMSettings: () -> Unit,
    val onNavigateToManagedBy: () -> Unit,
    val onNavigateToUserSwitcher: () -> Unit,
    val onNavigateToPermissions: () -> Unit,
    val onNavigateToAwgManual: () -> Unit,
    val onNavigateToAwgJson: () -> Unit,
    val onNavigateToAwgViewer: () -> Unit,
    val onNavigateBackHome: () -> Unit,
    val onBackToSettings: () -> Unit,
)

class SettingsViewModel : IpnViewModel() {
  // Display name for the logged in user
  val isAdmin: StateFlow<Boolean> = MutableStateFlow(false)
  // True if tailnet lock is enabled.  nil if not yet known.
  val tailNetLockEnabled: StateFlow<Boolean?> = MutableStateFlow(null)
  // True if tailscaleDNS is enabled. nil if not yet known.
  val corpDNSEnabled: StateFlow<Boolean?> = MutableStateFlow(null)
  val isClientRemoteLoggingEnabled: StateFlow<Boolean> = MutableStateFlow(true)

  // AWG peers refresh state
  private val _isRefreshingAwgPeers = MutableStateFlow(false)
  val isRefreshingAwgPeers: StateFlow<Boolean> = _isRefreshingAwgPeers

  private val _awgRefreshMessage = MutableStateFlow<String?>(null)
  val awgRefreshMessage: StateFlow<String?> = _awgRefreshMessage

  init {
    isClientRemoteLoggingEnabled.set(App.get().isClientLoggingEnabled())

    viewModelScope.launch {
      Notifier.netmap.collect { netmap -> isAdmin.set(netmap?.SelfNode?.isAdmin ?: false) }
    }

    Client(viewModelScope).tailnetLockStatus { result ->
      result.onSuccess { status -> tailNetLockEnabled.set(status.Enabled) }

      LoadingIndicator.stop()
    }

    viewModelScope.launch {
      Notifier.prefs.collect {
        it?.let { corpDNSEnabled.set(it.CorpDNS) } ?: run { corpDNSEnabled.set(null) }
      }
    }
  }

  fun refreshAwgPeers(onComplete: () -> Unit) {
    _isRefreshingAwgPeers.value = true
    val client = Client(viewModelScope)
    client.awgSyncPeers { result ->
      result
          .onSuccess { awgPeers: List<AwgPeerResult> ->
            val awgCount = awgPeers.count { it.hasAwgConfig }
            val failedCount = awgPeers.count { it.lookupFailed }
            val total = awgPeers.size
            _awgRefreshMessage.value =
                when {
                  total == 0 -> "No online peers found"
                  failedCount > 0 ->
                      "Found $awgCount/$total AWG peers; $failedCount could not be checked"
                  awgCount > 0 -> "Found $awgCount/$total peers with AWG config"
                  else -> "Checked $total peers; all use standard WireGuard"
                }
          }
          .onFailure { error ->
            TSLog.e("SettingsViewModel", "Failed to refresh AWG peers: ${error.message}")
            _awgRefreshMessage.value = "Failed to refresh AWG peers: ${error.message}"
          }
      _isRefreshingAwgPeers.value = false
      onComplete()
    }
  }

  fun clearAwgRefreshMessage() {
    _awgRefreshMessage.value = null
  }

  fun toggleIsClientRemoteLoggingEnabled() {
    isClientRemoteLoggingEnabled.set(!isClientRemoteLoggingEnabled.value)
    App.get().updateIsClientLoggingEnabled(isClientRemoteLoggingEnabled.value)
  }
}
