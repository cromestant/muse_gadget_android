package com.cromestant.musegadget.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord PCM source: 16 kHz mono 16-bit. Read loop on a worker
 * thread into a growable buffer. Never throws: recorder null
 * (getMinBufferSize ERROR_BAD_VALUE) or SecurityException (RECORD_AUDIO
 * not granted) just reports unavailable via start() == false.
 */
class AndroidPcmSource(
    private val sampleRate: Int = 16000,
) : PcmSource {
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private val running = AtomicBoolean(false)
    private val buf = ByteArrayOutputStream()
    private val bufLock = Any()

    override fun start(): Boolean {
        synchronized(bufLock) { buf.reset() }
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (e: Exception) {
            return false
        }
        if (minBuf <= 0) return false // ERROR_BAD_VALUE / ERROR: no recorder
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            return false // RECORD_AUDIO not granted; caller checks permission first
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (e: Exception) { }
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            try { rec.release() } catch (e2: Exception) { }
            return false
        }
        recorder = rec
        running.set(true)
        reader = Thread({
            val chunk = ByteArray(minBuf)
            while (running.get()) {
                val n = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) continue
                synchronized(bufLock) { buf.write(chunk, 0, n) }
            }
        }, "pcm-capture").also { it.isDaemon = true; it.start() }
        return true
    }

    override fun stopAndDrain(): ByteArray {
        running.set(false)
        try { reader?.join(1000) } catch (e: Exception) { }
        reader = null
        try { recorder?.stop() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        synchronized(bufLock) { return buf.toByteArray() }
    }
}
