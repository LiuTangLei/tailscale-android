// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.ComposableStringFormatter
import com.tailscale.ipn.ui.util.set
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PeerSettingInfo(val titleRes: Int, val value: ComposableStringFormatter)

class PeerDetailsViewModelFactory(
    private val nodeId: StableNodeID,
    private val filesDir: File,
    private val pingViewModel: PingViewModel
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PeerDetailsViewModel(nodeId, filesDir, pingViewModel) as T
  }
}

class PeerDetailsViewModel(
    val nodeId: StableNodeID,
    val filesDir: File,
    val pingViewModel: PingViewModel
) : IpnViewModel() {
  val node: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)
  val isPinging: StateFlow<Boolean> = MutableStateFlow(false)

  // AWG configuration for this peer
  private val _awgConfig = MutableStateFlow<AwgPeerResult?>(null)
  val awgConfig: StateFlow<AwgPeerResult?> = _awgConfig
  private var loadedAwgForNodeKey: String? = null
  private var loadedAwgOnline: Boolean? = null
  private var lastAwgPeerIdentity: Pair<String, Boolean?>? = null
  private var awgLookupGeneration = 0L
  private var awgRetryAttempt = 0
  private var awgRetryJob: Job? = null
  private var awgNetmapSelfKey: String? = null

  private fun loadAwgConfigForPeer(peer: Tailcfg.Node, force: Boolean = false) {
    val identity = peer.Key to peer.Online
    if (identity != lastAwgPeerIdentity) {
      lastAwgPeerIdentity = identity
      awgRetryAttempt = 0
      awgRetryJob?.cancel()
      awgRetryJob = null
    }
    if (!force && loadedAwgForNodeKey == peer.Key && loadedAwgOnline == peer.Online) return

    // A topology update can supersede a delayed retry. Its fresh request takes precedence.
    awgRetryJob?.cancel()
    awgRetryJob = null
    loadedAwgForNodeKey = peer.Key
    loadedAwgOnline = peer.Online
    val requestGeneration = ++awgLookupGeneration
    Client(viewModelScope).awgSyncPeers { result ->
      if (!isCurrentAwgPeerLookup(
          requestGeneration,
          awgLookupGeneration,
          peer.Key,
          node.value?.Key,
      )) {
        return@awgSyncPeers
      }
      result
          .onSuccess { peers ->
            val matchingPeer = findAwgPeerResult(peer, peers)
            if (matchingPeer != null) {
              val lastConfirmed =
                  _awgConfig.value?.takeIf {
                    it.nodeKey == peer.Key && it.hasAwgConfig && matchingPeer.lookupFailed
                  }
              _awgConfig.value = lastConfirmed ?: matchingPeer
              if (matchingPeer.lookupFailed && peer.Online == true) {
                scheduleAwgRetry(peer.Key)
              } else {
                awgRetryAttempt = 0
              }
            } else {
              setAwgErrorUnlessConfirmed(
                  peer,
                  "Peer is offline or unavailable for AWG discovery",
              )
              if (peer.Online == true) {
                scheduleAwgRetry(peer.Key)
              }
            }
          }
          .onFailure { error ->
            setAwgErrorUnlessConfirmed(peer, error.message ?: "AWG discovery failed")
            if (peer.Online == true) scheduleAwgRetry(peer.Key)
          }
    }
  }

  private fun clearAwgPeerState() {
    awgRetryJob?.cancel()
    awgRetryJob = null
    awgRetryAttempt = 0
    lastAwgPeerIdentity = null
    loadedAwgForNodeKey = null
    loadedAwgOnline = null
    awgLookupGeneration++
    node.set(null)
    _awgConfig.value = null
  }

  private fun setAwgErrorUnlessConfirmed(peer: Tailcfg.Node, message: String) {
    if (_awgConfig.value?.let { it.nodeKey == peer.Key && it.hasAwgConfig } == true) return
    _awgConfig.value =
        AwgPeerResult(
            nodeKey = peer.Key,
            hostname = peer.displayName,
            error = message,
        )
  }

  private fun scheduleAwgRetry(peerKey: String) {
    if (awgRetryAttempt >= AWG_DETAILS_RETRY_DELAYS_MILLIS.size || awgRetryJob?.isActive == true) {
      return
    }
    val retryDelay = AWG_DETAILS_RETRY_DELAYS_MILLIS[awgRetryAttempt++]
    awgRetryJob =
        viewModelScope.launch {
          delay(retryDelay)
          awgRetryJob = null
          node.value
              ?.takeIf { it.Key == peerKey && it.Online == true }
              ?.let { loadAwgConfigForPeer(it, force = true) }
        }
  }

  init {
    viewModelScope.launch {
      Notifier.netmap.collect { nm ->
        netmap.set(nm)
        if (nm == null) {
          awgNetmapSelfKey = null
          clearAwgPeerState()
          return@collect
        }
        if (awgNetmapSelfKey != null && awgNetmapSelfKey != nm.SelfNode.Key) {
          clearAwgPeerState()
        }
        awgNetmapSelfKey = nm.SelfNode.Key
        val peer = nm.getPeer(nodeId)
        if (peer == null) {
          clearAwgPeerState()
          return@collect
        }
        node.set(peer)
        loadAwgConfigForPeer(peer)
      }
    }
  }

  fun startPing() {
    isPinging.set(true)
    node.value?.let { this.pingViewModel.startPing(it) }
  }

  fun onPingDismissal() {
    isPinging.set(false)
    this.pingViewModel.handleDismissal()
  }
}

private val AWG_DETAILS_RETRY_DELAYS_MILLIS = longArrayOf(2_000L, 5_000L)

internal fun isCurrentAwgPeerLookup(
    requestGeneration: Long,
    currentGeneration: Long,
    requestNodeKey: String,
    currentNodeKey: String?,
): Boolean = requestGeneration == currentGeneration && requestNodeKey == currentNodeKey

internal fun findAwgPeerResult(
    peer: Tailcfg.Node,
    peers: List<AwgPeerResult>,
): AwgPeerResult? {
  val names =
      listOf(peer.Hostinfo.Hostname, peer.ComputedName, peer.Name).filterNotNull().map {
        it.trim().trimEnd('.').substringBefore('.').lowercase()
      }
  peers
      .find { it.nodeKey == peer.Key }
      ?.let {
        return it
      }
  peer.primaryIPv4Address?.let { address ->
    peers
        .find { it.nodeKey.isBlank() && it.tailscaleIP == address }
        ?.let {
          return it
        }
  }

  // Hostname fallback is only for older LocalAPI responses that omitted stable identity fields.
  return peers.find { result ->
    result.nodeKey.isBlank() &&
        result.tailscaleIP.isNullOrBlank() &&
        result.hostname.trim().trimEnd('.').substringBefore('.').lowercase() in names
  }
}
