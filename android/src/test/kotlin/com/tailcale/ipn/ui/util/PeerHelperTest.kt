// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerCategorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PeerHelperTest {
  @Test
  fun duplicateSelfFromControlServerIsShownOnce() {
    val stalePeerCopy = Tailcfg.Node(ID = 150, StableID = "150", User = 1, Name = "old-name")
    val selfNode =
        Tailcfg.Node(
            ID = 150, StableID = "150", User = 1, Key = "nodekey:self", Name = "current-name")
    val otherPeer = Tailcfg.Node(ID = 151, StableID = "151", User = 1, Name = "peer")
    val netmap =
        Netmap.NetworkMap(
            SelfNode = selfNode,
            Peers = listOf(stalePeerCopy, otherPeer),
            Domain = "example.test",
            UserProfiles = mapOf("1" to Tailcfg.UserProfile(ID = 1, DisplayName = "User")),
            TKAEnabled = false)

    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(netmap)

    val peers = categorizer.peerSets.single().peers
    assertEquals(listOf("150", "151"), peers.map(Tailcfg.Node::StableID))
    assertSame(selfNode, peers.first())
  }
}
