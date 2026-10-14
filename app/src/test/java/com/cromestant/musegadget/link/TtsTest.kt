package com.cromestant.musegadget.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private fun ttsEvent(name: String, payload: Map<String, Any?>): Map<String, Any?> =
    linkedMapOf("type" to "event", "event" to name, "payload" to payload)

class TtsTest {
    @Test
    fun ttsPathsPrimaryAndFallback() {
        assertEquals(
            listOf(
                "/api/voice/tts-stream?message_id=abc123",
                "/voice/tts-stream?message_id=abc123",
            ),
            ttsRequestPaths("abc123"),
        )
    }

    @Test
    fun ttsPathsEncodeMessageId() {
        assertEquals(
            listOf(
                "/api/voice/tts-stream?message_id=a%20b%2Fc%3F",
                "/voice/tts-stream?message_id=a%20b%2Fc%3F",
            ),
            ttsRequestPaths("a b/c?"),
        )
    }

    @Test
    fun selectTtsPrefersPrimaryWithoutFallbackCall() {
        val calls = mutableListOf<String>()
        val mp3 = byteArrayOf(1, 2, 3)
        val got = selectTtsAudio(ttsRequestPaths("m1")) { path ->
            calls.add(path)
            200 to mp3
        }
        assertArrayEquals(mp3, got)
        assertEquals(1, calls.size)
    }

    @Test
    fun selectTtsFallsBackOnPrimaryFailure() {
        val mp3 = byteArrayOf(4, 5)
        val got = selectTtsAudio(ttsRequestPaths("m1")) { path ->
            if (path.startsWith("/api/")) 500 to ByteArray(0) else 200 to mp3
        }
        assertArrayEquals(mp3, got)
    }

    @Test
    fun selectTtsSkipsThrowingAndEmptyAttempts() {
        var n = 0
        val got = selectTtsAudio(ttsRequestPaths("m1")) {
            n++
            if (n == 1) throw IOException("boom")
            200 to ByteArray(0) // empty body counts as failure
        }
        assertNull(got)
        assertEquals(2, n)
    }

    @Test
    fun selectTtsAllFailReturnsNull() {
        val got = selectTtsAudio(ttsRequestPaths("m1")) { 404 to ByteArray(0) }
        assertNull(got)
    }

    @Test
    fun trackerDoneMessageIdsInArrivalOrder() {
        val tracker = MuseTurnTracker()
        val start1 = ttsEvent("delta.message_start", mapOf("message_id" to "m1", "reply_to_message_id" to "u1"))
        val start2 = ttsEvent("delta.message_start", mapOf("message_id" to "m2", "reply_to_message_id" to "u1"))
        tracker.feed(start1)
        tracker.feed(start2)
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        // Neither reply done yet.
        assertTrue(tracker.doneMessageIds().isEmpty())
        tracker.feed(
            ttsEvent(
                "delta.message_done",
                mapOf("message_id" to "m2", "reply_to_message_id" to "u1", "display_text" to "second"),
            ),
        )
        assertEquals(listOf("m2"), tracker.doneMessageIds())
        tracker.feed(
            ttsEvent(
                "delta.message_done",
                mapOf("message_id" to "m1", "reply_to_message_id" to "u1", "display_text" to "first"),
            ),
        )
        // Arrival order, not completion order: m1 arrived first.
        assertEquals(listOf("m1", "m2"), tracker.doneMessageIds())
    }
}
