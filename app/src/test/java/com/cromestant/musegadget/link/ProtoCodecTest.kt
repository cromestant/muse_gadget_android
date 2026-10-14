package com.cromestant.musegadget.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtoCodecTest {
    @Test
    fun varintVectors() {
        assertArrayEquals(byteArrayOf(0x00), encodeVarint(0))
        assertArrayEquals(byteArrayOf(0x01), encodeVarint(1))
        assertArrayEquals(byteArrayOf(0x7F), encodeVarint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), encodeVarint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), encodeVarint(300))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            encodeVarint(0xFFFFFFFFL)
        )
        val maxBytes = ByteArray(8) { 0xFF.toByte() } + byteArrayOf(0x7F)
        assertArrayEquals(maxBytes, encodeVarint(Long.MAX_VALUE))
        // Every vector decodes back to itself.
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 0xFFFFFFFFL, Long.MAX_VALUE)) {
            val (decoded, next) = decodeVarint(encodeVarint(v), 0)
            assertEquals(v, decoded)
            assertEquals(encodeVarint(v).size, next)
        }
        // Negative and the 10-byte all-ones pattern (unsigned 2^64-1) are rejected on encode.
        try {
            encodeVarint(-1)
            fail("expected ProtoError for negative varint")
        } catch (e: ProtoError) {
        }
    }

    @Test
    fun truncatedVarintThrows() {
        try {
            decodeVarint(byteArrayOf(0x80.toByte()), 0)
            fail("expected ProtoError")
        } catch (e: ProtoError) {
        }
        try {
            decodeVarint(byteArrayOf(), 0)
            fail("expected ProtoError")
        } catch (e: ProtoError) {
        }
    }

    @Test
    fun tenByteMalformedVarintThrows() {
        val malformed = ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x02)
        try {
            decodeVarint(malformed, 0)
            fail("expected ProtoError")
        } catch (e: ProtoError) {
        }
        // The 10-byte encoding of unsigned 2^64-1 (0xFFx9 + 0x01) is well-formed.
        val minBits = ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01)
        val (decoded, next) = decodeVarint(minBits, 0)
        assertEquals(-1L, decoded)
        assertEquals(10, next)
    }

    @Test
    fun fieldRoundTrips() {
        // string
        val s = stringField(1, "héllo ✓")
        var (fn, wt, off) = readKey(s, 0)
        assertEquals(1, fn)
        assertEquals(WIRE_DELIMITED, wt)
        val (sRaw, sEnd) = readDelimited(s, off)
        assertEquals("héllo ✓", sRaw.toString(Charsets.UTF_8))
        assertEquals(s.size, sEnd)

        // bytes
        val b = bytesField(4, byteArrayOf(0x00, 0xFF.toByte(), 0x01))
        val (bfn, bwt, boff) = readKey(b, 0)
        assertEquals(4, bfn)
        assertEquals(WIRE_DELIMITED, bwt)
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x01), readDelimited(b, boff).first)

        // bool true / false
        for (v in listOf(true, false)) {
            val enc = boolField(5, v)
            val (_, w, o) = readKey(enc, 0)
            assertEquals(WIRE_VARINT, w)
            assertEquals(if (v) 1L else 0L, decodeVarint(enc, o).first)
        }

        // int32 incl. negative
        for (v in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val enc = int32Field(1, v)
            val (_, w, o) = readKey(enc, 0)
            assertEquals(WIRE_VARINT, w)
            assertEquals(v, wireToInt32(decodeVarint(enc, o).first))
        }

        // int64 incl. negative and extremes
        for (v in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, -123456789L)) {
            val enc = int64Field(1, v)
            val (_, w, o) = readKey(enc, 0)
            assertEquals(WIRE_VARINT, w)
            assertEquals(v, wireToInt64(decodeVarint(enc, o).first))
        }

        // uint32 incl. max
        for (v in listOf(0L, 2L, 0xFFFFFFFFL)) {
            val enc = uint32Field(2, v)
            val (_, w, o) = readKey(enc, 0)
            assertEquals(WIRE_VARINT, w)
            assertEquals(v, wireToUint32(decodeVarint(enc, o).first))
        }
        try {
            uint32Field(2, 0x1FFFFFFFFL)
            fail("expected ProtoError for uint32 overflow")
        } catch (e: ProtoError) {
        }
        try {
            uint32Field(2, -1L)
            fail("expected ProtoError for negative uint32")
        } catch (e: ProtoError) {
        }
        assertTrue(encodeVarint(0).contentEquals(byteArrayOf(0)))
    }

    @Test
    fun invalidFieldNumbersThrow() {
        try {
            delimitedField(0, ByteArray(0))
            fail("expected ProtoError for field 0")
        } catch (e: ProtoError) {
        }
        try {
            delimitedField(19000, ByteArray(0))
            fail("expected ProtoError for reserved field 19000")
        } catch (e: ProtoError) {
        }
        try {
            delimitedField(19999, ByteArray(0))
            fail("expected ProtoError for reserved field 19999")
        } catch (e: ProtoError) {
        }
        // Just outside the reserved range is fine.
        delimitedField(19999 + 1, ByteArray(0))
    }
}
