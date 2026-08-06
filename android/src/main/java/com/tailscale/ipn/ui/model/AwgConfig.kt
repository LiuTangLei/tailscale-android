// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.tailscale.ipn.ui.model

import java.security.SecureRandom
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Inclusive unsigned 32-bit range used by AWG headers and v3 timing parameters. */
@Serializable(with = MagicHeaderRangeSerializer::class)
data class MagicHeaderRange(val min: Long = 0, val max: Long = min) {
  fun hasValue(): Boolean = min != 0L || max != 0L

  fun isFixedValue(): Boolean = min == max

  fun getFixedValue(): Long? = if (isFixedValue()) min else null

  fun displayValue(): String = if (isFixedValue()) min.toString() else "$min-$max"
}

/**
 * Accepts all range forms understood by the Go core: a historical scalar, a "min-max" string, or an
 * object. Values are always encoded as an object for an unambiguous LocalAPI request.
 */
object MagicHeaderRangeSerializer : KSerializer<MagicHeaderRange> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun deserialize(decoder: Decoder): MagicHeaderRange {
    val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("JSON required")
    return parseRange(jsonDecoder.decodeJsonElement())
  }

  override fun serialize(encoder: Encoder, value: MagicHeaderRange) {
    val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("JSON required")
    jsonEncoder.encodeJsonElement(
        buildJsonObject {
          put("min", value.min)
          put("max", value.max)
        })
  }

  private fun parseRange(element: JsonElement): MagicHeaderRange =
      when (element) {
        is JsonPrimitive -> parsePrimitive(element)
        is JsonObject -> {
          val min = element.rangeValue("min")
          val max = element.rangeValue("max")
          if (min == null || max == null) {
            throw SerializationException("range object requires both min and max")
          }
          checkedRange(min, max)
        }
        else -> throw SerializationException("range must be a number, string, or object")
      }

  private fun parsePrimitive(value: JsonPrimitive): MagicHeaderRange {
    value.longOrNull?.let {
      return checkedRange(it, it)
    }
    val text = value.content.trim()
    val separator = text.indexOf('-')
    if (separator < 0) {
      val fixed = text.toLongOrNull() ?: throw SerializationException("invalid range: $text")
      return checkedRange(fixed, fixed)
    }
    val min = text.substring(0, separator).trim().toLongOrNull()
    val max = text.substring(separator + 1).trim().toLongOrNull()
    if (min == null || max == null) throw SerializationException("invalid range: $text")
    return checkedRange(min, max)
  }

  private fun JsonObject.rangeValue(name: String): Long? =
      entries
          .firstOrNull { it.key.equals(name, ignoreCase = true) }
          ?.value
          ?.let { it as? JsonPrimitive }
          ?.longOrNull

  private fun checkedRange(min: Long, max: Long): MagicHeaderRange {
    if (min !in 0..UINT32_MAX || max !in 0..UINT32_MAX) {
      throw SerializationException("range values must be between 0 and $UINT32_MAX")
    }
    if (max < min) throw SerializationException("range maximum $max is less than minimum $min")
    return MagicHeaderRange(min, max)
  }
}

@Serializable
data class AwgPeerResult(
    @SerialName("nodeKey") val nodeKey: String = "",
    @SerialName("hostname") val hostname: String = "",
    @SerialName("tailscaleIP") val tailscaleIP: String? = null,
    @SerialName("config") val config: AmneziaWGPrefs? = null,
    @SerialName("error") val error: String? = null,
) {
  val hasAwgConfig: Boolean
    get() = config?.hasNonDefaultValues() == true && error.isNullOrBlank()

  val lookupFailed: Boolean
    get() = !error.isNullOrBlank()
}

