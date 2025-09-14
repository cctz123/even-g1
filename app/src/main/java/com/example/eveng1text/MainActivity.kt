package com.example.eveng1text

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.eveng1text.ble.BleManager
import com.example.eveng1text.ble.BleTextProtocolConfig
import com.example.eveng1text.ble.ConnectionState
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val bleManager = BleManager(this)
		setContent {
			MaterialTheme {
				AppScreen(bleManager)
			}
		}
	}
}

@Composable
fun AppScreen(bleManager: BleManager) {
	val scope = rememberCoroutineScope()
	val scanned by bleManager.scannedDevices.collectAsState()
	val connState by bleManager.connectionState.collectAsState()

	var nameFilter by remember { mutableStateOf("Even") }
	var textToSend by remember { mutableStateOf("Hello, Even G1!") }
	var lastStatus by remember { mutableStateOf("Idle") }

	LaunchedEffect(Unit) {
		bleManager.statusMessages.collect { lastStatus = it }
	}

	// Permissions
	val requiredPermissions = remember {
		if (Build.VERSION.SDK_INT >= 31) {
			arrayOf(
				Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_CONNECT
			)
		} else {
			arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
		}
	}
	val permissionLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { }

	LaunchedEffect(Unit) {
		if (!bleManager.hasBlePermissions()) {
			permissionLauncher.launch(requiredPermissions)
		}
	}

	// Protocol/UUID inputs
	var evenServiceUuid by remember { mutableStateOf("") }
	var evenTxCharUuid by remember { mutableStateOf("") }
	var useNus by remember { mutableStateOf(true) }

	// Device selection
	var selectedAddress by remember { mutableStateOf<String?>(null) }

	Surface(Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier
				.padding(16.dp)
				.fillMaxSize(),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Text("Even G1 Text Sender", style = MaterialTheme.typography.titleLarge)

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = nameFilter,
					onValueChange = { nameFilter = it },
					label = { Text("Name filter (e.g., Even)") },
					modifier = Modifier.weight(1f)
				)
				Button(onClick = {
					if (!bleManager.hasBlePermissions()) {
						permissionLauncher.launch(requiredPermissions)
					} else {
						bleManager.startScan(
							nameFilter = nameFilter.ifBlank { null },
							serviceFilter = parseUuidOrNull(evenServiceUuid)
						)
					}
				}) { Text("Scan") }
				Button(onClick = { bleManager.stopScan() }) { Text("Stop") }
			}

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = evenServiceUuid,
					onValueChange = { evenServiceUuid = it },
					label = { Text("Service UUID (optional)") },
					modifier = Modifier.weight(1f)
				)
				OutlinedTextField(
					value = evenTxCharUuid,
					onValueChange = { evenTxCharUuid = it },
					label = { Text("TX Char UUID (optional)") },
					modifier = Modifier.weight(1f)
				)
			}

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				AssistChip(
					onClick = { useNus = !useNus },
					label = { Text(if (useNus) "Protocol: NUS" else "Protocol: Custom") }
				)
				Text("Status: $lastStatus")
				Text("Connection: $connState")
			}

			OutlinedTextField(
				value = textToSend,
				onValueChange = { textToSend = it },
				label = { Text("Text to send") },
				modifier = Modifier.fillMaxWidth()
			)

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				val selectedDevice: BluetoothDevice? = remember(scanned, selectedAddress) {
					scanned.firstOrNull { it.address == selectedAddress }?.device
				}
				Button(
					enabled = selectedDevice != null && connState != ConnectionState.Connected,
					onClick = {
						val dev = selectedDevice ?: return@Button
						val cfg = if (useNus) {
							BleTextProtocolConfig.NUS
						} else {
							BleTextProtocolConfig(
								serviceUuid = parseUuidOrNull(evenServiceUuid),
								txCharacteristicUuid = parseUuidOrNull(evenTxCharUuid),
								prependLengthHeader = false,
								useWriteNoResponse = true
							)
						}
						scope.launch { bleManager.connect(dev, cfg) }
					}
				) { Text("Connect") }
				Button(
					onClick = { bleManager.disconnect() },
					enabled = connState == ConnectionState.Connected
				) { Text("Disconnect") }
				Button(
					onClick = { scope.launch { bleManager.sendText(textToSend) } },
					enabled = connState == ConnectionState.Connected
				) { Text("Send") }
			}

			Text("Devices:")
			LazyColumn(Modifier.weight(1f)) {
				items(scanned, key = { it.address }) { item ->
					ElevatedCard(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 4.dp)
							.clickable { selectedAddress = item.address }
					) {
						Row(
							Modifier.padding(12.dp),
							horizontalArrangement = Arrangement.SpaceBetween
						) {
							Column(Modifier.weight(1f)) {
								Text(item.name ?: "(no name)")
								Text(item.address, style = MaterialTheme.typography.bodySmall)
							}
							if (selectedAddress == item.address) {
								Text("Selected", color = MaterialTheme.colorScheme.primary)
							}
						}
					}
				}
			}
		}
	}
}

private fun parseUuidOrNull(text: String): UUID? =
	runCatching { if (text.isBlank()) null else UUID.fromString(text.trim()) }.getOrNull()