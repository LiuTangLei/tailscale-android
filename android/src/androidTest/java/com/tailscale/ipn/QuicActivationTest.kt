// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailscale.ipn.ui.util.InputStreamAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Runs only on an explicitly selected, isolated emulator with local fake control. */
@RunWith(AndroidJUnit4::class)
class QuicActivationTest {
  private fun call(method: String, endpoint: String, body: JSONObject? = null, timeoutMillis: Long = 60_000L): JSONObject {
    val app = App.get().getLibtailscaleApp()
    val response = app.callLocalAPI(
        timeoutMillis, method, "/localapi/v0/$endpoint",
        body?.toString()?.byteInputStream()?.let { InputStreamAdapter(it) },
    )
    val bytes = response.bodyBytes() ?: ByteArray(0)
    check(response.statusCode() in 200..299) {
      "LocalAPI $endpoint failed (${response.statusCode()}): ${bytes.decodeToString()}"
    }
    return if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.decodeToString())
  }

  private fun awaitIdentity(expected: String? = null): JSONObject {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (System.nanoTime() < deadline) {
      val status = call("GET", "packet-transport")
      val key = status.optString("local_public_key")
      if (key.isNotEmpty()) {
        if (expected != null) assertEquals("node identity must survive engine restart", expected, key)
        return status
      }
      Thread.sleep(100)
    }
    error("isolated node did not become ready: ${call("GET", "status").optString("BackendState")}")
  }

  // The app deliberately disallows cleartext HttpURLConnection. Exercise a
  // plain TCP payload inside the authenticated VPN without relaxing the app's
  // production NetworkSecurityConfig. HTTP/1.0 bounds framing by connection
  // close, and the only target is the explicitly supplied isolated test peer.
  private fun kernelRequest(peerIP: String, upload: ByteArray? = null): ByteArray {
    require(peerIP.startsWith("100.64."))
    return Socket().use { socket ->
      socket.soTimeout = 10_000
      socket.connect(InetSocketAddress(peerIP, 18080), 5_000)
      val method = if (upload == null) "GET" else "POST"
      val header = "$method /payload?size=262144 HTTP/1.0\r\nHost: $peerIP\r\nConnection: close\r\n" +
          (upload?.let { "Content-Length: ${it.size}\r\n" } ?: "") + "\r\n"
      socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
      if (upload != null) socket.getOutputStream().write(upload)
      socket.getOutputStream().flush()
      val received = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = socket.getInputStream().read(buffer)
        if (count < 0) break
        received.write(buffer, 0, count)
        check(received.size() <= 1 shl 20) { "test response exceeded its size limit" }
      }
      val bytes = received.toByteArray()
      val end = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
      check(end > 0) { "missing test response headers" }
      val headers = bytes.copyOfRange(0, end).toString(Charsets.US_ASCII)
      check(headers.startsWith("HTTP/1.0 200") || headers.startsWith("HTTP/1.1 200")) {
        "test server rejected the request"
      }
      bytes.copyOfRange(end + 4, bytes.size)
    }
  }

  private fun verifyKernelPayload(peerIP: String) {
    val payload = kernelRequest(peerIP)
    assertEquals("kernel VPN download length", 262144, payload.size)
    val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }
    val result = JSONObject(kernelRequest(peerIP, payload).decodeToString())
    assertEquals("kernel VPN upload length", payload.size, result.getInt("bytes"))
    assertEquals("kernel VPN upload checksum", sha256, result.getString("sha256"))
  }

  @Test
  fun actualEngineReconstructsAcrossQuicAndAwg() {
    val args = InstrumentationRegistry.getArguments()
    val control = args.getString("quicControlURL") ?: error("explicit local control URL is required")
    require(control.startsWith("http://127.0.0.1:")) { "this test must not use a real control server" }
    require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
      "this test is only for an isolated emulator, never a physical phone"
    }
    call("POST", "start", JSONObject()
        .put("UpdatePrefs", JSONObject().put("ControlURL", control).put("WantRunning", true).put("CorpDNS", false))
        .put("AuthKey", "isolated-mobile-test"))
    call("POST", "login-interactive")
    val identity = awaitIdentity().getString("local_public_key")
    val kernelVPN = args.getString("quicKernelVPN") == "true"
    if (kernelVPN) {
      check(android.net.VpnService.prepare(App.get()) == null) {
        "the test runner must pre-authorize VPN only on this isolated emulator"
      }
      App.get().startVPN()
    }
    for (mode in listOf("quic", "awg", "quic")) {
      val before = call("GET", "packet-transport")
      val request = JSONObject().put("expected_revision", before.getString("revision"))
      if (mode == "awg") {
        request.put("action", "awg").put("awg", JSONObject().put("jc", 4).put("jmin", 64).put("jmax", 128))
      } else {
        request.put("action", "mode").put("mode", "quic")
      }
      val result = call("POST", "packet-transport", request)
      val expected = if (mode == "awg") "native" else "http3-ip"
      assertEquals(expected, result.getString("active_mode"))
      assertEquals(expected, result.getString("desired_mode"))
      assertFalse("a saved profile is not enough", result.getBoolean("pending_restart"))
      assertEquals(mode == "awg", result.getBoolean("awg_configured"))
      awaitIdentity(identity)
      if (mode == "awg") {
        assertEquals(4, call("GET", "prefs").getJSONObject("AmneziaWG").getInt("JC"))
      } else {
        assertTrue(call("GET", "prefs").getJSONObject("AmneziaWG").optInt("JC", 0) == 0)
        val peerIP = args.getString("quicPeerIP") ?: error("an isolated desktop QUIC peer is required")
        require(peerIP.startsWith("100.64."))
        var verified = false
        repeat(3) {
          if (!verified) {
            try {
              val ping = call("POST", "ping?ip=$peerIP&type=TSMP", timeoutMillis = 5_000L)
              verified = ping.optString("Err").isEmpty() && ping.optDouble("LatencySeconds", 0.0) > 0
            } catch (_: Exception) {
              Thread.sleep(100)
            }
          }
        }
        assertTrue("authenticated QUIC round trip to the desktop peer failed", verified)
        if (kernelVPN) {
          // Each engine reconstruction must also restore the real Android TUN.
          var failure: Throwable? = null
          for (attempt in 0..2) {
            try {
              verifyKernelPayload(peerIP)
              failure = null
              break
            } catch (error: Throwable) {
              failure = error
              Thread.sleep(300)
            }
          }
          if (failure != null) throw AssertionError("QUIC kernel VPN payload failed", failure)
        }
      }
    }
    if (kernelVPN) App.get().stopVPN()
  }
}
