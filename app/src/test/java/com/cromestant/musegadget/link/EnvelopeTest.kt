package com.cromestant.musegadget.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String): ByteArray {
    require(s.length % 2 == 0)
    return ByteArray(s.length / 2) { i ->
        ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
    }
}

class EnvelopeTest {
    @Test
    fun applicationRequestRoundTrip() {
        val req = ApplicationRequest(
            verb = "POST",
            path = "/chat/stream",
            headers = listOf(Header("x-app-id", "musegadget"), Header("empty", "")),
            body = "{}".toByteArray(Charsets.UTF_8),
            endBody = true
        )
        val back = decodeApplicationRequest(encodeApplicationRequest(req))
        assertEquals(req, back)
        // Defaults encode to empty.
        assertEquals(0, encodeApplicationRequest(ApplicationRequest()).size)
    }

    @Test
    fun applicationResponseRoundTrip() {
        val resp = ApplicationResponse(
            status = 200,
            headers = listOf(Header("Content-Type", "application/json")),
            body = "ok".toByteArray(Charsets.UTF_8),
            endBody = true
        )
        assertEquals(resp, decodeApplicationResponse(encodeApplicationResponse(resp)))
        val neg = ApplicationResponse(status = -1)
        assertEquals(-1, decodeApplicationResponse(encodeApplicationResponse(neg)).status)
    }

    @Test
    fun bodyChunkAndResetRoundTrip() {
        val chunk = BodyChunk("hello".toByteArray(Charsets.UTF_8), true)
        assertEquals(chunk, decodeBodyChunk(encodeBodyChunk(chunk)))
        assertEquals(BodyChunk(), decodeBodyChunk(encodeBodyChunk(BodyChunk())))

        val reset = Reset(ResetCode.CANCELLED, "consumer closed")
        assertEquals(reset, decodeReset(encodeReset(reset)))
        // UNSPECIFIED code with empty reason encodes to empty.
        assertEquals(0, encodeReset(Reset()).size)
    }

    @Test
    fun serviceFrameRoundTrip() {
        val req = ServiceFrame.request(7, ApplicationRequest("POST", "/x"))
        val reqBack = decodeServiceFrame(encodeServiceFrame(req))
        assertEquals(7L, reqBack.streamId)
        assertEquals(FrameKind.REQUEST, reqBack.kind)
        assertEquals(ApplicationRequest("POST", "/x"), reqBack.value)

        val resp = ServiceFrame.response(9, ApplicationResponse(200))
        assertEquals(resp, decodeServiceFrame(encodeServiceFrame(resp)))

        val chunk = ServiceFrame.bodyChunk(3, BodyChunk(byteArrayOf(1, 2), false))
        assertEquals(chunk, decodeServiceFrame(encodeServiceFrame(chunk)))

        val reset = ServiceFrame.reset(4, Reset(ResetCode.TIMEOUT, "t"))
        assertEquals(reset, decodeServiceFrame(encodeServiceFrame(reset)))

        // Kindless frame carries only the stream id.
        val bare = decodeServiceFrame(encodeServiceFrame(ServiceFrame(5)))
        assertEquals(5L, bare.streamId)
        assertNull(bare.kind)
    }

    @Test
    fun serviceRequestResponseRoundTrip() {
        val sr = ServiceRequest(ServiceType.VAULT, byteArrayOf(1, 2, 3))
        assertEquals(sr, decodeServiceRequest(encodeServiceRequest(sr)))
        // DAEMON service is omitted.
        assertEquals(
            ServiceRequest(),
            decodeServiceRequest(encodeServiceRequest(ServiceRequest()))
        )
        assertEquals(0, encodeServiceRequest(ServiceRequest()).size)

        val sp = ServiceResponse(byteArrayOf(9, 9))
        assertEquals(sp, decodeServiceResponse(encodeServiceResponse(sp)))
        assertEquals(0, encodeServiceResponse(ServiceResponse()).size)
    }

    @Test
    fun crossLanguageVectors() {
        // Produced by gen_link_vectors.py against the reference SDK (deleted after).
        val reqHex = "0a04504f5354120c2f636861742f73747265616d" +
            "1a160a08782d6170702d6964120a6d75736567616467657422027b7d2801"
        val frameHex = "08071232" + reqHex
        val noiseHex = "08ebe590c5ffffffffff0110021805220568656c6c6f"
        val svcHex = "1236" + frameHex

        val reqBytes = hex(reqHex)
        val req = decodeApplicationRequest(reqBytes)
        assertEquals("POST", req.verb)
        assertEquals("/chat/stream", req.path)
        assertEquals(listOf(Header("x-app-id", "musegadget")), req.headers)
        assertArrayEquals("{}".toByteArray(Charsets.UTF_8), req.body)
        assertTrue(req.endBody)
        assertArrayEquals(reqBytes, encodeApplicationRequest(req))

        val frameBytes = hex(frameHex)
        val frame = decodeServiceFrame(frameBytes)
        assertEquals(7L, frame.streamId)
        assertEquals(FrameKind.REQUEST, frame.kind)
        assertEquals(req, frame.value)
        assertArrayEquals(frameBytes, encodeServiceFrame(frame))

        val noiseBytes = hex(noiseHex)
        val noise = decodeNoiseFrame(noiseBytes)
        assertEquals(-123456789L, noise.chunkId)
        assertEquals(2, noise.chunkIndex)
        assertEquals(5, noise.totalChunks)
        assertArrayEquals("hello".toByteArray(Charsets.UTF_8), noise.payload)
        assertArrayEquals(noiseBytes, encodeNoiseFrame(noise))

        val svcBytes = hex(svcHex)
        val svc = decodeServiceRequest(svcBytes)
        assertEquals(ServiceType.DAEMON, svc.service)
        assertArrayEquals(frameBytes, svc.payload)
        assertArrayEquals(svcBytes, encodeServiceRequest(svc))
    }
}
