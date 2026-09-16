// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransportPeer(
    @SerialName("name") val name: String = "",
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("spki_sha256") val spkiSHA256: String = "",
    @SerialName("http3_url") val http3URL: String = "",
    @SerialName("server") val server: Boolean = false,
)

@Serializable
data class TransportUnconfiguredPeer(
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
data class TransportStatus(
    @SerialName("active_mode") val activeMode: String = "",
    @SerialName("desired_mode") val desiredMode: String = "",
    @SerialName("pending_restart") val pendingRestart: Boolean = false,
    @SerialName("available") val available: Boolean = false,
    @SerialName("source") val source: String = "",
    @SerialName("revision") val revision: String = "",
    @SerialName("server") val server: Boolean = false,
    @SerialName("auto_trust") val autoTrust: Boolean = false,
    @SerialName("authentication") val authentication: String = "",
    @SerialName("local_public_key") val localPublicKey: String = "",
    @SerialName("identity") val identity: TransportPeer? = null,
    @SerialName("peers") val peers: List<TransportPeer> = emptyList(),
    @SerialName("awg_configured") val awgConfigured: Boolean = false,
    @SerialName("warnings") val warnings: List<String> = emptyList(),
    @SerialName("mixed_peer_support") val mixedPeerSupport: Boolean = false,
    @SerialName("unconfigured_peers") val unconfiguredPeers: List<TransportUnconfiguredPeer> = emptyList(),
) {
  fun isActive(expectedMode: String): Boolean =
      !pendingRestart && activeMode == expectedMode && desiredMode == expectedMode
}

@Serializable
data class TransportControlRequest(
    @SerialName("action") val action: String,
    @SerialName("expected_revision") val expectedRevision: String = "",
    @SerialName("mode") val mode: String = "",
    @SerialName("awg") val awg: AmneziaWGPrefs? = null,
)