@Serializable
data class AmneziaWGPrefs(
    @SerialName("JC") @JsonNames("jc") val JC: Int? = null,
    @SerialName("JMin") @JsonNames("jmin", "Jmin", "jMin") val JMin: Int? = null,
    @SerialName("JMax") @JsonNames("jmax", "Jmax", "jMax") val JMax: Int? = null,
    @SerialName("S1") @JsonNames("s1") val S1: Int? = null,
    @SerialName("S2") @JsonNames("s2") val S2: Int? = null,
    @SerialName("S3") @JsonNames("s3") val S3: Int? = null,
    @SerialName("S4") @JsonNames("s4") val S4: Int? = null,
    @SerialName("I1") @JsonNames("i1") val I1: String? = null,
    @SerialName("I2") @JsonNames("i2") val I2: String? = null,
    @SerialName("I3") @JsonNames("i3") val I3: String? = null,
    @SerialName("I4") @JsonNames("i4") val I4: String? = null,
    @SerialName("I5") @JsonNames("i5") val I5: String? = null,
    @SerialName("H1") @JsonNames("h1") val H1: MagicHeaderRange? = null,
    @SerialName("H2") @JsonNames("h2") val H2: MagicHeaderRange? = null,
    @SerialName("H3") @JsonNames("h3") val H3: MagicHeaderRange? = null,
    @SerialName("H4") @JsonNames("h4") val H4: MagicHeaderRange? = null,
    @SerialName("HeaderProtectionKey")
    @JsonNames("headerProtectionKey", "header_protection_key")
    val HeaderProtectionKey: String? = null,
    @SerialName("ContentPaddingAddition")
    @JsonNames("contentPaddingAddition", "content_padding_addition")
    val ContentPaddingAddition: MagicHeaderRange? = null,
    @SerialName("RekeyAfterTime")
    @JsonNames("rekeyAfterTime", "rekey_after_time")
    val RekeyAfterTime: MagicHeaderRange? = null,
    @SerialName("RekeyTimeout")
    @JsonNames("rekeyTimeout", "rekey_timeout")
    val RekeyTimeout: MagicHeaderRange? = null,
    @SerialName("RejectAfterTime")
    @JsonNames("rejectAfterTime", "reject_after_time")
    val RejectAfterTime: MagicHeaderRange? = null,
    @SerialName("KeepaliveTimeout")
    @JsonNames("keepaliveTimeout", "keepalive_timeout")
    val KeepaliveTimeout: MagicHeaderRange? = null,
    @SerialName("MaxHandshakeAttempts")
    @JsonNames("maxHandshakeAttempts", "max_handshake_attempts")
    val MaxHandshakeAttempts: MagicHeaderRange? = null,
) {
  fun hasNonDefaultValues(): Boolean =
      listOf(JC, JMin, JMax, S1, S2, S3, S4).any { it != null && it != 0 } ||
          listOf(I1, I2, I3, I4, I5).any { !it.isNullOrEmpty() } ||
          listOf(H1, H2, H3, H4).any { it?.hasValue() == true } ||
          isV3()

  fun isV3(): Boolean =
      (!HeaderProtectionKey.isNullOrEmpty() && HeaderProtectionKey != ZERO_KEY) ||
          listOf(
                  ContentPaddingAddition,
                  RekeyAfterTime,
                  RekeyTimeout,
                  RejectAfterTime,
                  KeepaliveTimeout,
                  MaxHandshakeAttempts,
              )
              .any { it?.hasValue() == true }

  fun versionLabel(): String =
      when {
        !hasNonDefaultValues() -> "Standard WireGuard"
        isV3() -> "AWG v3"
        else -> "AWG v2"
      }

  /** Fast client-side checks; the Go core remains the final source of truth. */
  fun validationError(): String? {
    listOf(
            "JC" to JC,
            "JMin" to JMin,
            "JMax" to JMax,
            "S1" to S1,
            "S2" to S2,
            "S3" to S3,
            "S4" to S4,
        )
        .forEach { (name, value) ->
          if (value != null && value !in 0..UINT16_MAX) {
            return "$name must be between 0 and $UINT16_MAX"
          }
        }
    if ((JMin ?: 0) > 0 && (JMax ?: 0) > 0 && JMin!! > JMax!!) {
      return "JMin cannot be greater than JMax"
    }
    if ((JC ?: 0) > 4096) return "JC exceeds the safe limit of 4096"
    if ((JC ?: 0).toLong() * (JMax ?: 0).toLong() > 64L * 1024 * 1024) {
      return "JC and JMax request too much junk data"
    }

    val ranges =
        listOf(
            "H1" to H1,
            "H2" to H2,
            "H3" to H3,
            "H4" to H4,
            "ContentPaddingAddition" to ContentPaddingAddition,
            "RekeyAfterTime" to RekeyAfterTime,
            "RekeyTimeout" to RekeyTimeout,
            "RejectAfterTime" to RejectAfterTime,
            "KeepaliveTimeout" to KeepaliveTimeout,
            "MaxHandshakeAttempts" to MaxHandshakeAttempts,
        )
    ranges.forEach { (name, range) ->
      if (range != null && (range.min !in 0..UINT32_MAX || range.max !in range.min..UINT32_MAX)) {
        return "$name has an invalid range"
      }
    }
    val effectiveHeaders =
        listOf(H1, H2, H3, H4).mapIndexed { index, range ->
          range?.takeIf(MagicHeaderRange::hasValue)
              ?: MagicHeaderRange((index + 1).toLong(), (index + 1).toLong())
        }
    effectiveHeaders.forEachIndexed { firstIndex, first ->
      effectiveHeaders.drop(firstIndex + 1).forEachIndexed { offset, second ->
        if (first.min <= second.max && second.min <= first.max) {
          return "H${firstIndex + 1} overlaps H${firstIndex + offset + 2}"
        }
      }
    }

    listOf("I1" to I1, "I2" to I2, "I3" to I3, "I4" to I4, "I5" to I5).forEach { (name, value) ->
      if (value != null && RETIRED_COUNTER_TAG.containsMatchIn(value)) {
        return "$name contains retired <c>; remove only the <c> tag"
      }
      if (value?.any { it.code < 0x20 || it.code == 0x7f } == true) {
        return "$name contains a control character"
      }
    }

    val key = HeaderProtectionKey.orEmpty()
    if (key.isNotEmpty() &&
        (key.length != 64 || !key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })) {
      return "HeaderProtectionKey must contain exactly 64 hexadecimal characters"
    }
    if (key.isNotEmpty() && key != ZERO_KEY) {
      listOf(S1, S2, S3, S4).forEachIndexed { index, value ->
        if ((value ?: 0) < 12) return "S${index + 1} must be at least 12 for AWG v3"
      }
    }
    return null
  }

  companion object {
    const val ZERO_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    private val RETIRED_COUNTER_TAG = Regex("<\\s*c(?:\\s[^>]*)?>")
  }
}

