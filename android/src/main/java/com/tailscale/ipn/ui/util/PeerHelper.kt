// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.ui.util.fastAny
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID

data class PeerSet(
    val userID: UserID,
    val user: Tailcfg.UserProfile?,
    val peers: List<Tailcfg.Node>
)

class PeerCategorizer {
  var peerSets: List<PeerSet> = emptyList()
  var lastSearchResult: List<PeerSet> = emptyList()
  var lastSearchTerm: String = ""

  fun regenerateGroupedPeers(netmap: Netmap.NetworkMap) {
    val peers: List<Tailcfg.Node> = netmap.Peers ?: return
    val selfNode = netmap.SelfNode
    var grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()

    val mdm = MDMSettings.hiddenNetworkDevices.flow.value.value
    val hideMyDevices = mdm?.contains("current-user") ?: false
    val hideOtherDevices = mdm?.contains("other-users") ?: false
    val hideTaggedDevices = mdm?.contains("tagged-devices") ?: false

    val me = netmap.currentUserProfile()

    // Headscale and older control servers can include the local node in Peers as well as
    // SelfNode. Compose requires every LazyColumn key to be unique, and showing that duplicate
    // would otherwise crash the app during an in-place upgrade. Put SelfNode first so its
    // authoritative local fields win, then also discard any duplicate peer entries defensively.
    val uniquePeers =
        (listOf(selfNode) + peers).distinctBy { peer ->
          when {
            peer.StableID.isNotEmpty() -> "stable:${peer.StableID}"
            peer.Key.isNotEmpty() -> "key:${peer.Key}"
            else -> "fallback:${peer.User}:${peer.Name}:${peer.Addresses.orEmpty().joinToString()}"
          }
        }

    for (peer in uniquePeers) {

      val userId = peer.User
      val profile = netmap.userProfile(userId)

      // Mullvad nodes should not be shown in the peer list
      if (peer.isMullvadNode) {
        continue
      }

      // Hide devices based on MDM settings
      if (hideMyDevices && userId == me?.ID) {
        continue
      }

      if (hideOtherDevices && userId != me?.ID) {
        continue
      }

      if (hideTaggedDevices && (profile?.isTaggedDevice() == true)) {
        continue
      }

      if (!grouped.containsKey(userId)) {
        grouped[userId] = mutableListOf()
      }
      grouped[userId]?.add(peer)
    }

    peerSets =
        grouped
            .map { (userId, peers) ->
              val profile = netmap.userProfile(userId)
              PeerSet(
                  userId,
                  profile,
                  peers.sortedWith { a, b ->
                    when {
                      a.StableID == b.StableID -> 0
                      a.isSelfNode(netmap) -> -1
                      b.isSelfNode(netmap) -> 1
                      else ->
                          (a.ComputedName?.lowercase() ?: "").compareTo(
                              b.ComputedName?.lowercase() ?: "")
                    }
                  })
            }
            .sortedBy {
              if (it.user?.ID == me?.ID) {
                ""
              } else {
                it.user?.DisplayName?.lowercase() ?: "unknown user"
              }
            }
  }

  fun groupedAndFilteredPeers(searchTerm: String = ""): List<PeerSet> {
    if (searchTerm.isEmpty()) {
      return peerSets
    }

    if (searchTerm == this.lastSearchTerm) {
      return lastSearchResult
    }

    // We can optimize out typing... If the search term starts with the last search term, we can
    // just search the last result
    val setsToSearch =
        if (this.lastSearchTerm.isNotEmpty() && searchTerm.startsWith(this.lastSearchTerm))
            lastSearchResult
        else peerSets
    this.lastSearchTerm = searchTerm

    val matchingSets =
        setsToSearch
            .map { peerSet ->
              val user = peerSet.user
              val peers = peerSet.peers

              val userMatches = user?.DisplayName?.contains(searchTerm, ignoreCase = true) ?: false
              if (userMatches) {
                return@map peerSet
              }

              val matchingPeers =
                  peers.filter {
                    it.displayName.contains(searchTerm, ignoreCase = true) ||
                        (it.Addresses ?: emptyList()).fastAny { addr -> addr.contains(searchTerm) }
                  }
              if (matchingPeers.isNotEmpty()) {
                PeerSet(peerSet.userID, user, matchingPeers)
              } else {
                null
              }
            }
            .filterNotNull()
    lastSearchResult = matchingSets
    return matchingSets
  }
}
