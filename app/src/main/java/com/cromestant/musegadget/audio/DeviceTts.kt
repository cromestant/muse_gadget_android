package com.cromestant.musegadget.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks text using Android's built-in TextToSpeech engine.
 *
 * Fallback (or alternative) to server-side TTS: no network, no message IDs,
 * works offline once the engine is initialized. Owned by GadgetLink
 * (service scope). All methods are thread-safe; [speak] blocks until
 * done or timeout, so call it off the UI thread.
 */
class DeviceTts(appContext: Context) {
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private val initLatch = CountDownLatch(1)

    init {
        try {
            tts = TextToSpeech(appContext.applicationContext, { status ->
                synchronized(lock) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        try {
                            tts?.language = Locale.US
                        } catch (_: Exception) {
                        }
                    }
                }
                initLatch.countDown()
                System.out.println("MuseGadgetTts: device TTS ready=" + (status == TextToSpeech.SUCCESS))
            })
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: device TTS init failed: " + e.javaClass.simpleName)
            initLatch.countDown()
        }
    }

    /** Speak [text], stopping anything currently spoken. Blocks until done. */
    fun speak(text: String) {
        if (text.trim().isEmpty()) return
        try {
            initLatch.await(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return
        }
        val engine: TextToSpeech?
        synchronized(lock) {
            if (!ready) {
                System.out.println("MuseGadgetTts: device TTS not ready, skipping")
                return
            }
            engine = tts
        }
        if (engine == null) return
        try {
            engine.stop()
            val done = CountDownLatch(1)
            val utteranceId = "muse-" + System.currentTimeMillis()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { done.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { done.countDown() }
                override fun onError(id: String, code: Int) { done.countDown() }
            })
            System.out.println("MuseGadgetTts: device TTS speaking " + text.length + " chars")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            done.await(60, TimeUnit.SECONDS)
            System.out.println("MuseGadgetTts: device TTS done")
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: device TTS speak failed: " + e.javaClass.simpleName)
        }
    }

    /** Stop any in-progress speech. Safe to call when idle. */
    fun stop() {
        try {
            synchronized(lock) { tts }?.stop()
        } catch (_: Exception) {
        }
    }

    /** Release the engine. Call when the service stops. */
    fun release() {
        try {
            synchronized(lock) {
                tts?.shutdown()
                tts = null
                ready = false
            }
        } catch (_: Exception) {
        }
    }
}
