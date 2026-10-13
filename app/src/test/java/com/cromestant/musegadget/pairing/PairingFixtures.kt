package com.cromestant.musegadget.pairing

import com.cromestant.musegadget.common.Bytes
import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.pairing.PairingConstants.FROM_DEVICE
import com.cromestant.musegadget.pairing.PairingConstants.TO_DEVICE
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Shared fixtures for the pairing and setup tests (test source set only). */
val APP: JSONObject by lazy {
    val text = object {}.javaClass.getResourceAsStream("/link_pairing_v5.json")!!
        .bufferedReader().readText()
    val arr = JSONObject(text).getJSONArray("vectors")
    (0 until arr.length()).map { arr.getJSONObject(it) }
        .first { it.getString("name") == "community_app_v5" }
}

class FakeClock(var now: Double = 1000.0) : PairingClock {
    override fun nowSeconds(): Double = now
}

/** Mobile side of the record layer, written from the contract, not the device code. */
class Mobile(vector: JSONObject) {
    private val tx: ByteArray = Bytes.hex(vector.getString("mobile_tx_key_hex"))
    private val rx: ByteArray = Bytes.hex(vector.getString("mobile_rx_key_hex"))
    val sessionId: String = vector.getString("session_id")
    var txCounter = 0UL

    fun seal(command: Map<String, Any?>, counter: ULong? = null): Map<String, Any?> {
        val c = counter ?: txCounter.also { txCounter += 1UL }
        val plaintext = Json.stringify(command).toByteArray(Charsets.UTF_8)
        val (ct, tag) = PairingCrypto.seal(
            tx,
            PairingCrypto.recordNonce(TO_DEVICE, c),
            plaintext,
            PairingCrypto.recordAad(sessionId, TO_DEVICE, c),
        )
        return mapOf(
            "action" to "pairing_encrypted",
            "session_id" to sessionId,
            "counter" to c.toString(),
            "ciphertext" to PairingCrypto.b64urlEncode(ct),
            "tag" to PairingCrypto.b64urlEncode(tag),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun open(envelope: Map<String, Any?>): Map<String, Any?> {
        assertEquals("pairing_encrypted", envelope["type"])
        assertEquals(sessionId, envelope["session_id"])
        val c = (envelope["counter"] as String).toULong()
        val pt = PairingCrypto.open(
            rx,
            PairingCrypto.recordNonce(FROM_DEVICE, c),
            PairingCrypto.b64urlDecode(envelope["ciphertext"] as String),
            PairingCrypto.b64urlDecode(envelope["tag"] as String),
            PairingCrypto.recordAad(sessionId, FROM_DEVICE, c),
        )
        return Json.parse(pt.toString(Charsets.UTF_8)) as Map<String, Any?>
    }
}

fun makeDevice(
    clock: FakeClock = FakeClock(),
    sdkToken: String? = null,
): PairingSession = PairingSession(
    nodeId = APP.getString("node_id"),
    deviceId = APP.getString("device_id"),
    mac = APP.getString("mac"),
    firmwareVersion = APP.getString("firmware_version"),
    sdkToken = sdkToken,
    clock = clock,
    keyPairFactory = {
        val s = Bytes.hex(APP.getString("device_private_scalar_hex"))
        s to P256.publicFromScalar(s)
    },
    randomBytes = { _ -> PairingCrypto.b64urlDecode(APP.getString("device_nonce")) },
)

fun hello(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    m["action"] = "pairing_client_hello"
    m["version"] = 5
    m["pairing_auth"] = "none"
    m["pairing_policy"] = "confirm_app"
    m["mobile_pub"] = APP.getString("mobile_pub")
    m["mobile_nonce"] = APP.getString("mobile_nonce")
    m.putAll(overrides)
    return m
}

fun clientFinishedRecord(): Map<String, Any?> = mapOf(
    "action" to "pairing_encrypted",
    "session_id" to APP.getString("session_id"),
    "counter" to "0",
    "ciphertext" to APP.getString("client_finished_ciphertext"),
    "tag" to APP.getString("client_finished_tag"),
)
