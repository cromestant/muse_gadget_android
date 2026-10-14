package com.cromestant.musegadget.setup

import android.util.Log

import com.cromestant.musegadget.ble.ChunkFraming
import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.common.JsonParseException
import com.cromestant.musegadget.pairing.PairingError
import com.cromestant.musegadget.pairing.PairingSession
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Identity of this device for setup responses. */
data class DeviceIdentity(val nodeId: String, val deviceId: String, val mac: String)

/** GATT transport: delivers reassembled writes, sends framed notifications. */
interface BleTransport {
    fun sendPackets(packets: List<ByteArray>)
    fun mtu(): Int
    fun disconnect(delaySeconds: Double)
}

/** Network state for wifi_scan / provision. */
interface NetworkInfo {
    fun isOnline(): Boolean
    fun currentConnectionEntry(): Map<String, Any?>
}

/** Device tokens saved by provisioning. */
data class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
)

/** Setup could not finish; [status] is what the app is told. */
class ProvisionFailed(val status: String) : Exception(status)

val SENSITIVE_ACTIONS = setOf(
    "provision", "provision_v2", "wifi_scan", "ota", "device.ota",
    "unpair", "set_wifi", "set_auth",
)

val PLAINTEXT_STATUSES = setOf(
    "error_encryption_required",
    "error_pairing_invalid_hello",
    "error_pairing_unavailable",
    "error_pairing_decrypt",
)

const val DISCONNECT_AFTER_ERROR_S = 0.3

/**
 * BLE setup commands: the protocol logic behind the GATT characteristics,
 * mirroring ble_setup.py. Independent of Android Bluetooth: a transport
 * delivers reassembled writes and sends framed notifications. Messages are
 * handled one at a time on a worker thread, in arrival order, and every
 * send happens under one lock so encrypted record counters reach the phone
 * in order. Pure JVM: no Android imports.
 */
