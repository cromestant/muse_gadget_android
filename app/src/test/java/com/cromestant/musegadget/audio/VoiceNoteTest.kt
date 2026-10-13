package com.cromestant.musegadget.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VoiceNoteTest {
    @Test
    fun framingExactness() {
        val wav = WavEncoder.pcmToWav(byteArrayOf(0, 1, 2, 3))
        val b64 = Base64.getEncoder().encodeToString(wav)
        val json = VoiceNote.frameVoiceNote(b64)
        assertTrue(json.startsWith(VoiceNote.NOTE_HEAD))
        assertTrue(json.endsWith(VoiceNote.NOTE_TAIL))
        assertEquals(VoiceNote.NOTE_HEAD + b64 + VoiceNote.NOTE_TAIL, json)
        val inner = json.removePrefix(VoiceNote.NOTE_HEAD).removeSuffix(VoiceNote.NOTE_TAIL)
        val decoded = Base64.getDecoder().decode(inner)
        assertEquals("RIFF", decoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }
}
