// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.model.AmneziaWGPrefs
import com.tailscale.ipn.ui.model.AwgProfileVersion
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.AwgSettingsViewModel

@Composable
fun AwgSettingsView(
    onBack: () -> Unit,
    mode: String = "manual",
    viewModel: AwgSettingsViewModel = viewModel(),
) {
  val currentConfig by viewModel.currentConfig.collectAsState()
  val awgWriteInProgress by viewModel.awgWriteInProgress.collectAsState()
  val awgProfileMutationInProgress by viewModel.awgProfileMutationInProgress.collectAsState()
  val awgProfileSyncReady by viewModel.awgProfileSyncReady.collectAsState()
  val isLoading =
      awgWriteInProgress != null || awgProfileMutationInProgress != null || !awgProfileSyncReady
  val generatedProfileVersion by viewModel.generatedProfileVersion.collectAsState()
  val statusMessage by viewModel.statusMessage.collectAsState()
  val context = LocalContext.current
  val title = if (mode == "json") "Import AWG JSON" else "Configure AWG"

  LaunchedEffect(statusMessage) {
    statusMessage?.let {
      Toast.makeText(context, it, Toast.LENGTH_LONG).show()
      viewModel.clearStatusMessage()
    }
  }

  Scaffold(
      topBar = {
        Header(
            title = { Text(title, style = MaterialTheme.typography.titleLarge) }, onBack = onBack)
      }) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)) {
              CurrentConfigStatus(currentConfig)
              Lists.SectionDivider()
              if (mode == "json") {
                JsonConfig(viewModel, isLoading)
              } else {
                ManualConfig(viewModel, isLoading, generatedProfileVersion)
              }
              Lists.SectionDivider()
              OutlinedButton(
                  onClick = viewModel::clearFields,
                  enabled = !isLoading,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              ) {
                Text("Clear input fields")
              }
            }
      }
}

