package com.cromestant.musegadget.link

import android.content.Context
import com.cromestant.musegadget.audio.DeviceTts
import com.cromestant.musegadget.audio.TtsPlayer
import com.cromestant.musegadget.audio.DellTts
import com.cromestant.musegadget.identity.IdentityProtocol
import com.cromestant.musegadget.identity.TokenStore
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.pow

/**
 * Session orchestrator, port of the reconnect loop in
 * `ref/muse-gadget-sdk/linux/src/musegadget/service.py`.
 *
 * Reads `musegadget_provision` prefs (noise_host, api_url_v2, username,
 * access_token, refresh_token, token_issued_at). Token values are never
 * logged. Runs on one loop thread; [sendVoiceTurn] dispatches to workers.
 */
interface VoiceTurnListener {
    fun onAck()
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: String)
}


/**
 * Pure provision check: the same rule as [GadgetLink.isProvisioned], factored
 * out so JVM tests can cover it without Android SharedPreferences.
 */
internal fun isProvisionedSnapshot(noiseHost: String?, accessToken: String?, refreshToken: String?): Boolean =
    !noiseHost.isNullOrEmpty() && !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()

/**
 * One-line trace summary of a chat NDJSON event: event name plus truncated
 * message/parent ids. Never includes text content.
 */
internal fun describeChatEvent(ev: Map<String, Any?>): String {
    val name = ev["event"] as? String ?: "?"
    @Suppress("UNCHECKED_CAST")
    val payload = ev["payload"] as? Map<String, Any?>
    val mid = payload?.get("message_id") as? String
    val replyTo = (payload?.get("reply_to_message_id") as? String)
        ?: (payload?.get("parent_message_id") as? String)
    val sb = StringBuilder("event name=").append(name)
    if (mid != null) sb.append(" msg=").append(mid.take(12))
    if (replyTo != null) sb.append(" replyTo=").append(replyTo.take(12))
    if (name == "delta.message_start" && payload != null) {
        sb.append(" keys=").append(payload.keys.joinToString(","))
        val idVal = payload["id"] as? String
        if (idVal != null) sb.append(" id=").append(idVal.take(20))
        val meta = payload["metadata"]
        if (meta != null) sb.append(" meta=").append(meta.toString().take(120))
        val seq = payload["message_seq"]
        if (seq != null) sb.append(" seq=").append(seq.toString().take(20))
    }
    return sb.toString()
}

/** Reconnect delay math, mirroring `Backoff.next_delay` in `service.py:61`. */
internal fun backoffDelay(failures: Int, floor: Double): Double {
    val delay = minOf(2.0 * 2.0.pow(minOf(failures, 16)), 60.0)
    return maxOf(delay, floor)
}

class GadgetLink(context: Context, private val http: OkHttpClient = MuseApi.newHttpClient()) {
    enum class State { IDLE, WAITING_FOR_PROVISION, CONNECTING, REGISTERED, BACKOFF, UNPAIRED }

