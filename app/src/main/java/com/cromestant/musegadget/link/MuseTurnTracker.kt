package com.cromestant.musegadget.link

/**
 * Follows one gadget voice-note request through Muse's chat events to its replies.
 * Simplified port of `ref/muse-gadget-sdk/linux/src/musegadget/muse_turn.py`
 * (`MuseTurn`) for a shared-chat PTT device: we post voice notes to the MAIN
 * chat (no session_id) and correlate by acknowledged message ids. `owns_chat`
 * semantics do not apply — there is no session filtering, no replay scope,
 * and no follow-up support.
 *
 * Event shapes mirror `muse_turn.py`: each fed event is one parsed NDJSON
 * object with `type == "event"`; message events are `delta.message_start`,
 * `delta.text_append`, `delta.message_done`, `message.assistant`; status
 * events are `agent.status`, `task.status`.
 */

/** Typed turn outcome, mirroring `AgentStatus`/`Reply`/`TaskFinished` in `muse_turn.py:67,78,97`. */
sealed class TurnEvent {
    /** Muse's public progress, mirroring `AgentStatus` in `muse_turn.py:67`. */
    data class AgentStatus(val code: String?, val text: String?) : TurnEvent()

    /**
     * One assistant message answering the request, mirroring `Reply` in
     * `muse_turn.py:78`. `text` is streamed deltas replaced by Muse's final
     * text when sent; `streamed` is the deltas alone. (The Python `bound`
     * flag is dropped: binding only gates tracking here.)
     */
    data class Reply(
        val messageId: String,
        val text: String,
        val streamed: String,
        val done: Boolean
    ) : TurnEvent()

    /** Muse reported the request's task terminal; emitted once. */
    object TaskFinished : TurnEvent()
}

private class ReplyState(var text: String = "", var streamed: String = "", var done: Boolean = false)

/**
 * Single-request turn tracker, mirroring `MuseTurn` in `muse_turn.py:194`
 * minus chat ownership. Dependency-free and deterministic; time injectable
 * via [complete]'s `nowMs` param, otherwise `System.currentTimeMillis()`.
 */
class MuseTurnTracker {
    private val userIds = HashSet<String>()
    private val replies = LinkedHashMap<String, ReplyState>()
    private val ignored = HashSet<String>()
    private val pending = ArrayList<Map<String, Any?>>()
    private var acknowledged = false
    private var busy = false
    private var taskFinished = false
    private var taskFinishedEmitted = false

    /** Last state-changing event, mirroring `MuseTurn.last_activity` in `muse_turn.py:212`. */
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    /**
     * session_id echoed by the /chat/stream acknowledgement, when the server
     * sends one. Captured for tracing only — correlation still uses the
     * acknowledged user message ids.
     */
    var ackSessionId: String? = null
        private set

    companion object {
        private val TERMINAL_STATUSES = setOf("completed", "failed", "errored", "cancelled", "canceled")
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_MESSAGES = 32
        private const val MAX_TEXT_BYTES = 64 * 1024
        private val CONTROLS = Regex("[\u0000-\u0008\u000B-\u001F\u007F-\u009F]")
    }