class SetupController(
    private val pairing: PairingSession,
    private val identity: DeviceIdentity,
    private val firmwareVersion: String,
    private val transport: BleTransport,
    private val network: NetworkInfo,
    private val provision: (Credentials, ((() -> Boolean) -> Boolean)) -> Unit,
    private val onComplete: () -> Unit = {},
) {
    private val assembler = ChunkFraming.ChunkAssembler()
    // Java queues reject null, so a poison object stands in for Python's None sentinel.
    private val stopSentinel = Any()
    private val inbox: LinkedBlockingQueue<Any> = LinkedBlockingQueue()
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var _provisioning = false
    private var worker: Thread? = null

    /** Transport callback (any thread): reassemble one write packet. */
    fun onWrite(packet: ByteArray) {
        assembler.feed(packet)?.let { inbox.put(it) }
    }

    /** Transport callback (any thread): the client disconnected. */
    fun onDisconnect() {
        assembler.reset()
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
    }

    fun start() {
        worker = thread(isDaemon = true, name = "ble-setup") { run() }
    }

    fun stop() {
        inbox.put(stopSentinel)
    }

    private fun run() {
        while (true) {
            val m = inbox.take()
            if (m === stopSentinel) break
            try {
                handleMessage(m as ByteArray)
            } catch (e: Exception) {
                System.err.println("setup command failed: $e")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val parsed = try {
            Json.parse(raw.toString(Charsets.UTF_8))
        } catch (e: JsonParseException) {
            null
        }
        if (parsed !is Map<*, *>) {
            sendStatus("error_invalid_command")
            return
        }
        val command = parsed as Map<String, Any?>
        val action = command["action"] as? String ?: ""
        val blocked = synchronized(stateLock) { plaintextBlocked }
        if (!decrypted && action == "pairing_client_hello") {
            handleHello(command)
        } else if (!decrypted && action == "pairing_encrypted") {
            handleRecord(command)
        } else if (action == "get_device_info") {
            // Public metadata, safe in plaintext at any point. Apps re-read it
            // when they restart a handshake on the same connection.
            sendJson(deviceInfo())
        } else if (!decrypted && blocked) {
            // Plaintext command ignored after pairing started: no reply.
        } else if (!decrypted && action in SENSITIVE_ACTIONS) {
            sendStatus("error_encryption_required")
        } else if (decrypted && action == "pairing_client_finished") {
            handleClientFinished(command)
        } else if (decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed) {
            sendStatus("error_pairing_confirm_required")
        } else if (decrypted && action == "wifi_scan") {
            handleWifiScan()
        } else if (decrypted && action == "provision_v2") {
            handleProvision(command)
        } else {
            sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): Map<String, Any?> = mapOf(
        "type" to "device_info",
        "node_id" to identity.nodeId,
        "version" to firmwareVersion,
    ) + pairing.deviceInfo() + mapOf(
        "build_sha" to "",
        "network_ready" to network.isOnline(),
    )

    private fun handleHello(command: Map<String, Any?>) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingError) {
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        sendJson(ready)
    }

    private fun handleRecord(envelope: Map<String, Any?>) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingError) {
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_S)
            return
        }
        handleMessage(plaintext.toByteArray(Charsets.UTF_8), decrypted = true)
    }

    private fun handleClientFinished(command: Map<String, Any?>) {
        val generation = pairing.handleClientFinished(command)
        if (generation == 0L) {
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_S)
            return
        }
        sendStatus("pairing_confirmed", generation)
    }

    private fun handleWifiScan() {
        val networks = if (network.isOnline()) listOf(network.currentConnectionEntry()) else emptyList()
        sendEncryptedJson(mapOf("type" to "wifi_scan_result", "networks" to networks))
    }

    private fun handleProvision(command: Map<String, Any?>) {
        val keyTypes = command.entries.joinToString { (k, v) -> "$k:${v?.javaClass?.simpleName}" }
        Log.i("MuseGadgetProvision", "provision_v2 received keys=[$keyTypes]")
        fun text(key: String): String = command[key] as? String ?: ""
        if (command["ssid"] !is String ||
            command["password"] !is String ||
            text("access_token").isEmpty() ||
            text("refresh_token").isEmpty() ||
            text("token_type") != "device"
        ) {
            Log.w("MuseGadgetProvision", "provision_v2 validation FAILED")
            sendStatus("error_missing_credentials")
            return
        }
        Log.i("MuseGadgetProvision", "provision_v2 validation passed")
        var generation = 0L
        val refusal: String?
        synchronized(stateLock) {
            if (_provisioning) {
                refusal = "error_operation_in_progress"
            } else {
                generation = pairing.markProvisioning()
                refusal = if (generation != 0L) null else "error_pairing_confirm_required"
                _provisioning = refusal == null
            }
        }
        // Sent after the lock is released: sendStatus() takes stateLock
        // itself when the session has no keys, and the lock is not reentrant.
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // The Wi-Fi fields are deliberately dropped: this device only sets up
        // when it is already online.
        val credentials = Credentials(
            accessToken = text("access_token"),
            refreshToken = text("refresh_token"),
            username = text("username"),
            apiUrl = text("api_url"),
            apiUrlV2 = text("api_url_v2"),
            noiseHost = text("noise_host"),
        )
        thread(isDaemon = true, name = "provision") { runProvision(credentials, generation) }
    }

    private fun runProvision(credentials: Credentials, generation: Long) {
        try {
            sendStatus("wifi_connecting", generation)
            if (!network.isOnline()) {
                // Stay in provisioning so the app can retry, as the firmware does.
                pairing.extendProvisioning(generation)
                sendStatus("wifi_failed", generation)
                return
            }
            sendStatus("wifi_connected", generation)
            val commit: ((() -> Boolean) -> Boolean) = { save ->
                pairing.commitProvisioning(generation, save)
            }
            try {
                provision(credentials, commit)
            } catch (e: ProvisionFailed) {
                sendStatus(e.status, generation)
                transport.disconnect(0.5)
                return
            }
            sendStatus("auth_ok", generation)
            onComplete()
        } finally {
            synchronized(stateLock) { _provisioning = false }
        }
    }

    fun sendStatus(status: String, generation: Long = 0L) {
        synchronized(txLock) {
            val env = pairing.encryptStatus(status, generation)
            if (env != null) {
                sendLocked(Json.stringify(env))
                return
            }
            val blocked = synchronized(stateLock) { plaintextBlocked }
            if (generation != 0L || blocked || status !in PLAINTEXT_STATUSES) return
            transport.sendPackets(listOf(status.toByteArray(Charsets.UTF_8)))
        }
    }

    fun sendJson(obj: Map<String, Any?>) {
        synchronized(txLock) {
            sendLocked(Json.stringify(obj))
        }
    }

    fun sendEncryptedJson(obj: Map<String, Any?>, generation: Long = 0L) {
        synchronized(txLock) {
            val env = pairing.encryptJson(Json.stringify(obj), generation) ?: return
            sendLocked(Json.stringify(env))
        }
    }

    private fun sendLocked(text: String) {
        transport.sendPackets(ChunkFraming.encode(text.toByteArray(Charsets.UTF_8), transport.mtu()))
    }
}
