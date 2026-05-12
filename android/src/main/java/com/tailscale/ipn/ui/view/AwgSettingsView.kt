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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.AwgSettingsViewModel

@Composable
fun AwgSettingsView(
    onBack: () -> Unit,
    mode: String = "manual", // "manual" or "json"
    viewModel: AwgSettingsViewModel = viewModel(),
) {
    val currentConfig by viewModel.currentConfig.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val context = LocalContext.current

    val title = if (mode == "json") "Set JSON AWG Config" else "Set Manual AWG Config"

    LaunchedEffect(statusMessage) {
        statusMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            Header(title = { Text(title, style = MaterialTheme.typography.titleLarge) }, onBack = onBack)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Current config status
            CurrentConfigStatus(currentConfig?.hasNonDefaultValues() == true)

            Lists.SectionDivider()

            when (mode) {
                "manual" -> ManualConfigTab(viewModel, isLoading)
                "json" -> JsonConfigTab(viewModel, isLoading)
            }

            Lists.SectionDivider()

            // Clear input fields button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = { viewModel.clearFields() },
                    enabled = !isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear Input")
                }
            }
        }
    }
}

@Composable
private fun CurrentConfigStatus(hasConfig: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Current Status: ",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (hasConfig) "AWG Configured ★" else "Standard WireGuard",
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasConfig) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualConfigTab(viewModel: AwgSettingsViewModel, isLoading: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Lists.LargeTitle("Junk Packets")
        NumberField("JC (Junk Count)", viewModel.jc.collectAsState().value) { viewModel.jc.value = it }
        NumberField("JMin (Min Size)", viewModel.jMin.collectAsState().value) { viewModel.jMin.value = it }
        NumberField("JMax (Max Size)", viewModel.jMax.collectAsState().value) { viewModel.jMax.value = it }

        Spacer(modifier = Modifier.height(8.dp))
        Lists.LargeTitle("Packet Prefix")
        NumberField("S1 (Init)", viewModel.s1.collectAsState().value) { viewModel.s1.value = it }
        NumberField("S2 (Response)", viewModel.s2.collectAsState().value) { viewModel.s2.value = it }
        NumberField("S3 (Cookie)", viewModel.s3.collectAsState().value) { viewModel.s3.value = it }
        NumberField("S4 (Transport)", viewModel.s4.collectAsState().value) { viewModel.s4.value = it }

        Spacer(modifier = Modifier.height(8.dp))
        Lists.LargeTitle("Signature Packets (CPS format)")
        TextField("I1", viewModel.i1.collectAsState().value) { viewModel.i1.value = it }
        TextField("I2", viewModel.i2.collectAsState().value) { viewModel.i2.value = it }
        TextField("I3", viewModel.i3.collectAsState().value) { viewModel.i3.value = it }
        TextField("I4", viewModel.i4.collectAsState().value) { viewModel.i4.value = it }
        TextField("I5", viewModel.i5.collectAsState().value) { viewModel.i5.value = it }

        Spacer(modifier = Modifier.height(8.dp))
        Lists.LargeTitle("Magic Headers")
        HeaderRangeFields("H1", viewModel.h1Min.collectAsState().value, viewModel.h1Max.collectAsState().value,
            { viewModel.h1Min.value = it }, { viewModel.h1Max.value = it })
        HeaderRangeFields("H2", viewModel.h2Min.collectAsState().value, viewModel.h2Max.collectAsState().value,
            { viewModel.h2Min.value = it }, { viewModel.h2Max.value = it })
        HeaderRangeFields("H3", viewModel.h3Min.collectAsState().value, viewModel.h3Max.collectAsState().value,
            { viewModel.h3Min.value = it }, { viewModel.h3Max.value = it })
        HeaderRangeFields("H4", viewModel.h4Min.collectAsState().value, viewModel.h4Max.collectAsState().value,
            { viewModel.h4Min.value = it }, { viewModel.h4Max.value = it })

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { viewModel.applyManualConfig() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Apply Config")
        }
    }
}

@Composable
private fun JsonConfigTab(viewModel: AwgSettingsViewModel, isLoading: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Paste AWG configuration JSON:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Example: {\"JC\":2,\"JMin\":64,\"JMax\":128,\"I1\":\"<b 0x40><r 12>\"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = viewModel.jsonInput.collectAsState().value,
            onValueChange = { viewModel.jsonInput.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            label = { Text("JSON Config") },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { viewModel.applyJsonConfig() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Apply JSON Config")
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { newVal -> onValueChange(newVal.filter { it.isDigit() }) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun TextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun HeaderRangeFields(
    label: String,
    minValue: String,
    maxValue: String,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = minValue,
            onValueChange = { newVal -> onMinChange(newVal.filter { it.isDigit() }) },
            label = { Text("$label Min") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = maxValue,
            onValueChange = { newVal -> onMaxChange(newVal.filter { it.isDigit() }) },
            label = { Text("$label Max") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}
