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
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = node.connectedColor(netmap),
                                                    shape = RoundedCornerShape(percent = 50),
                                                ),
                                    ) {}
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(
                                        text = stringResource(id = node.connectedStrRes(netmap)),
                                        style = MaterialTheme.typography.bodyMedium.short,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { model.startPing() }) {
                                Icon(
                                    painter = painterResource(R.drawable.timer),
                                    contentDescription = "Ping device",
                                )
                            }
                        },
                        onBack = onNavigateBack,
                    )
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

                    // AWG Configuration section
                    awgConfig?.let { config ->
                        if (config.hasAwgConfig) {
                            item(key = "awgDivider") { Lists.SectionDivider() }

                            item(key = "awgHeader") {
                                Lists.MutedHeader("AWG Config")
                            }

                            config.config?.let { awgPrefs ->
                                item(key = "awgConfigString") {
                                    ValueRow(
                                        title = "Config Detail",
                                        value = formatAwgConfig(awgPrefs),
                                    )
                                }
                            }

                            if (config.error != null) {
                                item(key = "awgError") {
                                    ValueRow(
                                        title = "Config Error",
                                        value = config.error,
                                    )
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

    config.JC?.let { parts.add("JC=$it") }
    config.JMin?.let { parts.add("JMin=$it") }
    config.JMax?.let { parts.add("JMax=$it") }
    config.S1?.let { parts.add("S1=$it") }
    config.S2?.let { parts.add("S2=$it") }
    config.I1?.let { if (it.isNotEmpty()) parts.add("I1=$it") }
    config.I2?.let { if (it.isNotEmpty()) parts.add("I2=$it") }
    config.I3?.let { if (it.isNotEmpty()) parts.add("I3=$it") }
    config.I4?.let { if (it.isNotEmpty()) parts.add("I4=$it") }
    config.I5?.let { if (it.isNotEmpty()) parts.add("I5=$it") }
    config.H1?.let { parts.add("H1=$it") }
    config.H2?.let { parts.add("H2=$it") }
    config.H3?.let { parts.add("H3=$it") }
    config.H4?.let { parts.add("H4=$it") }

    return if (parts.isEmpty()) {
        "Base Config"
    } else {
        parts.joinToString("\n")
    }
}
