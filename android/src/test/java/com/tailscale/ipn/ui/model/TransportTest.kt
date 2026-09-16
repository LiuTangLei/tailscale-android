// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {
  @Test
  fun activationRequiresRunningAndDesiredModesToAgree() {
    val live = TransportStatus(activeMode = "http3-ip", desiredMode = "http3-ip")
    assertTrue(live.isActive("http3-ip"))
    assertFalse(live.isActive("native"))
    assertFalse(live.copy(pendingRestart = true).isActive("http3-ip"))
    assertFalse(live.copy(activeMode = "native").isActive("http3-ip"))
    assertFalse(live.copy(desiredMode = "native").isActive("http3-ip"))
    assertFalse(TransportStatus().isActive("http3-ip"))
  }

  @Test
  fun requestKeepsRevisionAndDoesNotContainPrivateIdentity() {
    val payload = Json.encodeToString(
        TransportControlRequest(action = "mode", expectedRevision = "test-revision", mode = "quic"))
    assertTrue(payload.contains("\"expected_revision\":\"test-revision\""))
    assertTrue(payload.contains("\"mode\":\"quic\""))
    assertFalse(payload.contains("private_key"))
    assertFalse(payload.contains("certificate_pem"))
  }
}
