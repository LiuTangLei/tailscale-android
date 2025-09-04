// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AwgPeerResult(
    @SerialName("nodeKey")
    val nodeKey: String,
    @SerialName("hostname")
    val hostname: String,
    @SerialName("config")
    val config: AmneziaWGPrefs?,
    @SerialName("error")
    val error: String? = null,
) {
    val hasAwgConfig: Boolean
        get() = config != null && error == null
}

@Serializable
data class AmneziaWGPrefs(
    @SerialName("JC")
    val JC: Int? = null, // Junk packet count
    @SerialName("JMin")
    val JMin: Int? = null, // Junk packet min size
    @SerialName("JMax")
    val JMax: Int? = null, // Junk packet max size
    @SerialName("S1")
    val S1: Int? = null, // Init packet junk size
    @SerialName("S2")
    val S2: Int? = null, // Response packet junk size
    @SerialName("I1")
    val I1: String? = null, // Init packet static content
    @SerialName("I2")
    val I2: String? = null, // Response packet static content
    @SerialName("I3")
    val I3: String? = null, // Reserved
    @SerialName("I4")
    val I4: String? = null, // Reserved
    @SerialName("I5")
    val I5: String? = null, // Reserved
    @SerialName("H1")
    val H1: Long? = null, // Init packet magic header value 1
    @SerialName("H2")
    val H2: Long? = null, // Init packet magic header value 2
    @SerialName("H3")
    val H3: Long? = null, // Init packet magic header value 3
    @SerialName("H4")
    val H4: Long? = null, // Init packet magic header value 4
) {
    /**
     * Check if this AWG configuration has non-default values
     * Returns true if any field has a meaningful (non-zero/non-empty) value
     */
    fun hasNonDefaultValues(): Boolean =
        (JC != null && JC != 0) ||
            (JMin != null && JMin != 0) ||
            (JMax != null && JMax != 0) ||
            (S1 != null && S1 != 0) ||
            (S2 != null && S2 != 0) ||
            (!I1.isNullOrEmpty()) ||
            (!I2.isNullOrEmpty()) ||
            (!I3.isNullOrEmpty()) ||
            (!I4.isNullOrEmpty()) ||
            (!I5.isNullOrEmpty()) ||
            (H1 != null && H1 != 0L) ||
            (H2 != null && H2 != 0L) ||
            (H3 != null && H3 != 0L) ||
            (H4 != null && H4 != 0L)
}

data class PeerAwgStatus(
    val peer: Tailcfg.Node,
    val hasAwgConfig: Boolean = false,
)

@Serializable
data class AwgSyncApplyRequest(
    @SerialName("nodeKey")
    val nodeKey: String,
    @SerialName("timeout")
    val timeout: Int = 10, // Timeout in seconds (1-60), default 10
)

@Serializable
data class AwgSyncApplyResponse(
    val success: Boolean = true,
    val message: String? = null,
)

@Serializable
data class LocalPrefs(
    @SerialName("AmneziaWG")
    val AmneziaWG: AmneziaWGPrefs? = null,
    // We only need AmneziaWG field for AWG configuration
    // Other prefs fields can be ignored for now
)
