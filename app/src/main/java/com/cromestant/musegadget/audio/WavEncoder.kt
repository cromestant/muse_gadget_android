package com.cromestant.musegadget.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit PCM mono -> WAV bytes. Exact 44-byte header then PCM,
 * mirroring muse_chat_text.c wav_header(rate): PCM/mono, byteRate = rate*2,
 * data size field = UINT32_MAX for unknown size. Pure JVM.
 */
object WavEncoder {
    const val HEADER_BYTES = 44
    const val UNKNOWN_SIZE = 0xFFFFFFFF.toInt()

    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val out = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(UNKNOWN_SIZE)
        out.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16) // fmt chunk size
        out.putShort(1) // PCM
        out.putShort(1) // mono
        out.putInt(sampleRate)
        out.putInt(sampleRate * 2) // byteRate
        out.putShort(2) // block align
        out.putShort(16) // bits per sample
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(UNKNOWN_SIZE)
        out.put(pcm)
        return out.array()
    }
}
