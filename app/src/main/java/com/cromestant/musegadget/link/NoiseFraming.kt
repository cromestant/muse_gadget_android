package com.cromestant.musegadget.link

import java.security.SecureRandom

/**
 * Noise chunk framing, mirroring
 * `ref/muse-gadget-sdk/linux/src/musegadget/noise/framing.py`.
 * Large service envelopes are split into <= 65489-byte payload chunks that
 * share one int64 chunk id; the decoder reassembles them.
 */

/** Max bytes of service payload per chunk, mirroring `framing.py:38`. */
const val MAX_CHUNK_PAYLOAD = 65489

/** Max concurrent partial assemblies, mirroring `framing.py:39`. */
const val MAX_PENDING_ASSEMBLIES = 16

/** Max chunks per message, mirroring `framing.py:40`. */
const val MAX_TOTAL_CHUNKS = 256

/** Budget across one assembly, mirroring `framing.py:41`. */
const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024

/** Assembly expiry in seconds, mirroring `framing.py:42`. */
const val ASSEMBLY_TTL_S = 60L

/**
 * One chunk of a framed message, mirroring `NoiseTransportFrame` in
 * `framing.py:45`. Plain class because ByteArray needs content equality.
 */
class NoiseTransportFrame(
    val chunkId: Long = 0,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean =
        other is NoiseTransportFrame && chunkId == other.chunkId &&
            chunkIndex == other.chunkIndex && totalChunks == other.totalChunks &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var r = chunkId.hashCode()
        r = 31 * r + chunkIndex
        r = 31 * r + totalChunks
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

private class Assembly(
    val chunks: MutableMap<Int, ByteArray> = HashMap(),
    val total: Int,
    var totalBytes: Int = 0,
    val createdAtS: Double,
    var lastUpdatedS: Double
)

/**
 * Single-frame encode, mirroring `encode_noise_frame` in `framing.py:69`.
 * chunk_id omitted when 0, chunk_index omitted when 0, total_chunks omitted
 * when 0 (exact Python condition, even though the default is 1), payload
 * omitted when empty.
 */
fun encodeNoiseFrame(frame: NoiseTransportFrame): ByteArray {
    var out = ByteArray(0)
    if (frame.chunkId != 0L) out += int64Field(1, frame.chunkId)
    if (frame.chunkIndex != 0) out += uint32Field(2, frame.chunkIndex.toLong())
    if (frame.totalChunks != 0) out += uint32Field(3, frame.totalChunks.toLong())
    if (frame.payload.isNotEmpty()) out += bytesField(4, frame.payload)
    return out
}

/**
 * Single-frame decode with field defaults (total_chunks defaults to 1),
 * mirroring `decode_noise_frame` in `framing.py:83`.
 */
fun decodeNoiseFrame(data: ByteArray): NoiseTransportFrame {
    var chunkId = 0L
    var chunkIndex = 0
    var totalChunks = 1
    var payload = ByteArray(0)
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.chunk_id wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                chunkId = wireToInt64(raw)
            }
            2 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.chunk_index wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                chunkIndex = wireToUint32(raw).toInt()
            }
            3 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.total_chunks wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                totalChunks = wireToUint32(raw).toInt()
            }
            4 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("NoiseTransportFrame.payload wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                payload = raw
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
}

/**
 * Split [data] into chunk frames, mirroring `encode_noise_frames` in
 * `framing.py:120`. A null [chunkId] draws a random signed int64 (like
 * Python's `secrets.randbits(64)` folded); empty data yields one empty frame.
 * More than 256 chunks raises [IllegalArgumentException].
 */
