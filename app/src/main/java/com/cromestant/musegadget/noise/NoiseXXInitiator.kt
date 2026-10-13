package com.cromestant.musegadget.noise

private enum class Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

/**
 * Noise XX initiator (we are the handshake initiator, as in noise_xx.py).
 * DHs ee, es, se (no ss). Empty payloads on all messages.
 * Wire: msg1 = 32B e_pub; msg2 = e(32) + s_ct(48) + payload_ct; msg3 = s_ct(48) + payload_ct.
 */
class NoiseXXInitiator {
    private val ss = SymmetricState()
    private var e: X25519KeyPair? = null
    private var s: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var phase = Phase.CREATED

    fun initialize() {
        requirePhase(Phase.CREATED, "initialize")
        ss.initialize()
        phase = Phase.INITIALIZED
    }

    fun writeMessage1(): ByteArray {
        requirePhase(Phase.INITIALIZED, "writeMessage1")
        try {
            val kp = X25519.generateKeyPair()
            e = kp
            ss.mixHash(kp.publicKey)
            ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG1_SENT
            return kp.publicKey.copyOf()
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    fun readMessage2(msg: ByteArray): ByteArray {
        requirePhase(Phase.MSG1_SENT, "readMessage2")
        if (msg.size < NoiseConstants.MIN_MSG2_LEN) {
            phase = Phase.DEAD
            throw NoiseProtocolError("NoiseXX: message 2 too short (${msg.size} < ${NoiseConstants.MIN_MSG2_LEN})")
        }
        try {
            var off = 0
            val reBytes = msg.copyOfRange(off, off + NoiseConstants.DH_KEY_LEN)
            re = reBytes
            ss.mixHash(reBytes)
            off += NoiseConstants.DH_KEY_LEN

            val eKp = e ?: throw NoiseProtocolError("NoiseXX: missing initiator ephemeral key")
            ss.mixKey(X25519.dh(eKp.privateKey, reBytes))

            val rsBytes = ss.decryptAndHash(msg.copyOfRange(off, off + NoiseConstants.DH_KEY_LEN + NoiseConstants.AEAD_TAG_LEN))
            rs = rsBytes
            off += NoiseConstants.DH_KEY_LEN + NoiseConstants.AEAD_TAG_LEN

            ss.mixKey(X25519.dh(eKp.privateKey, rsBytes))

            val payload = ss.decryptAndHash(msg.copyOfRange(off, msg.size))
            phase = Phase.MSG2_READ
            return payload
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    fun writeMessage3(): ByteArray {
        requirePhase(Phase.MSG2_READ, "writeMessage3")
        try {
            val kp = X25519.generateKeyPair()
            s = kp
            val encS = ss.encryptAndHash(kp.publicKey)

            val reBytes = re ?: throw NoiseProtocolError("NoiseXX: missing responder ephemeral key")
            ss.mixKey(X25519.dh(kp.privateKey, reBytes))

            val encPayload = ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG3_SENT
            return encS + encPayload
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    /** Returns (send, recv): initiator sends with first, receives with second. */
    fun split(): Pair<CipherState, CipherState> {
        requirePhase(Phase.MSG3_SENT, "split")
        phase = Phase.SPLIT
        val r = ss.split()
        e = null
        s = null
        re = null
        rs = null
        return r
    }

    fun handshakeHash(): ByteArray = ss.handshakeHash()

    fun remoteStaticPublicKey(): ByteArray? = rs?.copyOf()

    private fun requirePhase(expected: Phase, method: String) {
        if (phase == Phase.DEAD) throw NoiseProtocolError("NoiseXX: $method called on dead handshake")
        if (phase != expected) {
            throw NoiseProtocolError("NoiseXX: $method called in wrong phase (expected $expected, got $phase)")
        }
    }
}
