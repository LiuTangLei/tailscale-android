// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgConfigTest {
  private val json = Json { encodeDefaults = false }

  @Test
  fun historicalV2LowercaseAndScalarHeadersDecode() {
    val config =
        json.decodeFromString<AmneziaWGPrefs>(
            """{"s1":10,"s2":15,"s3":8,"s4":0,"h1":100000,"h2":"300000-350000","i1":"<b 0xc0><r 32>"}""")

    assertEquals(MagicHeaderRange(100000, 100000), config.H1)
    assertEquals(MagicHeaderRange(300000, 350000), config.H2)
    assertEquals("AWG v2", config.versionLabel())
    assertFalse(config.isV3())
    assertNull(config.validationError())
  }

  @Test
  fun historicalCamelCaseJunkRangeNamesDecode() {
    val config = json.decodeFromString<AmneziaWGPrefs>("""{"jMin":64,"jMax":128}""")

    assertEquals(64, config.JMin)
    assertEquals(128, config.JMax)
  }

  @Test
  fun inheritedV2RangeProfileRemainsValid() {
    val config =
        json.decodeFromString<AmneziaWGPrefs>(
            """{"jc":1,"jmin":64,"jmax":128,"s1":2,"s2":2,"s3":1,"s4":2,"h1":{"min":437103616,"max":804436808},"h2":{"min":1385444482,"max":1808425148},"h3":{"min":2603510117,"max":3050208882},"h4":{"min":3291575908,"max":3724355998}}""")

    assertEquals("AWG v2", config.versionLabel())
    assertNull(config.validationError())
    assertEquals(config, json.decodeFromString<AmneziaWGPrefs>(json.encodeToString(config)))
  }

  @Test
  fun v3SnakeCaseRoundTripsWithoutLosingFields() {
    val config =
        json.decodeFromString<AmneziaWGPrefs>(
            """{
              "s1":15,"s2":16,"s3":17,"s4":18,
              "h1":{"min":100000,"max":100096},
              "h2":{"min":1000000000,"max":1000000096},
              "h3":{"min":2000000000,"max":2000000096},
              "h4":{"min":3000000000,"max":3000000096},
              "header_protection_key":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "content_padding_addition":{"min":5,"max":31},
              "rekey_after_time":{"min":120,"max":180},
              "rekey_timeout":{"min":5,"max":7},
              "reject_after_time":{"min":180,"max":240},
              "keepalive_timeout":{"min":10,"max":15},
              "max_handshake_attempts":{"min":8,"max":12}
            }""")

    assertTrue(config.isV3())
    assertEquals("AWG v3", config.versionLabel())
    assertNull(config.validationError())
    assertEquals(config, json.decodeFromString<AmneziaWGPrefs>(json.encodeToString(config)))
  }

  @Test
  fun retiredCounterTagIsDistinctFromHexByte() {
    assertNull(AmneziaWGPrefs(I1 = "<b 0xc0><r 32>").validationError())
    assertTrue(AmneziaWGPrefs(I1 = "<b 0xc0><c><r 32>").validationError()!!.contains("<c>"))
  }

  @Test
  fun overlappingHeadersAreRejectedBeforeLocalApi() {
    val config =
        AmneziaWGPrefs(
            H1 = MagicHeaderRange(100, 200),
            H2 = MagicHeaderRange(200, 300),
        )
    assertTrue(config.validationError()!!.contains("H1 overlaps H2"))
  }

  @Test
  fun maskedPrefsKeepsExistingLocalApiShape() {
    val prefs = Ipn.MaskedPrefs().also { it.AmneziaWG = AmneziaWGPrefs(JC = 3) }
    val encoded = json.encodeToString(prefs)

    assertTrue(encoded.contains("\"AmneziaWGSet\":true"))
    assertTrue(encoded.contains("\"AmneziaWG\":{\"JC\":3}"))
    assertFalse(encoded.contains("ControlURL"))
  }

  @Test
  fun generatedV2AndV3ProfilesAreValid() {
    repeat(100) {
      val v2 = AwgProfileGenerator.generate(AwgProfileVersion.V2)
      assertFalse(v2.isV3())
      assertEquals("AWG v2", v2.versionLabel())
      assertNull(v2.validationError())
      assertTrue(listOf(v2.H1, v2.H2, v2.H3, v2.H4).all { range -> range!!.isFixedValue() })

      val v3 = AwgProfileGenerator.generate(AwgProfileVersion.V3)
      assertTrue(v3.isV3())
      assertEquals(64, v3.HeaderProtectionKey!!.length)
      assertNull(v3.validationError())
      assertTrue(listOf(v3.S1, v3.S2, v3.S3, v3.S4).all { prefix -> prefix!! >= 15 })
    }
  }

  @Test
  fun failedDiscoveryIsNotReportedAsStandardWireGuard() {
    val failed = AwgPeerResult(nodeKey = "nodekey:test", hostname = "peer", error = "timeout")
    assertTrue(failed.lookupFailed)
    assertFalse(failed.hasAwgConfig)
  }
}
