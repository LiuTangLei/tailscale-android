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

  private fun loadAwgConfigForPeer(peer: Tailcfg.Node) {
    if (loadedAwgForNodeKey == peer.Key) return
    loadedAwgForNodeKey = peer.Key
    Client(viewModelScope).awgSyncPeers { result ->
      result
          .onSuccess { peers ->
            val names =
                listOf(peer.Hostinfo.Hostname, peer.ComputedName, peer.Name).filterNotNull().map {
                  it.trim().trimEnd('.').substringBefore('.').lowercase()
                }
            _awgConfig.value =
                peers.find { result ->
                  result.nodeKey == peer.Key ||
                      result.tailscaleIP == peer.primaryIPv4Address ||
                      result.hostname.trim().trimEnd('.').substringBefore('.').lowercase() in names
                }
                    ?: AwgPeerResult(
                        nodeKey = peer.Key,
                        hostname = peer.displayName,
                        error = "Peer is offline or unavailable for AWG discovery",
                    )
          }
          .onFailure { error ->
            loadedAwgForNodeKey = null
            _awgConfig.value =
                AwgPeerResult(
                    nodeKey = peer.Key,
                    hostname = peer.displayName,
                    error = error.message ?: "AWG discovery failed",
                )
          }
    }
  }

  init {
    viewModelScope.launch {
      Notifier.netmap.collect { nm ->
        netmap.set(nm)
        nm?.getPeer(nodeId)?.let { peer ->
          node.set(peer)
          loadAwgConfigForPeer(peer)
        }
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
