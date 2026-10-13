package com.cromestant.musegadget.setup

import com.cromestant.musegadget.ble.ChunkFraming
import com.cromestant.musegadget.common.Bytes
import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.pairing.APP
import com.cromestant.musegadget.pairing.FakeClock
import com.cromestant.musegadget.pairing.Mobile
import com.cromestant.musegadget.pairing.P256
import com.cromestant.musegadget.pairing.PairingCrypto
import com.cromestant.musegadget.pairing.PairingSession
import com.cromestant.musegadget.pairing.PairingState
import com.cromestant.musegadget.pairing.clientFinishedRecord
import com.cromestant.musegadget.pairing.hello
import com.cromestant.musegadget.pairing.makeDevice
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeTransport(var mtu: Int = 185) : BleTransport {
    private val asm = ChunkFraming.ChunkAssembler()
    val messages: MutableList<Any> = Collections.synchronizedList(mutableListOf())
    val disconnects: MutableList<Double> = Collections.synchronizedList(mutableListOf())

    override fun mtu(): Int = mtu

    override fun sendPackets(packets: List<ByteArray>) {
        for (packet in packets) {
            assertTrue(packet.size <= maxOf(mtu - 3, 20))
            val message = asm.feed(packet) ?: continue
            if (packet.isNotEmpty() && packet[0] == 0xFE.toByte()) {
                messages.add(Json.parse(message.toString(Charsets.UTF_8))!!)
            } else {
                messages.add(message.toString(Charsets.UTF_8))
            }
        }
    }

    override fun disconnect(delaySeconds: Double) {
        disconnects.add(delaySeconds)
    }
}

class FakeNetwork(var online: Boolean = true) : NetworkInfo {
    override fun isOnline(): Boolean = online
    override fun currentConnectionEntry(): Map<String, Any?> =
        mapOf("ssid" to "HomeNet", "rssi" to -40, "secure" to false)
}

class Harness(
    online: Boolean = true,
    provision: ((Credentials, ((() -> Boolean) -> Boolean)) -> Unit)? = null,
    mtu: Int = 185,
    pairing: PairingSession? = null,
) {
    val transport = FakeTransport(mtu)
    val network = FakeNetwork(online)
    val pairing: PairingSession = pairing ?: makeDevice()
    val saved: MutableList<Credentials> = Collections.synchronizedList(mutableListOf())
    val completed = AtomicBoolean(false)
    val controller: SetupController
    val mobile = Mobile(APP)

    init {
        val defaultProvision: (Credentials, ((() -> Boolean) -> Boolean)) -> Unit =
            { creds, commit ->
                if (!commit { saved.add(creds); true }) throw ProvisionFailed("error_storage")
            }
        controller = SetupController(
            this.pairing,
            DeviceIdentity(
                APP.getString("node_id"),
                APP.getString("device_id"),
                APP.getString("mac"),
            ),
            APP.getString("firmware_version"),
            transport,
            network,
            provision ?: defaultProvision,
            onComplete = { completed.set(true) },
        )
    }

    fun send(obj: Map<String, Any?>) {
        controller.handleMessage(Json.stringify(obj).toByteArray(Charsets.UTF_8))
    }

    fun sendEncrypted(command: Map<String, Any?>) {
        send(mobile.seal(command))
    }

    fun pair() {
        send(hello())
        send(clientFinishedRecord())
        mobile.txCounter = 1UL
    }

    fun opened(): List<Map<String, Any?>> = transport.messages
        .filterIsInstance<Map<String, Any?>>()
        .filter { it["type"] == "pairing_encrypted" }
        .map { mobile.open(it) }

    fun statuses(): List<String> = opened()
        .filter { it["type"] == "status" }
        .map { it["status"] as String }

    fun waitForStatus(status: String, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (status !in statuses()) {
            if (System.currentTimeMillis() > deadline) fail("no $status; got ${statuses()}")
            Thread.sleep(10)
        }
    }
}

private val PROVISION: Map<String, Any?> = mapOf(
    "action" to "provision_v2",
    "ssid" to "HomeNet",
    "password" to "",
    "access_token" to "device-access",
    "refresh_token" to "device-refresh",
    "token_type" to "device",
    "username" to "someone",
    "api_url" to "https://legacy-api.example",
    "api_url_v2" to "https://api.example",
)

