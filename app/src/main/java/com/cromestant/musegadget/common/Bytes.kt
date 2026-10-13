package com.cromestant.musegadget.common

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared JVM-only byte primitives (SHA-256, HMAC-SHA256, HKDF-SHA256,
 * AES-256-GCM, hex). No Android imports: usable from plain JVM unit tests.
 */
object Bytes {
    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[2 * i], 16) shl 4) + Character.digit(s[2 * i + 1], 16)).toByte()
        }.also {
            require(s.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) { "bad hex" }
        }
    }

    fun toHex(b: ByteArray): String = buildString(b.size * 2) {
        for (v in b) {
            val x = v.toInt() and 0xFF
            append("0123456789abcdef"[x ushr 4])
            append("0123456789abcdef"[x and 0xF])
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 Extract. Empty salt means HashLen zeros. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val s = if (salt.isEmpty()) ByteArray(32) else salt
        return hmacSha256(s, ikm)
    }

    /** RFC 5869 Expand. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "bad hkdf length" }
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + counter.toByte())
            out.write(t, 0, t.size)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * Noise-style HKDF(chaining_key, input_key_material) yielding two
     * 32-byte outputs, exactly as noise_xx.py `_hkdf(ck, ikm, 2)`.
     */
    fun hkdfNoise2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmacSha256(chainingKey, ikm)
        val o1 = hmacSha256(temp, byteArrayOf(0x01))
        val o2 = hmacSha256(temp, o1 + byteArrayOf(0x02))
        return o1 to o2
    }

    /** AES-256-GCM encrypt. Returns ciphertext || 16-byte tag. */
    fun aesGcmEncrypt(key: ByteArray, iv12: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-GCM key must be 32 bytes" }
        require(iv12.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv12))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** AES-256-GCM decrypt. Takes ciphertext || 16-byte tag. */
    fun aesGcmDecrypt(key: ByteArray, iv12: ByteArray, ctAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-GCM key must be 32 bytes" }
        require(iv12.size == 12) { "AES-GCM nonce must be 12 bytes" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv12))
        c.updateAAD(aad)
        return c.doFinal(ctAndTag)
    }
}
