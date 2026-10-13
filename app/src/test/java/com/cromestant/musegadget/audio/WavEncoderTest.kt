package com.cromestant.musegadget.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {
    @Test
    fun goldenHeaderFields() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val wav = WavEncoder.pcmToWav(pcm, 16000)
        assertEquals(48, wav.size)
        fun ascii(off: Int, len: Int) = wav.copyOfRange(off, off + len).toString(Charsets.US_ASCII)
        assertEquals("RIFF", ascii(0, 4))
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xFFFFFFFF.toInt(), bb.getInt(4)) // chunk size UNKNOWN
        assertEquals("WAVEfmt ", ascii(8, 8))
        assertEquals(16, bb.getInt(16))
        assertEquals(1.toShort(), bb.getShort(20))
        assertEquals(1.toShort(), bb.getShort(22))
        assertEquals(16000, bb.getInt(24))
        assertEquals(32000, bb.getInt(28))
        assertEquals(2.toShort(), bb.getShort(32))
        assertEquals(16.toShort(), bb.getShort(34))
        assertEquals("data", ascii(36, 4))
        assertEquals(0xFFFFFFFF.toInt(), bb.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }
}
