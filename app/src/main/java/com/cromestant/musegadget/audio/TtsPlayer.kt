package com.cromestant.musegadget.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Speaks Muse's TTS MP3 replies through the device speaker.
 *
 * Owned by [com.cromestant.musegadget.link.GadgetLink] (service scope, not the
 * activity) so playback survives activity recreation. [stop] is the PTT
 * barge-in: it halts playback and releases the player. All methods are
 * thread-safe; [play] blocks the caller during prepare, so call it off the
 * UI thread.
 */
class TtsPlayer(appContext: Context) {
    private val cacheDir: File = appContext.cacheDir
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var tmpFile: File? = null
    private var generation = 0L

    /**
     * Stop any in-progress playback and release the player. Safe to call
     * when idle.
     */
    fun stop() {
        val p: MediaPlayer?
        val f: File?
        synchronized(lock) {
            generation++
            p = player
            player = null
            f = tmpFile
            tmpFile = null
        }
        try {
            p?.stop()
        } catch (_: Exception) {
        }
        try {
            p?.release()
        } catch (_: Exception) {
        }
        try {
            if (f != null && f.exists()) f.delete()
        } catch (_: Exception) {
        }
        System.out.println("MuseGadgetTts: stopped")
    }

    /**
     * Play MP3 bytes, stopping anything currently playing first. If [stop]
     * races the prepare, the new audio is discarded, never orphaned playing.
     */
    fun play(mp3: ByteArray) {
        if (mp3.isEmpty()) return
        stop()
        val gen: Long = synchronized(lock) { generation }
        val f = try {
            File.createTempFile("tts-", ".mp3", cacheDir).also {
                it.writeBytes(mp3)
                it.deleteOnExit()
            }
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: temp file failed: " + e.javaClass.simpleName)
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                System.out.println("MuseGadgetTts: playback complete")
                stop()
            }
            mp.prepare()
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: prepare failed: " + e.javaClass.simpleName)
            try {
                mp.release()
            } catch (_: Exception) {
            }
            try {
                f.delete()
            } catch (_: Exception) {
            }
            return
        }
        synchronized(lock) {
            if (gen != generation) {
                // Stopped while preparing: discard, never start orphaned audio.
                try {
                    mp.release()
                } catch (_: Exception) {
                }
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                return
            }
            player = mp
            tmpFile = f
        }
        try {
            mp.start()
            System.out.println("MuseGadgetTts: playing " + mp3.size + " bytes")
        } catch (e: Exception) {
            System.out.println("MuseGadgetTts: start failed: " + e.javaClass.simpleName)
            stop()
        }
    }

    /** Release everything; call from the owner's stop path. */
    fun release() = stop()
}