    /**
     * Bind the turn to the user message in the `/chat/stream` acknowledgement,
     * mirroring `MuseTurn.acknowledge` in `muse_turn.py:241`. Reads
     * `result.message_id` and `result.reply_to_message_id`, falling back to
     * top-level keys when `result` is absent (mirroring
     * `_acknowledged_message_ids` in `muse_turn.py:160`). Requires at least
     * one non-empty user id, else throws [IllegalArgumentException].
     * Returns events replayed from the pre-acknowledgement hold buffer.
     */
    fun acknowledge(response: Map<String, Any?>): List<TurnEvent> {
        @Suppress("UNCHECKED_CAST")
        val result = response["result"] as? Map<String, Any?> ?: response
        val ids = HashSet<String>()
        for (key in listOf("message_id", "reply_to_message_id")) {
            val id = result[key] as? String
            if (!id.isNullOrEmpty()) ids.add(id)
        }
        if (ids.isEmpty()) throw IllegalArgumentException("Muse chat acknowledgement omitted the user message ID")
        userIds.clear()
        userIds.addAll(ids)
        ackSessionId = (result["session_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (response["session_id"] as? String)?.takeIf { it.isNotEmpty() }
        acknowledged = true
        val held = pending.toList()
        pending.clear()
        val out = ArrayList<TurnEvent>()
        for (event in held) out.addAll(feed(event))
        return out
    }

    /**
     * Read one raw chat event, mirroring `MuseTurn.feed` in `muse_turn.py:277`.
     * Non-`type == "event"` objects are ignored. Before [acknowledge], events
     * are held (cap 64, mirroring `_MAX_PENDING_EVENTS` in `muse_turn.py:54`).
     */
    fun feed(event: Map<String, Any?>): List<TurnEvent> {
        if (event["type"] != "event") return emptyList()
        @Suppress("UNCHECKED_CAST")
        val payload = event["payload"] as? Map<String, Any?> ?: return emptyList()
        if (!acknowledged) {
            if (pending.size >= MAX_PENDING_EVENTS) {
                throw IllegalArgumentException("too many chat events before Muse acknowledged the turn")
            }
            pending.add(event)
            return emptyList()
        }
        val name = event["event"] as? String ?: return emptyList()
        if (name == "agent.status" || name == "task.status") return status(name, payload)
        if (name == "delta.message_start" || name == "delta.text_append" ||
            name == "delta.message_done" || name == "message.assistant"
        ) {
            return message(name, event, payload)
        }
        return emptyList()
    }

    /**
     * Muse is done answering, mirroring `MuseTurn.complete` in `muse_turn.py:324`
     * (without the follow-up extension): not busy, every reply done, and the
     * task finished or 3 s of quiet.
     */
    fun complete(nowMs: Long = System.currentTimeMillis()): Boolean =
        !busy && replies.values.all { it.done } &&
            (taskFinished || nowMs - lastActivityMs >= 3000)

    /** Concatenated text of ALL replies (done or not) in arrival order. */
    fun allText(): String =
        replies.values.joinToString("") { it.text }

    /** Concatenated done-reply texts in arrival order. */
    fun finalText(): String =
        replies.values.filter { it.done }.joinToString("") { it.text }

    /** Message ids of done replies, in arrival order (drives the TTS fetch). */
    fun doneMessageIds(): List<String> =
        replies.entries.filter { it.value.done }.map { it.key }

    /** Whether any reply message has bound to this turn. */
    fun hasReplies(): Boolean = replies.isNotEmpty()

    private fun status(name: String, payload: Map<String, Any?>): List<TurnEvent> {
        lastActivityMs = System.currentTimeMillis()
        val activity = payload["activity_code"] as? String
        val status = payload["status"] as? String
        val terminal = status != null && status in TERMINAL_STATUSES
        if (name == "agent.status") {
            if (activity != null) {
                busy = activity != "" && activity != "online" && activity != "idle" && !terminal
            } else if (status != null) {
                busy = status != "" && status != "idle" && !terminal
            }
            val text = payload["activity_text"] as? String
            return listOf(
                TurnEvent.AgentStatus(
                    activity,
                    if (text != null) stripControls(text) else null
                )
            )
        }
        // task.status (mirrors the shared-chat branch of `_status` in `muse_turn.py:348`)
        if (status != null) {
            if (terminal) {
                taskFinished = true
                busy = false
                if (!taskFinishedEmitted) {
                    taskFinishedEmitted = true
                    return listOf(TurnEvent.TaskFinished)
                }
            } else {
                busy = status != "" && status != "idle"
            }
        }
        return emptyList()
    }

    private fun message(
        name: String,
        event: Map<String, Any?>,
        payload: Map<String, Any?>
    ): List<TurnEvent> {
        val messageId = (payload["message_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (event["message_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (payload["id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        if (payload["role"] == "user") return emptyList()
        if (messageId in ignored) return emptyList()
        val parent = (payload["reply_to_message_id"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (payload["parent_message_id"] as? String)?.takeIf { it.isNotEmpty() }
        // A message binds when its parent is a user id or an already-bound reply
        // (mirrors `_message` in `muse_turn.py:393`); unrelated parents are ignored.
        if (parent != null && parent !in userIds && parent !in replies) {
            ignored.add(messageId)
            return emptyList()
        }
        var reply = replies[messageId]
        if (reply == null) {
            if (replies.size >= MAX_MESSAGES) {
                throw IllegalArgumentException("Muse returned too many messages for one request")
            }
            reply = ReplyState()
            replies[messageId] = reply
        }
        lastActivityMs = System.currentTimeMillis()
        if (name == "delta.text_append" && !reply.done) {
            val delta = payload["text"] as? String
            if (delta != null) {
                val clean = stripControls(delta)
                reply.text += clean
                reply.streamed += clean
            }
        }
        if (name == "delta.message_done" || name == "message.assistant") {
            var completed = payload["display_text"] as? String
            if (completed == null) {
                val transcript = payload["transcript"]
                completed = if (transcript is String) {
                    transcript
                } else {
                    transcriptText(transcript, messageId)
                }
                if (completed.isEmpty() && transcript !is String) {
                    completed = payload["content"] as? String ?: ""
                }
            }
            reply.text = stripControls(completed)
            if (name == "delta.message_done" || payload["display_text_ready"] != false) {
                reply.done = true
            }
        }
        if (reply.text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ||
            reply.streamed.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES
        ) {
            throw IllegalArgumentException("Muse returned more than 64 KiB of reply text")
        }
        return listOf(TurnEvent.Reply(messageId, reply.text, reply.streamed, reply.done))
    }

    private fun stripControls(text: String): String = CONTROLS.replace(text, "")
}

/**
 * Assistant-only text from Muse's structured transcript, mirroring
 * `transcript_text` in `muse_turn.py:171`: messages[] with role==assistant
 * (optionally id-matched), content[] items with type=="text" joined by "\n".
 */
fun transcriptText(value: Any?, messageId: String? = null): String {
    if (value is String) return value
    if (value !is Map<*, *>) return ""
    val messages = value["messages"] as? List<*> ?: return ""
    var assistants = messages.filter { it is Map<*, *> && it["role"] == "assistant" }
    if (messageId != null) {
        assistants = assistants.filter { (it as Map<*, *>)["id"] == messageId }
    }
    if (assistants.isEmpty()) return ""
    val content = (assistants.last() as Map<*, *>)["content"] as? List<*> ?: return ""
    return content
        .filter { it is Map<*, *> && it["type"] == "text" && (it["text"] as? String) != null }
        .joinToString("\n") { ((it as Map<*, *>)["text"] as String) }
}