class SetupFlowTest {
    @Test
    fun deviceInfoMatchesPairingTranscriptFields() {
        val h = Harness()
        h.send(mapOf("action" to "get_device_info"))
        @Suppress("UNCHECKED_CAST")
        val info = h.transport.messages.last() as Map<String, Any?>
        assertEquals("device_info", info["type"])
        assertEquals(APP.getString("node_id"), info["node_id"])
        assertEquals(APP.getString("firmware_version"), info["version"])
        assertEquals("hatch_link", info["model"])
        assertEquals(5L, info["pairing_protocol"])
        assertEquals("none", info["pairing_auth"])
        assertEquals("confirm_app", info["pairing_policy"])
        assertEquals(true, info["network_ready"])
    }

    @Test
    fun helloReturnsPairingReadyInChunksAtMinimumMtu() {
        val h = Harness(mtu = 23)
        h.send(hello())
        @Suppress("UNCHECKED_CAST")
        val ready = h.transport.messages.last() as Map<String, Any?>
        assertEquals("pairing_ready", ready["type"])
        assertEquals(APP.getString("session_id"), ready["session_id"])
    }

    @Test
    fun fullSetupFlow() {
        val h = Harness()
        h.pair()
        assertEquals(listOf("pairing_confirmed"), h.statuses())

        h.sendEncrypted(mapOf("action" to "wifi_scan"))
        assertEquals(
            mapOf(
                "type" to "wifi_scan_result",
                "networks" to listOf(mapOf("ssid" to "HomeNet", "rssi" to -40L, "secure" to false)),
            ),
            h.opened().last(),
        )

        h.sendEncrypted(PROVISION)
        h.waitForStatus("auth_ok")
        assertEquals(
            listOf("pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"),
            h.statuses(),
        )
        assertTrue(h.completed.get())
        val saved = h.saved[0]
        assertEquals("device-access", saved.accessToken)
        assertEquals("device-refresh", saved.refreshToken)
        assertEquals("someone", saved.username)
        assertEquals("https://legacy-api.example", saved.apiUrl)
        assertEquals("https://api.example", saved.apiUrlV2)
        assertEquals(PairingState.PROVISIONING, h.pairing.state)
    }

    @Test
    fun setupCommandsNeedEncryption() {
        val h = Harness()
        h.send(mapOf("action" to "wifi_scan"))
        h.send(mapOf("action" to "provision_v2") + PROVISION)
        assertEquals(
            listOf("error_encryption_required", "error_encryption_required"),
            h.transport.messages,
        )
    }

    @Test
    fun plaintextIsIgnoredOncePairingStarts() {
        val h = Harness()
        h.send(hello())
        h.send(mapOf("action" to "wifi_scan"))
        assertEquals(1, h.transport.messages.size)
    }

