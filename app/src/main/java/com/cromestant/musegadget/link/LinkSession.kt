package com.cromestant.musegadget.link

import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.noise.NoiseXXInitiator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Blocking Noise link session, port of `LinkSession` in
 * `ref/muse-gadget-sdk/linux/src/musegadget/link_client.py`
 * (blocking threads; no coroutines).
 *
 * Lifecycle mirrors `run` in `link_client.py:170`: WS upgrade with the
 * per-VM bearer, Noise XX handshake over binary WS frames, long-lived
 * `POST /link-control` stream (`_open_control_stream` at
 * `link_client.py:257`), reader dispatch (`_read_loop` at
 * `link_client.py:491`), invoke handling (`_invoke` at
 * `link_client.py:554`), chat post (`_post_chat` at `link_client.py:326`),
 * subscribe (`subscribe_chat` at `link_client.py:392`).
 *
 * OkHttp masks client frames per RFC 6455 automatically — no manual masking.
 * All sends are serialized through one send lock (Noise nonces must not gap).
 * Token values are never logged.
 */
enum class LinkOutcome { CLOSED, AUTH_REJECTED, FORBIDDEN, UNPAIRED, STOPPED }

/** Kotlin stand-in for Python's ConnectionError. */
open class ConnectionError(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Muse refused an HTTP request carried by the Noise session (mirrors `HttpStreamError`). */
class HttpStreamError(val status: Int, val path: String) :
    ConnectionError("Muse request $path failed: HTTP $status")

/**
 * One NDJSON chat-event line, mirroring `_chat_event` in `link_client.py:422`.
 * Blank, malformed, or non-`type == "event"` lines return null.
 */
internal fun parseChatEventLine(line: ByteArray): Map<String, Any?>? {
    val text = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(line)).toString()
    } catch (e: Exception) {
        return null
    }
    if (text.isBlank()) return null
    val event = try {
        Json.parse(text)
    } catch (e: Exception) {
        return null
    }
    return if (event is Map<*, *> && event["type"] == "event") {
        @Suppress("UNCHECKED_CAST")
        event as Map<String, Any?>
    } else {
        null
    }
}

