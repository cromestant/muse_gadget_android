package com.cromestant.musegadget.pairing

import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.pairing.PairingConstants.FROM_DEVICE
import com.cromestant.musegadget.pairing.PairingConstants.PAIRING_VERSION
import com.cromestant.musegadget.pairing.PairingConstants.POLICY_CONFIRM_APP
import com.cromestant.musegadget.pairing.PairingConstants.TO_DEVICE
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Clock for pairing deadlines; injectable for tests. */
interface PairingClock {
    fun nowSeconds(): Double
}

/** Wall clock based on System.nanoTime. */
object SystemPairingClock : PairingClock {
    override fun nowSeconds(): Double = System.nanoTime() / 1e9
}

/** Device pairing states, mirroring pairing.py. */
enum class PairingState {
    IDLE,
    WAIT_CLIENT_FINISHED,
    READY,
    PROVISIONING,
}

/** A handshake step failed; [status] is the wire error to report. */
class PairingError(val status: String) : Exception(status)

/**
 * Device side of Muse Gadget BLE pairing, protocol version 5, mirroring
 * pairing.py's PairingSession exactly. Community mode only (auth none,
 * policy confirm_app). Thread-safe: all mutable state under one lock.
 * Pure JVM: no Android imports.
 */
open class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    private val clock: PairingClock = SystemPairingClock,
    private val keyPairFactory: () -> Pair<ByteArray, ByteArray> = { P256.generateKeyPair() },
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    companion object {
        const val CLIENT_FINISHED_TIMEOUT_S = 60.0
        const val CONFIRMED_TIMEOUT_S = 120.0
        const val PROVISIONING_TIMEOUT_S = 120.0
        const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
        const val ERROR_DECRYPT = "error_pairing_decrypt"
    }

    private val firmwareVersion: String = firmwareVersion.ifEmpty { "unknown" }
    private val lock = Any()
    private var _generation = 0L
    private var _state = PairingState.IDLE
    private var _deadline = 0.0
    private var _rxKey: ByteArray? = null
    private var _txKey: ByteArray? = null
    private var _sessionIdB64 = ""
    private var _rxCounter = 0UL
    private var _txCounter = 0UL

    /** Pairing fields for the get_device_info response. */
    fun deviceInfo(): Map<String, Any?> = mapOf(
        "device_id" to deviceId,
        "mac" to mac,
        "model" to PairingConstants.MODEL,
        "pairing_protocol" to PAIRING_VERSION,
        "pairing_auth" to PairingConstants.AUTH_COMMUNITY,
        "pairing_auth_epoch" to 0,
        "pairing_policy" to POLICY_CONFIRM_APP,
    )

    val state: PairingState
        get() = synchronized(lock) {
            expireLocked()
            _state
        }

    val confirmed: Boolean
        get() = state == PairingState.READY || state == PairingState.PROVISIONING

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation != 0L && generation == _generation
    }

    fun reset() = synchronized(lock) {
        resetLocked()
    }

    /** Start a session from pairing_client_hello; returns pairing_ready. */
    fun handleHello(message: Map<String, Any?>): Map<String, Any?> {
        val v = message["version"]
        if (v is Boolean || v !is Number || v.toDouble() != PAIRING_VERSION.toDouble() ||
            message["pairing_auth"] != PairingConstants.AUTH_COMMUNITY ||
            message["pairing_policy"] != POLICY_CONFIRM_APP
        ) {
            throw PairingError(ERROR_INVALID_HELLO)
        }
        synchronized(lock) {
            resetLocked()
            val mobilePub: ByteArray
            val mobileNonce: ByteArray
            try {
                mobilePub = PairingCrypto.b64urlDecode(message["mobile_pub"] as? String ?: throw IllegalArgumentException("bad hello"))
                mobileNonce = PairingCrypto.b64urlDecode(message["mobile_nonce"] as? String ?: throw IllegalArgumentException("bad hello"))
                if (mobilePub.size != 65 || mobilePub[0] != 0x04.toByte() || mobileNonce.size != 16) {
                    throw IllegalArgumentException("invalid hello key material")
                }
                P256.requireValidPoint(mobilePub)
            } catch (e: IllegalArgumentException) {
                resetLocked()
                throw PairingError(ERROR_INVALID_HELLO)
            }
            val (deviceScalar, devicePub) = keyPairFactory()
            val deviceNonce = randomBytes(16)
            val transcript = PairingCrypto.buildTranscript(
                community = true,
                authEpoch = 0,
                policy = POLICY_CONFIRM_APP,
                deviceId = deviceId,
                nodeId = nodeId,
                mac = mac,
                firmwareVersion = firmwareVersion.ifEmpty { "unknown" },
                mobilePub = PairingCrypto.b64urlEncode(mobilePub),
                devicePub = PairingCrypto.b64urlEncode(devicePub),
                mobileNonce = PairingCrypto.b64urlEncode(mobileNonce),
                deviceNonce = PairingCrypto.b64urlEncode(deviceNonce),
            )
            val transcriptHash = PairingCrypto.transcriptHash(transcript)
            val ecdhSecret = P256.ecdh(deviceScalar, mobilePub)
            val keys = PairingCrypto.deriveSessionKeys(ecdhSecret, mobileNonce, deviceNonce, transcriptHash)
            _rxKey = keys.mobileTx
            _txKey = keys.mobileRx
            _sessionIdB64 = PairingCrypto.b64urlEncode(keys.sessionId)
            _rxCounter = 0UL
            _txCounter = 0UL
            _state = PairingState.WAIT_CLIENT_FINISHED
            _deadline = clock.nowSeconds() + CLIENT_FINISHED_TIMEOUT_S
            return mapOf(
                "type" to "pairing_ready",
                "version" to PAIRING_VERSION,
                "device_id" to deviceId,
                "node_id" to nodeId,
                "mac" to mac,
                "model" to PairingConstants.MODEL,
                "firmware_version" to firmwareVersion,
                "pairing_auth" to PairingConstants.AUTH_COMMUNITY,
                "pairing_auth_epoch" to 0,
                "pairing_policy" to POLICY_CONFIRM_APP,
                "device_pub" to PairingCrypto.b64urlEncode(devicePub),
                "device_nonce" to PairingCrypto.b64urlEncode(deviceNonce),
                "transcript_hash" to PairingCrypto.b64urlEncode(transcriptHash),
                "session_id" to _sessionIdB64,
            )
        }
    }

    /**
     * Open one mobile-to-device pairing_encrypted record. Any failure
     * clears the session, as the firmware does.
     */
    fun decrypt(envelope: Map<String, Any?>): String {
        synchronized(lock) {
            if (expireLocked() || _state == PairingState.IDLE) {
                resetLocked()
                throw PairingError(ERROR_DECRYPT)
            }
            fun fail(): Nothing = throw IllegalArgumentException("bad record")
            try {
                if ((envelope["session_id"] as? String) != _sessionIdB64) fail()
                val counterText = envelope["counter"] as? String ?: fail()
                val counter = try {
                    PairingCrypto.parseCounter(counterText)
                } catch (e: IllegalArgumentException) {
                    fail()
                }
                if (counter != _rxCounter) fail()
                val ciphertext = try {
                    PairingCrypto.b64urlDecode(envelope["ciphertext"] as? String ?: fail(), maxChars = 16384)
                } catch (e: IllegalArgumentException) {
                    fail()
                }
                val tag = try {
                    PairingCrypto.b64urlDecode(envelope["tag"] as? String ?: fail())
                } catch (e: IllegalArgumentException) {
                    fail()
                }
                if (tag.size != 16) fail()
                val rxKey = _rxKey ?: fail()
                val pt = try {
                    PairingCrypto.open(
                        rxKey,
                        PairingCrypto.recordNonce(TO_DEVICE, counter),
                        ciphertext,
                        tag,
                        PairingCrypto.recordAad(_sessionIdB64, TO_DEVICE, counter),
                    )
                } catch (e: GeneralSecurityException) {
                    fail()
                }
                val plaintext = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(pt)).toString()
                } catch (e: CharacterCodingException) {
                    fail()
                }
                _rxCounter++
                return plaintext
            } catch (e: IllegalArgumentException) {
                resetLocked()
                throw PairingError(ERROR_DECRYPT)
            } catch (e: GeneralSecurityException) {
                resetLocked()
                throw PairingError(ERROR_DECRYPT)
            } catch (e: CharacterCodingException) {
                resetLocked()
                throw PairingError(ERROR_DECRYPT)
            }
        }
    }

    /**
     * Confirm the session after the first decrypted record. [command] must be
     * exactly {"action": "pairing_client_finished"} and must have been the
     * first record. Returns the new generation, or 0 if invalid.
     */
    fun handleClientFinished(command: Map<String, Any?>): Long {
        synchronized(lock) {
            val ok = command == mapOf("action" to "pairing_client_finished") &&
                !expireLocked() &&
                _state == PairingState.WAIT_CLIENT_FINISHED &&
                _rxCounter == 1UL
            if (!ok) {
                resetLocked()
                return 0L
            }
            advanceGenerationLocked()
            _state = PairingState.READY
            _deadline = clock.nowSeconds() + CONFIRMED_TIMEOUT_S
            return _generation
        }
    }

    /** Enter provisioning from a confirmed session; returns its generation. */
    open fun markProvisioning(): Long {
        synchronized(lock) {
            if (!expireLocked() && _state == PairingState.READY) {
                advanceGenerationLocked()
                _state = PairingState.PROVISIONING
                _deadline = clock.nowSeconds() + PROVISIONING_TIMEOUT_S
            }
            if (_state == PairingState.PROVISIONING) return _generation
            return 0L
        }
    }

    fun extendProvisioning(generation: Long): Boolean {
        synchronized(lock) {
            val valid = provisioningLocked(generation)
            if (valid) _deadline = clock.nowSeconds() + PROVISIONING_TIMEOUT_S
            return valid
        }
    }

    /**
     * Run [commit] under the pairing lock if the session is still valid.
     * [commit] must only persist local state; no network or BLE calls.
     */
    fun commitProvisioning(generation: Long, commit: () -> Boolean): Boolean {
        synchronized(lock) {
            return provisioningLocked(generation) && commit()
        }
    }

    /**
     * Seal a device-to-mobile record. Returns null when there is no active
     * session, or when a nonzero [generation] no longer matches.
     */
    fun encryptJson(plaintext: String, generation: Long = 0L): Map<String, Any?>? {
        synchronized(lock) {
            if ((generation != 0L && generation != _generation) ||
                expireLocked() ||
                _state == PairingState.IDLE
            ) {
                return null
            }
            val txKey = _txKey ?: return null
            val counter = _txCounter
            val (ct, tag) = PairingCrypto.seal(
                txKey,
                PairingCrypto.recordNonce(FROM_DEVICE, counter),
                plaintext.toByteArray(Charsets.UTF_8),
                PairingCrypto.recordAad(_sessionIdB64, FROM_DEVICE, counter),
            )
            _txCounter++
            return mapOf(
                "type" to "pairing_encrypted",
                "session_id" to _sessionIdB64,
                "counter" to counter.toString(),
                "ciphertext" to PairingCrypto.b64urlEncode(ct),
                "tag" to PairingCrypto.b64urlEncode(tag),
            )
        }
    }

    fun encryptStatus(status: String, generation: Long = 0L): Map<String, Any?>? {
        val msg = LinkedHashMap<String, Any?>()
        msg["type"] = "status"
        msg["status"] = status
        // Apps read only type and status, so older ones ignore the token.
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") {
            msg["sdk_token"] = sdkToken
        }
        return encryptJson(Json.stringify(msg), generation)
    }

    private fun provisioningLocked(generation: Long): Boolean {
        return generation != 0L &&
            generation == _generation &&
            !expireLocked() &&
            _state == PairingState.PROVISIONING
    }

    private fun advanceGenerationLocked() {
        _generation++
    }

    private fun resetLocked() {
        advanceGenerationLocked()
        clearLocked()
    }

    private fun clearLocked() {
        _state = PairingState.IDLE
        _deadline = 0.0
        _rxKey = null
        _txKey = null
        _sessionIdB64 = ""
        _rxCounter = 0UL
        _txCounter = 0UL
    }

    private fun expireLocked(): Boolean {
        if (_state == PairingState.IDLE || clock.nowSeconds() <= _deadline) return false
        // Drop the keys but keep the generation, so the owner of the expired
        // session can still recognise it and close the connection.
        clearLocked()
        return true
    }
}
