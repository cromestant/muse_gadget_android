package com.cromestant.musegadget.noise

private enum class ResponderPhase { CREATED, INITIALIZED, MSG2_SENT, MSG3_READ, SPLIT, DEAD }

/**
 * Test-only responder mirroring the Python NoiseXXResponder in noise_xx.py.
 * Lives in the test sourceset; not shipped behavior, only handshake verification.
 */
class TestResponder(private val payload: ByteArray = ByteArray(0)) {
    private val ss = SymmetricState()
    private var e: X25519KeyPair? = null
    private var s: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var phase = ResponderPhase.CREATED

    fun initialize() {
        requirePhase(ResponderPhase.CREATED, "initialize")
        ss.initialize()
        phase = ResponderPhase.INITIALIZED
    }

    fun readMessage1AndWriteMessage2(msg1: ByteArray): ByteArray {
        requirePhase(ResponderPhase.INITIALIZED, "readMessage1AndWriteMessage2")
        if (msg1.size < NoiseConstants.DH_KEY_LEN) {
            phase = ResponderPhase.DEAD
            throw NoiseProtocolError("NoiseXX: message 1 too short (${msg1.size} < ${NoiseConstants.DH_KEY_LEN})")
        }
        try {
            val reBytes = msg1.copyOfRange(0, NoiseConstants.DH_KEY_LEN)
            re = reBytes
            ss.mixHash(reBytes)
            ss.decryptAndHash(msg1.copyOfRange(NoiseConstants.DH_KEY_LEN, msg1.size))

            val eKp = X25519.generateKeyPair()
            e = eKp
            ss.mixHash(eKp.publicKey)
            ss.mixKey(X25519.dh(eKp.privateKey, reBytes))

            val sKp = X25519.generateKeyPair()
            s = sKp
            val encS = ss.encryptAndHash(sKp.publicKey)
            ss.mixKey(X25519.dh(sKp.privateKey, reBytes))

            val encPayload = ss.encryptAndHash(payload.copyOf())
            phase = ResponderPhase.MSG2_SENT
            return eKp.publicKey + encS + encPayload
        } catch (ex: Exception) {
            phase = ResponderPhase.DEAD
            throw ex
        }
    }

    fun readMessage3(msg3: ByteArray) {
        requirePhase(ResponderPhase.MSG2_SENT, "readMessage3")
        if (msg3.size < NoiseConstants.MIN_MSG3_LEN) {
            phase = ResponderPhase.DEAD
            throw NoiseProtocolError("NoiseXX: message 3 too short (${msg3.size} < ${NoiseConstants.MIN_MSG3_LEN})")
        }
        try {
            var off = 0
            val rs = ss.decryptAndHash(msg3.copyOfRange(off, off + NoiseConstants.DH_KEY_LEN + NoiseConstants.AEAD_TAG_LEN))
            off += NoiseConstants.DH_KEY_LEN + NoiseConstants.AEAD_TAG_LEN

            val eKp = e ?: throw NoiseProtocolError("NoiseXX: missing responder ephemeral key")
            ss.mixKey(X25519.dh(eKp.privateKey, rs))

            ss.decryptAndHash(msg3.copyOfRange(off, msg3.size))
            phase = ResponderPhase.MSG3_READ
        } catch (ex: Exception) {
            phase = ResponderPhase.DEAD
            throw ex
        }
    }

    /** Returns (send, recv): responder sends with first, receives with second. */
    fun split(): Pair<CipherState, CipherState> {
        requirePhase(ResponderPhase.MSG3_READ, "split")
        phase = ResponderPhase.SPLIT
        val (c1, c2) = ss.split()
        e = null
        s = null
        re = null
        return c2 to c1
    }

    fun handshakeHash(): ByteArray = ss.handshakeHash()

    private fun requirePhase(expected: ResponderPhase, method: String) {
        if (phase == ResponderPhase.DEAD) throw NoiseProtocolError("NoiseXX: $method called on dead handshake")
        if (phase != expected) {
            throw NoiseProtocolError("NoiseXX: $method called in wrong phase (expected $expected, got $phase)")
        }
    }
}
