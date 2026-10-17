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
    val sess = (payload?.get("session_id") as? String)?.takeIf { it.isNotEmpty() }
        ?: (ev["session_id"] as? String)?.takeIf { it.isNotEmpty() }
    if (sess != null) sb.append(" sess=").append(sess.take(8))
    if (mid != null) sb.append(" msg=").append(mid.take(12))
    if (replyTo != null) sb.append(" replyTo=").append(replyTo.take(12))
    val role = payload?.get("role") as? String
    if (role != null) sb.append(" role=").append(role)
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

/**
 * Shared chat fan-out: one `subscribeChat` owner publishes each event to
 * every tap, keeping a capped replay buffer so a late joiner (voice turn)
 * sees what arrived before it subscribed. Pure JVM, no Android.
 */
internal class ChatFanout(private val replayCap: Int = 500) {
    private val lock = Any()
    private val taps = java.util.concurrent.CopyOnWriteArrayList<LinkedBlockingQueue<Map<String, Any?>>>()
    private val replay = ArrayDeque<Map<String, Any?>>()

    fun publish(ev: Map<String, Any?>) {
        synchronized(lock) {
            replay.addLast(ev)
            while (replay.size > replayCap) replay.removeFirst()
            for (tap in taps) tap.offer(ev)
        }
    }

    fun subscribe(replay: Boolean = true): LinkedBlockingQueue<Map<String, Any?>> {
        val q = LinkedBlockingQueue<Map<String, Any?>>()
        synchronized(lock) {
            if (replay) for (ev in this.replay) q.offer(ev)
            taps.add(q)
        }
        return q
    }

    fun unsubscribe(q: LinkedBlockingQueue<Map<String, Any?>>) {
        taps.remove(q)
    }
}

/**
 * Idle-poll rule for the voice-turn loop: end the turn only when Muse is
 * done AND at least one reply bound. `MuseTurnTracker.complete()` is true
 * on a silent turn with zero replies (vacuous), so without the
 * [MuseTurnTracker.hasReplies] guard a slow server would end the turn at
 * the first 5 s poll instead of at the 25 s deadline.
 */