class LinkSession(
    private val noiseHost: String,
    private val vmId: String,
    private val vmAuthToken: String,
    private val nodeId: String,
    private val displayName: String,
    private val version: String,
    private val commandsV2: Map<String, Map<String, Any?>> = defaultCommandsV2,
    private val runCommand: (command: String, params: Map<String, Any?>, timeoutMs: Long?) -> Map<String, Any?> =
        defaultRunCommand("1.0"),
    private val http: OkHttpClient,
) {
    companion object {
        const val CONTROL_PATH = "/link-control"
        const val CHAT_PATH = "/chat/stream"
        const val SUBSCRIBE_PATH = "/chat/subscribe"
        const val APP_ID = "musegadget"
        const val HANDSHAKE_TIMEOUT_S = 20L
        const val REQUEST_TIMEOUT_S = 60L
        const val KEEPALIVE_S = 60L
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_CHAT_EVENT_BYTES = 256 * 1024
        const val MAX_STREAM_BUFFER_BYTES = 1024 * 1024
        const val STREAM_CHUNK_BYTES = 16 * 1024
        const val REQUEST_CHUNK_BYTES = 16 * 1024
        const val MAX_INLINE_REQUEST_BYTES = 64 * 1024
        const val MAX_VOICE_BYTES = 2_800_000
        const val MAX_CONCURRENT_INVOKES = 4

        /**
         * Default command handler: answers `device.health`, rejects the rest.
         * Gadget is PTT-only; no shell/file commands are ever exposed.
         */
        fun defaultRunCommand(version: String): (String, Map<String, Any?>, Long?) -> Map<String, Any?> =
            { command, _, _ ->
                if (command == "device.health") {
                    mapOf(
                        "ok" to true,
                        "payload" to mapOf(
                            "model" to "Portal Mini DT90GB",
                            "firmware" to version,
                            "link" to "up",
                            "battery_pct" to null,
                        ),
                    )
                } else {
                    mapOf("ok" to false, "error" to "unknown command: ${sanitizeCommand(command)}")
                }
            }

        /** Control chars replaced so a command name can't forge log lines. */
        internal fun sanitizeCommand(name: String): String =
            name.map { c -> if (!c.isISOControl() && c != '\u007F') c else '?' }.joinToString("")
    }

    private sealed interface WsFrame {
        class Binary(val data: ByteArray) : WsFrame
        class Text(val text: String) : WsFrame
        object Closed : WsFrame
    }

    private val sendLock = Any()
    private val inbound = LinkedBlockingQueue<WsFrame>()
    private val pending = ConcurrentHashMap<Long, PendingRequest>()
    private val streams = ConcurrentHashMap<Long, HttpStream>()
    private val invokeSem = Semaphore(MAX_CONCURRENT_INVOKES)
    private val invokeExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "link-invoke").also { it.isDaemon = true }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var transport: NoiseLinkTransport? = null
    @Volatile private var controlStreamId: Long = 0
    @Volatile private var registerId: String = ""
    private val outcomeRef = AtomicReference<LinkOutcome?>(null)
    private val outcomeLatch = CountDownLatch(1)
    private val closed = AtomicBoolean(false)

    /**
     * Full lifecycle, blocking. Returns the terminal outcome; throws only for
     * pre-registration failures (upgrade rejected with an unexpected code,
     * handshake/open timeouts) which the caller treats as CLOSED.
     */
    fun run(stop: AtomicBoolean): LinkOutcome {
        val openLatch = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val failureCode = AtomicInteger(-2) // -2 = none yet
        val failureExc = AtomicReference<Throwable?>(null)

        val request = Request.Builder()
            .url(noiseUrl(noiseHost, vmId))
            .header("Authorization", "Bearer $vmAuthToken")
            .header("User-Agent", MuseApi.userAgent())
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                inbound.offer(WsFrame.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.offer(WsFrame.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                inbound.offer(WsFrame.Closed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.offer(WsFrame.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.get()) {
                    failureCode.set(response?.code ?: -1)
                    failureExc.set(t)
                    openLatch.countDown()
                } else {
                    inbound.offer(WsFrame.Closed)
                }
            }
        })
        ws = socket
        try {
            if (!openLatch.await(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS)) {
                socket.cancel()
                throw ConnectionError("Noise WS open timed out")
            }
            if (!opened.get()) {
                val code = failureCode.get()
                if (code == 401) return LinkOutcome.AUTH_REJECTED
                if (code == 403) return LinkOutcome.FORBIDDEN
                throw ConnectionError("Noise WS failed before open", failureExc.get())
            }
            // Noise XX handshake over binary WS frames (mirrors `_handshake`).
            val initiator = NoiseXXInitiator()
            initiator.initialize()
            sendRaw(initiator.writeMessage1())
            val msg2 = nextHandshakeBytes()
            initiator.readMessage2(msg2)
            sendRaw(initiator.writeMessage3())
            val (send, recv) = initiator.split()
            transport = NoiseLinkTransport(send, recv)

            // Control stream + register (mirrors `_open_control_stream`).
            val t = transport ?: throw ConnectionError("no transport")
            val enc = t.startStreamRequest(
                "POST", CONTROL_PATH,
                headers = listOf(Header("x-request-id", UUID.randomUUID().toString()), Header("x-app-id", APP_ID)),
            )
            controlStreamId = enc.streamId
            sendFrames(enc.frames)
            registerId = UUID.randomUUID().toString()
            val reg = registerMessage(nodeId, displayName, version, commandsV2, registerId)
            sendFrames(t.encryptBodyChunk(controlStreamId, encodeLinkMessage(reg), endBody = false))

            val reader = thread(isDaemon = true, name = "link-reader") { readLoop() }
            try {
                while (true) {
                    if (outcomeLatch.await(200, TimeUnit.MILLISECONDS)) break
                    if (stop.get() || closed.get()) {
                        if (outcomeRef.compareAndSet(null, LinkOutcome.STOPPED)) outcomeLatch.countDown()
                        doClose()
                        break
                    }
                }
            } finally {
                doClose()
                reader.join(5000)
            }
            return outcomeRef.get() ?: LinkOutcome.CLOSED
        } finally {
            doClose()
        }
    }

    /** Cancel the reader, fail pending work, close the WS. */
    fun close() {
        closed.set(true)
        doClose()
    }

    // -- Device-originated requests ------------------------------------------

    private fun requestHeaders(): List<Header> = listOf(
        Header("x-request-id", UUID.randomUUID().toString()),
        Header("x-app-id", APP_ID),
    )

    /**
     * POST a chat/stream body, mirroring `_post_chat` in `link_client.py:326`.
     * Small bodies go inline; large ones stream in 16 KiB chunks.
     * Returns (status, body). Timeout/cancel sends a reset.
     */
    fun postChat(body: ByteArray): Pair<Int, ByteArray> {
        val t = transport ?: throw ConnectionError("Muse session is not connected")
        val headers = listOf(Header("Content-Type", "application/json")) + requestHeaders()
        val streamed = body.size > MAX_INLINE_REQUEST_BYTES
        val enc = if (streamed) {
            t.startStreamRequest("POST", CHAT_PATH, headers = headers)
        } else {
            t.encryptHttpRequest("POST", CHAT_PATH, body, headers = headers)
        }
        val req = PendingRequest()
        pending[enc.streamId] = req
        try {
            sendFrames(enc.frames)
            if (streamed) {
                var offset = 0
                while (offset < body.size) {
                    val end = minOf(offset + REQUEST_CHUNK_BYTES, body.size)
                    sendFrames(
                        t.encryptBodyChunk(enc.streamId, body.copyOfRange(offset, end), endBody = end == body.size),
                    )
                    offset = end
                }
            }
            try {
                return req.done.get(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                sendReset(enc.streamId, "consumer closed")
                throw e
            }
        } finally {
            pending.remove(enc.streamId)
        }
    }

    /**
     * Post a PTT voice-note JSON frame to the main chat. The `json` arg from
     * PttController already contains message/items per VoiceNote framing;
     * we only ADD device_id/session_id here.
     */
    fun sendVoiceNoteJson(json: String, sessionId: String? = null): Map<String, Any?> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_VOICE_BYTES) { "voice note too large" }
        val parsed = try {
            Json.parse(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("voice note is not JSON")
        }
        if (parsed !is Map<*, *>) throw IllegalArgumentException("voice note is not a JSON object")
        @Suppress("UNCHECKED_CAST")
        val bodyMap = LinkedHashMap<String, Any?>(parsed as Map<String, Any?>)
        bodyMap["device_id"] = nodeId
        if (sessionId != null) bodyMap["session_id"] = sessionId
        val (status, respBody) = postChat(Json.stringify(bodyMap).toByteArray(Charsets.UTF_8))
        val text = respBody.toString(Charsets.UTF_8)
        val response: Any? = try {
            if (text.isEmpty()) null else Json.parse(text)
        } catch (e: Exception) {
            text.take(2000)
        }
        return mapOf("ok" to (status in 200..299), "status" to status, "response" to response)
    }

    /** Open the NDJSON chat subscription, mirroring `subscribe_chat` (`link_client.py:392`). */
    fun subscribeChat(sessionId: String?): ChatSubscription {
        val t = transport ?: throw ConnectionError("Muse session is not connected")
        val bodyMap: Map<String, Any?> = if (sessionId != null) mapOf("session_id" to sessionId) else emptyMap()
        val body = Json.stringify(bodyMap).toByteArray(Charsets.UTF_8)
        val headers = listOf(
            Header("Content-Type", "application/json"),
            Header("Accept", "application/x-ndjson"),
        ) + requestHeaders()
        val enc = t.encryptHttpRequest("POST", SUBSCRIBE_PATH, body, headers = headers)
        val stream = HttpStream()
        streams[enc.streamId] = stream
        try {
            sendFrames(enc.frames)
            try {
                stream.ready.get(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                streams.remove(enc.streamId)
                sendReset(enc.streamId, "consumer closed")
                throw ConnectionError("chat subscribe timed out", e)
            }
            if (stream.status !in 200..299) {
                streams.remove(enc.streamId)
                sendReset(enc.streamId, "consumer closed")
                throw HttpStreamError(stream.status, SUBSCRIBE_PATH)
            }
            return ChatSubscription(enc.streamId, stream)
        } catch (e: Exception) {
            streams.remove(enc.streamId)
            stream.close()
            throw e
        }
    }

    /** NDJSON subscription handle, mirroring the consume half of `subscribe_chat`. */
    inner class ChatSubscription internal constructor(val streamId: Long, private val stream: HttpStream) {
        private var pendingLine = ByteArray(0)

        /**
         * Next `type == "event"` dict, or null on timeout/end. Blank and
         * malformed lines are skipped. Per-event cap 256 KiB (throws).
         */
        fun nextEvent(timeoutMs: Long): Map<String, Any?>? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val nl = pendingLine.indexOf('\n'.code.toByte())
                if (nl >= 0) {
                    val line = pendingLine.copyOfRange(0, nl + 1)
                    pendingLine = pendingLine.copyOfRange(nl + 1, pendingLine.size)
                    val ev = parseChatEventLine(line)
                    if (ev != null) return ev
                    continue
                }
                if (stream.ended) {
                    if (pendingLine.isEmpty()) return null
                    val line = pendingLine
                    pendingLine = ByteArray(0)
                    return parseChatEventLine(line)
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                val chunk = try {
                    stream.takeChunk(remaining)
                } catch (e: Exception) {
                    throw e
                } ?: return null
                if (pendingLine.size + chunk.size > MAX_CHAT_EVENT_BYTES) {
                    throw IllegalStateException("Muse chat event exceeds 256 KiB")
                }
                pendingLine += chunk
            }
        }

        fun close() {
            streams.remove(streamId)
            stream.close()
            sendReset(streamId, "consumer closed")
        }
    }

    // -- Receiving -------------------------------------------------------------

    private fun nextHandshakeBytes(): ByteArray {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_S * 1000
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw ConnectionError("Noise handshake timed out")
            val frame = inbound.poll(remaining, TimeUnit.MILLISECONDS)
                ?: throw ConnectionError("Noise handshake timed out")
            when (frame) {
                is WsFrame.Binary -> return frame.data
                is WsFrame.Text -> throw ConnectionError("Noise handshake got a text frame")
                is WsFrame.Closed -> throw ConnectionError("Noise WS closed during handshake")
            }
        }
    }

    private fun readLoop() {
        val decoder = LinkMessageDecoder()
        try {
            while (true) {
                // poll (not take) so a 60 s quiet period emits the control
                // keepalive: an empty chunk, endBody=false, mirroring the
                // ESP32 `noise_control.cpp` keepalive.
                val frame = inbound.poll(KEEPALIVE_S, TimeUnit.SECONDS)
                if (frame == null) {
                    try {
                        val t = transport ?: return setOutcome(LinkOutcome.CLOSED)
                        sendFrames(t.encryptBodyChunk(controlStreamId, ByteArray(0), endBody = false))
                    } catch (e: Exception) {
                        return setOutcome(LinkOutcome.CLOSED)
                    }
                    continue
                }
                when (frame) {
                    is WsFrame.Text -> continue // ignore text on the Noise connection
                    is WsFrame.Closed -> return setOutcome(LinkOutcome.CLOSED)
                    is WsFrame.Binary -> {
                        val t = transport ?: return setOutcome(LinkOutcome.CLOSED)
                        val dec = try {
                            t.decryptFrame(frame.data)
                        } catch (e: Exception) {
                            return setOutcome(LinkOutcome.CLOSED)
                        } ?: continue
                        if (route(dec, decoder)) return
                    }
                }
                if (outcomeRef.get() != null) return
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (outcomeRef.compareAndSet(null, LinkOutcome.CLOSED)) outcomeLatch.countDown()
        }
    }

    /** Route one decrypted frame; returns true when the session outcome is decided. */
    private fun route(dec: DecryptedFrame, decoder: LinkMessageDecoder): Boolean {
        val sid = when (dec) {
            is DecryptedFrame.Response -> dec.streamId
            is DecryptedFrame.BodyChunk -> dec.streamId
            is DecryptedFrame.Reset -> dec.streamId
        }
        if (sid != controlStreamId) {
            val stream = streams[sid]
            if (stream != null) {
                if (!stream.onFrame(dec)) sendReset(sid, "consumer closed")
            } else {
                pending[sid]?.onFrame(dec)
            }
            return false
        }
        when (dec) {
            is DecryptedFrame.Reset -> {
                setOutcome(LinkOutcome.CLOSED)
                return true
            }
            is DecryptedFrame.Response -> {
                if (dec.response.status >= 400) {
                    setOutcome(if (dec.response.status == 403) LinkOutcome.FORBIDDEN else LinkOutcome.CLOSED)
                    return true
                }
                for (msg in decoder.feed(dec.response.body)) handleControlMessage(msg)
                if (dec.response.endBody) {
                    setOutcome(LinkOutcome.CLOSED)
                    return true
                }
            }
            is DecryptedFrame.BodyChunk -> {
                for (msg in decoder.feed(dec.chunk.data)) handleControlMessage(msg)
                if (dec.chunk.endBody) {
                    setOutcome(LinkOutcome.CLOSED)
                    return true
                }
            }
        }
        return outcomeRef.get() != null
    }

    private fun handleControlMessage(msg: Map<String, Any?>) {
        if (msg["id"] == registerId && !msg.containsKey("method")) {
            if (msg["error"] != null || msg["ok"] == false) {
                setOutcome(LinkOutcome.FORBIDDEN)
            }
            return
        }
        val event = msg["event"]
        if (event == "link.unpaired" || event == "node.unpaired") {
            setOutcome(LinkOutcome.UNPAIRED)
            return
        }
        if (msg["method"] == "link.invoke") {
            val invokeId = msg["id"] as? String ?: return
            @Suppress("UNCHECKED_CAST")
            val params = (msg["params"] as? Map<String, Any?>) ?: emptyMap()
            val timeoutMs = (msg["timeout_ms"] as? Number)?.toLong()
            invokeExecutor.execute {
                invokeSem.acquire()
                try {
                    val command = msg["command"] as? String ?: ""
                    val result = try {
                        runCommand(command, params, timeoutMs)
                    } catch (e: Exception) {
                        mapOf<String, Any?>("ok" to false, "error" to (e.message ?: "invoke failed"))
                    }
                    val reply = LinkedHashMap<String, Any?>()
                    reply["method"] = "link.result"
                    reply["id"] = invokeId
                    reply.putAll(result)
                    try {
                        val t = transport ?: return@execute
                        sendFrames(t.encryptBodyChunk(controlStreamId, encodeLinkMessage(reply), endBody = false))
                    } catch (e: Exception) {
                        // Session is dying; drop the result.
                    }
                } finally {
                    invokeSem.release()
                }
            }
        }
    }

    // -- Sending -----------------------------------------------------------------

    private fun sendRaw(data: ByteArray) {
        val s = ws ?: throw ConnectionError("Noise WS is not connected")
        if (!s.send(data.toByteString())) throw ConnectionError("Noise WS send failed")
    }

    private fun sendFrames(frames: List<ByteArray>) {
        synchronized(sendLock) {
            val s = ws ?: throw ConnectionError("Noise WS is not connected")
            for (f in frames) {
                if (!s.send(f.toByteString())) throw ConnectionError("Noise WS send failed")
            }
        }
    }

    private fun sendReset(streamId: Long, reason: String = "") {
        try {
            val t = transport ?: return
            sendFrames(t.encryptReset(streamId, reason = reason))
        } catch (e: Exception) {
            // Best effort on a dying session.
        }
    }

    private fun setOutcome(outcome: LinkOutcome) {
        if (outcomeRef.compareAndSet(null, outcome)) outcomeLatch.countDown()
    }

    private fun doClose() {
        try {
            ws?.close(1000, null)
        } catch (e: Exception) {
        }
        inbound.offer(WsFrame.Closed)
        for (req in pending.values) {
            req.done.completeExceptionally(ConnectionError("session ended"))
        }
        pending.clear()
        for (stream in streams.values) stream.fail(ConnectionError("session ended"))
        streams.clear()
    }

    /** Collects one request/response stream, mirroring `_Request` (`link_client.py:691`). */
    private inner class PendingRequest {
        val done = CompletableFuture<Pair<Int, ByteArray>>()
        private var status: Int = 0
        private var body = ByteArray(0)

        @Synchronized
        fun onFrame(frame: DecryptedFrame) {
            if (done.isDone) return
            when (frame) {
                is DecryptedFrame.Reset ->
                    done.completeExceptionally(ConnectionError("stream reset: ${frame.reset.reason}"))
                is DecryptedFrame.Response -> {
                    status = frame.response.status
                    body += frame.response.body
                    if (frame.response.endBody) finish()
                }
                is DecryptedFrame.BodyChunk -> {
                    body += frame.chunk.data
                    if (frame.chunk.endBody) finish()
                }
            }
        }

        private fun finish() {
            if (body.size > MAX_RESPONSE_BYTES) {
                done.completeExceptionally(IllegalStateException("response too large"))
            } else {
                done.complete(status to body)
            }
        }
    }

    /**
     * Bounded HTTP response stream, mirroring `HttpStream` (`link_client.py:599`):
     * 1 MiB budget, 16 KiB coalescing, status/headers/ended, ready future.
     */
    internal inner class HttpStream(
        private val maxBufferBytes: Int = MAX_STREAM_BUFFER_BYTES,
    ) {
        val ready = CompletableFuture<Unit>()
        @Volatile var status: Int = 0
        @Volatile var headers: List<Header> = emptyList()
        @Volatile var ended: Boolean = false
        @Volatile var resetRequested: Boolean = false
        private val lock = Any()
        private var finished: Boolean = false
        private var error: Exception? = null
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val buffered = AtomicInteger(0)
        private var tail = ByteArrayOutputStream()

        /** Returns false when the consumer fell behind (caller sends a reset). */
        fun onFrame(frame: DecryptedFrame): Boolean {
            synchronized(lock) {
                if (finished) return true
                when (frame) {
                    is DecryptedFrame.Reset -> {
                        ended = true
                        failLocked(ConnectionError("Muse response stream reset"))
                        return true
                    }
                    is DecryptedFrame.Response -> {
                        status = frame.response.status
                        headers = frame.response.headers.toList()
                        if (!ready.isDone) ready.complete(Unit)
                        absorbLocked(frame.response.body, frame.response.endBody)
                    }
                    is DecryptedFrame.BodyChunk -> {
                        if (!ready.isDone) {
                            failLocked(ConnectionError("Muse response body arrived without headers"))
                            return false
                        }
                        absorbLocked(frame.chunk.data, frame.chunk.endBody)
                    }
                }
                return true
            }
        }

        private fun absorbLocked(data: ByteArray, end: Boolean) {
            if (data.isNotEmpty()) {
                if (buffered.get() + data.size > maxBufferBytes) {
                    failLocked(IOException("Muse response consumer fell behind"))
                    return
                }
                buffered.addAndGet(data.size)
                tail.write(data)
                while (tail.size() >= STREAM_CHUNK_BYTES) {
                    val full = tail.toByteArray()
                    queue.offer(full.copyOfRange(0, STREAM_CHUNK_BYTES))
                    tail.reset()
                    tail.write(full, STREAM_CHUNK_BYTES, full.size - STREAM_CHUNK_BYTES)
                }
            }
            if (end) {
                if (tail.size() > 0) queue.offer(tail.toByteArray())
                tail.reset()
                ended = true
                finished = true
            }
        }

        fun fail(e: Exception) {
            synchronized(lock) { failLocked(e) }
        }

        private fun failLocked(e: Exception) {
            if (finished) return
            finished = true
            if (!ready.isDone) ready.completeExceptionally(e)
            queue.clear()
            tail.reset()
            buffered.set(0)
            error = e
        }

        fun close() {
            synchronized(lock) {
                if (!ready.isDone) ready.cancel(false)
                finished = true
                error = null
                queue.clear()
                tail.reset()
                buffered.set(0)
            }
        }

        /** Next 16 KiB-coalesced chunk; null on timeout/end; throws a stream error. */
        fun takeChunk(timeoutMs: Long): ByteArray? {
            error?.let { throw it }
            val chunk = try {
                queue.poll(maxOf(timeoutMs, 0L), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: return null
            buffered.addAndGet(-chunk.size)
            error?.let { throw it }
            return chunk
        }
    }
}
