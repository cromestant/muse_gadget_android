package com.cromestant.musegadget.pairing

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
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
}
