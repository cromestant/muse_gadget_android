package com.cromestant.musegadget.link

import com.cromestant.musegadget.noise.CipherState
import com.cromestant.musegadget.noise.NoiseProtocolError
import com.cromestant.musegadget.noise.NoiseXXInitiator
import com.cromestant.musegadget.noise.TestResponder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoiseLinkTransportTest {
    private class Peer(
        val transport: NoiseLinkTransport,
        val send: CipherState,
        val recv: CipherState
    )

    private fun linkedPair(): Pair<Peer, Peer> {
        val init = NoiseXXInitiator()
        init.initialize()
        val resp = TestResponder()
        resp.initialize()
        resp.readMessage1AndWriteMessage2(init.writeMessage1()).let { init.readMessage2(it) }
        resp.readMessage3(init.writeMessage3())
        val (iSend, iRecv) = init.split()
        val (rSend, rRecv) = resp.split()
        return Peer(NoiseLinkTransport(iSend, iRecv), iSend, iRecv) to
            Peer(NoiseLinkTransport(rSend, rRecv), rSend, rRecv)
    }

    @Test
    fun httpRequestRoundTrip() {
        val (a, b) = linkedPair()
        val body = "{\"message\":\"hi\"}".toByteArray(Charsets.UTF_8)
        val headers = listOf(Header("Content-Type", "application/json"))
        val encrypted = a.transport.encryptHttpRequest("POST", "/chat/stream", body, headers)
        assertEquals(1L, encrypted.streamId)

        // Device->server direction carries a request: decode the wire manually
        // (decryptFrame only accepts server->device kinds) and check the fields.
        // Note the device wraps in ServiceRequest (field 2), not ServiceResponse.
        val decoder = NoiseFrameDecoder()
        var frame: ServiceFrame? = null
        for (ciphertext in encrypted.frames) {
            val plain = b.recv.decryptWithAd(EMPTY_AD, ciphertext)
            val reassembled = decoder.decode(plain)
            if (reassembled != null) {
                val request = decodeServiceRequest(reassembled)
                assertTrue(request.payload.isNotEmpty())
                frame = decodeServiceFrame(request.payload)
            }
        }
        val got = frame ?: throw AssertionError("no reassembled frame")
        assertEquals(1L, got.streamId)
        assertEquals(FrameKind.REQUEST, got.kind)
        val req = got.value as ApplicationRequest
        assertEquals("POST", req.verb)
        assertEquals("/chat/stream", req.path)
        assertEquals(headers, req.headers)
        assertArrayEquals(body, req.body)
        assertTrue(req.endBody)

        // Server->device frames flow through decryptFrame. The server wraps in
        // ServiceResponse (mirror `encode_response_envelope` in transport.py);
        // the device's own encryptBodyChunk emits ServiceRequest envelopes, so
        // the test peer plays server here with a response-envelope helper.
        fun serverSend(frame: ServiceFrame, chunkId: Long): List<ByteArray> {
            val bytes = encodeServiceResponse(ServiceResponse(encodeServiceFrame(frame)))
            return encodeNoiseFrames(bytes, chunkId = chunkId)
                .map { b.send.encryptWithAd(EMPTY_AD, it) }
        }
        val chunks = serverSend(
            ServiceFrame.bodyChunk(1L, BodyChunk("part".toByteArray(Charsets.UTF_8), true)),
            chunkId = 99L
        )
        var last: DecryptedFrame? = null
        for (ciphertext in chunks) {
            val out = a.transport.decryptFrame(ciphertext)
            if (out != null) last = out
        }
        val chunk = last as DecryptedFrame.BodyChunk
        assertEquals(1L, chunk.streamId)
        assertArrayEquals("part".toByteArray(Charsets.UTF_8), chunk.chunk.data)
        assertTrue(chunk.chunk.endBody)

        // Response and reset kinds map to their DecryptedFrame subtypes.
        val respFrames = serverSend(
            ServiceFrame.response(
                2L,
                ApplicationResponse(200, listOf(Header("x-app-id", "musegadget")),
                    "{}".toByteArray(Charsets.UTF_8), true)
            ),
            chunkId = 100L
        )
        val resp = a.transport.decryptFrame(respFrames[0]) as DecryptedFrame.Response
        assertEquals(2L, resp.streamId)
        assertEquals(200, resp.response.status)

        val resetFrames = serverSend(
            ServiceFrame.reset(2L, Reset(ResetCode.CANCELLED, "gone")),
            chunkId = 101L
        )
        val reset = a.transport.decryptFrame(resetFrames[0]) as DecryptedFrame.Reset
        assertEquals(2L, reset.streamId)
        assertEquals(ResetCode.CANCELLED, reset.reset.code)

        // The device's own body-chunk envelope is a ServiceRequest carrying body_chunk.
        // (Decrypt with the peer's matching receive state to keep nonces in sync.)
        val upload = b.transport.encryptBodyChunk(1L, "up".toByteArray(Charsets.UTF_8), false)
        val upPlain = NoiseFrameDecoder().decode(a.recv.decryptWithAd(EMPTY_AD, upload[0]))!!
        val upFrame = decodeServiceFrame(decodeServiceRequest(upPlain).payload)
        assertEquals(FrameKind.BODY_CHUNK, upFrame.kind)
    }

    @Test
    fun streamIdsIncrement() {
        val (a, _) = linkedPair()
        assertEquals(1L, a.transport.startStreamRequest("POST", "/link-control").streamId)
        assertEquals(2L, a.transport.encryptHttpRequest("POST", "/chat/stream").streamId)
        assertEquals(3L, a.transport.encryptHttpRequest("POST", "/chat/stream").streamId)
    }

    @Test
    fun tamperedCiphertextKillsTransport() {
        val (a, b) = linkedPair()
        val encrypted = a.transport.encryptHttpRequest("POST", "/chat/stream")
        val tampered = encrypted.frames[0].copyOf()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 1).toByte()
        try {
            b.transport.decryptFrame(tampered)
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
        try {
            b.transport.decryptFrame(encrypted.frames[0])
            fail("expected dead-transport error")
        } catch (e: NoiseProtocolError) {
        }
    }
}
