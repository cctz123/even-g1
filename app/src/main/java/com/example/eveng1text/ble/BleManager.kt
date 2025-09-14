package com.example.eveng1text.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

enum class ConnectionState { Disconnected, Connecting, Connected }

data class ScannedDevice(
	val device: BluetoothDevice,
	val name: String?,
	val address: String
)

class BleManager(
	private val context: Context
) {
	private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
	private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
	private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

	val scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
	val connectionState = MutableStateFlow(ConnectionState.Disconnected)
	val statusMessages = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	private var bluetoothGatt: BluetoothGatt? = null
	private var writeCharacteristic: BluetoothGattCharacteristic? = null
	private var negotiatedMtu: Int = 23

	private var currentProtocolConfig: BleTextProtocolConfig = BleTextProtocolConfig.NUS

	private val scanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			val current = scannedDevices.value
			if (current.any { it.address == result.device.address }) return
			val updated = current.toMutableList().apply {
				add(ScannedDevice(result.device, result.device.name, result.device.address))
			}
			scannedDevices.value = updated.sortedBy { it.name ?: it.address }
		}

		override fun onBatchScanResults(results: MutableList<ScanResult>) {
			results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
		}

		override fun onScanFailed(errorCode: Int) {
			emitStatus("Scan failed: $errorCode")
		}
	}

	fun setProtocolConfig(config: BleTextProtocolConfig) {
		currentProtocolConfig = config
	}

	fun hasBlePermissions(): Boolean {
		return if (Build.VERSION.SDK_INT >= 31) {
			listOf(
				Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_CONNECT
			).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
		} else {
			ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}
	}

	fun startScan(nameFilter: String?, serviceFilter: UUID?) {
		scannedDevices.value = emptyList()
		val settings = ScanSettings.Builder()
			.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
			.build()

		val filters = mutableListOf<ScanFilter>()
		if (!nameFilter.isNullOrBlank()) {
			filters.add(ScanFilter.Builder().setDeviceName(nameFilter).build())
		}
		if (serviceFilter != null) {
			filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceFilter)).build())
		}

		if (filters.isEmpty()) {
			bleScanner?.startScan(scanCallback)
		} else {
			bleScanner?.startScan(filters, settings, scanCallback)
		}
		emitStatus("Scanning...")
	}

	fun stopScan() {
		bleScanner?.stopScan(scanCallback)
		emitStatus("Scan stopped")
	}

	@SuppressLint("MissingPermission")
	fun disconnect() {
		connectionState.value = ConnectionState.Disconnected
		bluetoothGatt?.disconnect()
		bluetoothGatt?.close()
		bluetoothGatt = null
		writeCharacteristic = null
	}

	@SuppressLint("MissingPermission")
	suspend fun connect(device: BluetoothDevice, protocol: BleTextProtocolConfig): Boolean {
		stopScan()
		currentProtocolConfig = protocol
		connectionState.value = ConnectionState.Connecting
		emitStatus("Connecting to ${device.address}...")
		return suspendCancellableCoroutine { cont ->
			bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
				override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
					if (status != BluetoothGatt.GATT_SUCCESS) {
						emitStatus("GATT error: $status")
						connectionState.value = ConnectionState.Disconnected
						if (cont.isActive) cont.resume(false)
						return
					}
					if (newState == BluetoothProfile.STATE_CONNECTED) {
						connectionState.value = ConnectionState.Connected
						emitStatus("Connected, discovering services...")
						gatt.discoverServices()
						if (Build.VERSION.SDK_INT >= 21) {
							gatt.requestMtu(517)
						}
					} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
						connectionState.value = ConnectionState.Disconnected
						emitStatus("Disconnected")
						if (cont.isActive) cont.resume(false)
					}
				}

				override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
					if (status == BluetoothGatt.GATT_SUCCESS) {
						negotiatedMtu = mtu
						emitStatus("MTU negotiated: $mtu")
					}
				}

				override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
					if (status != BluetoothGatt.GATT_SUCCESS) {
						emitStatus("Service discovery failed: $status")
						if (cont.isActive) cont.resume(false)
						return
					}
					val ok = resolveWriteCharacteristic(gatt, protocol)
					if (!ok) {
						emitStatus("Write characteristic not found")
						disconnect()
					}
					if (cont.isActive) cont.resume(ok)
				}
			})
		}
	}

	private fun resolveWriteCharacteristic(gatt: BluetoothGatt, protocol: BleTextProtocolConfig): Boolean {
		val services = gatt.services ?: emptyList()

		// Preferred: exact service + characteristic
		if (protocol.serviceUuid != null && protocol.txCharacteristicUuid != null) {
			val svc = services.firstOrNull { it.uuid == protocol.serviceUuid }
			val chr = svc?.characteristics?.firstOrNull { it.uuid == protocol.txCharacteristicUuid }
			if (chr != null && isWritable(chr)) {
				writeCharacteristic = chr
				return true
			}
		}

		// Same service, any writable chr
		if (protocol.serviceUuid != null) {
			val svc = services.firstOrNull { it.uuid == protocol.serviceUuid }
			val chr = svc?.characteristics?.firstOrNull { isWritable(it) }
			if (chr != null) {
				writeCharacteristic = chr
				return true
			}
		}

		// Fallback: any writable char in any service
		services.forEach { svc ->
			val chr = svc.characteristics.firstOrNull { isWritable(it) }
			if (chr != null) {
				writeCharacteristic = chr
				return true
			}
		}

		return false
	}

	private fun isWritable(c: BluetoothGattCharacteristic): Boolean {
		val p = c.properties
		return (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
			(p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
	}

	@SuppressLint("MissingPermission")
	suspend fun sendText(text: String): Boolean {
		val gatt = bluetoothGatt ?: return false
		val chr = writeCharacteristic ?: return false
		val data = preparePayload(text, currentProtocolConfig)
		val chunks = splitIntoBleChunks(data, negotiatedMtu, currentProtocolConfig.chunkOverheadBytes)
		for (chunk in chunks) {
			val ok = writeNoResp(gatt, chr, chunk)
			if (!ok) return false
		}
		emitStatus("Sent ${data.size} bytes in ${chunks.size} chunks")
		return true
	}

	private fun preparePayload(text: String, cfg: BleTextProtocolConfig): ByteArray {
		val bytes = text.toByteArray(cfg.encoding)
		if (!cfg.prependLengthHeader) return bytes
		val header = byteArrayOf(
			((bytes.size shr 8) and 0xFF).toByte(),
			(bytes.size and 0xFF).toByte()
		)
		return header + bytes
	}

	private fun writeNoResp(
		gatt: BluetoothGatt,
		characteristic: BluetoothGattCharacteristic,
		chunk: ByteArray
	): Boolean {
		characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
		characteristic.value = chunk
		@Suppress("DEPRECATION")
		return gatt.writeCharacteristic(characteristic)
	}

	private fun emitStatus(msg: String) {
		statusMessages.tryEmit(msg)
	}
}