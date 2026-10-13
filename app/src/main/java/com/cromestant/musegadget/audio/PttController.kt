package com.cromestant.musegadget.audio

import java.util.Base64

/** Clock for PTT timing; injectable for tests. */
interface PttClock {
    fun nowMs(): Long
}

object SystemPttClock : PttClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** PCM source; Android impl wraps AudioRecord, tests use a fake. */
interface PcmSource {
    /** Begin capture; returns false when capture is unavailable. */
    fun start(): Boolean

    /** Stop capture and return all PCM bytes recorded so far. */
    fun stopAndDrain(): ByteArray
}

data class CaptureInfo(val durationMs: Long, val pcmBytes: Int, val b64Chars: Int)

/**
 * Tap-to-start -> tap-to-stop PTT state machine (no VAD).
 * Min 0.3s (shorter => discard + onTooShort), max 15s auto-stop.
 * Pure JVM, thread-safe. Sample rate fixed at 16 kHz mono 16-bit.
 */
class PttController(
    private val clock: PttClock = SystemPttClock,
    private val source: PcmSource,
    private val transport: VoiceTransport,
    private val sampleRate: Int = 16000,
    private val onAutoStop: (() -> Unit)? = null,
    private val onTooShort: (() -> Unit)? = null,
) {
    companion object {
        const val MIN_MS = 300L
        const val MAX_MS = 15000L
    }

    enum class State { IDLE, RECORDING }

    private val lock = Any()

    @Volatile private var state = State.IDLE
    private var startMs = 0L
    @Volatile var lastCaptureInfo: CaptureInfo? = null
        private set

    fun currentState(): State = state

    /** Begin capture. Returns false if already recording or source unavailable. */
    fun start(): Boolean {
        synchronized(lock) {
            if (state == State.RECORDING) return false
            if (!source.start()) return false
            startMs = clock.nowMs()
            state = State.RECORDING
            return true
        }
    }

    /**
     * Stop capture. [auto] marks the 15s auto-stop path (same handling;
     * fires onAutoStop after a valid capture).
     * Returns true when a voice note was produced.
     */
    fun stop(auto: Boolean = false): Boolean {
        val pcm: ByteArray
        val elapsed: Long
        synchronized(lock) {
            if (state != State.RECORDING) return false
            elapsed = clock.nowMs() - startMs
            pcm = try {
                source.stopAndDrain()
            } finally {
                state = State.IDLE
            }
        }
        if (elapsed < MIN_MS) {
            onTooShort?.invoke()
            return false
        }
        val wav = WavEncoder.pcmToWav(pcm, sampleRate)
        val b64 = Base64.getEncoder().encodeToString(wav)
        lastCaptureInfo = CaptureInfo(elapsed, pcm.size, b64.length)
        transport.sendVoiceNote(VoiceNote.frameVoiceNote(b64))
        if (auto) onAutoStop?.invoke()
        return true
    }

    /** Call from a timer tick; fires the 15s auto-stop once. */
    fun checkAutoStop(): Boolean {
        synchronized(lock) {
            if (state != State.RECORDING) return false
            if (clock.nowMs() - startMs < MAX_MS) return false
        }
        return stop(auto = true)
    }
}
