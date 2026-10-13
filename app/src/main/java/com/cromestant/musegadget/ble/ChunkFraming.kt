package com.cromestant.musegadget.ble

/**
 * Chunked BLE framing, mirroring ble_framing.py exactly.
 * Pure JVM: no Android imports.
 */
object ChunkFraming {
    const val CHUNK_MAGIC = 0xFE
    const val HEADER_BYTES = 3
    const val MAX_PACKET_BYTES = 160
    const val MAX_CHUNKS = 255
    const val MAX_MESSAGE_BYTES = 8192
    const val DEFAULT_ATT_MTU = 23
    const val CHUNK_STAGGER_S = 0.05

    /** Split [data] into framed packets that fit one notification at [mtu]. */
    fun encode(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
        val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES)
        val usable = notifyMax - HEADER_BYTES
        val fragments: List<ByteArray> = if (data.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            List((data.size + usable - 1) / usable) { i ->
                data.copyOfRange(i * usable, minOf((i + 1) * usable, data.size))
            }
        }
        if (fragments.size > MAX_CHUNKS) throw IllegalArgumentException(
            "message needs ${fragments.size} chunks (max $MAX_CHUNKS)",
        )
        val total = fragments.size
        return fragments.mapIndexed { i, frag ->
            byteArrayOf(CHUNK_MAGIC.toByte(), i.toByte(), total.toByte()) + frag
        }
    }

    /** Reassembles chunked writes, strictly in order. */
    class ChunkAssembler(private val maxBytes: Int = MAX_MESSAGE_BYTES) {
        private var buf = ByteArray(0)
        private var _total = 0
        private var _next = 0

        fun reset() {
            buf = ByteArray(0)
            _total = 0
            _next = 0
        }

        /** Add one write; returns a complete message once one is available. */
        fun feed(packet: ByteArray): ByteArray? {
            if (packet.size < HEADER_BYTES || packet[0] != CHUNK_MAGIC.toByte()) {
                return packet.copyOf()
            }
            val index = packet[1].toInt() and 0xFF
            val total = packet[2].toInt() and 0xFF
            val fragment = packet.copyOfRange(HEADER_BYTES, packet.size)
            if (total == 0) {
                reset()
                return null
            }
            if (index == 0 || total != _total) {
                reset()
                _total = total
            }
            if (index != _next || index >= _total) {
                reset()
                return null
            }
            if (buf.size + fragment.size > maxBytes) {
                reset()
                return null
            }
            buf += fragment
            _next = index + 1
            if (_next < _total) return null
            val message = buf.copyOf()
            reset()
            return message
        }
    }
}