internal fun voiceTurnSettled(tracker: MuseTurnTracker, nowMs: Long = System.currentTimeMillis()): Boolean =
    tracker.hasReplies() && tracker.complete(nowMs)

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
        const val GADGET_SIDE_CHAT_ID = "64a26808-9d89-47eb-ba4d-ad0e987e1470"
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
    internal val ttsQueue = LinkedBlockingQueue<String>()
    /** Recently spoken sentences (with timestamps) to deduplicate voice loop + speakReplies. */
    private val recentSpoken = mutableMapOf<String, Long>()
    private val recentSpokenLock = Object()
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

    /**
     * Enqueue [text] for streaming TTS playback. Splits into sentences, non-blocking.
     * Mid-turn streaming ([isFinal] = false) queues only COMPLETE sentences
     * (with terminal . ! ?); the trailing partial fragment stays unqueued
     * until the next delta completes it. The end-of-turn flush passes
     * [isFinal] = true so the final fragment speaks too. Returns true when
     * anything was offered (drives the speakReplies skip).
     */
    private fun speakStreaming(text: String, isFinal: Boolean = false): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        ensureTtsWorker()
        // Split into sentences for smoother streaming playback.
        val (sentences, newIdx) = extractSentences(clean, 0)
        val toQueue = if (isFinal) {
            val rest = clean.substring(newIdx).trim()
            if (rest.isNotEmpty()) sentences + rest else sentences
        } else {
            sentences
        }
        if (toQueue.isEmpty()) return false
        // Deduplicate per-sentence: skip if queued in the last 30s
        // (voice turn loop and speakReplies can both fire for the same reply).
        val now = System.currentTimeMillis()
        synchronized(recentSpokenLock) {
            // Clean old entries
            val it = recentSpoken.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > 30_000) it.remove()
            }
            for (sentence in toQueue) {
                if (recentSpoken.containsKey(sentence)) {
                    System.out.println("MuseGadgetTts: skipping duplicate sentence")
                    continue
                }
                recentSpoken[sentence] = now
                ttsQueue.offer(sentence)
            }
        }
        return true
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
    @Volatile private var bgChatThread: Thread? = null
    @Volatile private var bgTap: LinkedBlockingQueue<Map<String, Any?>>? = null
    @Volatile private var onBackgroundMessage: ((String, String) -> Unit)? = null

    /**
     * Single shared chat feed. Transport-wise two `subscribeChat` calls get
     * different stream ids and both stay open (see `LinkSession.route`: demux
     * by stream id), but server-side they compete for the same session's
     * events — voice deltas land in the background sub (where
     * `tryParseStructured` drops them) and digest JSON lands in the voice
     * sub (where the turn tracker ignores it). So neither side completes.
     * One pump thread owns the single subscription and fans each event out
     * to every tap; voice turns subscribe-before-post so nothing is missed.
     */
    private val chatBus = ChatFanout()
    private var chatPumpThread: Thread? = null
    private val chatPumpLock = Any()
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
            MuseApi.refreshDeviceToken(refresh, IdentityProtocol.nodeId(m), root, http)
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

    /** Set callback for background digest messages (type, content). */
    fun setOnBackgroundMessage(cb: ((String, String) -> Unit)?) {
        onBackgroundMessage = cb
    }

    /**
     * Set callback for plain (non-structured) assistant replies seen on the
     * side chat outside any voice turn — e.g. answers to text messages typed
     * in the side chat. Only done (full) texts are forwarded, never deltas,
     * so this never fights the voice turn's streaming partials.
     */
    @Volatile private var onBackgroundText: ((String) -> Unit)? = null

    fun setOnBackgroundText(cb: ((String) -> Unit)?) {
        onBackgroundText = cb
    }

    fun clearTtsQueue() {
        try { ttsQueue.clear() } catch (_: Exception) { }
    }

    fun clearTtsQueueOnBackgroundText() = clearTtsQueue()

    fun clearBackgroundTtsQueue() = clearTtsQueue()

    /**
     * Pump thread: the ONE `subscribeChat` owner. Reconnects across sessions
     * and fans every event out to all taps plus a small replay buffer for
     * late joiners (voice turns). Started lazily, daemon, idempotent.
     */
    private fun ensureChatPump() {
        synchronized(chatPumpLock) {
            if (chatPumpThread?.isAlive == true) return
            val t = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val session = current
                        if (session == null) {
                            Thread.sleep(5000); continue
                        }
                        val chatSessionId =
                            if (USE_DEDICATED_CHAT_SESSION) gadgetChatSessionId() else null
                        System.out.println("MuseGadgetBg: subscribing sess=" + (chatSessionId ?: "shared") + " gadget=" + GADGET_SIDE_CHAT_ID)
                        val sub = try {
                            session.subscribeChat(chatSessionId)
                        } catch (e: InterruptedException) {
                            break
                        } catch (e: Exception) {
                            System.out.println("MuseGadgetBg: error: " + e.javaClass.simpleName)
                            try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                            continue
                        }
                        try {
                            while (!Thread.currentThread().isInterrupted && current === session) {
                                val ev = try { sub.nextEvent(30000) }
                                catch (e: Exception) {
                                    System.out.println("MuseGadgetBg: event error: " + e.javaClass.simpleName)
                                    break
                                }
                                if (ev == null) continue
                                System.out.println("MuseGadgetPump: " + describeChatEvent(ev))
                                chatBus.publish(ev)
                            }
                        } finally {
                            try { sub.close() } catch (_: Exception) { }
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        System.out.println("MuseGadgetBg: error: " + e.javaClass.simpleName)
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    }
                }
            }, "muse-bg-chat")
            t.isDaemon = true
            t.start()
            chatPumpThread = t
        }
    }

    /** Register a fan-out tap; voice turns pass replay=false (subscribed pre-post). */
    private fun subscribeTap(replay: Boolean = true): LinkedBlockingQueue<Map<String, Any?>> =
        chatBus.subscribe(replay)

    private fun unsubscribeTap(q: LinkedBlockingQueue<Map<String, Any?>>) = chatBus.unsubscribe(q)

    /**
     * Start background digest consumer on the shared chat feed.
     * Runs in a daemon thread; the pump owns the subscription.
     */
    fun startBackgroundChatListener() {
        synchronized(this) {
            if (bgChatThread?.isAlive == true) return
            ensureChatPump()
            val tap = subscribeTap()
            bgTap = tap
            val t = Thread({
                try {
                    val buffers = mutableMapOf<String, StringBuilder>()
                    while (!Thread.currentThread().isInterrupted) {
                        val ev = try {
                            tap.poll(30, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: InterruptedException) {
                            break
                        } ?: continue
                        System.out.println("MuseGadgetBg: " + describeChatEvent(ev))
                        val update = extractBgText(ev)
                        if (update == null) {
                            val name = ev["event"] as? String ?: ""
                            if (name.startsWith("delta.") || name.startsWith("message.")) {
                                System.out.println("MuseGadgetBg: dropped " + describeChatEvent(ev))
                            }
                            continue
                        }
                        val (msgId, text, done) = update
                        val buf = buffers.getOrPut(msgId) { StringBuilder() }
                        // Done events carry the full text; deltas are fragments.
                        // An empty done marker flushes the streamed fragments.
                        if (done && text.isEmpty()) {
                            // Leave the buffered deltas in place for the
                            // flush below; nothing new to append.
                        } else if (done) {
                            buf.clear()
                            buf.append(text)
                        } else {
                            buf.append(text)
                        }
                        if (tryParseStructured(buf.toString())) {
                            if (done) buffers.remove(msgId)
                        } else if (done) {
                            buffers.remove(msgId)
                            // Plain assistant reply (e.g. to a side-chat text
                            // message): surface it so the Portal shows what
                            // the voice turn path would show for PTT replies.
                            val text = buf.toString().trim()
                            if (text.isNotEmpty()) {
                                System.out.println("MuseGadgetBg: plain reply len=" + text.length)
                                try { ttsQueue.clear() } catch (_: Exception) { }
                                try { onBackgroundText?.invoke(text) } catch (_: Exception) { }
                            }
                        }
                    }
                } finally {
                    System.out.println("MuseGadgetBg: consumer stopped")
                    unsubscribeTap(tap)
                    if (bgTap === tap) bgTap = null
                }
            }, "muse-bg-consumer")
            t.isDaemon = true
            t.start()
            bgChatThread = t
        }
    }

    fun stopBackgroundChatListener() {
        try { bgChatThread?.interrupt() } catch (_: Exception) { }
        bgChatThread = null
        // Tap is removed by the consumer thread's finally; drop our ref so a
        // stuck thread can't keep the queue fanned out to forever.
        bgTap?.let { unsubscribeTap(it) }
        bgTap = null
    }

    /**
     * One text update from a background chat event. Returns
     * (messageId, text, done). Mirrors the payload shapes handled by
     * `MuseTurnTracker.message`: `delta.text_append` carries a streaming
     * fragment in `payload.text`, while `delta.message_done` /
     * `message.assistant` carry the full text in `display_text`,
     * `transcript`, or `content` — never `text`. `delta.presentation`
     * carries `fallback_text`. Returns null for non-message events.
     */
    internal fun extractBgText(ev: Map<String, Any?>): Triple<String, String, Boolean>? {
        if (ev["type"] != "event") return null
        val name = ev["event"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val payload = ev["payload"] as? Map<String, Any?> ?: return null
        val msgId = (payload["message_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (ev["message_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (payload["id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: return null
        if (payload["role"] == "user") return null
        return when (name) {
            "delta.text_append" -> {
                val text = payload["text"] as? String ?: return null
                Triple(msgId, text, false)
            }
            "delta.message_done", "message.assistant" -> {
                var full = payload["display_text"] as? String
                if (full == null) {
                    val transcript = payload["transcript"]
                    full = if (transcript is String) transcript
                    else transcriptText(transcript, msgId)
                    if (full.isEmpty() && transcript !is String) {
                        full = payload["content"] as? String ?: ""
                    }
                }
                // An empty done marker carries no text (cf. muse_chat_link.c):
                // flush the streamed deltas buffered so far instead of
                // dropping the event and stalling the buffer forever.
                Triple(msgId, full, true)
            }
            "delta.presentation" -> {
                val data = payload["data"] as? Map<*, *>
                var fallback: String? = data?.get("fallback_text") as? String
                if (fallback == null) {
                    for (key in listOf("audio", "videos", "images")) {
                        val section = data?.get(key)
                        val candidates = if (section is List<*>) section else listOf(section)
                        for (c in candidates) {
                            val ft = (c as? Map<*, *>)?.get("fallback_text") as? String
                            if (!ft.isNullOrEmpty()) { fallback = ft; break }
                        }
                        if (!fallback.isNullOrEmpty()) break
                    }
                }
                if (fallback == null) fallback = payload["fallback_text"] as? String
                if (fallback.isNullOrEmpty()) return null
                Triple(msgId, fallback, true)
            }
            else -> null
        }
    }

    private fun tryParseStructured(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val json = org.json.JSONObject(trimmed)
            val type = json.optString("type", "")
            val content = json.optString("content", "")
            if (type in listOf("digest", "ticker", "artifact", "tasks") && content.isNotEmpty()) {
                onBackgroundMessage?.invoke(type, content)
                System.out.println("MuseGadgetBg: routed " + type)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Post one PTT voice-note frame and stream the reply events. Never blocks
     * the caller: all work happens on a worker thread.
     */
    fun sendVoiceTurn(json: String, listener: VoiceTurnListener) {
        try { ttsQueue.clear() } catch (_: Exception) { }
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
                // Tap the shared feed BEFORE posting, without replay: the tap
                // captures everything from here on, so nothing is missed.
                ensureChatPump()
                val tap = subscribeTap(false)
                // Owned side-chat turn: every assistant message on this chat
                // binds even without a parent link (mirrors owns_chat in
                // muse_turn.py); other chats' events are dropped.
                val tracker = MuseTurnTracker(chatSessionId, USE_DEDICATED_CHAT_SESSION)
                try {
                    val ack = session.sendVoiceNoteJson(json, chatSessionId)
                    System.out.println("MuseGadgetVoice: chat/stream ack ok=" + ack["ok"] + " status=" + ack["status"])
                    if (ack["ok"] != true) {
                        listener.onError("chat/stream HTTP ${ack["status"]}")
                        return@thread
                    }
                    @Suppress("UNCHECKED_CAST")
                    val body = ack["response"] as? Map<String, Any?> ?: emptyMap()
                    tracker.acknowledge(body)
                    System.out.println("MuseGadgetVoice: ack keys=" + body.keys.joinToString(",") +
                        " ackSession=" + (tracker.ackSessionId?.take(8) ?: "none"))
                    listener.onAck()
                    System.out.println("MuseGadgetVoice: acked, reading shared feed")
                    var deadlineMs = System.currentTimeMillis() + 90_000
                    var lastActivityMs = tracker.lastActivityMs
                    // Streaming TTS: per-message offset into the streamed
                    // deltas, plus the latest streamed text for the flush.
                    val streamedUpTo: HashMap<String, Int> = LinkedHashMap()
                    val streamedText: HashMap<String, String> = LinkedHashMap()
                    var spokeAnything = false
                    while (System.currentTimeMillis() < deadlineMs) {
                        val ev = try {
                            tap.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            throw e
                        }
                        if (ev == null) {
                            // Idle poll timeout: end the turn only when Muse
                            // is actually done (all replies done + 3 s quiet,
                            // mirroring `MuseTurn.complete`), and never on an
                            // empty turn — a slow server may take most of the
                            // 90 s deadline before its first delta. Otherwise
                            // keep waiting for the deadline.
                            if (voiceTurnSettled(tracker)) break
                            continue
                        }
                        System.out.println("MuseGadgetVoice: " + describeChatEvent(ev))
                        val turnEvents = try {
                            tracker.feed(ev)
                        } catch (e: Exception) {
                            System.out.println("MuseGadgetVoice: tracker EX " + e.javaClass.simpleName)
                            throw e
                        }
                        if (turnEvents.isEmpty()) {
                            System.out.println("MuseGadgetVoice: no turn event (dropped or non-message)")
                        }
                        for (te in turnEvents) when (te) {
                            is TurnEvent.Reply -> {
                                System.out.println("MuseGadgetVoice: reply msg=" + te.messageId.take(8) +
                                    " done=" + te.done + " len=" + te.text.length)
                                listener.onPartial(te.text)
                                // Slice the append-only streamed deltas (te.text
                                // is replaced by the final text on done).
                                streamedText[te.messageId] = te.streamed
                                val seen = streamedUpTo[te.messageId] ?: 0
                                if (te.streamed.length > seen) {
                                    val (sentences, newIdx) = extractSentences(te.streamed, seen)
                                    for (sentence in sentences) {
                                        if (speakStreaming(sentence)) spokeAnything = true
                                    }
                                    streamedUpTo[te.messageId] = newIdx
                                }
                            }
                            is TurnEvent.AgentStatus -> {}
                            TurnEvent.TaskFinished -> {}
                        }
                        val activityMs = tracker.lastActivityMs
                        if (activityMs != lastActivityMs) {
                            lastActivityMs = activityMs
                            deadlineMs = System.currentTimeMillis() + 90_000
                        }
                        if (voiceTurnSettled(tracker)) break
                    }
                    // Flush each reply's unspoken streamed tail after the turn.
                    for ((msgId, streamed) in streamedText) {
                        val seen = streamedUpTo[msgId] ?: 0
                        val tail = streamed.substring(seen.coerceAtMost(streamed.length)).trim()
                        if (tail.isNotEmpty() && speakStreaming(tail, isFinal = true)) spokeAnything = true
                    }
                    val finalText = tracker.finalText()
                    System.out.println("MuseGadgetVoice: turn done finalLen=" + finalText.length +
                        " doneIds=" + tracker.doneMessageIds().size +
                        " hasReplies=" + tracker.hasReplies())
                    listener.onFinal(finalText)
                    if (!spokeAnything) speakReplies(tracker.doneMessageIds(), tracker.allText())
                } finally {
                    unsubscribeTap(tap)
                }
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
            ttsWorker?.interrupt()
        } catch (e: Exception) {
        }
        // Drop queued (not yet spoken) sentences so barge-in actually
        // silences streaming speech instead of just the current sentence,
        // and forget their dedup marks so the next turn can queue them again.
        try { ttsQueue.clear() } catch (_: Exception) { }
        synchronized(recentSpokenLock) { recentSpoken.clear() }
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
        // End-of-turn playback is authoritative: full server audio (or the
        // full fallback text) replaces the streaming fragments the voice
        // loop queued. Always stop those first — leaving them queued lets
        // the worker talk over (or after) the server audio on the same
        // player, which chops both into fragments.
        stopTtsPlayback()
        if (messageIds.isEmpty() && fallbackText.trim().isEmpty()) return
        val session = current
        // Device TTS works without a server session; server TTS needs one.
        if (session == null) {
            if (fallbackText.trim().isNotEmpty()) {
                ttsThread = thread(isDaemon = true, name = "tts-speak") {
                    speakStreaming(fallbackText, isFinal = true)
                }
            }
            return
        }
        ttsThread = thread(isDaemon = true, name = "tts-speak") {
            try {
                var playedServerAudio = false
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
                        // Full server audio supersedes streaming fragments:
                        // drop them so they can't talk over (or after) it.
                        if (!playedServerAudio) {
                            try { ttsQueue.clear() } catch (_: Exception) { }
                        }
                        // Blocking: plays fully before the next id starts.
                        ttsPlayer.play(mp3)
                        playedServerAudio = true
                    } else {
                        System.out.println("MuseGadgetTts: no audio for reply, skipping")
                    }
                }
                // Fallback only when no server audio played: the streaming
                // queue would otherwise stop() this playback mid-sentence.
                if (!playedServerAudio && fallbackText.trim().isNotEmpty()) {
                    try {
                        speakStreaming(fallbackText, isFinal = true)
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
