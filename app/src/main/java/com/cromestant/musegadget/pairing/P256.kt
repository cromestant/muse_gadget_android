package com.cromestant.musegadget.pairing

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement

/**
 * P-256 ECDH via standard JCA "ECDH". Pure JVM: no Android imports.
 */
object P256 {
    private fun params(): ECParameterSpec {
        val alg = AlgorithmParameters.getInstance("EC")
        alg.init(ECGenParameterSpec("secp256r1"))
        return alg.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * ECDH shared secret: our 32-byte big-endian private scalar with the
     * peer's 65-byte uncompressed 0x04||X||Y point. Returns 32 bytes
     * (left-padded X coordinate).
     */
    fun ecdh(privateScalar32: ByteArray, peerUncompressed65: ByteArray): ByteArray {
        require(privateScalar32.size == 32) { "P-256 private scalar must be 32 bytes" }
        require(peerUncompressed65.size == 65 && peerUncompressed65[0] == 0x04.toByte()) {
            "P-256 peer point must be 65-byte uncompressed"
        }
        val spec = params()
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(
            ECPrivateKeySpec(BigInteger(1, privateScalar32), spec)
        )
        val x = BigInteger(1, peerUncompressed65.copyOfRange(1, 33))
        val y = BigInteger(1, peerUncompressed65.copyOfRange(33, 65))
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val raw = ka.generateSecret()
        if (raw.size == 32) return raw
        val out = ByteArray(32)
        if (raw.size > 32) {
            raw.copyInto(out, 0, raw.size - 32, raw.size)
        } else {
            raw.copyInto(out, 32 - raw.size, 0, raw.size)
        }
        return out
    }

    // Hardcoded secp256r1 domain parameters for pure-BigInteger point math.
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B6FCF6E6BF11F", 16)

    /**
     * Reject anything that is not a valid secp256r1 uncompressed point:
     * 65 bytes starting 0x04 with y^2 == x^3 + a*x + b (mod p).
     */
    fun requireValidPoint(uncompressed65: ByteArray) {
        require(uncompressed65.size == 65 && uncompressed65[0] == 0x04.toByte()) {
            "P-256 point must be 65-byte uncompressed"
        }
        val x = BigInteger(1, uncompressed65.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed65.copyOfRange(33, 65))
        require(x < P && y < P) { "P-256 point out of range" }
        val lhs = y.multiply(y).mod(P)
        val rhs = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P)
        require(lhs == rhs) { "P-256 point not on curve" }
    }

    /**
     * 65-byte uncompressed point for a 32-byte private scalar via
     * double-and-add on the hardcoded curve. Not side-channel hardened —
     * production keygen uses KeyPairGenerator; this exists for the injected
     * test key.
     */
    fun publicFromScalar(privateScalar32: ByteArray): ByteArray {
        require(privateScalar32.size == 32) { "P-256 private scalar must be 32 bytes" }
        val s = BigInteger(1, privateScalar32)
        require(s.signum() > 0 && s < N) { "P-256 scalar out of range" }
        var acc: Pair<BigInteger, BigInteger>? = null
        var addend: Pair<BigInteger, BigInteger>? = GX to GY
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) acc = pointAdd(acc, addend)
            addend = pointDouble(addend)
            k = k.shiftRight(1)
        }
        val (x, y) = acc ?: throw IllegalArgumentException("P-256 scalar gives infinity")
        return byteArrayOf(0x04) + toFixed32(x) + toFixed32(y)
    }

    private fun toFixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("P-256 coordinate out of range")
        }
    }

    private fun pointDouble(p: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
        if (p == null) return null
        val (x, y) = p
        if (y.signum() == 0) return null
        val lam = x.multiply(x).multiply(BigInteger.valueOf(3)).add(A)
            .multiply(y.multiply(BigInteger.TWO).modInverse(P)).mod(P)
        val x3 = lam.multiply(lam).subtract(x.multiply(BigInteger.TWO)).mod(P)
        val y3 = lam.multiply(x.subtract(x3)).subtract(y).mod(P)
        return x3 to y3
    }

    private fun pointAdd(
        p: Pair<BigInteger, BigInteger>?,
        q: Pair<BigInteger, BigInteger>?,
    ): Pair<BigInteger, BigInteger>? {
        if (p == null) return q
        if (q == null) return p
        val (x1, y1) = p
        val (x2, y2) = q
        if (x1 == x2) {
            if (y1.add(y2).mod(P).signum() == 0) return null
            return pointDouble(p)
        }
        val lam = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P)
        val x3 = lam.multiply(lam).subtract(x1).subtract(x2).mod(P)
        val y3 = lam.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return x3 to y3
    }

    /** Fresh (privateScalar32, uncompressedPub65) via the platform key generator. */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = gen.generateKeyPair()
        val priv = (kp.private as ECPrivateKey).s
        val w = (kp.public as ECPublicKey).w
        return toFixed32(priv) to (byteArrayOf(0x04) + toFixed32(w.affineX) + toFixed32(w.affineY))
    }
}
