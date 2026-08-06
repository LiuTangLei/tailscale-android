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
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.model.AwgRefreshFeedback
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.awgRefreshMessage
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val AWG_REFRESH_RETRY_DELAYS_MILLIS = longArrayOf(2_000L, 5_000L)

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

  // AWG results are indexed by full node key, Tailscale IP, and hostname aliases. Using the full
  // key first avoids collisions between duplicate/renamed hosts and keeps sync compatible with the
  // LocalAPI contract.
  private val _awgPeersStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
  val awgPeersStatus: StateFlow<Map<String, Boolean>> = _awgPeersStatus

  // AWG user-action result message for toast. Background discovery stays silent.
  private val _awgStatusMessage = MutableStateFlow<String?>(null)
  val awgStatusMessage: StateFlow<String?> = _awgStatusMessage

  // AWG peers data - normalized peer key to full peer data mapping
  private val _awgPeersData = MutableStateFlow<Map<String, AwgPeerResult>>(emptyMap())
  val awgPeersData: StateFlow<Map<String, AwgPeerResult>> = _awgPeersData

  // Every AWG prefs writer shares the application-scoped operation lock.
  val awgSyncInProgress: StateFlow<AwgWriteOperation?> = appViewModel.awgWriteInProgress
  val awgProfileMutationInProgress: StateFlow<AwgProfileMutationOperation?> =
      appViewModel.awgProfileMutationInProgress
  val awgProfileSyncReady: StateFlow<Boolean> = appViewModel.awgProfileSyncReady

  private var awgPeersLoading = false
  private var awgPeerFingerprint: String? = null
  private var awgRefreshPending = false
  private var awgPendingFeedback = AwgRefreshFeedback.SILENT
  private var awgSilentRetryAttempt = 0
  private var awgRetryJob: Job? = null
  private var awgTopologyAvailable = false
  private var awgTopologyGeneration = 0L
  private var awgProfileIdentity: String? = null
  private var awgPeersProfileGeneration: Long? = null

  // Local machine AWG configuration status
  val localAwgStatus: StateFlow<Boolean> = appViewModel.localAwgConfigured

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
      var observedProfileGeneration = appViewModel.awgProfileGeneration
      appViewModel.awgProfileGenerationState.collect { profileGeneration ->
        if (profileGeneration != observedProfileGeneration) {
          observedProfileGeneration = profileGeneration
          invalidateAwgPeerProfileCache()
          if (appViewModel.awgProfileSyncReady.value &&
              appViewModel.awgProfileMutationInProgress.value == null) {
            resumeAwgDiscoveryFromCurrentNetmap()
          }
        }
      }
    }
    viewModelScope.launch {
      appViewModel.awgProfileSyncReady.collect { ready ->
        if (ready && appViewModel.awgProfileMutationInProgress.value == null) {
          resumeAwgDiscoveryFromCurrentNetmap()
        }
      }
    }
    viewModelScope.launch {
      Notifier.netmap.collect { netmap ->
        if (netmap == null) {
          invalidateAwgPeerTopology()
          return@collect
        }
        val newProfileIdentity =
            listOf(netmap.Domain, netmap.SelfNode.StableID, netmap.SelfNode.User.toString())
                .joinToString("\u0000")
        val profileChanged = awgProfileIdentity != null && awgProfileIdentity != newProfileIdentity
        if (profileChanged) {
          invalidateAwgPeerTopology()
        }
        awgProfileIdentity = newProfileIdentity

        searchJob?.cancel()
        launch(Dispatchers.Default) {
          peerCategorizer.regenerateGroupedPeers(netmap)
          val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
          _peers.value = peerCategorizer.peerSets
          _searchViewPeers.value = filteredPeers
        }
        if (appViewModel.awgProfileSyncReady.value &&
            appViewModel.awgProfileMutationInProgress.value == null) {
          refreshAwgPeerTopology(netmap)
        } else {
          awgTopologyAvailable = false
        }
        if (netmap.SelfNode.keyDoesNotExpire) {
          showExpiry.set(false)
          return@collect
        } else {
          val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
          val window =
              expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
          val expiresSoon =
              TimeUtil.isWithinExpiryNotificationWindow(window, netmap.SelfNode.KeyExpiry ?: "")
          showExpiry.set(expiresSoon)
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
            currentState != Ipn.State.Running -> showVPNPermissionLauncherIfUnauthorized()
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

  fun loadAwgPeersStatus(feedback: AwgRefreshFeedback = AwgRefreshFeedback.SILENT) {
    if (!awgTopologyAvailable ||
        !appViewModel.awgProfileSyncReady.value ||
        appViewModel.awgProfileMutationInProgress.value != null) {
      return
    }
    if (feedback == AwgRefreshFeedback.USER_REQUESTED) {
      awgRetryJob?.cancel()
      awgRetryJob = null
      awgSilentRetryAttempt = 0
    }
    if (awgPeersLoading) {
      awgRefreshPending = true
      awgPendingFeedback = strongerAwgFeedback(awgPendingFeedback, feedback)
      return
    }
    awgRetryJob?.cancel()
    awgRetryJob = null
    awgPeersLoading = true
    val requestToken =
        AwgRefreshRequestToken(
            topologyGeneration = awgTopologyGeneration,
            fingerprint = awgPeerFingerprint,
            profileGeneration = appViewModel.awgProfileGeneration,
        )
    Client(viewModelScope).awgSyncPeers { result ->
      awgPeersLoading = false
      val responseIsCurrent =
          isCurrentAwgRefresh(
              requestToken,
              awgTopologyGeneration,
              awgPeerFingerprint,
              awgTopologyAvailable,
              appViewModel.awgProfileGeneration,
          )
      var shouldRetrySilently = false

      if (responseIsCurrent) {
        result
            .onSuccess { peers ->
              val merged = mergeAwgPeerResults(_awgPeersData.value, peers)
              _awgPeersStatus.value = merged.status
              _awgPeersData.value = merged.data
              awgPeersProfileGeneration = requestToken.profileGeneration
              shouldRetrySilently = peers.any(AwgPeerResult::lookupFailed)
              awgRefreshMessage(peers, feedback)?.let { _awgStatusMessage.value = it }
            }
            .onFailure { error ->
              // Preserve the last confirmed map. Treating a transport failure as an empty result
              // makes previously confirmed AWG peers appear to switch to standard WireGuard.
              shouldRetrySilently = true
              TSLog.e("MainViewModel", "Failed to load AWG peers: ${error.message}")
              if (feedback == AwgRefreshFeedback.USER_REQUESTED) {
                _awgStatusMessage.value = "Could not refresh AWG peers: ${error.message}"
              }
            }
      }

      val shouldRerun = awgRefreshPending && awgTopologyAvailable
      if (shouldRerun) {
        val rerunFeedback = strongerAwgFeedback(feedback, awgPendingFeedback)
        awgRefreshPending = false
        awgPendingFeedback = AwgRefreshFeedback.SILENT
        loadAwgPeersStatus(rerunFeedback)
      } else if (shouldRetrySilently) {
        scheduleAwgPeersRetry()
      } else {
        awgSilentRetryAttempt = 0
      }
    }
  }

  private fun invalidateAwgPeerTopology() {
    awgProfileIdentity = null
    invalidateAwgPeerProfileCache()
  }

  private fun invalidateAwgPeerProfileCache() {
    awgTopologyAvailable = false
    awgTopologyGeneration++
    awgPeerFingerprint = null
    awgPeersProfileGeneration = null
    awgRefreshPending = false
    awgPendingFeedback = AwgRefreshFeedback.SILENT
    awgRetryJob?.cancel()
    awgRetryJob = null
    awgSilentRetryAttempt = 0
    _awgPeersStatus.value = emptyMap()
    _awgPeersData.value = emptyMap()
    _awgStatusMessage.value = null
  }

  private fun resumeAwgDiscoveryFromCurrentNetmap() {
    val netmap = Notifier.netmap.value ?: return
    if (!appViewModel.awgProfileSyncReady.value ||
        appViewModel.awgProfileMutationInProgress.value != null) {
      return
    }
    refreshAwgPeerTopology(netmap)
  }

  private fun refreshAwgPeerTopology(netmap: Netmap.NetworkMap) {
    awgTopologyAvailable = true
    val newFingerprint = awgPeerFingerprint(netmap)
    if (newFingerprint != awgPeerFingerprint ||
        awgPeersProfileGeneration != appViewModel.awgProfileGeneration) {
      awgPeerFingerprint = newFingerprint
      awgRetryJob?.cancel()
      awgRetryJob = null
      awgSilentRetryAttempt = 0
      loadAwgPeersStatus()
    }
    loadLocalAwgStatus()
  }

  private fun scheduleAwgPeersRetry() {
    if (awgSilentRetryAttempt >= AWG_REFRESH_RETRY_DELAYS_MILLIS.size) return
    val expectedFingerprint = awgPeerFingerprint
    val retryDelay = AWG_REFRESH_RETRY_DELAYS_MILLIS[awgSilentRetryAttempt++]
    awgRetryJob =
        viewModelScope.launch {
          delay(retryDelay)
          if (expectedFingerprint == awgPeerFingerprint && appViewModel.awgProfileSyncReady.value) {
            awgRetryJob = null
            loadAwgPeersStatus(AwgRefreshFeedback.SILENT)
          }
        }
  }

  fun loadLocalAwgStatus() {
    if (!appViewModel.awgProfileSyncReady.value ||
        appViewModel.awgProfileMutationInProgress.value != null) {
      return
    }
    val client = Client(viewModelScope)
    val topologyGeneration = awgTopologyGeneration
    val prefsReadToken = appViewModel.beginAwgPrefsRead(AwgPrefsReader.MAIN)
    TSLog.d("MainViewModel", "Loading local AWG configuration status")
    client.getLocalPrefs { result ->
      if (!awgTopologyAvailable ||
          topologyGeneration != awgTopologyGeneration ||
          !appViewModel.isCurrentAwgPrefsRead(prefsReadToken)) {
        return@getLocalPrefs
      }
      result
          .onSuccess { prefs ->
            val hasLocalAwg = prefs.AmneziaWG?.hasNonDefaultValues() == true
            if (!appViewModel.commitLocalAwgStatus(prefsReadToken, hasLocalAwg)) {
              return@getLocalPrefs
            }
            TSLog.d("MainViewModel", "Local AWG status loaded: hasAwgConfig=$hasLocalAwg")
          }
          .onFailure { error ->
            TSLog.e("MainViewModel", "Failed to load local AWG status: ${error.message}")
            // Keep the last confirmed value. A transient LocalAPI failure must not be displayed as
            // "standard WireGuard" or overwrite the status of an inherited profile.
          }
    }
  }

  fun clearAwgStatusMessage() {
    _awgStatusMessage.value = null
  }

  fun syncAwgConfigFromPeer(peer: Tailcfg.Node, timeout: Int = 10) {
    val hostname = peer.displayName
    val currentPeer = currentNetmapPeer(peer, Notifier.netmap.value)
    if (currentPeer == null) {
      _awgStatusMessage.value = "AWG peer $hostname is no longer in the current network map"
      return
    }
    if (!canUseAwgPeerCache(
        cacheProfileGeneration = awgPeersProfileGeneration,
        currentProfileGeneration = appViewModel.awgProfileGeneration,
        profileReady = appViewModel.awgProfileSyncReady.value,
        selectedPeerIsCurrent = true,
    )) {
      _awgStatusMessage.value = "AWG status for $hostname is stale; refresh and retry"
      return
    }
    val peerData = getAwgConfigForPeer(currentPeer)
    if (peerData == null) {
      _awgStatusMessage.value = "AWG status for $hostname is not loaded; refresh and retry"
      return
    }
    if (peerData.lookupFailed) {
      _awgStatusMessage.value = "Could not check $hostname: ${peerData.error}"
      return
    }
    if (!peerData.hasAwgConfig) {
      _awgStatusMessage.value = "$hostname uses standard WireGuard"
      return
    }
    val fullNodeKey = awgSyncNodeKey(currentPeer, peerData)
    if (fullNodeKey == null) {
      _awgStatusMessage.value = "AWG identity for $hostname changed; refresh and retry"
      return
    }
    val writeOperation = appViewModel.tryBeginAwgWrite(currentPeer.StableID)
    if (writeOperation == null) {
      _awgStatusMessage.value = "An account or AWG configuration update is already in progress"
      return
    }
    Client(appViewModel.viewModelScope).awgSyncApply(fullNodeKey, timeout) { result ->
      val appliedConfig = result.getOrNull()
      if (!appViewModel.finishAwgWrite(
          operation = writeOperation,
          writeSucceeded = result.isSuccess,
          localAwgConfiguredOnSuccess = appliedConfig?.hasNonDefaultValues() == true,
      )) {
        return@awgSyncApply
      }
      if (!viewModelScope.isActive) return@awgSyncApply
      result
          .onSuccess { config ->
            _awgStatusMessage.value = "${config.versionLabel()} synced from $hostname"
            // EditPrefs performs a live wgengine reconfiguration; restarting VpnService here can
            // interrupt the control connection during a mobile upgrade.
          }
          .onFailure { error ->
            TSLog.e("MainViewModel", "Failed to apply AWG config from $hostname: ${error.message}")
            _awgStatusMessage.value = parseAwgApplyError(error, hostname)
          }
    }
  }

  fun getAwgConfigForPeer(peer: Tailcfg.Node): AwgPeerResult? =
      findIndexedAwgPeerResult(peer, _awgPeersData.value)

  fun hasAwgConfigForPeer(peer: Tailcfg.Node, data: Map<String, AwgPeerResult>): Boolean =
      canUseAwgPeerCache(
          cacheProfileGeneration = awgPeersProfileGeneration,
          currentProfileGeneration = appViewModel.awgProfileGeneration,
          profileReady = appViewModel.awgProfileSyncReady.value,
          selectedPeerIsCurrent = currentNetmapPeer(peer, Notifier.netmap.value) != null,
      ) && indexedAwgStatus(peer, data)

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }

  private fun parseAwgApplyError(
      error: Throwable,
      hostname: String,
  ): String {
    val message = error.message ?: ""
    TSLog.e("MainViewModel", "Raw error message: $message")
    return when {
      message.contains("405") || message.contains("only POST allowed") ->
          "Request method error, only POST allowed"
      message.contains("403") ||
          message.contains("access denied") ||
          message.contains("awg-sync-apply access denied") ->
          "Access denied, cannot apply AWG config"
      message.contains("400") || message.contains("invalid JSON") ->
          when {
            message.contains("nodeKey required") -> "NodeKey cannot be empty"
            message.contains("invalid JSON") ->
                "Request format error - JSON parsing failed: $message"
            else -> "Request parameter error - Details: $message"
          }
      message.contains("404") || message.contains("peer not found") ->
          "Target peer $hostname not in network or offline"
      message.contains("409") ||
          message.contains("no Amnezia-WG config") ||
          message.contains("peer has no Amnezia-WG config") ->
          "Target peer $hostname has no AWG config"
      message.contains("500") ->
          when {
            message.contains("no netmap available") ->
                "Network map unavailable, please try again later"
            message.contains("failed to fetch config") -> "Cannot fetch config from target peer"
            message.contains("failed to apply config") ->
                "Config apply failed, please check permissions"
            else -> "Server internal error: $message"
          }
      message.contains("timeout") || message.contains("Timeout") ->
          "Operation timeout, please retry"
      message.contains("no netmap available") ->
          "Network connection unavailable, please check network status"
      message.contains("failed to fetch config") ->
          "Cannot fetch config from peer $hostname, please check peer status"
      message.contains("failed to apply config") ->
          "Apply config failed, please check local permissions"
      else -> "AWG config apply failed, raw error: $message"
    }
  }
}

internal data class AwgPeerDiscoveryIndex(
    val status: Map<String, Boolean>,
    val data: Map<String, AwgPeerResult>,
)

internal data class AwgRefreshRequestToken(
    val topologyGeneration: Long,
    val fingerprint: String?,
    val profileGeneration: Long,
)

internal fun isCurrentAwgRefresh(
    request: AwgRefreshRequestToken,
    topologyGeneration: Long,
    fingerprint: String?,
    topologyAvailable: Boolean,
    profileGeneration: Long,
): Boolean =
    topologyAvailable &&
        request.topologyGeneration == topologyGeneration &&
        request.fingerprint == fingerprint &&
        request.profileGeneration == profileGeneration

internal fun awgPeerFingerprint(netmap: Netmap.NetworkMap): String {
  val peers =
      (netmap.Peers ?: emptyList())
          .filter { it.Online == true }
          .map {
            listOf(it.Key, it.primaryIPv4Address, it.Name, it.ComputedName).joinToString(
                "\u0000") { value ->
                  value.orEmpty()
                }
          }
          .sorted()
          .joinToString("\u0001")
  return "${netmap.SelfNode.Key}\u0002$peers"
}

internal fun canUseAwgPeerCache(
    cacheProfileGeneration: Long?,
    currentProfileGeneration: Long,
    profileReady: Boolean,
    selectedPeerIsCurrent: Boolean,
): Boolean =
    profileReady &&
        selectedPeerIsCurrent &&
        cacheProfileGeneration != null &&
        cacheProfileGeneration == currentProfileGeneration

internal fun currentNetmapPeer(
    selected: Tailcfg.Node,
    netmap: Netmap.NetworkMap?,
): Tailcfg.Node? {
  val peers = netmap?.Peers ?: return null
  if (selected.StableID.isNotBlank()) {
    val current = peers.firstOrNull { it.StableID == selected.StableID } ?: return null
    if (selected.Key.isNotBlank() && current.Key != selected.Key) return null
    return current
  }
  if (selected.Key.isNotBlank()) return peers.firstOrNull { it.Key == selected.Key }
  return peers.firstOrNull { candidate ->
    selected.primaryIPv4Address != null &&
        candidate.primaryIPv4Address == selected.primaryIPv4Address
  }
}

private fun peerKeyCandidates(value: String): List<String> {
  val trimmed = value.trim().trimEnd('.')
  val short = trimmed.substringBefore('.')
  return listOf(trimmed, trimmed.lowercase(), short, short.lowercase())
      .filter(String::isNotEmpty)
      .distinct()
}

internal fun peerKeyCandidates(peer: Tailcfg.Node): List<String> =
    buildList {
          add(peer.Key)
          peer.primaryIPv4Address?.let(::add)
          addAll(peerKeyCandidates(peer.Hostinfo.Hostname ?: peer.ComputedName ?: peer.Name))
          addAll(peerKeyCandidates(peer.Name))
        }
        .filter(String::isNotEmpty)
        .distinct()

private fun legacyPeerKeyCandidates(peer: Tailcfg.Node): List<String> =
    buildList {
          peer.primaryIPv4Address?.let(::add)
          addAll(peerKeyCandidates(peer.Hostinfo.Hostname ?: peer.ComputedName ?: peer.Name))
          addAll(peerKeyCandidates(peer.Name))
        }
        .filter(String::isNotEmpty)
        .distinct()

internal fun resultKeyCandidates(peer: AwgPeerResult): List<String> =
    buildList {
          add(peer.nodeKey)
          peer.tailscaleIP?.let(::add)
          addAll(peerKeyCandidates(peer.hostname))
        }
        .filter(String::isNotEmpty)
        .distinct()

/**
 * Indexes a fresh discovery result while retaining a previously confirmed AWG configuration when
 * the same peer has a transient lookup error. Peers omitted by the fresh response are deliberately
 * removed, so offline nodes do not remain visible forever.
 */
internal fun mergeAwgPeerResults(
    previousData: Map<String, AwgPeerResult>,
    peers: List<AwgPeerResult>,
): AwgPeerDiscoveryIndex {
  val status = linkedMapOf<String, Boolean>()
  val data = linkedMapOf<String, AwgPeerResult>()
  peers.forEach { peer ->
    val previous =
        if (peer.lookupFailed) {
          previousAwgResult(previousData, peer)
        } else {
          null
        }
    val effective = previous?.takeIf(AwgPeerResult::hasAwgConfig) ?: peer
    resultKeyCandidates(peer).forEach { key ->
      status.putIfAbsent(key, effective.hasAwgConfig)
      data.putIfAbsent(key, effective)
    }
  }
  return AwgPeerDiscoveryIndex(status = status, data = data)
}

private fun previousAwgResult(
    previousData: Map<String, AwgPeerResult>,
    peer: AwgPeerResult,
): AwgPeerResult? {
  // A modern response with a node key must only inherit data for that exact identity. Reusing an
  // old Tailscale IP after re-enrollment must not transfer an AWG badge or stale sync target.
  if (peer.nodeKey.isNotBlank()) return previousData[peer.nodeKey]

  // Identity-less legacy LocalAPI responses can only be correlated by IP or hostname.
  peer.tailscaleIP?.takeIf(String::isNotBlank)?.let {
    previousData[it]?.let { old ->
      return old
    }
  }

  // A hostname is not an identity: a newly enrolled node may reuse an old device name. Only legacy
  // responses that also omit the IP need this final compatibility path.
  if (!peer.tailscaleIP.isNullOrBlank()) return null
  return peerKeyCandidates(peer.hostname).firstNotNullOfOrNull(previousData::get)
}

/**
 * Resolves a modern selected peer only by its exact node key. IP/hostname fallback is retained only
 * when either side lacks a node identity, for compatibility with historical LocalAPI responses.
 */
internal fun findIndexedAwgPeerResult(
    peer: Tailcfg.Node,
    data: Map<String, AwgPeerResult>,
): AwgPeerResult? {
  if (peer.Key.isNotBlank()) {
    data[peer.Key]
        ?.takeIf { it.nodeKey == peer.Key }
        ?.let {
          return it
        }
    return legacyPeerKeyCandidates(peer).firstNotNullOfOrNull { candidate ->
      data[candidate]?.takeIf { it.nodeKey.isBlank() }
    }
  }
  return legacyPeerKeyCandidates(peer).firstNotNullOfOrNull(data::get)
}

internal fun indexedAwgStatus(peer: Tailcfg.Node, data: Map<String, AwgPeerResult>): Boolean =
    findIndexedAwgPeerResult(peer, data)?.hasAwgConfig == true

/** Revalidates the selected identity immediately before calling the mutating sync endpoint. */
internal fun awgSyncNodeKey(peer: Tailcfg.Node, result: AwgPeerResult): String? {
  val selectedNodeKey = peer.Key.takeIf { it.startsWith("nodekey:") }
  if (selectedNodeKey != null) {
    if (result.nodeKey.isNotBlank() && result.nodeKey != selectedNodeKey) return null
    return selectedNodeKey
  }
  return result.nodeKey.takeIf { it.startsWith("nodekey:") }
}

private fun strongerAwgFeedback(
    first: AwgRefreshFeedback,
    second: AwgRefreshFeedback,
): AwgRefreshFeedback =
    if (first == AwgRefreshFeedback.USER_REQUESTED || second == AwgRefreshFeedback.USER_REQUESTED) {
      AwgRefreshFeedback.USER_REQUESTED
    } else {
      AwgRefreshFeedback.SILENT
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
