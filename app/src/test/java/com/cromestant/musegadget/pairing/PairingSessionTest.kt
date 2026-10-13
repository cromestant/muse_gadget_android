package com.cromestant.musegadget.pairing

import com.cromestant.musegadget.common.Bytes
import com.cromestant.musegadget.common.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private data class Confirmed(val device: PairingSession, val mobile: Mobile, val generation: Long)

@Suppress("UNCHECKED_CAST")
private fun confirmedDevice(clock: FakeClock = FakeClock()): Confirmed {
    val device = makeDevice(clock)
    device.handleHello(hello())
    val command = Json.parse(device.decrypt(clientFinishedRecord())) as Map<String, Any?>
    val generation = device.handleClientFinished(command)
    val mobile = Mobile(APP)
    mobile.txCounter = 1UL
    return Confirmed(device, mobile, generation)
}

private fun expectDecryptFailure(device: PairingSession, envelope: Map<String, Any?>) {
    try {
        device.decrypt(envelope)
        fail("expected PairingError")
    } catch (e: PairingError) {
        assertEquals("error_pairing_decrypt", e.status)
    }
    assertEquals(PairingState.IDLE, device.state)
}

class PairingSessionTest {
    @Test
    fun helloReturnsVectorPairingReady() {
        assertEquals(
            mapOf(
                "type" to "pairing_ready",
                "version" to 5,
                "device_id" to APP.getString("device_id"),
                "node_id" to APP.getString("node_id"),
                "mac" to APP.getString("mac"),
                "model" to "hatch_link",
                "firmware_version" to APP.getString("firmware_version"),
                "pairing_auth" to "none",
                "pairing_auth_epoch" to 0,
                "pairing_policy" to "confirm_app",
                "device_pub" to APP.getString("device_pub"),
                "device_nonce" to APP.getString("device_nonce"),
                "transcript_hash" to APP.getString("transcript_hash"),
                "session_id" to APP.getString("session_id"),
            ),
            makeDevice().handleHello(hello()),
        )
    }

