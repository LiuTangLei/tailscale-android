// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.Duration

class MainViewModelFactory(private val appViewModel: AppViewModel) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(appViewModel) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(private val appViewModel: AppViewModel) : IpnViewModel() {
  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))
  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState
  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  var isToggleInProgress = MutableStateFlow(false)
  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission
  // Select Taildrop directory
  private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers
  // The list of peers
  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers
  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state
  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm
  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)
  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  val isVpnPrepared: StateFlow<Boolean> = appViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = appViewModel.vpnActive

  var searchJob: Job? = null

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

    // AWG peers status - nodeKey to hasAwgConfig mapping
    private val _awgPeersStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val awgPeersStatus: StateFlow<Map<String, Boolean>> = _awgPeersStatus

    // AWG status message for toast
    private val _awgStatusMessage = MutableStateFlow<String?>(null)
    val awgStatusMessage: StateFlow<String?> = _awgStatusMessage

    // AWG peers data - hostname to full peer data mapping
    private val _awgPeersData = MutableStateFlow<Map<String, AwgPeerResult>>(emptyMap())
    val awgPeersData: StateFlow<Map<String, AwgPeerResult>> = _awgPeersData

    // AWG sync operation status
    private val _awgSyncInProgress = MutableStateFlow<String?>(null) // hostname of peer being synced
    val awgSyncInProgress: StateFlow<String?> = _awgSyncInProgress

    // Flag to prevent multiple AWG peers requests
    private var awgPeersLoaded = false

    // Local machine AWG configuration status
    private val _localAwgStatus = MutableStateFlow<Boolean>(false)
    val localAwgStatus: StateFlow<Boolean> = _localAwgStatus

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIpAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  // Returns true if we should skip all of the user-interactive permissions prompts
  // (with the exception of the VPN permission prompt)
  fun skipPromptsForAuthKeyLogin(): Boolean {
    val v = MDMSettings.authKey.flow.value.value
    return v != null && v != ""
  }

  private val peerCategorizer = PeerCategorizer()

  init {
    viewModelScope.launch {
      var previousState: State? = null
      combine(Notifier.state, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            stateRes.set(userStringRes(currentState, previousState, active))
            // Determine if the VPN toggle should be on
            val isOn =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true
                  previousState == State.NoState && currentState == State.Starting -> true
                  else -> false
                }
            // Update the VPN toggle state
            _vpnToggleState.value = isOn
            // Update the previous state
            previousState = currentState
          }
    }
    viewModelScope.launch {
      _searchTerm.debounce(250L).collect { term ->
        // run the search as a background task
        searchJob?.cancel()
        searchJob =
            launch(Dispatchers.Default) {
              val filteredPeers = peerCategorizer.groupedAndFilteredPeers(term)
              _searchViewPeers.value = filteredPeers
            }
      }
    }
    viewModelScope.launch {
      Notifier.netmap.collect { it ->
        it?.let { netmap ->
          searchJob?.cancel()
          launch(Dispatchers.Default) {
            peerCategorizer.regenerateGroupedPeers(netmap)
            val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
            _peers.value = peerCategorizer.peerSets
            _searchViewPeers.value = filteredPeers
          }
          // Load AWG peers status when network map changes, but only once
          if (!awgPeersLoaded) {
              loadAwgPeersStatus()
              loadLocalAwgStatus() // Also load local AWG status
              awgPeersLoaded = true
          }
          if (netmap.SelfNode.keyDoesNotExpire) {
            showExpiry.set(false)
            return@let
          } else {
            val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
            val window =
                expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
            val expiresSoon =
                TimeUtil.isWithinExpiryNotificationWindow(window, it.SelfNode.KeyExpiry ?: "")
            showExpiry.set(expiresSoon)
          }
        }
      }
    }
    viewModelScope.launch {
      App.get().healthNotifier?.currentIcon?.collect { icon -> healthIcon.set(icon) }
    }
  }

  fun maybeRequestVpnPermission() {
    _requestVpnPermission.value = true
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    TSLog.d("VpnPermissions", "vpnIntent=$vpnIntent")
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    } else {
      appViewModel.setVpnPrepared(true)
      startVPN()
    }
    _requestVpnPermission.value = false // reset
  }

  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      isToggleInProgress.value = true
      try {
        val currentState = Notifier.state.value

        if (desiredState) {
          // User wants to turn ON the VPN
          when {
            currentState != Ipn.State.Running -> startVPN()
          }
        } else {
          // User wants to turn OFF the VPN
          if (currentState == Ipn.State.Running) {
            stopVPN()
          }
        }
      } finally {
        isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }

  fun enableSearchAutoFocus() {
    autoFocusSearch = true
  }

  fun disableSearchAutoFocus() {
    autoFocusSearch = false
  }

    fun loadAwgPeersStatus() {
        val client = Client(viewModelScope)

        // Add debug logging
        TSLog.d("MainViewModel", "Attempting to call awg-sync-peers API")

        client.awgSyncPeers { result ->
            result
                .onSuccess { awgPeers: List<AwgPeerResult> ->
                    TSLog.d("MainViewModel", "AWG peers API success: ${awgPeers.size} peers returned")

                    // Log each peer's details for debugging
                    awgPeers.forEach { peer ->
                        TSLog.d(
                            "MainViewModel",
                            "AWG peer: hostname=${peer.hostname}, nodeKey=${peer.nodeKey}, hasConfig=${peer.hasAwgConfig}",
                        )
                        // Specifically log nodeKey format for debugging - FULL nodeKey
                        TSLog.d(
                            "MainViewModel",
                            "FULL NodeKey: '${peer.nodeKey}' (length=${peer.nodeKey.length})",
                        )
                        TSLog.d(
                            "MainViewModel",
                            "NodeKey details: starts_with=${peer.nodeKey.take(20)}, contains_colon=${peer.nodeKey.contains(":")}",
                        )
                    }

                    // Convert list to map for easier lookup, using hostname instead of nodeKey
                    val statusMap: Map<String, Boolean> = awgPeers.associate { it.hostname to it.hasAwgConfig }
                    _awgPeersStatus.value = statusMap

                    // Also save the full peer data for detailed view and sync operations
                    val peerDataMap: Map<String, AwgPeerResult> = awgPeers.associateBy { it.hostname }
                    _awgPeersData.value = peerDataMap

                    // Show toast with AWG peers count, using computed property
                    val awgPeersCount = awgPeers.count { it.hasAwgConfig }
                    val totalPeers = awgPeers.size
                    val message =
                        if (totalPeers > 0) {
                            if (awgPeersCount > 0) {
                                "Found $awgPeersCount/$totalPeers peers with AWG config"
                            } else {
                                "Checked $totalPeers peers, no AWG config found"
                            }
                        } else {
                            "No peers found"
                        }
                    _awgStatusMessage.value = message
                }.onFailure { error ->
                    TSLog.e("MainViewModel", "Failed to load AWG peers: ${error.message}")
                    TSLog.e("MainViewModel", "Error details: $error")
                    _awgStatusMessage.value = "Failed to get AWG config info: ${error.message}"
                }
        }
    }

    fun loadLocalAwgStatus() {
        val client = Client(viewModelScope)

        TSLog.d("MainViewModel", "Loading local AWG configuration status")

        client.getLocalPrefs { result ->
            result
                .onSuccess { prefs ->
                    val hasLocalAwg = prefs.AmneziaWG?.hasNonDefaultValues() == true
                    _localAwgStatus.value = hasLocalAwg

                    TSLog.d("MainViewModel", "Local AWG status loaded: hasAwgConfig=$hasLocalAwg")
                    if (hasLocalAwg) {
                        TSLog.d("MainViewModel", "Local AWG config details: ${prefs.AmneziaWG}")
                    }
                }.onFailure { error ->
                    TSLog.e("MainViewModel", "Failed to load local AWG status: ${error.message}")
                    _localAwgStatus.value = false
                }
        }
    }

    fun clearAwgStatusMessage() {
        _awgStatusMessage.value = null
    }

    fun syncAwgConfigFromPeer(
        hostname: String,
        timeout: Int = 10,
    ) {
        val peerData = _awgPeersData.value[hostname]
        if (peerData == null) {
            _awgStatusMessage.value = "Peer $hostname AWG config info not found"
            return
        }

        if (!peerData.hasAwgConfig) {
            _awgStatusMessage.value = "Peer $hostname has no AWG config"
            return
        }

        // Find the full nodeKey from netmap since AWG API returns truncated nodeKey
        val netmap = Notifier.netmap.value
        val fullNodeKey =
            netmap?.let { nm ->
                // Find the peer by hostname and get its full nodeKey
                val allNodes = listOfNotNull(nm.SelfNode) + (nm.Peers ?: emptyList())
                allNodes
                    .find { node ->
                        node.ComputedName == hostname || node.Name == hostname
                    }?.Key
            }

        if (fullNodeKey.isNullOrEmpty()) {
            _awgStatusMessage.value = "Cannot find full nodeKey for peer $hostname"
            TSLog.e("MainViewModel", "Could not find full nodeKey for hostname: $hostname")
            return
        }

        _awgSyncInProgress.value = hostname
        TSLog.d("MainViewModel", "Starting AWG sync from peer: $hostname")
        TSLog.d("MainViewModel", "AWG truncated nodeKey: ${peerData.nodeKey}")
        TSLog.d("MainViewModel", "NetMap full nodeKey: $fullNodeKey")

        val client = Client(viewModelScope)
        client.awgSyncApply(fullNodeKey, timeout) { result ->
            _awgSyncInProgress.value = null

            result
                .onSuccess { appliedConfig ->
                    TSLog.d("MainViewModel", "AWG config applied successfully from $hostname: $appliedConfig")
                    _awgStatusMessage.value = "AWG config from $hostname applied successfully"

                    // Auto-reconnect: disconnect and then reconnect to apply AWG config
                    autoReconnectForAwgConfig()
                }.onFailure { error ->
                    TSLog.e("MainViewModel", "Failed to apply AWG config from $hostname: ${error.message}")

                    // Parse specific error messages based on Go code and HTTP status codes
                    val errorMessage = parseAwgApplyError(error, hostname)
                    _awgStatusMessage.value = errorMessage
                }
        }
    }

    fun getAwgConfigForPeer(hostname: String): AwgPeerResult? = _awgPeersData.value[hostname]

    /**
     * Auto-reconnect to apply AWG configuration changes
     * This disconnects and then reconnects the VPN to ensure AWG config takes effect
     */
    private fun autoReconnectForAwgConfig() {
        viewModelScope.launch {
            try {
                TSLog.d("MainViewModel", "Starting auto-reconnect for AWG config")

                // Step 1: Disconnect
                TSLog.d("MainViewModel", "Disconnecting VPN for AWG config update")
                stopVPN()

                // Step 2: Wait a moment for disconnection to complete
                delay(2000) // 2 seconds

                // Step 3: Reconnect
                TSLog.d("MainViewModel", "Reconnecting VPN with new AWG config")
                startVPN()

                TSLog.d("MainViewModel", "Auto-reconnect for AWG config completed")
            } catch (e: Exception) {
                TSLog.e("MainViewModel", "Auto-reconnect failed: ${e.message}")
                _awgStatusMessage.value = "AWG config applied but auto-reconnect failed: ${e.message}"
            }
        }
    }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }

    /**
     * Parse AWG apply error based on Go code error messages and HTTP status codes
     */
    private fun parseAwgApplyError(
        error: Throwable,
        hostname: String,
    ): String {
        val message = error.message ?: ""

        // For debugging, let's first show the raw error message
        TSLog.e("MainViewModel", "Raw error message: $message")

        return when {
            // HTTP status code 405 - only POST allowed
            message.contains("405") || message.contains("only POST allowed") ->
                "Request method error, only POST allowed"

            // HTTP status code 403 - access denied
            message.contains("403") || message.contains("access denied") || message.contains("awg-sync-apply access denied") ->
                "Access denied, cannot apply AWG config"

            // HTTP status code 400 - bad request
            message.contains("400") || message.contains("invalid JSON") ->
                when {
                    message.contains("nodeKey required") -> "NodeKey cannot be empty"
                    message.contains("invalid JSON") -> "Request format error - JSON parsing failed: $message"
                    else -> "Request parameter error - Details: $message"
                }

            // HTTP status code 404 - peer not found
            message.contains("404") || message.contains("peer not found") ->
                "Target peer $hostname not in network or offline"

            // HTTP status code 409 - peer has no AWG config
            message.contains("409") || message.contains("no Amnezia-WG config") || message.contains("peer has no Amnezia-WG config") ->
                "Target peer $hostname has no AWG config"

            // HTTP status code 500 - server errors
            message.contains("500") ->
                when {
                    message.contains("no netmap available") -> "Network map unavailable, please try again later"
                    message.contains("failed to fetch config") -> "Cannot fetch config from target peer"
                    message.contains("failed to apply config") -> "Config apply failed, please check permissions"
                    else -> "Server internal error: $message"
                }

            // Timeout errors
            message.contains("timeout") || message.contains("Timeout") ->
                "Operation timeout, please retry"

            // Network connection errors
            message.contains("no netmap available") ->
                "Network connection unavailable, please check network status"

            // Failed to fetch config from peer
            message.contains("failed to fetch config") ->
                "Cannot fetch config from peer $hostname, please check peer status"

            // Failed to apply config locally
            message.contains("failed to apply config") ->
                "Apply config failed, please check local permissions"

            // Generic fallback - show raw error for debugging
            else -> "AWG config apply failed, raw error: $message"
        }
    }
}

private fun userStringRes(currentState: State?, previousState: State?, vpnActive: Boolean): Int {
  return when {
    previousState == State.NoState && currentState == State.Starting -> R.string.starting
    currentState == State.NoState -> R.string.placeholder
    currentState == State.InUseOtherUser -> R.string.placeholder
    currentState == State.NeedsLogin ->
        if (vpnActive) R.string.please_login else R.string.connect_to_vpn
    currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
    currentState == State.Stopped -> R.string.stopped
    currentState == State.Starting -> R.string.starting
    currentState == State.Running -> if (vpnActive) R.string.connected else R.string.placeholder
    else -> R.string.placeholder
  }
}
