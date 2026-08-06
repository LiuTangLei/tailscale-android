// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.AwgPeerResult
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgDiscoveryTest {
  private val configured = AmneziaWGPrefs(JC = 1)

  @Test
  fun transientLookupFailureRetainsLastConfirmedAwgConfig() {
    val previous =
        AwgPeerResult(
            nodeKey = "nodekey:peer",
            hostname = "old-name",
            config = configured,
        )
    val previousIndex = mergeAwgPeerResults(emptyMap(), listOf(previous))
    val failed =
        AwgPeerResult(
            nodeKey = "nodekey:peer",
            hostname = "new-name",
            error = "timeout",
        )

    val merged = mergeAwgPeerResults(previousIndex.data, listOf(failed))

    assertTrue(merged.status.getValue("nodekey:peer"))
    assertSame(previous, merged.data.getValue("nodekey:peer"))
    assertSame(previous, merged.data.getValue("new-name"))
  }

  @Test
  fun confirmedStandardWireGuardResultReplacesOldAwgConfig() {
    val previous =
        AwgPeerResult(
            nodeKey = "nodekey:peer",
            hostname = "peer",
            config = configured,
        )
    val previousIndex = mergeAwgPeerResults(emptyMap(), listOf(previous))
    val current = AwgPeerResult(nodeKey = "nodekey:peer", hostname = "peer")

    val merged = mergeAwgPeerResults(previousIndex.data, listOf(current))

    assertFalse(merged.status.getValue("nodekey:peer"))
    assertSame(current, merged.data.getValue("nodekey:peer"))
  }

  @Test
  fun lookupFailureForNewSameNamedNodeDoesNotInheritOldAwgConfig() {
    val oldNode =
        AwgPeerResult(
            nodeKey = "nodekey:old",
            hostname = "reused-name",
            config = configured,
        )
    val previous = mergeAwgPeerResults(emptyMap(), listOf(oldNode))
    val replacement =
        AwgPeerResult(
            nodeKey = "nodekey:new",
            hostname = "reused-name",
            error = "timeout",
        )

    val merged = mergeAwgPeerResults(previous.data, listOf(replacement))

    assertFalse(merged.status.getValue("nodekey:new"))
    assertSame(replacement, merged.data.getValue("nodekey:new"))
  }

  @Test
  fun lookupFailureForNewNodeKeyOnReusedIpDoesNotInheritOldAwgConfig() {
    val oldNode =
        AwgPeerResult(
            nodeKey = "nodekey:old",
            hostname = "peer",
            tailscaleIP = "100.64.0.10",
            config = configured,
        )
    val previous = mergeAwgPeerResults(emptyMap(), listOf(oldNode))
    val replacement =
        AwgPeerResult(
            nodeKey = "nodekey:new",
            hostname = "peer",
            tailscaleIP = "100.64.0.10",
            error = "timeout",
        )

    val merged = mergeAwgPeerResults(previous.data, listOf(replacement))

    assertFalse(merged.status.getValue("nodekey:new"))
    assertSame(replacement, merged.data.getValue("nodekey:new"))
  }

  @Test
  fun peersOmittedByFreshDiscoveryAreRemoved() {
    val previous =
        mergeAwgPeerResults(
            emptyMap(),
            listOf(
                AwgPeerResult(
                    nodeKey = "nodekey:offline",
                    hostname = "offline",
                    config = configured,
                )),
        )

    val merged = mergeAwgPeerResults(previous.data, emptyList())

    assertTrue(merged.status.isEmpty())
    assertTrue(merged.data.isEmpty())
  }

  @Test
  fun stableNodeKeyWinsOverCollidingHostnameAlias() {
    val index =
        mergeAwgPeerResults(
            emptyMap(),
            listOf(
                AwgPeerResult(
                    nodeKey = "nodekey:configured",
                    hostname = "shared-name",
                    config = configured,
                ),
                AwgPeerResult(nodeKey = "nodekey:standard", hostname = "shared-name"),
            ),
        )
    val standardPeer =
        Tailcfg.Node(
            Key = "nodekey:standard",
            Name = "shared-name.example.ts.net",
            Hostinfo = Tailcfg.Hostinfo(Hostname = "shared-name"),
        )

    assertFalse(indexedAwgStatus(standardPeer, index.data))
  }

  @Test
  fun modernSelectedPeerDoesNotUseStaleSameNameOrIpCache() {
    val stale =
        AwgPeerResult(
            nodeKey = "nodekey:old",
            hostname = "shared-name",
            tailscaleIP = "100.64.0.20",
            config = configured,
        )
    val index = mergeAwgPeerResults(emptyMap(), listOf(stale))
    val replacement =
        Tailcfg.Node(
            Key = "nodekey:new",
            Name = "shared-name.example.ts.net",
            Addresses = listOf("100.64.0.20/32"),
            Hostinfo = Tailcfg.Hostinfo(Hostname = "shared-name"),
        )

    assertNull(findIndexedAwgPeerResult(replacement, index.data))
    assertFalse(indexedAwgStatus(replacement, index.data))
    assertNull(awgSyncNodeKey(replacement, stale))
  }

  @Test
  fun identitylessLegacyResultCanUseSelectedPeersCurrentNodeKey() {
    val legacy =
        AwgPeerResult(
            hostname = "legacy-peer",
            tailscaleIP = "100.64.0.30",
            config = configured,
        )
    val index = mergeAwgPeerResults(emptyMap(), listOf(legacy))
    val selected =
        Tailcfg.Node(
            Key = "nodekey:current",
            Name = "legacy-peer.example.ts.net",
            Addresses = listOf("100.64.0.30/32"),
            Hostinfo = Tailcfg.Hostinfo(Hostname = "legacy-peer"),
        )

    assertSame(legacy, findIndexedAwgPeerResult(selected, index.data))
    assertEquals("nodekey:current", awgSyncNodeKey(selected, legacy))
  }

  @Test
  fun onlyOneAwgApplyCanBeActiveAndStaleCompletionCannotClearIt() {
    val coordinator = AwgWriteCoordinator()
    val first = coordinator.tryBegin("peer-a")!!

    assertNull(coordinator.tryBegin("peer-b"))
    coordinator.invalidateProfile()
    assertNull(coordinator.tryBegin("peer-b"))
    assertFalse(coordinator.finish(first))
    val second = coordinator.tryBegin("peer-b")!!
    assertTrue(second.profileGeneration > first.profileGeneration)
    assertFalse(coordinator.finish(first))
    assertEquals(second, coordinator.inProgress.value)
    assertTrue(coordinator.finish(second))
    assertNull(coordinator.inProgress.value)
  }

  @Test
  fun profileMutationAndAwgWriteAreMutuallyExclusive() {
    val coordinator = AwgWriteCoordinator()
    val profileMutation = coordinator.tryBeginProfileMutation(invalidateProfile = true)!!

    assertNull(coordinator.tryBegin("peer"))
    assertNull(coordinator.tryBeginProfileMutation(invalidateProfile = true))
    assertTrue(coordinator.finishProfileMutation(profileMutation))

    val write = coordinator.tryBegin("peer")!!
    assertNull(coordinator.tryBeginProfileMutation(invalidateProfile = true))
    assertTrue(coordinator.finish(write))
  }

  @Test
  fun staleProfileMutationCompletionCannotClearNewToken() {
    val coordinator = AwgWriteCoordinator()
    val first = coordinator.tryBeginProfileMutation(invalidateProfile = true)!!
    assertTrue(coordinator.finishProfileMutation(first))
    val second = coordinator.tryBeginProfileMutation(invalidateProfile = true)!!

    assertFalse(coordinator.finishProfileMutation(first))
    assertEquals(second, coordinator.profileMutationInProgress.value)
    assertTrue(coordinator.finishProfileMutation(second))
    assertNull(coordinator.profileMutationInProgress.value)
  }

  @Test
  fun onlyLatestPrefsReadForEachConsumerCanCommit() {
    val coordinator = AwgWriteCoordinator()
    val first = coordinator.beginPrefsRead(AwgPrefsReader.MAIN)
    val latest = coordinator.beginPrefsRead(AwgPrefsReader.MAIN)
    val independentViewer = coordinator.beginPrefsRead(AwgPrefsReader.VIEWER)

    assertFalse(coordinator.isCurrentPrefsRead(first))
    assertTrue(coordinator.isCurrentPrefsRead(latest))
    assertTrue(coordinator.isCurrentPrefsRead(independentViewer))
  }

  @Test
  fun prefsReadFromBeforeOrDuringSuccessfulWriteCannotCommit() {
    val coordinator = AwgWriteCoordinator()
    val beforeWrite = coordinator.beginPrefsRead(AwgPrefsReader.MAIN)
    val write = coordinator.tryBegin("peer")!!

    assertFalse(coordinator.isCurrentPrefsRead(beforeWrite))
    val duringWrite = coordinator.beginPrefsRead(AwgPrefsReader.MAIN)
    assertFalse(coordinator.isCurrentPrefsRead(duringWrite))
    assertTrue(coordinator.finish(write))
    assertFalse(coordinator.isCurrentPrefsRead(duringWrite))
    assertTrue(coordinator.isCurrentPrefsRead(coordinator.beginPrefsRead(AwgPrefsReader.MAIN)))
  }

  @Test
  fun prefsReadStartedDuringAmbiguousFailedWriteCannotCommit() {
    val coordinator = AwgWriteCoordinator()
    val write = coordinator.tryBegin("peer")!!
    val duringWrite = coordinator.beginPrefsRead(AwgPrefsReader.MAIN)

    assertTrue(coordinator.finish(write))
    assertFalse(coordinator.isCurrentPrefsRead(duringWrite))
  }

  @Test
  fun profileMutationInvalidatesReadsFromBeforeAndDuringOperation() {
    val coordinator = AwgWriteCoordinator()
    val beforeMutation = coordinator.beginPrefsRead(AwgPrefsReader.SETTINGS)
    val mutation = coordinator.tryBeginProfileMutation(invalidateProfile = true)!!

    assertFalse(coordinator.isCurrentPrefsRead(beforeMutation))
    val duringMutation = coordinator.beginPrefsRead(AwgPrefsReader.SETTINGS)
    assertFalse(coordinator.isCurrentPrefsRead(duringMutation))
    assertTrue(coordinator.finishProfileMutation(mutation))
    assertFalse(coordinator.isCurrentPrefsRead(duringMutation))
    assertTrue(coordinator.isCurrentPrefsRead(coordinator.beginPrefsRead(AwgPrefsReader.SETTINGS)))
  }

  @Test
  fun logoutInvalidatesRefreshEvenWhenNextFingerprintWouldMatch() {
    val request =
        AwgRefreshRequestToken(
            topologyGeneration = 4,
            fingerprint = "same",
            profileGeneration = 9,
        )

    assertTrue(
        isCurrentAwgRefresh(request, 4, "same", topologyAvailable = true, profileGeneration = 9))
    assertFalse(
        isCurrentAwgRefresh(request, 5, "same", topologyAvailable = true, profileGeneration = 9))
    assertFalse(
        isCurrentAwgRefresh(request, 4, "same", topologyAvailable = false, profileGeneration = 9))
    // A delayed response from profile A must not update profile B even if topology matches.
    assertFalse(
        isCurrentAwgRefresh(request, 4, "same", topologyAvailable = true, profileGeneration = 10))
  }

  @Test
  fun successfulProfileMutationWaitsForFreshNetmapBeforeCacheCanBeUsed() {
    val coordinator = AwgWriteCoordinator()
    val mutation =
        coordinator.tryBeginProfileMutation(
            invalidateProfile = true,
            profileBoundaryEpochAtStart = 12,
        )!!

    assertFalse(
        awgProfileReadyAfterMutation(
            mutation,
            currentProfileGeneration = mutation.profileGeneration,
            mutationSucceeded = true,
            currentProfileBoundaryEpoch = 12,
            hasCurrentNetmap = true,
        ))
    assertFalse(
        canUseAwgPeerCache(
            cacheProfileGeneration = mutation.profileGeneration,
            currentProfileGeneration = mutation.profileGeneration,
            profileReady = false,
            selectedPeerIsCurrent = true,
        ))
    assertTrue(
        awgProfileReadyAfterMutation(
            mutation,
            currentProfileGeneration = mutation.profileGeneration,
            mutationSucceeded = true,
            currentProfileBoundaryEpoch = 13,
            hasCurrentNetmap = true,
        ))
    assertTrue(
        awgProfileReadyAfterMutation(
            mutation,
            currentProfileGeneration = mutation.profileGeneration,
            mutationSucceeded = false,
            currentProfileBoundaryEpoch = 12,
            hasCurrentNetmap = true,
        ))
  }

  @Test
  fun staleSelectedPeerMustStillBelongToCurrentNetmap() {
    val stale = Tailcfg.Node(StableID = "stable", Key = "nodekey:old", Name = "peer")
    val current = Tailcfg.Node(StableID = "stable", Key = "nodekey:new", Name = "peer")
    val netmap =
        Netmap.NetworkMap(
            SelfNode = Tailcfg.Node(Key = "nodekey:self"),
            Peers = listOf(current),
            Domain = "example.ts.net",
            UserProfiles = emptyMap(),
            TKAEnabled = false,
        )

    assertNull(currentNetmapPeer(stale, netmap))
    assertSame(current, currentNetmapPeer(current, netmap))
  }

  @Test
  fun disappearingPeerInvalidatesDetailsCallback() {
    assertTrue(isCurrentAwgPeerLookup(7, 7, "nodekey:peer", "nodekey:peer"))
    assertFalse(isCurrentAwgPeerLookup(7, 8, "nodekey:peer", "nodekey:peer"))
    assertFalse(isCurrentAwgPeerLookup(7, 7, "nodekey:peer", null))
  }

  @Test
  fun peerDetailsLookupMatchesFullKeyAndDoesNotInventAMatch() {
    val peer = Tailcfg.Node(Key = "nodekey:peer", Name = "peer.example.ts.net")
    val matching = AwgPeerResult(nodeKey = "nodekey:peer", hostname = "renamed")

    assertSame(matching, findAwgPeerResult(peer, listOf(matching)))
    assertNull(
        findAwgPeerResult(
            peer,
            listOf(AwgPeerResult(nodeKey = "nodekey:other", hostname = "peer")),
        ))
  }

  @Test
  fun peerDetailsHostnameFallbackStillSupportsLegacyIdentityLessResponses() {
    val peer = Tailcfg.Node(Key = "nodekey:peer", Name = "peer.example.ts.net")
    val legacy = AwgPeerResult(hostname = "peer", config = configured)

    assertSame(legacy, findAwgPeerResult(peer, listOf(legacy)))
  }

  @Test
  fun peerDetailsIpFallbackRejectsDifferentModernIdentity() {
    val peer =
        Tailcfg.Node(
            Key = "nodekey:new",
            Name = "peer.example.ts.net",
            Addresses = listOf("100.64.0.20/32"),
        )
    val stale =
        AwgPeerResult(
            nodeKey = "nodekey:old",
            hostname = "peer",
            tailscaleIP = "100.64.0.20",
            config = configured,
        )
    val legacy =
        AwgPeerResult(
            hostname = "peer",
            tailscaleIP = "100.64.0.20",
            config = configured,
        )

    assertNull(findAwgPeerResult(peer, listOf(stale)))
    assertSame(legacy, findAwgPeerResult(peer, listOf(stale, legacy)))
  }
}
