package com.example.eveng1text.ble

import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.min

data class BleTextProtocolConfig(
    val serviceUuid: UUID?,
    val txCharacteristicUuid: UUID?,
    val chunkOverheadBytes: Int = 3,
    val useWriteNoResponse: Boolean = true,
    val encoding: Charset = Charsets.UTF_8,
    val prependLengthHeader: Boolean = false
) {
    companion object {
        val NUS = BleTextProtocolConfig(
            serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            txCharacteristicUuid = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            chunkOverheadBytes = 3,
            useWriteNoResponse = true,
            encoding = Charsets.UTF_8,
            prependLengthHeader = false
        )
        val EVEN_G1_PLACEHOLDER = BleTextProtocolConfig(
            serviceUuid = null,
            txCharacteristicUuid = null
        )
    }
}

internal fun splitIntoBleChunks(
    payload: ByteArray,
    mtu: Int,
    overhead: Int
): List<ByteArray> {
    val maxChunk = maxOf(20, mtu - overhead)
    val result = ArrayList<ByteArray>()
    var idx = 0
    while (idx < payload.size) {
        val next = min(maxChunk, payload.size - idx)
        result.add(payload.copyOfRange(idx, idx + next))
        idx += next
    }
    return result
}