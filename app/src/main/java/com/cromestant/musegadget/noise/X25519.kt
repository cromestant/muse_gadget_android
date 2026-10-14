package com.cromestant.musegadget.noise

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Handshake or transport state-machine violation, mirroring NoiseProtocolError. */
class NoiseProtocolError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

private val FIELD_P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
private val A24 = BigInteger.valueOf(121665) // (486662 - 2) / 4

private fun leToBig(u: ByteArray): BigInteger {
    val be = u.reversedArray()
    be[0] = (be[0].toInt() and 0x7F).toByte() // mask top bit per RFC 7748
    return BigInteger(1, be)
}

private fun bigToLe(x: BigInteger): ByteArray {
    val be = x.toByteArray() // big-endian two's complement
    var start = 0
    while (start < be.size - 1 && be[start] == 0.toByte()) start++
    var mag = be.copyOfRange(start, be.size)
    if (mag.size > 32) mag = mag.copyOfRange(mag.size - 32, mag.size)
    val padded = ByteArray(32)
    System.arraycopy(mag, 0, padded, 32 - mag.size, mag.size)
    return padded.reversedArray()
}

/**
 * Pure-Kotlin X25519 per RFC 7748 (Montgomery ladder). Deliberately not
 * JCA "XDH": that provider algorithm is absent on Android API 29, and
 * this module must be provider-independent.
 */
object X25519 {
    fun scalarMult(scalar32: ByteArray, u32: ByteArray): ByteArray {
        require(scalar32.size == 32 && u32.size == 32) { "x25519 inputs must be 32 bytes" }
        val k = scalar32.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()

        val x1 = leToBig(u32)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt
            val a = x2.add(z2).mod(FIELD_P)
            val aa = a.multiply(a).mod(FIELD_P)
            val b = x2.subtract(z2).mod(FIELD_P)
            val bb = b.multiply(b).mod(FIELD_P)
            val e = aa.subtract(bb).mod(FIELD_P)
            val c = x3.add(z3).mod(FIELD_P)
            val d = x3.subtract(z3).mod(FIELD_P)
            val da = d.multiply(a).mod(FIELD_P)
            val cb = c.multiply(b).mod(FIELD_P)
            val sumSq = da.add(cb).mod(FIELD_P)
            x3 = sumSq.multiply(sumSq).mod(FIELD_P)
            val difSq = da.subtract(cb).mod(FIELD_P)
            val difSq2 = difSq.multiply(difSq).mod(FIELD_P)
            z3 = x1.multiply(difSq2).mod(FIELD_P)
            x2 = aa.multiply(bb).mod(FIELD_P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(FIELD_P)).mod(FIELD_P)
        }
        // Residual swap: after bit 0 the pair order follows k_0; restore
        // canonical order exactly as the RFC 7748 pseudocode final cswap.
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val z2Inv = z2.modPow(FIELD_P.subtract(BigInteger.valueOf(2)), FIELD_P)
        return bigToLe(x2.multiply(z2Inv).mod(FIELD_P))
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): X25519KeyPair {
        val priv = ByteArray(32)
        random.nextBytes(priv)
        return keyPairFromPrivate(priv)
    }

    fun keyPairFromPrivate(priv: ByteArray): X25519KeyPair {
        require(priv.size == 32) { "x25519 private key must be 32 bytes" }
        val base = ByteArray(32)
        base[0] = 9
        return X25519KeyPair(priv.copyOf(), scalarMult(priv, base))
    }

    /** DH with the Python 7-point low-order blocklist and all-zero-output reject. */
    fun dh(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        require(privateKey.size == 32) { "x25519 private key must be 32 bytes" }
        if (peerPublic.size != NoiseConstants.DH_KEY_LEN) {
            throw NoiseProtocolError("x25519: invalid public key length")
        }
        for (lo in NoiseConstants.LOW_ORDER_POINTS) {
            if (MessageDigest.isEqual(peerPublic, lo)) {
                throw NoiseProtocolError("x25519: rejected low-order public key")
            }
        }
        val shared = scalarMult(privateKey, peerPublic)
        if (shared.all { it == 0.toByte() }) {
            throw NoiseProtocolError("x25519: DH produced all-zeros output")
        }
        return shared
    }
}