    companion object {
        const val PREFS = "musegadget_provision"
        const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
        const val TOKEN_REFRESH_AGE_MS = 3L * 3600 * 1000
        const val TOKEN_RETRY_MS = 300L * 1000
        const val AUTH_BACKOFF_MIN_S = 15.0
        const val HEALTHY_SESSION_S = 30L
        const val UNPROVISIONED_POLL_MS = 5000L
        /**
         * Gadget voice turns go to the "Plan custom Android Muse gadget"
         * side chat via session_id instead of the shared main chat
         * (Charles, 2026-10-14). The backend routes session_id to a side
         * chat; without it the turn lands in main. Set false to revert to
         * the null-session (shared chat) behavior.
         */
        const val USE_DEDICATED_CHAT_SESSION = true
        private const val CHAT_SESSION_ID_KEY = "gadget_chat_session_id"
        /** "Plan custom Android Muse gadget" side chat UUID. */
        const val GADGET_SIDE_CHAT_ID = "c9c273dc-22a7-4572-a98a-0d6e66f44375"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenStore = TokenStore(appContext)

    @Volatile var state: State = State.IDLE
        private set

    /** Currently connected session, or null. Set while `LinkSession.run` executes. */
    @Volatile private var current: LinkSession? = null

    /** Invoked (any thread) when the pairing is revoked so BLE can re-advertise. */
    var onUnpaired: (() -> Unit)? = null

    private val stopFlag = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private val loopLock = Any()

    /**
     * Spoken-reply player, service-scoped so playback survives activity
     * recreation. Lazy so unit-test construction never touches Android.
     */
    private val ttsPlayer: TtsPlayer by lazy { TtsPlayer(appContext) }
    private val deviceTts: DeviceTts by lazy { DeviceTts(appContext) }
    private val dellTts: DellTts by lazy { DellTts(player = ttsPlayer) }

    /** Streaming TTS: sentences are queued and spoken sequentially by a worker thread. */
    private val ttsQueue = LinkedBlockingQueue<String>()
    @Volatile private var ttsWorker: Thread? = null
    private val ttsWorkerLock = Object()

    /**
     * Start the TTS worker thread if not running. Speaks queued sentences
     * sequentially via Dell (Edge-TTS) with DeviceTts fallback.
     */
    private fun ensureTtsWorker() {
        synchronized(ttsWorkerLock) {
            val w = ttsWorker
            if (w != null && w.isAlive) return
            val t = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val sentence = ttsQueue.take()
                        if (sentence.isEmpty()) continue
                        val viaDell = try { dellTts.speak(sentence) } catch (_: Exception) { false }
                        if (!viaDell) {
                            try { deviceTts.speak(sentence) } catch (_: Exception) { }
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }, "MuseGadgetTtsWorker")
            t.isDaemon = true
            t.start()
            ttsWorker = t
        }
    }

    /** Enqueue [text] for streaming TTS playback. Non-blocking. */
    private fun speakStreaming(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        ensureTtsWorker()
        ttsQueue.offer(clean)
    }

    /**
     * Extract complete sentences from [text] starting at [fromIndex].
     * Returns (sentences, newIndex) where newIndex is the position after
     * the last complete sentence. A sentence ends with . ! ? followed by
     * whitespace or end of text.
     */
    private fun extractSentences(text: String, fromIndex: Int): Pair<List<String>, Int> {
        val sentences = mutableListOf<String>()
        var idx = fromIndex
        var start = fromIndex
        while (idx < text.length) {
            val c = text[idx]
            if (c == '.' || c == '!' || c == '?') {
                // Check if followed by whitespace or end (sentence boundary)
                val next = if (idx + 1 < text.length) text[idx + 1] else ' '
                if (next.isWhitespace() || idx + 1 >= text.length) {
                    val sentence = text.substring(start, idx + 1).trim()
                    if (sentence.isNotEmpty()) sentences.add(sentence)
                    // Skip whitespace after the sentence
                    var j = idx + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    start = j
                    idx = j
                    continue
                }
            }
            idx++
        }
        return sentences to start
    }

