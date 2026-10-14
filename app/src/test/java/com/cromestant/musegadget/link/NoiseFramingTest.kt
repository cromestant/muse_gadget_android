package com.cromestant.musegadget.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoiseFramingTest {
    @Test
    fun singleFrameRoundTrip() {
        val payload = "hello link".toByteArray(Charsets.UTF_8)
        val frames = encodeNoiseFrames(payload, chunkId = 42L)
        assertEquals(1, frames.size)
        val decoded = NoiseFrameDecoder().decode(frames[0])
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun largePayloadOutOfOrderReassembly() {
        val payload = ByteArray(200_000) { (it * 31 and 0xFF).toByte() }
        val frames = encodeNoiseFrames(payload, chunkId = 7L)
        assertEquals(4, frames.size)
        // 200000 = 3 x 65489 + 3533
        val decoder = NoiseFrameDecoder()
        assertNull(decoder.decode(frames[2]))
        assertNull(decoder.decode(frames[0]))
        assertNull(decoder.decode(frames[3]))
        val reassembled = decoder.decode(frames[1])
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun duplicateChunkIndexPoisons() {
        val payload = ByteArray(70_000) { it.toByte() }
        val frames = encodeNoiseFrames(payload, chunkId = 9L)
        assertEquals(2, frames.size)
        val decoder = NoiseFrameDecoder()
        assertNull(decoder.decode(frames[0]))
        try {
            decoder.decode(frames[0])
            fail("expected duplicate-chunk failure")
        } catch (e: IllegalArgumentException) {
        }
        // Poisoned: even a fresh valid frame now throws.
        try {
            decoder.decode(encodeNoiseFrames("x".toByteArray(), chunkId = 10L)[0])
            fail("expected poisoned-decoder failure")
        } catch (e: IllegalStateException) {
        }
    }

    @Test
    fun zeroTotalChunksPoisons() {
        // total_chunks == 0 cannot come out of encodeNoiseFrame (it omits the
        // field when 0, and the wire default is 1), so craft it raw.
        val bad = uint32Field(3, 0)
        val decoder = NoiseFrameDecoder()
        try {
            decoder.decode(bad)
            fail("expected invalid-total failure")
        } catch (e: IllegalArgumentException) {
        }
        try {
            decoder.decode(bad)
            fail("expected poisoned-decoder failure")
        } catch (e: IllegalStateException) {
        }
    }

    @Test
    fun emptyPayloadEncodesToOneFrame() {
        val frames = encodeNoiseFrames(ByteArray(0), chunkId = 1L)
        assertEquals(1, frames.size)
        val frame = decodeNoiseFrame(frames[0])
        assertEquals(1L, frame.chunkId)
        assertEquals(0, frame.chunkIndex)
        assertEquals(1, frame.totalChunks)
        assertEquals(0, frame.payload.size)
        val out = NoiseFrameDecoder().decode(frames[0])
        assertTrue(out != null && out.isEmpty())
    }

    @Test
    fun tooLargePayloadRejected() {
        val huge = ByteArray(MAX_CHUNK_PAYLOAD * MAX_TOTAL_CHUNKS + 1)
        try {
            encodeNoiseFrames(huge, chunkId = 1L)
            fail("expected payload-too-large failure")
        } catch (e: IllegalArgumentException) {
        }
    }
}