    @Test
    fun deviceInfoAnsweredMidSessionAndNewHelloRestarts() {
        val h = Harness()
        h.pair()
        h.send(mapOf("action" to "get_device_info"))
        @Suppress("UNCHECKED_CAST")
        assertEquals("device_info", (h.transport.messages.last() as Map<String, Any?>)["type"])
        assertTrue(h.pairing.confirmed)
        h.send(hello())
        @Suppress("UNCHECKED_CAST")
        assertEquals("pairing_ready", (h.transport.messages.last() as Map<String, Any?>)["type"])
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, h.pairing.state)
    }

    @Test
    fun badHelloGetsPlaintextError() {
        val h = Harness()
        h.send(hello(mapOf("pairing_policy" to "confirm_press")))
        assertEquals(listOf("error_pairing_invalid_hello"), h.transport.messages)
    }

    @Test
    fun decryptFailureDisconnectsWithoutPlaintextReply() {
        val h = Harness()
        h.send(hello())
        val record = clientFinishedRecord().toMutableMap()
        record["counter"] = "1"
        h.send(record)
        assertEquals(1, h.transport.messages.size) // just pairing_ready
        assertEquals(listOf(0.3), h.transport.disconnects)
        assertEquals(PairingState.IDLE, h.pairing.state)
    }

    @Test
    fun wrongFirstRecordMeansSessionCanNeverConfirm() {
        val h = Harness()
        h.send(hello())
        h.sendEncrypted(mapOf("action" to "wifi_scan"))
        assertEquals(listOf("error_pairing_confirm_required"), h.statuses())
        h.sendEncrypted(mapOf("action" to "pairing_client_finished"))
        assertEquals(listOf(0.3), h.transport.disconnects)
        assertEquals(PairingState.IDLE, h.pairing.state)
    }

    @Test
    fun provisionRequiresDeviceTokens() {
        val changes: List<Map<String, Any?>> = listOf(
            mapOf("refresh_token" to ""),
            mapOf("token_type" to "user"),
            mapOf("access_token" to null),
            mapOf("password" to null),
        )
        for (change in changes) {
            val h = Harness()
            h.pair()
            h.sendEncrypted(PROVISION + change)
            assertEquals("error_missing_credentials", h.statuses().last())
            assertEquals(PairingState.READY, h.pairing.state)
        }
    }

    @Test
    fun offlineDeviceReportsWifiFailureAndCanRetry() {
        val h = Harness(online = false)
        h.pair()
        h.sendEncrypted(mapOf("action" to "wifi_scan"))
        assertEquals(listOf<Any>(), h.opened().last()["networks"])

        h.sendEncrypted(PROVISION)
        h.waitForStatus("wifi_failed")
        assertTrue(h.transport.disconnects.isEmpty())

        h.network.online = true
        h.sendEncrypted(PROVISION)
        h.waitForStatus("auth_ok")
    }

    @Test
    fun rejectedTokenReportsAuthFailedAndDisconnects() {
        val h = Harness(provision = { _, _ -> throw ProvisionFailed("auth_failed") })
        h.pair()
        h.sendEncrypted(PROVISION)
        h.waitForStatus("auth_failed")
        assertEquals(listOf(0.5), h.transport.disconnects)
        assertFalse(h.completed.get())
    }

    @Test
    fun disconnectClearsSession() {
        val h = Harness()
        h.pair()
        h.controller.onDisconnect()
        assertEquals(PairingState.IDLE, h.pairing.state)
        h.send(mapOf("action" to "wifi_scan"))
        assertEquals("error_encryption_required", h.transport.messages.last())
    }

    @Test
    fun writesAreReassembledBeforeDispatch() {
        val h = Harness()
        h.controller.start()
        try {
            for (packet in ChunkFraming.encode(Json.stringify(hello()).toByteArray(Charsets.UTF_8), 23)) {
                h.controller.onWrite(packet)
            }
            val deadline = System.currentTimeMillis() + 2000
            while (h.transport.messages.isEmpty()) {
                if (System.currentTimeMillis() > deadline) fail("no pairing_ready received")
                Thread.sleep(10)
            }
        } finally {
            h.controller.stop()
        }
        @Suppress("UNCHECKED_CAST")
        assertEquals("pairing_ready", (h.transport.messages[0] as Map<String, Any?>)["type"])
    }

    @Test
    fun provisionRefusalWithoutSessionKeysDoesNotDeadlock() {
        // The session can expire between the dispatcher's confirmed check and
        // markProvisioning(). The refusal then goes out without keys, and that
        // path of sendStatus() takes the state lock itself.
        val refusing = object : PairingSession(
            nodeId = APP.getString("node_id"),
            deviceId = APP.getString("device_id"),
            mac = APP.getString("mac"),
            firmwareVersion = APP.getString("firmware_version"),
            clock = FakeClock(),
            keyPairFactory = {
                val s = Bytes.hex(APP.getString("device_private_scalar_hex"))
                s to P256.publicFromScalar(s)
            },
            randomBytes = { _ -> PairingCrypto.b64urlDecode(APP.getString("device_nonce")) },
        ) {
            override fun markProvisioning(): Long {
                reset()
                return 0L
            }
        }
        val h = Harness(pairing = refusing)
        h.pair()
        val sealed = h.mobile.seal(PROVISION)
        val worker = thread(isDaemon = true) { h.send(sealed) }
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertNull(h.pairing.encryptStatus("probe"))
    }
}
