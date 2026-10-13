package com.cromestant.musegadget.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class NoiseHandshakeTest {
    private fun handshakingPair(): Pair<NoiseXXInitiator, TestResponder> {
        val init = NoiseXXInitiator()
        init.initialize()
        val resp = TestResponder()
        resp.initialize()
        return init to resp
    }

    @Test
    fun fullHandshakeRoundTrip() {
        val (init, resp) = handshakingPair()

        val m1 = init.writeMessage1()
        assertEquals(32, m1.size)

        val m2 = resp.readMessage1AndWriteMessage2(m1)
        assertEquals(32 + 48 + 16, m2.size) // e + s_ct + empty-payload ct

        val payload2 = init.readMessage2(m2)
        assertEquals(0, payload2.size)

        val m3 = init.writeMessage3()
        assertEquals(48 + 16, m3.size) // s_ct + empty-payload ct
        resp.readMessage3(m3)

        assertArrayEquals(init.handshakeHash(), resp.handshakeHash())

        val (iSend, iRecv) = init.split()
        val (rSend, rRecv) = resp.split()

        val toDevice = iSend.encryptWithAd(ByteArray(0), "hello device".toByteArray(Charsets.UTF_8))
        assertArrayEquals("hello device".toByteArray(Charsets.UTF_8), rRecv.decryptWithAd(ByteArray(0), toDevice))

        val toMobile = rSend.encryptWithAd(ByteArray(0), "hello mobile".toByteArray(Charsets.UTF_8))
        assertArrayEquals("hello mobile".toByteArray(Charsets.UTF_8), iRecv.decryptWithAd(ByteArray(0), toMobile))

        // Nonces advanced: the same plaintext must not encrypt identically twice.
        val again = iSend.encryptWithAd(ByteArray(0), "hello device".toByteArray(Charsets.UTF_8))
        assertFalse(toDevice.contentEquals(again))
    }

    @Test
    fun tamperedMessage2PoisonsHandshake() {
        val (init, resp) = handshakingPair()
        val m2 = resp.readMessage1AndWriteMessage2(init.writeMessage1())
        m2[40] = (m2[40].toInt() xor 1).toByte()
        try {
            init.readMessage2(m2)
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
            // expected
        }
        try {
            init.readMessage2(m2)
            fail("expected dead-handshake error")
        } catch (e: NoiseProtocolError) {
            // handshake is dead after the first failure
        }
    }

    @Test
    fun shortMessagesAreRejected() {
        val (init, resp) = handshakingPair()
        try {
            resp.readMessage1AndWriteMessage2(ByteArray(31))
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
        val (init2, resp2) = handshakingPair()
        val m2 = resp2.readMessage1AndWriteMessage2(init2.writeMessage1())
        try {
            init2.readMessage2(m2.copyOf(95))
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
        try {
            resp2.readMessage3(ByteArray(63))
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
    }

    @Test
    fun wrongPhaseCallsThrow() {
        val init = NoiseXXInitiator()
        try {
            init.writeMessage1()
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
        init.initialize()
        init.writeMessage1()
        try {
            init.writeMessage1()
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
        try {
            init.split()
            fail("expected NoiseProtocolError")
        } catch (e: NoiseProtocolError) {
        }
    }
}