@Composable
private fun CurrentConfigStatus(config: AmneziaWGPrefs?) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Text("Current profile", style = MaterialTheme.typography.labelMedium)
    Text(
        text = config?.versionLabel() ?: "Standard WireGuard",
        style = MaterialTheme.typography.titleMedium,
        color =
            if (config?.hasNonDefaultValues() == true) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ManualConfig(
    viewModel: AwgSettingsViewModel,
    isLoading: Boolean,
    generatedProfileVersion: AwgProfileVersion?,
) {
  Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Lists.LargeTitle("Quick profile generator")
    Text(
        "AWG v3 is recommended. Generate one profile, then copy the exact same JSON to every peer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { viewModel.generateProfile(AwgProfileVersion.V3) },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Generate AWG v3 (recommended)")
    }
    OutlinedButton(
        onClick = { viewModel.generateProfile(AwgProfileVersion.V2) },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Generate compatible AWG v2")
    }
    generatedProfileVersion?.let { version ->
      Button(
          onClick = viewModel::applyManualConfig,
          enabled = !isLoading,
          modifier = Modifier.fillMaxWidth(),
      ) {
        val label = if (version == AwgProfileVersion.V3) "AWG v3" else "AWG v2"
        Text("Apply generated $label profile")
      }
    }

    Spacer(Modifier.height(10.dp))
    Lists.LargeTitle("Junk packets")
    NumberField("JC", viewModel.jc.collectAsState().value) { viewModel.jc.value = it }
    NumberField("JMin", viewModel.jMin.collectAsState().value) { viewModel.jMin.value = it }
    NumberField("JMax", viewModel.jMax.collectAsState().value) { viewModel.jMax.value = it }

    Spacer(Modifier.height(8.dp))
    Lists.LargeTitle("Packet prefixes")
    NumberField("S1 (init)", viewModel.s1.collectAsState().value) { viewModel.s1.value = it }
    NumberField("S2 (response)", viewModel.s2.collectAsState().value) { viewModel.s2.value = it }
    NumberField("S3 (cookie)", viewModel.s3.collectAsState().value) { viewModel.s3.value = it }
    NumberField("S4 (transport)", viewModel.s4.collectAsState().value) { viewModel.s4.value = it }

    Spacer(Modifier.height(8.dp))
    Lists.LargeTitle("Signature packets (CPS)")
    TextValueField("I1", viewModel.i1.collectAsState().value) { viewModel.i1.value = it }
    TextValueField("I2", viewModel.i2.collectAsState().value) { viewModel.i2.value = it }
    TextValueField("I3", viewModel.i3.collectAsState().value) { viewModel.i3.value = it }
    TextValueField("I4", viewModel.i4.collectAsState().value) { viewModel.i4.value = it }
    TextValueField("I5", viewModel.i5.collectAsState().value) { viewModel.i5.value = it }
    Text(
        "AmneziaWG 2.0 removed the <c> tag. A byte value such as <b 0xc0> is valid and is not <c>.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Lists.LargeTitle("Magic headers")
    RangeFields(
        "H1",
        viewModel.h1Min.collectAsState().value,
        viewModel.h1Max.collectAsState().value,
        { viewModel.h1Min.value = it },
        { viewModel.h1Max.value = it })
    RangeFields(
        "H2",
        viewModel.h2Min.collectAsState().value,
        viewModel.h2Max.collectAsState().value,
        { viewModel.h2Min.value = it },
        { viewModel.h2Max.value = it })
    RangeFields(
        "H3",
        viewModel.h3Min.collectAsState().value,
        viewModel.h3Max.collectAsState().value,
        { viewModel.h3Min.value = it },
        { viewModel.h3Max.value = it })
    RangeFields(
        "H4",
        viewModel.h4Min.collectAsState().value,
        viewModel.h4Max.collectAsState().value,
        { viewModel.h4Min.value = it },
        { viewModel.h4Max.value = it })

    Spacer(Modifier.height(8.dp))
    Lists.LargeTitle("AWG v3 parameters")
    Text(
        "Leave this section empty for a v2 profile.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextValueField(
        "Header protection key (64 hex characters)",
        viewModel.headerProtectionKey.collectAsState().value,
    ) {
      viewModel.headerProtectionKey.value = it.trim()
    }
    RangeFields(
        "Content padding",
        viewModel.contentPaddingMin.collectAsState().value,
        viewModel.contentPaddingMax.collectAsState().value,
        { viewModel.contentPaddingMin.value = it },
        { viewModel.contentPaddingMax.value = it })
    RangeFields(
        "Rekey after",
        viewModel.rekeyAfterMin.collectAsState().value,
        viewModel.rekeyAfterMax.collectAsState().value,
        { viewModel.rekeyAfterMin.value = it },
        { viewModel.rekeyAfterMax.value = it })
    RangeFields(
        "Rekey timeout",
        viewModel.rekeyTimeoutMin.collectAsState().value,
        viewModel.rekeyTimeoutMax.collectAsState().value,
        { viewModel.rekeyTimeoutMin.value = it },
        { viewModel.rekeyTimeoutMax.value = it })
    RangeFields(
        "Reject after",
        viewModel.rejectAfterMin.collectAsState().value,
        viewModel.rejectAfterMax.collectAsState().value,
        { viewModel.rejectAfterMin.value = it },
        { viewModel.rejectAfterMax.value = it })
    RangeFields(
        "Keepalive timeout",
        viewModel.keepaliveTimeoutMin.collectAsState().value,
        viewModel.keepaliveTimeoutMax.collectAsState().value,
        { viewModel.keepaliveTimeoutMin.value = it },
        { viewModel.keepaliveTimeoutMax.value = it })
    RangeFields(
        "Max handshake attempts",
        viewModel.maxHandshakeAttemptsMin.collectAsState().value,
        viewModel.maxHandshakeAttemptsMax.collectAsState().value,
        { viewModel.maxHandshakeAttemptsMin.value = it },
        { viewModel.maxHandshakeAttemptsMax.value = it })

    Spacer(Modifier.height(12.dp))
    ApplyButton("Apply profile", isLoading, viewModel::applyManualConfig)
  }
}

@Composable
private fun JsonConfig(viewModel: AwgSettingsViewModel, isLoading: Boolean) {
  Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        "Imports historical v2 scalar headers and current v2/v3 range objects. Field names may use Go style or lowercase CLI style.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "v2 example: {\"s1\":10,\"s2\":15,\"h1\":100000,\"i1\":\"<b 0xc0><r 32>\"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = viewModel.jsonInput.collectAsState().value,
        onValueChange = { viewModel.jsonInput.value = it },
        modifier = Modifier.fillMaxWidth().height(260.dp),
        label = { Text("AWG configuration JSON") },
        textStyle = MaterialTheme.typography.bodySmall,
    )
    ApplyButton("Validate and apply JSON", isLoading, viewModel::applyJsonConfig)
  }
}

@Composable
private fun ApplyButton(label: String, isLoading: Boolean, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
    if (isLoading) {
      CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
      )
      Spacer(Modifier.size(8.dp))
    }
    Text(label)
  }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = { onValueChange(it.filter(Char::isDigit)) },
      label = { Text(label) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
  )
}

@Composable
private fun TextValueField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
  )
}

@Composable
private fun RangeFields(
    label: String,
    minValue: String,
    maxValue: String,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
        value = minValue,
        onValueChange = { onMinChange(it.filter(Char::isDigit)) },
        label = { Text("$label min") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
    OutlinedTextField(
        value = maxValue,
        onValueChange = { onMaxChange(it.filter(Char::isDigit)) },
        label = { Text("$label max") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
        singleLine = true,
    )
  }
}
