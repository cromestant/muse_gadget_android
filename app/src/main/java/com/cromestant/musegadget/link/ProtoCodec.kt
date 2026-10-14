package com.cromestant.musegadget.link

import java.io.ByteArrayOutputStream

/**
 * Malformed-protobuf signal, mirroring `ProtoError` in
 * `ref/muse-gadget-sdk/linux/src/musegadget/noise/_proto.py:30`.
 */
class ProtoError(message: String) : IllegalArgumentException(message)

/** Wire types, mirroring `_proto.py:20-23`. */
const val WIRE_VARINT = 0
const val WIRE_FIXED64 = 1
const val WIRE_DELIMITED = 2
const val WIRE_FIXED32 = 5

private const val MAX_FIELD_NUMBER = (1 shl 29) - 1

private fun validFieldNumber(n: Int): Boolean =
    n != 0 && n <= MAX_FIELD_NUMBER && n !in 19000..19999

private fun checkWireType(wireType: Int) {
    if (wireType != WIRE_VARINT && wireType != WIRE_FIXED64 &&
        wireType != WIRE_DELIMITED && wireType != WIRE_FIXED32
    ) {
        throw ProtoError("invalid wire type")
    }
}

/**
 * Unsigned LEB128 encode of a non-negative [value], mirroring
 * `encode_varint` in `_proto.py:42`. Rejects negatives (>= 2^64 is
 * unrepresentable as a Long). Values that are semantically unsigned but
 * stored as negative Long bit patterns (e.g. int64 -1) must go through
 * [int64Field]/[int32Field], which encode the raw bits directly.
 */
fun encodeVarint(value: Long): ByteArray {
    if (value < 0) throw ProtoError("varint value must be non-negative")
    return encodeVarintBits(value)
}

/** Encode the 64-bit two's-complement pattern as an unsigned varint (up to 10 bytes). */
private fun encodeVarintBits(value: Long): ByteArray {
    val out = ByteArrayOutputStream()
    var v = value
    while (true) {
        val byte = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) {
            out.write(byte or 0x80)
        } else {
            out.write(byte)
            return out.toByteArray()
        }
    }
}

/**
 * Strict varint decode returning (unsigned value as two's-complement Long, new offset),
 * mirroring `read_varint` in `_proto.py:136`. Truncated input and the 10-byte
 * malformed form (`i == 9 and (byte & 0xFE) != 0`) raise [ProtoError].
 */
fun decodeVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
    var value = 0L
    var shift = 0
    var off = offset
    for (i in 0 until 10) {
        if (off >= data.size) throw ProtoError("truncated varint")
        val byte = data[off].toInt() and 0xFF
        off++
        if (i == 9 && (byte and 0xFE) != 0) throw ProtoError("malformed varint")
        value = value or ((byte and 0x7F).toLong() shl shift)
        if ((byte and 0x80) == 0) return value to off
        shift += 7
    }
    throw ProtoError("malformed varint")
}

/**
 * Field key encode, mirroring `encode_key` in `_proto.py:94`.
 * Invalid field numbers and unknown wire types raise [ProtoError].
 */
fun encodeKey(fieldNumber: Int, wireType: Int): ByteArray {
    if (!validFieldNumber(fieldNumber)) throw ProtoError("invalid field number")
    checkWireType(wireType)
    return encodeVarintBits(((fieldNumber shl 3) or wireType).toLong())
}

/**
 * Field key decode returning (fieldNumber, wireType, newOffset),
 * mirroring `read_key` in `_proto.py:153`.
 */
fun readKey(data: ByteArray, offset: Int): Triple<Int, Int, Int> {
    val (key, off) = decodeVarint(data, offset)
    val fieldNumber = (key ushr 3).toInt()
    val wireType = (key and 0x07).toInt()
    if (!validFieldNumber(fieldNumber)) throw ProtoError("invalid field number")
    checkWireType(wireType)
    return Triple(fieldNumber, wireType, off)
}

