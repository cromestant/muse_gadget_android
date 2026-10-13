package com.cromestant.musegadget.pairing

import com.cromestant.musegadget.common.Bytes
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Vector conformance against link_pairing_v5.json: for EVERY case,
 * exact byte equality on transcript_hash, session_secret (via tx/rx
 * keys derived from it), mobile_tx, mobile_rx, session_id, and the
 * client_finished record {plaintext, aad, ciphertext, tag}.
 */
@RunWith(Parameterized::class)
class PairingVectorsTest(private val vector: JSONObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun vectors(): List<Array<Any>> {
            val text = PairingVectorsTest::class.java
                .getResourceAsStream("/link_pairing_v5.json")!!
                .bufferedReader().readText()
            val arr = JSONObject(text).getJSONArray("vectors")
            return List(arr.length()) { arrayOf<Any>(arr.getJSONObject(it)) }
        }

        private fun unb64(s: String): ByteArray = PairingCrypto.b64urlDecode(s)
    }

    private fun transcript(): String = PairingCrypto.buildTranscript(
        community = vector.getBoolean("community"),
        authEpoch = vector.getInt("pairing_auth_epoch"),
        policy = vector.getString("pairing_policy"),
        deviceId = vector.getString("device_id"),
        nodeId = vector.getString("node_id"),
        mac = vector.getString("mac"),
        firmwareVersion = vector.getString("firmware_version"),
        mobilePub = vector.getString("mobile_pub"),
        devicePub = vector.getString("device_pub"),
        mobileNonce = vector.getString("mobile_nonce"),
        deviceNonce = vector.getString("device_nonce"),
    )

    private fun keys(): SessionKeys = PairingCrypto.deriveSessionKeys(
        Bytes.hex(vector.getString("ecdh_secret_hex")),
        unb64(vector.getString("mobile_nonce")),
        unb64(vector.getString("device_nonce")),
        unb64(vector.getString("transcript_hash")),
    )

    @Test
    fun transcriptAndHashMatch() {
        assertEquals(vector.getString("transcript"), transcript())
        assertArrayEquals(
            unb64(vector.getString("transcript_hash")),
            PairingCrypto.transcriptHash(transcript()),
        )
    }

    @Test
    fun keyScheduleMatches() {
        val k = keys()
        assertEquals(vector.getString("mobile_tx_key_hex"), Bytes.toHex(k.mobileTx))
        assertEquals(vector.getString("mobile_rx_key_hex"), Bytes.toHex(k.mobileRx))
        assertEquals(vector.getString("session_id"), PairingCrypto.b64urlEncode(k.sessionId))
    }

    @Test
    fun ecdhMatchesVectorBothDirections() {
        val fromMobilePriv = P256.ecdh(
            Bytes.hex(vector.getString("mobile_private_scalar_hex")),
            unb64(vector.getString("device_pub")),
        )
        assertEquals(vector.getString("ecdh_secret_hex"), Bytes.toHex(fromMobilePriv))
        val fromDevicePriv = P256.ecdh(
            Bytes.hex(vector.getString("device_private_scalar_hex")),
            unb64(vector.getString("mobile_pub")),
        )
        assertArrayEquals(fromMobilePriv, fromDevicePriv)
    }

    @Test
    fun clientFinishedRecordMatches() {
        val k = keys()
        val sessionB64 = PairingCrypto.b64urlEncode(k.sessionId)
        assertEquals(vector.getString("session_id"), sessionB64)

        val aad = PairingCrypto.recordAad(sessionB64, PairingConstants.TO_DEVICE, 0UL)
        assertEquals(vector.getString("client_finished_aad"), String(aad, Charsets.UTF_8))

        val plaintext = vector.getString("client_finished_plaintext").toByteArray(Charsets.UTF_8)
        val nonce = PairingCrypto.recordNonce(PairingConstants.TO_DEVICE, 0UL)
        val (ct, tag) = PairingCrypto.seal(k.mobileTx, nonce, plaintext, aad)
        assertArrayEquals(unb64(vector.getString("client_finished_ciphertext")), ct)
        assertArrayEquals(unb64(vector.getString("client_finished_tag")), tag)

        // And the vector record opens back to the same plaintext.
        assertArrayEquals(
            plaintext,
            PairingCrypto.open(
                k.mobileTx, nonce,
                unb64(vector.getString("client_finished_ciphertext")),
                unb64(vector.getString("client_finished_tag")),
                aad,
            ),
        )
    }

    @Test
    fun vectorConstantsMatchCode() {
        assertEquals(PairingConstants.MODEL, vector.getString("model"))
        assertEquals(PairingConstants.SUITE, vector.getString("selected_cipher_suite"))
        assertEquals(PairingConstants.PAIRING_VERSION, vector.getInt("version"))
        assertEquals(PairingConstants.RECORD_LABEL, "hatch-link ble setup v1")
    }
}

class PairingCryptoEdgeTest {
    @Test
    fun officialCommunityAppPolicyRejected() {
        try {
            PairingCrypto.buildTranscript(
                community = false, authEpoch = 1, policy = "confirm_app",
                deviceId = "d", nodeId = "n", mac = "m", firmwareVersion = "1",
                mobilePub = "a", devicePub = "b", mobileNonce = "c", deviceNonce = "e",
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun b64urlRejectsWhatFirmwareRejects() {
        for (bad in listOf("", "a", "ab=c", "ab+c", "ab/c", "a b", "abcde")) {
            try {
                PairingCrypto.b64urlDecode(bad)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertEquals("-_8", PairingCrypto.b64urlEncode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertArrayEquals(
            byteArrayOf(0xFB.toByte(), 0xFF.toByte()),
            PairingCrypto.b64urlDecode("-_8"),
        )
    }

    @Test
    fun counters() {
        assertEquals(0UL, PairingCrypto.parseCounter("0"))
        assertEquals(1UL, PairingCrypto.parseCounter("1"))
        for (bad in listOf("-1", "+1", " 1", "1.0", "", "18446744073709551616", "abc")) {
            try {
                PairingCrypto.parseCounter(bad)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertArrayEquals(
            Bytes.hex("000000000000000000000000"),
            PairingCrypto.recordNonce(0, 0UL),
        )
        assertArrayEquals(
            Bytes.hex("010000000000000000000001"),
            PairingCrypto.recordNonce(1, 1UL),
        )
    }
}
