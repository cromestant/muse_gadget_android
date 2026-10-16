package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunk 7: paired-state derivation, sub-chat session_id plumbing, ack
 * session_id capture, and event trace summaries. Pure JVM, no Android.
 */
class ChatSessionTest {

    // --- isProvisionedSnapshot ---

    @Test
    fun provisionedWhenAllKeysPresent() {
        assertTrue(isProvisionedSnapshot("hatch.metaaivm.com", "acc", "ref"))
    }

    @Test
    fun notProvisionedWhenAnyKeyMissing() {
        assertFalse(isProvisionedSnapshot("", "acc", "ref"))
        assertFalse(isProvisionedSnapshot("h", "", "ref"))
        assertFalse(isProvisionedSnapshot("h", "acc", ""))
        assertFalse(isProvisionedSnapshot(null, "acc", "ref"))
        assertFalse(isProvisionedSnapshot("h", null, "ref"))
        assertFalse(isProvisionedSnapshot("h", "acc", null))
        assertFalse(isProvisionedSnapshot("", "", ""))
    }

    // --- buildChatStreamBody / buildChatSubscribeBody ---

    @Test
    fun streamBodyCarriesSessionIdWhenGiven() {
        val body = buildChatStreamBody(mapOf("voice_note" to "abc"), "node1", "sess-9")
        assertEquals("abc", body["voice_note"])
        assertEquals("node1", body["device_id"])
        assertEquals("sess-9", body["session_id"])
    }

    @Test
    fun streamBodyOmitsSessionIdWhenNull() {
        val body = buildChatStreamBody(mapOf("voice_note" to "abc"), "node1", null)
        assertEquals("node1", body["device_id"])
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun subscribeBodyCarriesSessionIdWhenGiven() {
        assertEquals(mapOf("session_id" to "sess-9"), buildChatSubscribeBody("sess-9"))
    }

    @Test
    fun subscribeBodyEmptyWhenNull() {
        assertTrue(buildChatSubscribeBody(null).isEmpty())
    }

    // --- describeChatEvent ---

    @Test
    fun describeEventSummarizesWithoutText() {
        val ev = mapOf<String, Any?>(
            "type" to "event",
            "event" to "delta.text_append",
            "payload" to mapOf(
                "message_id" to "m1234567890abcdef",
                "reply_to_message_id" to "u999",
                "text" to "secret reply text",
            ),
        )
        val desc = describeChatEvent(ev)
        assertTrue(desc.contains("delta.text_append"))
        assertTrue(desc.contains("m1234567890a"))
        assertTrue(desc.contains("u999"))
        assertFalse(desc.contains("secret reply text"))
    }

    @Test
    fun describeEventHandlesMissingPayload() {
        assertEquals("event name=agent.status", describeChatEvent(mapOf("event" to "agent.status")))
        assertEquals("event name=?", describeChatEvent(emptyMap()))
    }

    // --- MuseTurnTracker.ackSessionId ---

    @Test
    fun ackCapturesSessionIdFromResult() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(
            mapOf("result" to mapOf("message_id" to "u1", "session_id" to "sess-abc"))
        )
        assertEquals("sess-abc", tracker.ackSessionId)
    }

    @Test
    fun ackCapturesSessionIdFromTopLevel() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("message_id" to "u1", "session_id" to "sess-top"))
        assertEquals("sess-top", tracker.ackSessionId)
    }

    @Test
    fun ackSessionIdNullWhenAbsent() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        assertNull(tracker.ackSessionId)
    }

    // --- voiceTurnSettled ---

    @Test
    fun voiceTurnNeverSettlesWhenEmpty() {
        // complete() is vacuously true on a quiet empty turn; the loop must
        // still wait for the 25 s deadline so a slow server gets its chance.
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        assertTrue(tracker.complete(tracker.lastActivityMs + 30_000))
        assertFalse(voiceTurnSettled(tracker, tracker.lastActivityMs + 30_000))
    }

    @Test
    fun voiceTurnDoesNotSettleWhileReplyUndone() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        tracker.feed(
            mapOf(
                "type" to "event", "event" to "delta.text_append",
                "payload" to mapOf("message_id" to "m1", "text" to "Got"),
            )
        )
        assertTrue(tracker.hasReplies())
        assertFalse(voiceTurnSettled(tracker, tracker.lastActivityMs + 30_000))
    }

    @Test
    fun voiceTurnSettlesWhenDoneAndQuiet() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        tracker.feed(
            mapOf(
                "type" to "event", "event" to "delta.text_append",
                "payload" to mapOf("message_id" to "m1", "text" to "Got"),
            )
        )
        tracker.feed(
            mapOf(
                "type" to "event", "event" to "delta.message_done",
                "payload" to mapOf("message_id" to "m1"),
            )
        )
        assertFalse(voiceTurnSettled(tracker, tracker.lastActivityMs + 1000))
        assertTrue(voiceTurnSettled(tracker, tracker.lastActivityMs + 3000))
    }

    // --- selectTtsAudio path diagnostics ---

    @Test
    fun ttsTriesPathsInOrderRecordingStatuses() {
        val seen = mutableListOf<Pair<String, Int>>()
        val got = selectTtsAudio(ttsRequestPaths("m1")) { path ->
            val status = if (path.startsWith("/api/")) 404 else 500
            seen.add(path.substringBefore('?') to status)
            status to ByteArray(0)
        }
        assertNull(got)
        assertEquals(
            listOf("/api/voice/tts-stream" to 404, "/voice/tts-stream" to 500),
            seen,
        )
    }
}
