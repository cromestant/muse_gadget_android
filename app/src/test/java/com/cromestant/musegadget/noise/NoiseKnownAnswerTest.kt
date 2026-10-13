package com.cromestant.musegadget.noise

import com.cromestant.musegadget.common.Bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Known-answer tests: X25519 RFC 7748, HKDF-SHA256 RFC 5869, AES-256-GCM. */
class NoiseKnownAnswerTest {
    // --- X25519, RFC 7748 section 5.2 --------------------------------------

    @Test
    fun x25519Rfc7748Section52Vector1() {
        val out = X25519.scalarMult(
            Bytes.hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            Bytes.hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", Bytes.toHex(out))
    }

    @Test
    fun x25519Rfc7748Section52Vector2() {
        val out = X25519.scalarMult(
            Bytes.hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
            Bytes.hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
        )
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957", Bytes.toHex(out))
    }

    @Test
    fun x25519Rfc7748IteratedVector() {
        val k = Bytes.hex("0900000000000000000000000000000000000000000000000000000000000000")
        val out = X25519.scalarMult(k, k)
        assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", Bytes.toHex(out))
    }

    @Test
    fun x25519Rfc7748Section61DiffieHellman() {
        val a = Bytes.hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = Bytes.hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val ka = X25519.keyPairFromPrivate(a)
        val kb = X25519.keyPairFromPrivate(b)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", Bytes.toHex(ka.publicKey))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", Bytes.toHex(kb.publicKey))
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, Bytes.toHex(X25519.dh(a, kb.publicKey)))
        assertEquals(expected, Bytes.toHex(X25519.dh(b, ka.publicKey)))
    }

    @Test
    fun x25519RejectsLowOrderPoints() {
        val priv = X25519.generateKeyPair().privateKey
        for (lo in NoiseConstants.LOW_ORDER_POINTS) {
            try {
                X25519.dh(priv, lo)
                fail("expected NoiseProtocolError for low-order point ${Bytes.toHex(lo)}")
            } catch (e: NoiseProtocolError) {
                // expected
            }
        }
        try {
            X25519.dh(priv, ByteArray(31))
            fail("expected NoiseProtocolError for short key")
        } catch (e: NoiseProtocolError) {
        }
    }

    // --- HKDF-SHA256, RFC 5869 appendix A -----------------------------------

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, l: Int): Pair<ByteArray, ByteArray> {
        val prk = Bytes.hkdfExtract(salt, ikm)
        return prk to Bytes.hkdfExpand(prk, info, l)
    }

    @Test
    fun hkdfRfc5869Case1() {
        val (prk, okm) = hkdf(
            Bytes.hex("000102030405060708090a0b0c"),
            Bytes.hex("0b".repeat(22)),
            Bytes.hex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Bytes.toHex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.toHex(okm),
        )
    }

    @Test
    fun hkdfRfc5869Case2() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        val (prk, okm) = hkdf(salt, ikm, info, 82)
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", Bytes.toHex(prk))
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Bytes.toHex(okm),
        )
    }

    @Test
    fun hkdfRfc5869Case3EmptySaltAndInfo() {
        val (prk, okm) = hkdf(ByteArray(0), Bytes.hex("0b".repeat(22)), ByteArray(0), 42)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", Bytes.toHex(prk))
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Bytes.toHex(okm),
        )
    }

    // --- AES-256-GCM --------------------------------------------------------

    @Test
    fun aes256GcmZeroKeyEmptyPlaintext() {
        // NIST CAVP zero-length case: key/IV all zero, empty PT and AAD.
        val sealed = Bytes.aesGcmEncrypt(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0))
        assertEquals(16, sealed.size)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", Bytes.toHex(sealed))
        assertArrayEquals(ByteArray(0), Bytes.aesGcmDecrypt(ByteArray(32), ByteArray(12), sealed, ByteArray(0)))
    }

    @Test
    fun aes256GcmSpecTestCase16() {
        // McGrew-Viega GCM spec test case 16 (AES-256, 60B PT, 20B AAD).
        val key = Bytes.hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = Bytes.hex("cafebabefacedbaddecaf888")
        val pt = Bytes.hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = Bytes.hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = Bytes.aesGcmEncrypt(key, iv, pt, aad)
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
            Bytes.toHex(sealed),
        )
        assertArrayEquals(pt, Bytes.aesGcmDecrypt(key, iv, sealed, aad))
    }

    @Test
    fun cipherStateNonceIvLayout() {
        assertArrayEquals(Bytes.hex("000000000000000000000000"), buildNonceIv(0))
        assertArrayEquals(Bytes.hex("000000000000000000000001"), buildNonceIv(1))
        assertArrayEquals(Bytes.hex("000000007fffffffffffffff"), buildNonceIv(Long.MAX_VALUE))
        try {
            buildNonceIv(-1)
            fail("expected NoiseProtocolError for negative nonce")
        } catch (e: NoiseProtocolError) {
        }
    }
}
