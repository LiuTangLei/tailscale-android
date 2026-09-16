// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.model.AwgRefreshFeedback
import com.tailscale.ipn.ui.model.TransportStatus
import com.tailscale.ipn.ui.model.awgRefreshMessage
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

  private val _transportStatus = MutableStateFlow<TransportStatus?>(null)
  val transportStatus: StateFlow<TransportStatus?> = _transportStatus
  private val _isChangingTransport = MutableStateFlow(false)
  val isChangingTransport: StateFlow<Boolean> = _isChangingTransport

  init {
    isClientRemoteLoggingEnabled.set(App.get().isClientLoggingEnabled())
    refreshTransportStatus()

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
        refreshTransportStatus()
      }
    }
  }

  fun refreshAwgPeers(onComplete: () -> Unit) {
    _isRefreshingAwgPeers.value = true
    val client = Client(viewModelScope)
    client.awgSyncPeers { result ->
      result
          .onSuccess { awgPeers: List<AwgPeerResult> ->
            _awgRefreshMessage.value =
                awgRefreshMessage(awgPeers, AwgRefreshFeedback.USER_REQUESTED)
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

  fun refreshTransportStatus() {
    Client(viewModelScope).packetTransportStatus { result ->
      result.onSuccess { status -> _transportStatus.value = status }
      result.exceptionOrNull()?.let { TSLog.e("SettingsViewModel", "Failed to read packet transport: ${it.message}") }
    }
  }

  fun setPacketTransport(enabled: Boolean, appViewModel: AppViewModel) {
    if (_isChangingTransport.value) return
    val operation = appViewModel.tryBeginAwgWrite()
    if (operation == null) {
      _awgRefreshMessage.value = "An account or network configuration update is already in progress"
      return
    }
    _isChangingTransport.value = true
    val mode = if (enabled) "quic" else "native"
    val expected = if (enabled) "http3-ip" else "native"
    val revision = _transportStatus.value?.revision.orEmpty()
    Client(appViewModel.viewModelScope).setPacketTransport(mode, revision) { result ->
      val active = result.getOrNull()
      val verified = active?.isActive(expected) == true
      val currentAccount = appViewModel.finishAwgWrite(
          operation = operation,
          writeSucceeded = verified,
          localAwgConfiguredOnSuccess = active?.awgConfigured ?: false,
      )
      _isChangingTransport.value = false
      if (currentAccount) {
        active?.let { _transportStatus.value = it }
        _awgRefreshMessage.value = if (verified) {
          if (enabled) "QUIC is active" else "Native WG/AWG is active"
        } else {
          "Mode change failed: ${result.exceptionOrNull()?.message ?: "the selected engine is not active"}"
        }
      }
      refreshTransportStatus()
    }
  }

  fun toggleIsClientRemoteLoggingEnabled() {
    isClientRemoteLoggingEnabled.set(!isClientRemoteLoggingEnabled.value)
    App.get().updateIsClientLoggingEnabled(isClientRemoteLoggingEnabled.value)
  }
}