/** Varint field, mirroring `varint_field` in `_proto.py:102`. */
fun varintField(fieldNumber: Int, value: Long): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(value)

/**
 * Signed int64 field, mirroring `int64_field`/`signed_int64_value` in
 * `_proto.py:106,59`. Any Long is in int64 range; its bit pattern is
 * encoded as unsigned (same as Python's `value & 2^64-1`).
 */
fun int64Field(fieldNumber: Int, value: Long): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarintBits(value)

/**
 * Signed int32 field, mirroring `int32_field`/`signed_int32_value` in
 * `_proto.py:110,65`. Any Int is in range; sign-extended bits match
 * Python's `value & 2^64-1` masking.
 */
fun int32Field(fieldNumber: Int, value: Int): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarintBits(value.toLong())

/** Unsigned 32-bit field, mirroring `uint32_field` in `_proto.py:114`. */
fun uint32Field(fieldNumber: Int, value: Long): ByteArray {
    if (value < 0 || value > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
    return encodeKey(fieldNumber, WIRE_VARINT) + encodeVarintBits(value)
}

/** Bool field, mirroring `bool_field` in `_proto.py:120`. */
fun boolField(fieldNumber: Int, value: Boolean): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarintBits(if (value) 1L else 0L)

/** Length-delimited field, mirroring `delimited_field` in `_proto.py:124`. */
fun delimitedField(fieldNumber: Int, payload: ByteArray): ByteArray =
    encodeKey(fieldNumber, WIRE_DELIMITED) + encodeVarintBits(payload.size.toLong()) + payload

/** UTF-8 string field, mirroring `string_field` in `_proto.py:128`. */
fun stringField(fieldNumber: Int, s: String): ByteArray =
    delimitedField(fieldNumber, s.toByteArray(Charsets.UTF_8))

/** Raw-bytes field, mirroring `bytes_field` in `_proto.py:132`. */
fun bytesField(fieldNumber: Int, b: ByteArray): ByteArray =
    delimitedField(fieldNumber, b.copyOf())

/**
 * Length-delimited read returning (payload, newOffset),
 * mirroring `read_delimited` in `_proto.py:164`.
 */
fun readDelimited(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
    val (length, off) = decodeVarint(data, offset)
    if (length < 0) throw ProtoError("truncated delimited field")
    // length as unsigned could exceed Int range; guard before narrowing.
    if (length > (data.size - off).toLong()) throw ProtoError("truncated delimited field")
    val end = off + length.toInt()
    if (end > data.size || end < off) throw ProtoError("truncated delimited field")
    return data.copyOfRange(off, end) to end
}

/** Skip one unknown field, mirroring `skip_field` in `_proto.py:172`. */
fun skipField(data: ByteArray, offset: Int, wireType: Int): Int {
    when (wireType) {
        WIRE_VARINT -> return decodeVarint(data, offset).second
        WIRE_FIXED64 -> {
            val end = offset + 8
            if (end > data.size) throw ProtoError("truncated fixed64 field")
            return end
        }
        WIRE_DELIMITED -> return readDelimited(data, offset).second
        WIRE_FIXED32 -> {
            val end = offset + 4
            if (end > data.size) throw ProtoError("truncated fixed32 field")
            return end
        }
        else -> throw ProtoError("invalid wire type")
    }
}

/**
 * Unsigned wire value -> signed int64 (two's complement; the Long bit
 * pattern already is the result), mirroring `decode_int64` in `_proto.py:71`.
 */
fun wireToInt64(raw: Long): Long = raw

/**
 * Unsigned wire value -> signed int32 with range check, mirroring
 * `decode_int32` in `_proto.py:79`.
 */
fun wireToInt32(raw: Long): Int {
    if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) throw ProtoError("int32 value out of range")
    return raw.toInt()
}

/** Unsigned wire value -> uint32 with range check, mirroring `decode_uint32` in `_proto.py:88`. */
fun wireToUint32(raw: Long): Long {
    if (raw < 0 || raw > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
    return raw
}