    /**
     * Speak [text] via Edge-TTS (Dell server) first, falling back to the
     * on-device system TTS if the Dell is unreachable.
     */
    private fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val viaDell = try { dellTts.speak(clean) } catch (_: Exception) { false }
        if (!viaDell) {
            try { deviceTts.speak(clean) } catch (_: Exception) { }
        }
    }
    @Volatile private var ttsThread: Thread? = null
    @Volatile private var lastRotateAttemptMs: Long = Long.MIN_VALUE / 2
    @Volatile private var forceRefresh: Boolean = false

    fun isProvisioned(): Boolean {
        val p = prefs
        return isProvisionedSnapshot(
            p.getString("noise_host", ""),
            p.getString("access_token", ""),
            p.getString("refresh_token", ""),
        )
    }

    /**
     * Pinned side-chat id for gadget voice turns (the "Plan custom Android
     * Muse gadget" chat). Stored to prefs and used as session_id on
     * chat/stream + chat/subscribe when USE_DEDICATED_CHAT_SESSION is true.
     */
    fun gadgetChatSessionId(): String {
        prefs.edit().putString(CHAT_SESSION_ID_KEY, GADGET_SIDE_CHAT_ID).apply()
        return GADGET_SIDE_CHAT_ID
    }

    /** Idempotent; launches the loop thread. */
    fun start() {
        synchronized(loopLock) {
            if (loopThread?.isAlive == true) return
            stopFlag.set(false)
            loopThread = thread(isDaemon = true, name = "gadget-link") { loop() }
        }
    }

    /** Sets the stop flag, closes the session, joins the loop thread. */
    fun stop() {
        stopFlag.set(true)
        try {
            ttsPlayer.release()
        } catch (e: Exception) {
        }
        try { deviceTts.release() } catch (_: Exception) { }
        try {
            current?.close()
        } catch (e: Exception) {
        }
        val t = synchronized(loopLock) { loopThread }
        try {
            t?.interrupt()
            t?.join(5000)
        } catch (e: Exception) {
        }
    }

    private fun prefsSnapshot(): Map<String, Any?> = mapOf(
        "noise_host" to prefs.getString("noise_host", ""),
        "api_url_v2" to prefs.getString("api_url_v2", ""),
        "username" to prefs.getString("username", ""),
        "access_token" to prefs.getString("access_token", ""),
        "refresh_token" to prefs.getString("refresh_token", ""),
        "token_issued_at" to prefs.getLong("token_issued_at", 0L),
    )

    private fun mac(): String = tokenStore.loadOrGenerateMac {
        IdentityProtocol.generateMac(ByteArray(6).also { SecureRandom().nextBytes(it) })
    }

    private fun loop() {
        var failures = 0
        var floor = 0.0
        while (!stopFlag.get()) {
            if (!isProvisioned()) {
                state = State.WAITING_FOR_PROVISION
                sleepOrStop(UNPROVISIONED_POLL_MS)
                continue
            }
            maybeRotateToken()
            if (stopFlag.get()) break
            if (!isProvisioned()) continue // unpaired during rotation
            val snap = prefsSnapshot()
            val root = MuseApi.apiRoot(snap["api_url_v2"] as? String ?: "")
            var access = snap["access_token"] as? String ?: ""
            var (vms, status) = try {
                MuseApi.fetchVms(access, root, http)
            } catch (e: Exception) {
                emptyList<MuseApi.VmInfo>() to null
            }
            if (status == 401) {
                // Force one refresh then re-fetch (mirrors `service.py:107`).
                if (maybeRotateToken(force = true)) {
                    access = prefs.getString("access_token", "") ?: ""
                    try {
                        val r = MuseApi.fetchVms(access, root, http)
                        vms = r.first
                        status = r.second
                    } catch (e: Exception) {
                        vms = emptyList()
                        status = null
                    }
                }
                if (status == 401) {
                    unpaired()
                    break
                }
            }
            if (status == null) {
                state = State.BACKOFF
                failures++
                sleepOrStop((backoffDelay(failures, floor) * 1000).toLong())
                continue
            }
            val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
            if (vm == null) {
                state = State.BACKOFF
                failures++
                sleepOrStop((backoffDelay(failures, floor) * 1000).toLong())
                continue
            }
            val m = mac()
            val nodeId = IdentityProtocol.nodeId(m)
            state = State.CONNECTING
            val session = LinkSession(
                noiseHost = (snap["noise_host"] as? String)?.takeIf { it.isNotEmpty() } ?: DEFAULT_NOISE_HOST,
                vmId = vm.vmId.ifEmpty { vm.vmName },
                vmAuthToken = vm.vmAuthToken,
                nodeId = nodeId,
                displayName = try {
                    android.os.Build.MODEL ?: nodeId
                } catch (e: Exception) {
                    nodeId
                },
                version = "1.0",
                commandsV2 = defaultCommandsV2,
                runCommand = LinkSession.defaultRunCommand("1.0"),
                http = http,
            )
            current = session
            state = State.REGISTERED
            val sessionStart = System.currentTimeMillis()
            val outcome = try {
                session.run(stopFlag)
            } catch (e: Exception) {
                LinkOutcome.CLOSED
            } finally {
                current = null
            }
            when (outcome) {
                LinkOutcome.STOPPED -> break
                LinkOutcome.UNPAIRED -> {
                    unpaired()
                    break
                }
                LinkOutcome.AUTH_REJECTED, LinkOutcome.FORBIDDEN -> {
                    floor = AUTH_BACKOFF_MIN_S
                    failures++
                    forceRefresh = true
                }
                LinkOutcome.CLOSED -> failures++
            }
            if (System.currentTimeMillis() - sessionStart >= HEALTHY_SESSION_S * 1000) {
                failures = 0
                floor = 0.0
            }
            if (stopFlag.get()) break
            state = State.BACKOFF
            sleepOrStop((backoffDelay(failures, floor) * 1000).toLong())
        }
    }

    /**
     * Rotate device tokens when due (older than 3 h, attempts at most every
     * 300 s), mirroring `_maybe_refresh` in `service.py:160`. Returns true
     * when the current tokens are usable.
     */
    private fun maybeRotateToken(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val issuedAt = prefs.getLong("token_issued_at", 0L)
        val due = force || forceRefresh || (now - issuedAt > TOKEN_REFRESH_AGE_MS)
        if (!due) return true
        if (!force && !forceRefresh && now - lastRotateAttemptMs < TOKEN_RETRY_MS) return true
        lastRotateAttemptMs = now
        forceRefresh = false
        val refresh = prefs.getString("refresh_token", "") ?: ""
        val root = MuseApi.apiRoot(prefs.getString("api_url_v2", "") ?: "")
        val m = mac()
        val (tokens, status) = try {
            MuseApi.refreshDeviceToken(refresh, IdentityProtocol.deviceId(m), root, http)
        } catch (e: Exception) {
            null to null
        }
        if (tokens != null) {
            prefs.edit()
                .putString("access_token", tokens.accessToken)
                .putString("refresh_token", tokens.refreshToken)
                .putLong("token_issued_at", System.currentTimeMillis())
                .apply()
            return true
        }
        if (status == 401) {
            unpaired()
            return false
        }
        // Transient failure: keep the old tokens (retry later); a forced
        // refresh that fails means the caller must back off, not proceed.
        return !force
    }

    /**
     * Pairing revoked (401 on refresh): clear the device tokens and notify so
     * GadgetService can resume BLE advertising. Mirrors `service.py:120`.
     */
    fun unpaired() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("token_issued_at")
            .apply()
        state = State.UNPAIRED
        try {
            onUnpaired?.invoke()
        } catch (e: Exception) {
        }
    }

    /**
     * Post one PTT voice-note frame and stream the reply events. Never blocks
     * the caller: all work happens on a worker thread.
     */
    fun sendVoiceTurn(json: String, listener: VoiceTurnListener) {
        thread(isDaemon = true, name = "voice-turn") {
            System.out.println("MuseGadgetVoice: voice-turn thread started")
            val session = current ?: run {
                System.out.println("MuseGadgetVoice: no session -> not connected")
                listener.onError("not connected to Muse")
                return@thread
            }
            System.out.println("MuseGadgetVoice: session present, posting chat/stream")
            try {
                val chatSessionId = if (USE_DEDICATED_CHAT_SESSION) gadgetChatSessionId() else null
                System.out.println("MuseGadgetVoice: chatSession=" + (chatSessionId?.take(8) ?: "shared"))
                val ack = session.sendVoiceNoteJson(json, chatSessionId)
                System.out.println("MuseGadgetVoice: chat/stream ack ok=" + ack["ok"] + " status=" + ack["status"])
                if (ack["ok"] != true) {
                    listener.onError("chat/stream HTTP ${ack["status"]}")
                    return@thread
                }
                @Suppress("UNCHECKED_CAST")
                val body = ack["response"] as? Map<String, Any?> ?: emptyMap()
                val tracker = MuseTurnTracker()
                tracker.acknowledge(body)
                System.out.println("MuseGadgetVoice: ack keys=" + body.keys.joinToString(",") +
                    " ackSession=" + (tracker.ackSessionId?.take(8) ?: "none"))
                listener.onAck()
                System.out.println("MuseGadgetVoice: acked, subscribing")
                val sub = session.subscribeChat(chatSessionId)
                try {
                    val deadline = System.currentTimeMillis() + 25_000
                    var spokenText = ""
                    var lastTextMs = 0L
                    // Streaming TTS: track how much text has been sent to TTS.
                    var streamedUpTo = 0
                    while (System.currentTimeMillis() < deadline) {
                        val ev = try {
                            sub.nextEvent(5_000)
                        } catch (e: Exception) {
                            throw e
                        }
                        if (ev == null) {
                            // Quiet timeout: if we have text and nothing new for 6s, speak it.
                            if (spokenText.trim().isNotEmpty() &&
                                System.currentTimeMillis() - lastTextMs > 3_000) break
                            continue
                        }
                        System.out.println("MuseGadgetVoice: " + describeChatEvent(ev))
                        for (te in tracker.feed(ev)) when (te) {
                            is TurnEvent.Reply -> {
                                listener.onPartial(te.text)
                                val txt = te.text
                                if (txt.trim().isNotEmpty()) {
                                    spokenText = txt
                                    lastTextMs = System.currentTimeMillis()
                                    // Extract and speak complete sentences immediately.
                                    if (txt.length > streamedUpTo) {
                                        val (sentences, newIdx) = extractSentences(txt, streamedUpTo)
                                        for (sentence in sentences) {
                                            speakStreaming(sentence)
                                        }
                                        streamedUpTo = newIdx
                                    }
                                }
                            }
                            is TurnEvent.AgentStatus -> {}
                            TurnEvent.TaskFinished -> {}
                        }
                        if (tracker.complete()) break
                    }
                    // Flush any remaining text after the turn completes.
                    val remaining = spokenText.substring(streamedUpTo.coerceAtMost(spokenText.length)).trim()
                    if (remaining.isNotEmpty()) {
                        speakStreaming(remaining)
                    }
                } finally {
                    try {
                        sub.close()
                    } catch (e: Exception) {
                    }
                }
                val finalText = tracker.finalText()
                System.out.println("MuseGadgetVoice: turn done finalLen=" + finalText.length)
                listener.onFinal(finalText)
                speakReplies(tracker.doneMessageIds(), tracker.allText())
            } catch (e: Exception) {
                System.out.println("MuseGadgetVoice: turn EX " + e.javaClass.simpleName + ": " + e.message)
                listener.onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Stop spoken-reply playback (PTT barge-in). Also interrupts an in-flight
     * TTS fetch so a new tap is never talked over.
     */
    fun stopTtsPlayback() {
        try {
            ttsThread?.interrupt()
        } catch (e: Exception) {
        }
        try {
            ttsPlayer.stop()
        } catch (e: Exception) {
        }
        try { deviceTts.stop() } catch (_: Exception) { }
    }

    /**
     * Fetch + speak the TTS audio for these chat message ids, in order, on a
     * worker thread. A new call cancels the previous one; fetch failures or a
     * dead session skip silently (the text reply already showed).
     */
    fun speakReplies(messageIds: List<String>, fallbackText: String = "") {
        stopTtsPlayback()
        if (messageIds.isEmpty() && fallbackText.trim().isEmpty()) return
        val session = current
        // Device TTS works without a server session; server TTS needs one.
        if (session == null) {
            if (fallbackText.trim().isNotEmpty()) {
                ttsThread = thread(isDaemon = true, name = "tts-speak") {
                    speakStreaming(fallbackText)
                }
            }
            return
        }
        ttsThread = thread(isDaemon = true, name = "tts-speak") {
            try {
                for (id in messageIds) {
                    if (Thread.currentThread().isInterrupted) break
                    System.out.println("MuseGadgetTts: fetching reply audio")
                    val mp3 = try {
                        session.fetchTtsMp3(id)
                    } catch (e: Exception) {
                        System.out.println("MuseGadgetTts: fetch failed: " + e.javaClass.simpleName)
                        null
                    }
                    if (Thread.currentThread().isInterrupted) break
                    if (mp3 != null) {
                        ttsPlayer.play(mp3)
                    } else {
                        System.out.println("MuseGadgetTts: no audio for reply, skipping")
                    }
                }
                // Fallback: device TTS speaks the reply text directly.
                if (fallbackText.trim().isNotEmpty()) {
                    try {
                        speakStreaming(fallbackText)
                    } catch (e: Exception) {
                        System.out.println("MuseGadgetTts: device TTS failed: " + e.javaClass.simpleName)
                    }
                }
            } finally {
                if (ttsThread === Thread.currentThread()) ttsThread = null
            }
        }
    }

    private fun sleepOrStop(ms: Long) {
        try {
            Thread.sleep(maxOf(ms, 0L))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