enum class AwgProfileVersion {
  V2,
  V3,
}

/** Generates profiles with the same safety ranges as the desktop CLI. */
object AwgProfileGenerator {
  fun generate(version: AwgProfileVersion, random: SecureRandom = SecureRandom()): AmneziaWGPrefs {
    val minPrefix = if (version == AwgProfileVersion.V3) 15 else 5
    val headers =
        listOf(
                100_000L to 900_000_000L,
                1_000_000_000L to 1_900_000_000L,
                2_000_000_000L to 2_900_000_000L,
                3_000_000_000L to 4_000_000_000L,
            )
            .map { (min, max) ->
              val base = randomLong(random, min, max - 96)
              MagicHeaderRange(
                  min = base,
                  max =
                      if (version == AwgProfileVersion.V3) base + randomLong(random, 16, 96)
                      else base,
              )
            }
    val key =
        if (version == AwgProfileVersion.V3) {
          ByteArray(32).also(random::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
          }
        } else {
          null
        }
    return AmneziaWGPrefs(
        JC = randomInt(random, 3, 6),
        JMin = randomInt(random, 500, 700),
        JMax = randomInt(random, 800, 1000),
        S1 = randomInt(random, minPrefix, 32),
        S2 = randomInt(random, minPrefix, 32),
        S3 = randomInt(random, minPrefix, 32),
        S4 = randomInt(random, minPrefix, 32),
        H1 = headers[0],
        H2 = headers[1],
        H3 = headers[2],
        H4 = headers[3],
        HeaderProtectionKey = key,
        ContentPaddingAddition = if (key != null) MagicHeaderRange(5, 31) else null,
        RekeyAfterTime = if (key != null) MagicHeaderRange(120, 180) else null,
        RekeyTimeout = if (key != null) MagicHeaderRange(5, 7) else null,
        RejectAfterTime = if (key != null) MagicHeaderRange(180, 240) else null,
        KeepaliveTimeout = if (key != null) MagicHeaderRange(10, 15) else null,
        MaxHandshakeAttempts = if (key != null) MagicHeaderRange(8, 12) else null,
    )
  }

  private fun randomInt(random: SecureRandom, min: Int, max: Int): Int =
      min + random.nextInt(max - min + 1)

  private fun randomLong(random: SecureRandom, min: Long, max: Long): Long {
    val bytes = ByteArray(4).also(random::nextBytes)
    val unsigned =
        (bytes[0].toLong() and 0xff) or
            ((bytes[1].toLong() and 0xff) shl 8) or
            ((bytes[2].toLong() and 0xff) shl 16) or
            ((bytes[3].toLong() and 0xff) shl 24)
    return min + unsigned % (max - min + 1)
  }
}

data class PeerAwgStatus(val peer: Tailcfg.Node, val hasAwgConfig: Boolean = false)

@Serializable
data class AwgSyncApplyRequest(
    @SerialName("nodeKey") val nodeKey: String,
    @SerialName("timeout") val timeout: Int = 10,
)

@Serializable
data class LocalPrefs(
    @SerialName("AmneziaWG") val AmneziaWG: AmneziaWGPrefs? = null,
)

private const val UINT16_MAX = 65_535
private const val UINT32_MAX = 4_294_967_295L