fun encodeNoiseFrames(data: ByteArray, chunkId: Long? = null): List<ByteArray> {
    val id = chunkId ?: SecureRandom().nextLong()
    val totalChunks = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
    if (totalChunks > MAX_TOTAL_CHUNKS) {
        throw IllegalArgumentException(
            "payload too large for noise framing " +
                "(${data.size} bytes, $totalChunks chunks > $MAX_TOTAL_CHUNKS)"
        )
    }
    if (data.isEmpty()) {
        return listOf(encodeNoiseFrame(NoiseTransportFrame(id, 0, 1, ByteArray(0))))
    }
    val frames = ArrayList<ByteArray>(totalChunks)
    for (index in 0 until totalChunks) {
        val start = index * MAX_CHUNK_PAYLOAD
        val end = minOf(start + MAX_CHUNK_PAYLOAD, data.size)
        frames.add(
            encodeNoiseFrame(
                NoiseTransportFrame(id, index, totalChunks, data.copyOfRange(start, end))
            )
        )
    }
    return frames
}

/**
 * Chunk reassembler, mirroring `NoiseFrameDecoder` in `framing.py:159`.
 * Validates ranges (total 1..256, index in range, payload size), evicts
 * assemblies older than 60 s, caps 16 pending assemblies and a 16 MB budget.
 * A duplicate index or inconsistent total drops the assembly and poisons the
 * decoder; ANY failure poisons it (later calls throw [IllegalStateException]).
 *
 * Returns null while an assembly is incomplete, the reassembled bytes when
 * the last chunk arrives (a single-chunk frame returns its payload).
 */
class NoiseFrameDecoder {
    private val pending = HashMap<Long, Assembly>()
    private var poisoned = false

    fun decode(frameBytes: ByteArray): ByteArray? {
        if (poisoned) throw IllegalStateException("NoiseFrameDecoder: poisoned after prior failure")
        try {
            val frame = decodeNoiseFrame(frameBytes)
            if (frame.totalChunks < 1 || frame.totalChunks > MAX_TOTAL_CHUNKS) {
                throw IllegalArgumentException("invalid totalChunks: ${frame.totalChunks}")
            }
            if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
                throw IllegalArgumentException(
                    "chunkIndex ${frame.chunkIndex} out of range [0, ${frame.totalChunks})"
                )
            }
            if (frame.payload.size > MAX_CHUNK_PAYLOAD) {
                throw IllegalArgumentException(
                    "payload too large for noise frame (${frame.payload.size} bytes)"
                )
            }

            evictExpired()

            var assembly = pending[frame.chunkId]
            if (assembly == null) {
                if (pending.size >= MAX_PENDING_ASSEMBLIES) {
                    throw IllegalArgumentException("too many pending noise frame assemblies")
                }
                val now = nowS()
                assembly = Assembly(total = frame.totalChunks, createdAtS = now, lastUpdatedS = now)
                pending[frame.chunkId] = assembly
            }

            if (assembly.total != frame.totalChunks) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException(
                    "inconsistent totalChunks for chunkId: " +
                        "expected ${assembly.total}, got ${frame.totalChunks}"
                )
            }
            if (assembly.chunks.containsKey(frame.chunkIndex)) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("duplicate chunkIndex ${frame.chunkIndex}")
            }

            assembly.lastUpdatedS = nowS()
            assembly.totalBytes += frame.payload.size
            if (assembly.totalBytes > MAX_ASSEMBLY_BYTES) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("assembly exceeded byte budget")
            }

            assembly.chunks[frame.chunkIndex] = frame.payload
            if (assembly.chunks.size < assembly.total) return null

            pending.remove(frame.chunkId)
            if (assembly.total == 1) return assembly.chunks[0]
            var size = 0
            for (i in 0 until assembly.total) size += assembly.chunks[i]!!.size
            val out = ByteArray(size)
            var pos = 0
            for (i in 0 until assembly.total) {
                val chunk = assembly.chunks[i]!!
                chunk.copyInto(out, pos)
                pos += chunk.size
            }
            return out
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    private fun evictExpired() {
        val now = nowS()
        val expired = ArrayList<Long>()
        for ((id, assembly) in pending) {
            if (now - assembly.createdAtS > ASSEMBLY_TTL_S) expired.add(id)
        }
        for (id in expired) pending.remove(id)
    }

    private fun nowS(): Double = System.currentTimeMillis() / 1000.0
}