    @Test
    fun fullAppConfirmedHandshake() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state)

        val plaintext = device.decrypt(clientFinishedRecord())
        assertEquals(APP.getString("client_finished_plaintext"), plaintext)

        val generation = device.handleClientFinished(Json.parse(plaintext) as Map<String, Any?>)
        assertTrue(generation != 0L)
        assertTrue(device.confirmed)

        val mobile = Mobile(APP)
        val confirmedEnv = device.encryptStatus("pairing_confirmed", generation)!!
        assertEquals("0", confirmedEnv["counter"])
        val confirmed = mobile.open(confirmedEnv)
        assertEquals(mapOf("type" to "status", "status" to "pairing_confirmed"), confirmed)

        val scan = device.decrypt(mobile.seal(mapOf("action" to "wifi_scan"), counter = 1UL))
        assertEquals(mapOf("action" to "wifi_scan"), Json.parse(scan))
    }

    @Test
    fun deviceRecordsCountUpFromZero() {
        val (device, mobile, generation) = confirmedDevice()
        val first = device.encryptStatus("pairing_confirmed", generation)!!
        val second = device.encryptJson("{\"type\":\"wifi_scan_result\",\"networks\":[]}", generation)!!
        assertEquals("0", first["counter"])
        assertEquals("1", second["counter"])
        assertEquals(
            mapOf("type" to "wifi_scan_result", "networks" to listOf<Any>()),
            mobile.open(second),
        )
    }

    @Test
    fun helloRejectsInvalidInput() {
        val badPub04Zeros = PairingCrypto.b64urlEncode(ByteArray(65).also { it[0] = 0x04 })
        val badPub02 = PairingCrypto.b64urlEncode(byteArrayOf(0x02) + ByteArray(32))
        val badNonce15 = PairingCrypto.b64urlEncode(ByteArray(15))
        val cases: List<Map<String, Any?>> = listOf(
            mapOf("version" to 4),
            mapOf("version" to "5"),
            mapOf("version" to true),
            mapOf("pairing_auth" to "fleet_ecdsa_p256_v1"),
            mapOf("pairing_policy" to "confirm_press"),
            mapOf("pairing_policy" to null),
            mapOf("mobile_pub" to ""),
            mapOf("mobile_pub" to badPub04Zeros),
            mapOf("mobile_pub" to badPub02),
            mapOf("mobile_pub" to APP.getString("mobile_pub") + "="),
            mapOf("mobile_nonce" to badNonce15),
            mapOf("mobile_nonce" to 7),
        )
        for (overrides in cases) {
            val device = makeDevice()
            try {
                device.handleHello(hello(overrides))
                fail("expected PairingError for $overrides")
            } catch (e: PairingError) {
                assertEquals("error_pairing_invalid_hello", e.status)
            }
            assertEquals(PairingState.IDLE, device.state)
        }
    }

    @Test
    fun newHelloReplacesPreviousSession() {
        val (device, _, generation) = confirmedDevice()
        device.handleHello(hello())
        assertFalse(device.isCurrent(generation))
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state)
    }

    @Test
    fun replayedRecordClearsSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(mapOf("action" to "wifi_scan"))
        device.decrypt(record)
        expectDecryptFailure(device, record)
    }

    @Test
    fun skippedCounterClearsSession() {
        val (device, mobile, _) = confirmedDevice()
        expectDecryptFailure(device, mobile.seal(mapOf("action" to "wifi_scan"), counter = 2UL))
    }

    @Test
    fun malformedCounterClearsSession() {
        val bad: List<Any> = listOf("-1", "+1", " 1", "1.0", "", "18446744073709551616", 1)
        for (counter in bad) {
            val (device, mobile, _) = confirmedDevice()
            val record = mobile.seal(mapOf("action" to "wifi_scan")).toMutableMap()
            record["counter"] = counter
            expectDecryptFailure(device, record)
        }
    }

    @Test
    fun tamperedCiphertextClearsSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(mapOf("action" to "wifi_scan")).toMutableMap()
        val raw = PairingCrypto.b64urlDecode(record["ciphertext"] as String).also { it[0] = (it[0].toInt() xor 1).toByte() }
        record["ciphertext"] = PairingCrypto.b64urlEncode(raw)
        expectDecryptFailure(device, record)
    }

    @Test
    fun wrongSessionIdClearsSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(mapOf("action" to "wifi_scan")).toMutableMap()
        record["session_id"] = PairingCrypto.b64urlEncode(ByteArray(16))
        expectDecryptFailure(device, record)
    }

    @Test
    fun shortTagClearsSession() {
        val (device, mobile, _) = confirmedDevice()
        val record = mobile.seal(mapOf("action" to "wifi_scan")).toMutableMap()
        record["tag"] = (record["tag"] as String).dropLast(2)
        expectDecryptFailure(device, record)
    }

    @Test
    fun decryptWithoutSessionFails() {
        expectDecryptFailure(makeDevice(), clientFinishedRecord())
    }

    @Test
    fun firstRecordMustBeExactClientFinished() {
        val commands = listOf(
            mapOf("action" to "wifi_scan"),
            mapOf("action" to "pairing_client_finished", "extra" to true),
            mapOf("action" to "provision_v2", "ssid" to "x"),
        )
        for (command in commands) {
            val device = makeDevice()
            device.handleHello(hello())
            val plaintext = device.decrypt(Mobile(APP).seal(command))
            @Suppress("UNCHECKED_CAST")
            assertEquals(0L, device.handleClientFinished(Json.parse(plaintext) as Map<String, Any?>))
            assertEquals(PairingState.IDLE, device.state)
        }
    }

    @Test
    fun clientFinishedOnlyCountsAsFirstRecord() {
        val (device, mobile, _) = confirmedDevice()
        val plaintext = device.decrypt(mobile.seal(mapOf("action" to "pairing_client_finished")))
        @Suppress("UNCHECKED_CAST")
        assertEquals(0L, device.handleClientFinished(Json.parse(plaintext) as Map<String, Any?>))
        assertEquals(PairingState.IDLE, device.state)
    }

    @Test
    fun clientFinishedTimesOutAfter60Seconds() {
        val clock = FakeClock()
        val device = makeDevice(clock)
        device.handleHello(hello())
        clock.now += 60.5
        expectDecryptFailure(device, clientFinishedRecord())
    }

    @Test
    fun confirmedSessionExpiresAfter120Seconds() {
        val clock = FakeClock()
        val (device, _, generation) = confirmedDevice(clock)
        clock.now += 119.0
        assertTrue(device.confirmed)
        clock.now += 2.0
        assertFalse(device.confirmed)
        assertNull(device.encryptStatus("wifi_connected"))
        assertTrue(device.isCurrent(generation))
    }

    @Test
    fun staleGenerationCannotEncrypt() {
        val (device, mobile, generation) = confirmedDevice()
        val provisioning = device.markProvisioning()
        assertTrue(provisioning != 0L && provisioning != generation)
        assertNull(device.encryptStatus("wifi_connecting", generation))
        assertEquals(
            mapOf("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", provisioning)!!),
        )
    }

    @Test
    fun provisioningRequiresConfirmation() {
        val device = makeDevice()
        device.handleHello(hello())
        assertEquals(0L, device.markProvisioning())
    }

    @Test
    fun commitRunsOnlyForCurrentProvisioningSession() {
        val clock = FakeClock()
        val (device, _, _) = confirmedDevice(clock)
        val generation = device.markProvisioning()
        val committed = mutableListOf<Int>()
        assertFalse(device.commitProvisioning(generation + 1) { committed.add(1); true })
        assertTrue(device.commitProvisioning(generation) { committed.add(2); true })
        assertEquals(listOf(2), committed)

        clock.now += 119.0
        assertTrue(device.extendProvisioning(generation))
        clock.now += 119.0
        assertTrue(device.commitProvisioning(generation) { true })
        clock.now += 2.0
        assertFalse(device.commitProvisioning(generation) { true })
    }

    @Test
    fun publicFromScalarMatchesVectors() {
        val devicePub = P256.publicFromScalar(Bytes.hex(APP.getString("device_private_scalar_hex")))
        assertEquals(APP.getString("device_pub"), PairingCrypto.b64urlEncode(devicePub))
        val mobilePub = P256.publicFromScalar(Bytes.hex(APP.getString("mobile_private_scalar_hex")))
        assertEquals(APP.getString("mobile_pub"), PairingCrypto.b64urlEncode(mobilePub))
    }

    @Test
    fun pairingConfirmedCarriesSdkToken() {
        val token = "mgst_" + "A".repeat(43)
        val device = makeDevice(sdkToken = token)
        device.handleHello(hello())
        @Suppress("UNCHECKED_CAST")
        val generation = device.handleClientFinished(
            Json.parse(device.decrypt(clientFinishedRecord())) as Map<String, Any?>,
        )
        val mobile = Mobile(APP)
        assertEquals(
            mapOf("type" to "status", "status" to "pairing_confirmed", "sdk_token" to token),
            mobile.open(device.encryptStatus("pairing_confirmed", generation)!!),
        )
        assertEquals(
            mapOf("type" to "status", "status" to "wifi_connecting"),
            mobile.open(device.encryptStatus("wifi_connecting", generation)!!),
        )
    }
}
