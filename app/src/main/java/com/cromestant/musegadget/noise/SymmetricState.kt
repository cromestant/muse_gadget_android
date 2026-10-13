package com.cromestant.musegadget.noise

import com.cromestant.musegadget.common.Bytes

/** SymmetricState: MixHash/MixKey/EncryptAndHash/DecryptAndHash/Split, mirroring noise_xx.py. */
class SymmetricState {
    private var ck = ByteArray(32)
    private var h = ByteArray(32)
    private var cipher = CipherState()

    fun initialize() {
        val padded = ByteArray(32)
        NoiseConstants.PROTOCOL_NAME.copyInto(padded)
        h = padded
        ck = h.copyOf()
        mixHash(ByteArray(0))
    }

    fun mixHash(data: ByteArray) {
        h = Bytes.sha256(h, data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = Bytes.hkdfNoise2(ck, ikm)
        ck = newCk
        cipher = CipherState()
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val c = cipher.encryptWithAd(h, plaintext)
        mixHash(c)
        return c
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val p = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return p
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Bytes.hkdfNoise2(ck, ByteArray(0))
        ck = ByteArray(32)
        h = ByteArray(32)
        val c1 = CipherState()
        c1.initializeKey(k1)
        val c2 = CipherState()
        c2.initializeKey(k2)
        return c1 to c2
    }

    fun handshakeHash(): ByteArray = h.copyOf()
}
