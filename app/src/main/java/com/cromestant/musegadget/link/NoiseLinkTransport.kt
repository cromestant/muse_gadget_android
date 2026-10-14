package com.cromestant.musegadget.link

import com.cromestant.musegadget.noise.CipherState
import com.cromestant.musegadget.noise.NoiseProtocolError

/**
 * Encrypted Noise link transport, mirroring
 * `ref/muse-gadget-sdk/linux/src/musegadget/noise/transport.py`.
 *
 * Internal layering per message: ServiceFrame -> [encodeServiceFrame] ->
 * ServiceRequest(service=DAEMON) -> [encodeNoiseFrames] -> each chunk
 * AES-GCM encrypted with empty AD. Stream ids start at 1 and increment per
 * new request (the control stream takes 1). Any failure poisons the
 * transport: later calls throw [NoiseProtocolError].
 */

/** Empty associated data for every transport record, mirroring `transport.py:42`. */
val EMPTY_AD = ByteArray(0)

/**
 * Encrypted request handle, mirroring `EncryptedFrames` in `transport.py:52`.
 */
data class EncryptedFrames(val streamId: Long, val frames: List<ByteArray>)

/**
 * One decrypted server message, mirroring `DecryptedFrame` in `transport.py:58`
 * as a sealed class (Python uses a `kind` string with `response`/`body_chunk`/`reset`
 * accessors; the subclasses carry the same payloads with types enforced).
 */
sealed class DecryptedFrame {
    /** Server HTTP response, mirroring kind `"response"` in `transport.py:207`. */
    class Response(val streamId: Long, val response: ApplicationResponse) : DecryptedFrame()

    /** Server streaming body fragment, mirroring kind `"body_chunk"` in `transport.py:215`. */
    class BodyChunk(
        val streamId: Long,
        val chunk: com.cromestant.musegadget.link.BodyChunk
    ) : DecryptedFrame()

    /** Server stream reset, mirroring kind `"reset"` in `transport.py:223`. */
    class Reset(
        val streamId: Long,
        val reset: com.cromestant.musegadget.link.Reset
    ) : DecryptedFrame()
}

/**
 * Bidirectional encrypted session over a split Noise XX handshake,
 * mirroring `NoiseTransport` in `transport.py:86`. [send] encrypts with our
 * initiator send key, [recv] decrypts with our receive key; the peer builds
 * its transport with the cipher states swapped.
 */
class NoiseLinkTransport(private val send: CipherState, private val recv: CipherState) {
    private val decoder = NoiseFrameDecoder()
    private var nextStreamId = 1L
    private var dead = false

    private fun assertAlive() {
        if (dead) throw NoiseProtocolError("NoiseLinkTransport: dead after prior failure")
    }

    /**
     * One-shot HTTP request (end_body=true), mirroring
     * `encrypt_http_request` in `transport.py:98`.
     */
    fun encryptHttpRequest(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        headers: List<Header> = emptyList()
    ): EncryptedFrames {
        assertAlive()
        try {
            val streamId = nextStreamId++
            val frame = ServiceFrame.request(
                streamId,
                ApplicationRequest(
                    verb = method,
                    path = path,
                    headers = headers.toList(),
                    body = body.copyOf(),
                    endBody = true
                )
            )
            return EncryptedFrames(streamId, encryptRequest(frame))
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    /**
     * Streaming HTTP request head (end_body=false, empty body), mirroring
     * `start_stream_request` in `transport.py:115`. The control stream is
     * opened this way and keeps stream id 1.
     */
    fun startStreamRequest(
        method: String,
        path: String,
        headers: List<Header> = emptyList()
    ): EncryptedFrames {
        assertAlive()
        try {
            val streamId = nextStreamId++
            val frame = ServiceFrame.request(
                streamId,
                ApplicationRequest(
                    verb = method,
                    path = path,
                    headers = headers.toList(),
                    body = ByteArray(0),
                    endBody = false
                )
            )
            return EncryptedFrames(streamId, encryptRequest(frame))
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    /**
     * Body chunk on an open stream, mirroring `encrypt_body_chunk` in
     * `transport.py:158`.
     */
    fun encryptBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean): List<ByteArray> {
        assertAlive()
        try {
            val frame = ServiceFrame.bodyChunk(
                streamId,
                com.cromestant.musegadget.link.BodyChunk(data.copyOf(), endBody)
            )
            return encryptRequest(frame)
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    /**
     * Stream reset, mirroring `encrypt_reset` in `transport.py:176`.
     */
    fun encryptReset(
        streamId: Long,
        reason: String = "",
        code: Int = ResetCode.CANCELLED
    ): List<ByteArray> {
        assertAlive()
        try {
            val frame = ServiceFrame.reset(
                streamId,
                com.cromestant.musegadget.link.Reset(code, reason)
            )
            return encryptRequest(frame)
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    /**
     * Decrypt one server record, mirroring `decrypt_frame` in `transport.py:194`.
     * Returns null while a multi-chunk message is still assembling. A
     * `request` frame from the server raises [NoiseProtocolError], as does an
     * empty ServiceResponse payload.
     */
    fun decryptFrame(ciphertext: ByteArray): DecryptedFrame? {
        assertAlive()
        try {
            val plainFrame = recv.decryptWithAd(EMPTY_AD, ciphertext)
            val reassembled = decoder.decode(plainFrame) ?: return null
            val response = decodeServiceResponse(reassembled)
            if (response.payload.isEmpty()) throw NoiseProtocolError("empty ServiceResponse payload")
            val frame = decodeServiceFrame(response.payload)
            when (frame.kind) {
                FrameKind.RESPONSE -> {
                    val value = frame.value as? ApplicationResponse
                        ?: throw NoiseProtocolError("invalid response frame")
                    return DecryptedFrame.Response(frame.streamId, value)
                }
                FrameKind.BODY_CHUNK -> {
                    val value = frame.value as? com.cromestant.musegadget.link.BodyChunk
                        ?: throw NoiseProtocolError("invalid body_chunk frame")
                    return DecryptedFrame.BodyChunk(frame.streamId, value)
                }
                FrameKind.RESET -> {
                    val value = frame.value as? com.cromestant.musegadget.link.Reset
                        ?: throw NoiseProtocolError("invalid reset frame")
                    return DecryptedFrame.Reset(frame.streamId, value)
                }
                FrameKind.REQUEST ->
                    throw NoiseProtocolError("NoiseLinkTransport: unexpected request frame from server")
                null -> return null
            }
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    private fun encryptRequest(frame: ServiceFrame): List<ByteArray> {
        val frameBytes = encodeServiceFrame(frame)
        val requestBytes = encodeServiceRequest(
            ServiceRequest(service = ServiceType.DAEMON, payload = frameBytes)
        )
        return encodeNoiseFrames(requestBytes).map { send.encryptWithAd(EMPTY_AD, it) }
    }
}
