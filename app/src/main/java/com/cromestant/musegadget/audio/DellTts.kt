package com.cromestant.musegadget.audio

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches Edge-TTS MP3 audio from the Dell TTS server and plays it.
 *
 * The Dell runs ~/code/portal/tts-server/server.py (port 8765) using the
 * official edge-tts Python library. This client GETs /tts?text=... and
 * plays the returned MP3 via [TtsPlayer]. Falls back silently on any
 * failure so callers can try DeviceTts instead.
 */
class DellTts(
    private val serverHost: String = "192.168.68.83",
    private val serverPort: Int = 8765,
    private val voice: String = "en-US-AndrewMultilingualNeural",
    private val player: TtsPlayer
) {
    /**
     * Synthesize [text] via the Dell and play it. Returns true if audio
     * was fetched and playback started. Blocks the caller; call off UI.
     */
    fun speak(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        return try {
            val encoded = URLEncoder.encode(clean.take(4000), "UTF-8")
            val url = URL("http://$serverHost:$serverPort/tts?text=$encoded&voice=$voice")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                System.out.println("MuseGadgetTts: Dell TTS HTTP " + code)
                return false
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 100) {
                System.out.println("MuseGadgetTts: Dell TTS empty response")
                return false
            }
            System.out.println("MuseGadgetTts: Dell TTS got " + bytes.size + " bytes, playing")
            if (Thread.currentThread().isInterrupted) return false
            player.play(bytes)
            true
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: Dell TTS failed: " + e.javaClass.simpleName)
            false
        }
    }

    fun stop() {
        try { player.stop() } catch (_: Exception) {}
    }
}
