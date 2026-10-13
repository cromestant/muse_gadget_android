package com.cromestant.musegadget.noise

import com.cromestant.musegadget.common.Bytes
import javax.crypto.AEADBadTagException

/** 12-byte nonce IV: 4 zero bytes || big-endian u64. */
fun buildNonceIv(nonce: Long): ByteArray {
    if (nonce < 0) throw NoiseProtocolError("nonce outside uint64 range")
    val iv = ByteArray(12)
    for (i in 0 until 8) iv[11 - i] = (nonce ushr (8 * i)).toByte()
    return iv
}

/**
 * AES-256-GCM CipherState, mirroring noise_xx.py: nonces start at 0,
 * ceiling 2^53-1 with poison-on-exhaust, decrypt failure poisons.
 * Unkeyed state passes plaintext through (handshake prologue).
 */
class CipherState {
    private var key: ByteArray? = null
    private var nonce: Long = 0
    private var poisoned = false

    @Synchronized
    fun initializeKey(newKey: ByteArray) {
        assertAlive()
        require(newKey.size == 32) { "CipherState: AES-GCM key must be 32 bytes" }
        key = newKey.copyOf()
        nonce = 0
    }

    @Synchronized
    fun hasKey(): Boolean = key != null

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        assertAlive()
        val k = key ?: return plaintext.copyOf()
        val n = nextNonce()
        try {
            return Bytes.aesGcmEncrypt(k, buildNonceIv(n), plaintext, ad)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        assertAlive()
        val k = key ?: return ciphertext.copyOf()
        val n = nextNonce()
        try {
            return Bytes.aesGcmDecrypt(k, buildNonceIv(n), ciphertext, ad)
        } catch (e: AEADBadTagException) {
            poisoned = true
            throw NoiseProtocolError("CipherState: decrypt failed", e)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    private fun assertAlive() {
        if (poisoned) throw NoiseProtocolError("CipherState: poisoned after prior failure")
    }

    private fun nextNonce(): Long {
        if (nonce >= NoiseConstants.MAX_SAFE_NONCE) {
            poisoned = true
            throw NoiseProtocolError("CipherState: nonce exhausted")
        }
        return nonce++
    }
}
