package com.cromestant.musegadget.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ChunkFramingTest {
    @Test
    fun roundTripAtMinMtu() {
        val data = ByteArray(500) { it.toByte() }
        val packets = ChunkFraming.encode(data, mtu = 23)
        for (p in packets) {
            if (p.size > 20) fail("packet ${p.size} bytes exceeds mtu 23 budget")
        }
        val asm = ChunkFraming.ChunkAssembler()
        var out: ByteArray? = null
        for (p in packets) {
            val m = asm.feed(p)
            if (m != null) {
                if (out != null) fail("two messages from one encode")
                out = m
            }
        }
        assertArrayEquals(data, out)
    }

    @Test
    fun roundTripAtLargeMtu() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val packets = ChunkFraming.encode(data, mtu = 185)
        for (p in packets) {
            if (p.size > 182) fail("packet ${p.size} bytes exceeds mtu 185 budget")
        }
        val asm = ChunkFraming.ChunkAssembler()
        var out: ByteArray? = null
        for (p in packets) {
            val m = asm.feed(p)
            if (m != null) {
                if (out != null) fail("two messages from one encode")
                out = m
            }
        }
        assertArrayEquals(data, out)
    }

    @Test
    fun emptyInputIsASingleEmptyChunk() {
        val packets = ChunkFraming.encode(ByteArray(0))
        assertEquals(1, packets.size)
        assertArrayEquals(
            byteArrayOf(0xFE.toByte(), 0, 1),
            packets[0],
        )
        assertArrayEquals(ByteArray(0), ChunkFraming.ChunkAssembler().feed(packets[0]))
    }

    @Test
    fun tooManyChunksRejected() {
        // mtu 23 -> usable 17 bytes/chunk; 255 chunks hold 4335 bytes.
        try {
            ChunkFraming.encode(ByteArray(4336), mtu = 23)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
        // Exactly 255 chunks is fine.
        assertEquals(255, ChunkFraming.encode(ByteArray(4335), mtu = 23).size)
    }

    @Test
    fun unchunkedPassthrough() {
        val asm = ChunkFraming.ChunkAssembler()
        assertArrayEquals("hi".toByteArray(), asm.feed("hi".toByteArray()))
        assertArrayEquals(byteArrayOf(0x01, 0x02), asm.feed(byteArrayOf(0x01, 0x02)))
        // First byte != 0xFE with >= 3 bytes is still unchunked.
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x02, 0x41),
            asm.feed(byteArrayOf(0x00, 0x00, 0x02, 0x41)),
        )
    }

    @Test
    fun inOrderReassembly() {
        val asm = ChunkFraming.ChunkAssembler()
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 2, 0x41)))
        assertArrayEquals(byteArrayOf(0x41, 0x42), asm.feed(byteArrayOf(0xFE.toByte(), 1, 2, 0x42)))
    }

    @Test
    fun outOfOrderChunkDiscards() {
        val asm = ChunkFraming.ChunkAssembler()
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 3, 0x41)))
        // Skipping index 1 discards the message.
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 2, 3, 0x42)))
        // The collector reset; a fresh single-chunk message completes.
        assertArrayEquals(
            byteArrayOf(0x43),
            asm.feed(byteArrayOf(0xFE.toByte(), 0, 1, 0x43)),
        )
    }

    @Test
    fun singleChunkCompletesImmediately() {
        val asm = ChunkFraming.ChunkAssembler()
        assertArrayEquals(
            byteArrayOf(0x41),
            asm.feed(byteArrayOf(0xFE.toByte(), 0, 1, 0x41)),
        )
    }

    @Test
    fun indexPastTotalDiscards() {
        val asm = ChunkFraming.ChunkAssembler()
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 3, 2, 0x41)))
        // State was reset; index 0 with total 1 completes.
        assertArrayEquals(
            byteArrayOf(0x42),
            asm.feed(byteArrayOf(0xFE.toByte(), 0, 1, 0x42)),
        )
    }

    @Test
    fun zeroTotalResets() {
        val asm = ChunkFraming.ChunkAssembler()
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 2, 0x41)))
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 0)))
        // Partial message was dropped; start over.
        assertArrayEquals(
            byteArrayOf(0x42),
            asm.feed(byteArrayOf(0xFE.toByte(), 0, 1, 0x42)),
        )
    }

    @Test
    fun totalChangeStartsNewMessage() {
        val asm = ChunkFraming.ChunkAssembler()
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 3, 0x41)))
        // Same index 0 but different total: old buffer dropped, new started.
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 2, 0x42)))
        assertArrayEquals(
            byteArrayOf(0x42, 0x43),
            asm.feed(byteArrayOf(0xFE.toByte(), 1, 2, 0x43)),
        )
    }

    @Test
    fun oversizeMessageDiscards() {
        val asm = ChunkFraming.ChunkAssembler(maxBytes = 4)
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 0, 3, 0x41, 0x42, 0x43)))
        // 3 buffered bytes so far; 2 more would exceed 4 -> discard.
        assertNull(asm.feed(byteArrayOf(0xFE.toByte(), 1, 3, 0x44, 0x45)))
        // Collector reset; a complete small message still works.
        assertArrayEquals(
            byteArrayOf(0x46),
            asm.feed(byteArrayOf(0xFE.toByte(), 0, 1, 0x46)),
        )
    }

    @Test
    fun defaultMaxBytesIs8192() {
        assertEquals(8192, ChunkFraming.MAX_MESSAGE_BYTES)
        val asm = ChunkFraming.ChunkAssembler()
        val data = ByteArray(8192) { 0x41 }
        var out: ByteArray? = null
        for (p in ChunkFraming.encode(data, mtu = 185)) {
            out = asm.feed(p) ?: out
        }
        assertArrayEquals(data, out)
    }
}
