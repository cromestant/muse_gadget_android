package com.cromestant.musegadget.pairing

import com.cromestant.musegadget.common.Bytes
import com.cromestant.musegadget.pairing.PairingConstants.AUTH_COMMUNITY
import com.cromestant.musegadget.pairing.PairingConstants.AUTH_OFFICIAL
import com.cromestant.musegadget.pairing.PairingConstants.BUTTON_CONFIRM_TIMEOUT_S
import com.cromestant.musegadget.pairing.PairingConstants.FROM_DEVICE
import com.cromestant.musegadget.pairing.PairingConstants.MODEL
import com.cromestant.musegadget.pairing.PairingConstants.PAIRING_VERSION
import com.cromestant.musegadget.pairing.PairingConstants.POLICY_CONFIRM_APP
import com.cromestant.musegadget.pairing.PairingConstants.POLICY_CONFIRM_PRESS
import com.cromestant.musegadget.pairing.PairingConstants.RECORD_LABEL
import com.cromestant.musegadget.pairing.PairingConstants.SESSION_ID_LABEL
import com.cromestant.musegadget.pairing.PairingConstants.SUITE
import com.cromestant.musegadget.pairing.PairingConstants.TAG_BYTES
import com.cromestant.musegadget.pairing.PairingConstants.TO_DEVICE
import java.util.Base64

private val B64URL_RE = Regex("[A-Za-z0-9_-]+")
private val DECIMAL_RE = Regex("[0-9]+")

data class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

/**
 * Stateless pairing-v5 crypto, mirroring pairing.py: canonical transcript,
 * key schedule, and record protection. No session state machine here —
 * that belongs with the BLE chunk. Pure JVM: no Android imports.
 */
object PairingCrypto {
    fun buildTranscript(
        community: Boolean,
        authEpoch: Int,
        policy: String,
        deviceId: String,
        nodeId: String,
        mac: String,
        firmwareVersion: String,
        mobilePub: String,
        devicePub: String,
        mobileNonce: String,
        deviceNonce: String,
    ): String {
        val button = policy == POLICY_CONFIRM_PRESS
        if (!(button || (community && policy == POLICY_CONFIRM_APP))) {
            throw IllegalArgumentException("unsupported pairing policy: '$policy'")
        }
        if (if (community) authEpoch != 0 else authEpoch <= 0) {
            throw IllegalArgumentException("invalid auth epoch: $authEpoch")
        }
        if (listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
            .any { it.isEmpty() }
        ) {
            throw IllegalArgumentException("transcript fields must be non-empty")
        }
        return listOf(
            "hatch-link-pairing-v$PAIRING_VERSION",
            "version=$PAIRING_VERSION",
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=$deviceId",
            "node_id=$nodeId",
            "mac=$mac",
            "model=$MODEL",
            "firmware_version=$firmwareVersion",
            "selected_cipher_suite=$SUITE",
            "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
            "pairing_auth_epoch=$authEpoch",
            "pairing_policy=$policy",
            "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
            "mobile_pub=$mobilePub",
            "device_pub=$devicePub",
            "mobile_nonce=$mobileNonce",
            "device_nonce=$deviceNonce",
        ).joinToString("\n")
    }

    fun transcriptHash(transcript: String): ByteArray =
        Bytes.sha256(transcript.toByteArray(Charsets.UTF_8))

    /**
     * Returns (mobileTx, mobileRx, sessionId). mobileTx decrypts records
     * the device receives; mobileRx encrypts records it sends.
     */
    fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        mobileNonce: ByteArray,
        deviceNonce: ByteArray,
        transcriptHash: ByteArray,
    ): SessionKeys {
        val salt = Bytes.sha256(mobileNonce, deviceNonce, transcriptHash)
        val sessionSecret = Bytes.hkdfExpand(
            Bytes.hkdfExtract(salt, ecdhSecret),
            RECORD_LABEL.toByteArray(Charsets.UTF_8),
            32,
        )
        val mobileTx = Bytes.hkdfExpand(sessionSecret, "mobile->device".toByteArray(Charsets.US_ASCII), 32)
        val mobileRx = Bytes.hkdfExpand(sessionSecret, "device->mobile".toByteArray(Charsets.US_ASCII), 32)
        val sessionId = Bytes.sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret).copyOf(16)
        return SessionKeys(mobileTx, mobileRx, sessionId)
    }

    /** 12-byte nonce: [direction, 0, 0, 0] ++ big-endian u64 counter. */
    fun recordNonce(direction: Int, counter: ULong): ByteArray {
        require(direction == TO_DEVICE || direction == FROM_DEVICE) { "bad direction" }
        val out = ByteArray(12)
        out[0] = direction.toByte()
        for (i in 0 until 8) out[11 - i] = (counter shr (8 * i)).toByte()
        return out
    }

    fun recordAad(sessionIdB64: String, direction: Int, counter: ULong): ByteArray {
        val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
        return "$RECORD_LABEL|$sessionIdB64|$arrow|$counter".toByteArray(Charsets.UTF_8)
    }

    /** AES-256-GCM seal. Returns (ciphertext, tag). */
    fun seal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val sealed = Bytes.aesGcmEncrypt(key, nonce12, plaintext, aad)
        return sealed.copyOf(sealed.size - TAG_BYTES) to
            sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)
    }

    fun open(key: ByteArray, nonce12: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray {
        require(tag.size == TAG_BYTES) { "invalid tag length" }
        return Bytes.aesGcmDecrypt(key, nonce12, ciphertext + tag, aad)
    }

    fun b64urlEncode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** Unpadded base64url decode, rejecting what the firmware rejects. */
    fun b64urlDecode(text: String, maxChars: Int = 4096): ByteArray {
        if (text.isEmpty() || text.length > maxChars) throw IllegalArgumentException("invalid base64url length")
        if (text.length % 4 == 1 || !B64URL_RE.matches(text)) throw IllegalArgumentException("invalid base64url")
        return Base64.getUrlDecoder().decode(text + "=".repeat((4 - text.length % 4) % 4))
    }

    fun parseCounter(text: String): ULong {
        if (!DECIMAL_RE.matches(text)) throw IllegalArgumentException("invalid counter")
        return text.toULongOrNull() ?: throw IllegalArgumentException("counter overflow")
    }
}
