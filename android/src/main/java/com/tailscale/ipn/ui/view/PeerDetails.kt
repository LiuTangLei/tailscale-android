// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModelFactory
import com.tailscale.ipn.ui.viewModel.PingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetails(
    onNavigateBack: () -> Unit,
    nodeId: String,
    pingViewModel: PingViewModel,
    model: PeerDetailsViewModel =
        viewModel(
            factory =
                PeerDetailsViewModelFactory(nodeId, LocalContext.current.filesDir, pingViewModel))
) {
  val isPinging by model.isPinging.collectAsState()
  val awgConfig by model.awgConfig.collectAsState()

  model.netmap.collectAsState().value?.let { netmap ->
    model.node.collectAsState().value?.let { node ->
      Scaffold(
          topBar = {
            Header(
                title = {
                  Column {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleMedium.short,
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier =
                              Modifier.size(8.dp)
                                  .background(
                                      color = node.connectedColor(netmap),
                                      shape = RoundedCornerShape(percent = 50))) {}
                      Spacer(modifier = Modifier.size(8.dp))
                      Text(
                          text = stringResource(id = node.connectedStrRes(netmap)),
                          style = MaterialTheme.typography.bodyMedium.short,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                },
                actions = {
                  IconButton(onClick = { model.startPing() }) {
                    Icon(
                        painter = painterResource(R.drawable.timer),
                        contentDescription = "Ping device")
                  }
                },
                onBack = onNavigateBack)
          },
      ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
        ) {
          item(key = "tailscaleAddresses") {
            Lists.MutedHeader(stringResource(R.string.tailscale_addresses))
          }

          itemsWithDividers(node.displayAddresses, key = { it.address }) {
            AddressRow(address = it.address, type = it.typeString)
          }

          item(key = "infoDivider") { Lists.SectionDivider() }

          itemsWithDividers(node.info, key = { "info_${it.titleRes}" }) {
            ValueRow(title = stringResource(id = it.titleRes), value = it.value.getString())
          }
          awgConfig?.let { result ->
            item(key = "awgDivider") { Lists.SectionDivider() }
            item(key = "awgHeader") { Lists.MutedHeader("AWG") }
            when {
              result.lookupFailed -> {
                item(key = "awgError") {
                  ValueRow(title = "Status", value = "Could not check: ${result.error}")
                }
              }
              result.hasAwgConfig -> {
                result.config?.let { config ->
                  item(key = "awgProfile") {
                    ValueRow(title = "Profile", value = config.versionLabel())
                  }
                  item(key = "awgConfigString") {
                    ValueRow(title = "Config detail", value = formatAwgConfig(config))
                  }
                }
              }
              else -> {
                item(key = "awgStandard") {
                  ValueRow(title = "Profile", value = "Standard WireGuard")
                }
              }
            }
          }
        }
        if (isPinging) {
          ModalBottomSheet(onDismissRequest = { model.onPingDismissal() }) {
            PingView(model = model.pingViewModel)
          }
        }
      }
    }
  }
}

@Composable
fun AddressRow(address: String, type: String) {
  val localClipboardManager = LocalClipboardManager.current

  // Android TV doesn't have a clipboard, nor any way to use the values, so visible only.
  val modifier =
      if (isAndroidTV()) {
        Modifier.focusable(false)
      } else {
        Modifier.clickable { localClipboardManager.setText(AnnotatedString(address)) }
      }

  ListItem(
      modifier = modifier,
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = address) },
      supportingContent = { Text(text = type) },
      trailingContent = {
        // TODO: there is some overlap with other uses of clipboard, DRY
        if (!isAndroidTV()) {
          Icon(painter = painterResource(id = R.drawable.clipboard), null)
        }
      })
}

@Composable
fun ValueRow(title: String, value: String) {
  ListItem(
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = title) },
      supportingContent = { Text(text = value) })
}

private fun formatAwgConfig(config: com.tailscale.ipn.ui.model.AmneziaWGPrefs): String {
  val parts = mutableListOf<String>()
  listOf(
          "JC" to config.JC,
          "JMin" to config.JMin,
          "JMax" to config.JMax,
          "S1" to config.S1,
          "S2" to config.S2,
          "S3" to config.S3,
          "S4" to config.S4,
      )
      .forEach { (name, value) -> value?.let { parts.add("$name=$it") } }
  listOf(
          "I1" to config.I1,
          "I2" to config.I2,
          "I3" to config.I3,
          "I4" to config.I4,
          "I5" to config.I5,
      )
      .forEach { (name, value) ->
        value?.takeIf(String::isNotEmpty)?.let { parts.add("$name=$it") }
      }
  listOf(
          "H1" to config.H1,
          "H2" to config.H2,
          "H3" to config.H3,
          "H4" to config.H4,
          "ContentPaddingAddition" to config.ContentPaddingAddition,
          "RekeyAfterTime" to config.RekeyAfterTime,
          "RekeyTimeout" to config.RekeyTimeout,
          "RejectAfterTime" to config.RejectAfterTime,
          "KeepaliveTimeout" to config.KeepaliveTimeout,
          "MaxHandshakeAttempts" to config.MaxHandshakeAttempts,
      )
      .forEach { (name, range) ->
        range?.takeIf { it.hasValue() }?.let { parts.add("$name=${it.displayValue()}") }
      }
  if (config.isV3()) parts.add("HeaderProtectionKey=set")
  return parts.joinToString("\n").ifEmpty { "No obfuscation parameters" }
}
