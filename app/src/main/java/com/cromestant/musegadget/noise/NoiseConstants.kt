package com.cromestant.musegadget.noise

import com.cromestant.musegadget.common.Bytes

/** Noise XX handshake constants, mirroring noise_xx.py. */
object NoiseConstants {
    val PROTOCOL_NAME: ByteArray = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)
    const val DH_KEY_LEN = 32
    const val AEAD_TAG_LEN = 16
    const val MIN_MSG2_LEN = DH_KEY_LEN + (DH_KEY_LEN + AEAD_TAG_LEN) + AEAD_TAG_LEN // 96
    const val MIN_MSG3_LEN = DH_KEY_LEN + AEAD_TAG_LEN + AEAD_TAG_LEN // 64
    const val MAX_SAFE_NONCE: Long = (1L shl 53) - 1
    val EMPTY_AD = ByteArray(0)

    /**
     * 7-point X25519 low-order/all-zero blocklist from noise_xx.py.
     * Any of these as a peer public key aborts the handshake.
     */
    val LOW_ORDER_POINTS: List<ByteArray> = listOf(
        Bytes.hex("0000000000000000000000000000000000000000000000000000000000000000"),
        Bytes.hex("0100000000000000000000000000000000000000000000000000000000000000"),
        Bytes.hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
        Bytes.hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
        Bytes.hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        Bytes.hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        Bytes.hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    )
}
