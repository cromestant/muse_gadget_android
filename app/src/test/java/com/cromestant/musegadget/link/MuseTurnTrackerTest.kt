package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun event(name: String, payload: Map<String, Any?>): Map<String, Any?> =
    linkedMapOf("type" to "event", "event" to name, "payload" to payload)

class MuseTurnTrackerTest {
    private val start = event(
        "delta.message_start",
        mapOf("message_id" to "m1", "reply_to_message_id" to "u1")
    )
    private val append1 = event("delta.text_append", mapOf("message_id" to "m1", "text" to "Hello "))
    private val append2 = event("delta.text_append", mapOf("message_id" to "m1", "text" to "there"))
    private val done = event(
        "delta.message_done",
        mapOf("message_id" to "m1", "reply_to_message_id" to "u1", "display_text" to "Hello there!")
    )
    private val taskDone = event("task.status", mapOf("status" to "completed"))

    @Test
    fun heldEventsReplayOnAcknowledge() {
        val tracker = MuseTurnTracker()
        // Before acknowledge: everything is held, nothing emitted.
        assertTrue(tracker.feed(start).isEmpty())
        assertTrue(tracker.feed(append1).isEmpty())
        assertTrue(tracker.feed(append2).isEmpty())
        assertTrue(tracker.feed(done).isEmpty())
        assertFalse(tracker.hasReplies())

        val replayed = tracker.acknowledge(
            mapOf("result" to mapOf("message_id" to "u1", "reply_to_message_id" to "u0"))
        )
        // start, append, append, done each emit the current Reply state.
        assertEquals(4, replayed.size)
        val last = replayed.last() as TurnEvent.Reply
        assertEquals("m1", last.messageId)
        assertEquals("Hello there!", last.text)
        assertEquals("Hello there!", tracker.finalText())
        assertTrue(tracker.hasReplies())
        assertTrue(last.done)
    }

    @Test
    fun completeNeedsQuietOrTaskFinished() {
        val tracker = MuseTurnTracker()
        tracker.feed(start)
        tracker.feed(append1)
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        // Reply m1 is not done (only start + one append seen): never complete.
        assertFalse(tracker.complete(tracker.lastActivityMs + 10_000))

        tracker.feed(append2)
        tracker.feed(done)
        val quiet = tracker.lastActivityMs
        assertFalse(tracker.complete(quiet + 1000))
        assertTrue(tracker.complete(quiet + 3000))

        // Terminal task status emits TaskFinished once and completes immediately.
        val first = tracker.feed(taskDone)
        assertEquals(1, first.size)
        assertTrue(first[0] is TurnEvent.TaskFinished)
        assertTrue(tracker.feed(event("task.status", mapOf("status" to "completed"))).isEmpty())
        assertTrue(tracker.complete(tracker.lastActivityMs))
    }

    @Test
    fun unrelatedParentIgnored() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        val stranger = event(
            "delta.message_start",
            mapOf("message_id" to "mx", "reply_to_message_id" to "someone-else")
        )
        assertTrue(tracker.feed(stranger).isEmpty())
        // Even a later parentless event for mx stays dropped.
        assertTrue(
            tracker.feed(event("delta.text_append", mapOf("message_id" to "mx", "text" to "x")))
                .isEmpty()
        )
        assertFalse(tracker.hasReplies())
        assertEquals("", tracker.finalText())
    }

    @Test
    fun transcriptFallback() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("message_id" to "u1"))
        val transcript = mapOf(
            "messages" to listOf(
                mapOf(
                    "role" to "assistant",
                    "id" to "m2",
                    "content" to listOf(mapOf("type" to "text", "text" to "Hi"))
                )
            )
        )
        val out = tracker.feed(
            event(
                "message.assistant",
                mapOf("message_id" to "m2", "reply_to_message_id" to "u1", "transcript" to transcript)
            )
        )
        assertEquals(1, out.size)
        val reply = out[0] as TurnEvent.Reply
        assertEquals("Hi", reply.text)
        assertTrue(reply.done)
        assertEquals("Hi", tracker.finalText())
    }

    @Test
    fun acknowledgeRequiresUserId() {
        val tracker = MuseTurnTracker()
        try {
            tracker.acknowledge(mapOf("result" to mapOf("message_id" to "")))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
        try {
            tracker.acknowledge(mapOf("result" to mapOf<String, Any?>()))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun ownedChatBindsSelfParentedReply() {
        val tracker = MuseTurnTracker(sessionId = "side", ownsChat = true)
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1", "session_id" to "side")))
        val out = tracker.feed(
            event(
                "delta.message_done",
                mapOf(
                    "message_id" to "m1", "reply_to_message_id" to "m1",
                    "display_text" to "Hi", "session_id" to "side",
                ),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("Hi", (out[0] as TurnEvent.Reply).text)
        assertEquals("Hi", tracker.finalText())
    }

    @Test
    fun unownedChatStillDropsSelfParentedReply() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1")))
        assertTrue(
            tracker.feed(
                event(
                    "delta.message_done",
                    mapOf("message_id" to "m1", "reply_to_message_id" to "m1", "display_text" to "Hi"),
                ),
            ).isEmpty()
        )
        assertEquals("", tracker.finalText())
    }

    @Test
    fun ownedChatDropsForeignSessionEvents() {
        val tracker = MuseTurnTracker(sessionId = "side", ownsChat = true)
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1", "session_id" to "side")))
        assertTrue(
            tracker.feed(
                event(
                    "delta.message_done",
                    mapOf(
                        "message_id" to "m1", "reply_to_message_id" to "u1",
                        "display_text" to "Hi", "session_id" to "other",
                    ),
                ),
            ).isEmpty()
        )
        assertEquals("", tracker.finalText())
    }

    @Test
    fun sessionLearnedFromAckFiltersOtherChats() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1", "session_id" to "learned")))
        assertEquals("learned", tracker.sessionId)
        assertTrue(
            tracker.feed(
                event(
                    "delta.message_done",
                    mapOf(
                        "message_id" to "m1", "reply_to_message_id" to "u1",
                        "display_text" to "No", "session_id" to "other",
                    ),
                ),
            ).isEmpty()
        )
        val out = tracker.feed(
            event(
                "delta.message_done",
                mapOf(
                    "message_id" to "m2", "reply_to_message_id" to "u1",
                    "display_text" to "Yes", "session_id" to "learned",
                ),
            ),
        )
        assertEquals("Yes", (out[0] as TurnEvent.Reply).text)
    }

    @Test
    fun ownedChatBindsParentlessReplyOnItsSession() {
        val tracker = MuseTurnTracker(sessionId = "side", ownsChat = true)
        tracker.acknowledge(mapOf("result" to mapOf("message_id" to "u1", "session_id" to "side")))
        val out = tracker.feed(
            event(
                "delta.message_done",
                mapOf("message_id" to "m1", "display_text" to "Hello", "session_id" to "side"),
            ),
        )
        assertEquals("Hello", (out[0] as TurnEvent.Reply).text)
    }

    @Test
    fun agentStatusAndBusy() {
        val tracker = MuseTurnTracker()
        tracker.acknowledge(mapOf("message_id" to "u1"))
        val status = tracker.feed(
            event("agent.status", mapOf("activity_code" to "thinking", "activity_text" to "Thinking"))
        )
        assertEquals(1, status.size)
        val agent = status[0] as TurnEvent.AgentStatus
        assertEquals("thinking", agent.code)
        assertEquals("Thinking", agent.text)
        // Busy with no replies: not complete even after quiet.
        assertFalse(tracker.complete(tracker.lastActivityMs + 60_000))
        tracker.feed(event("agent.status", mapOf("activity_code" to "idle")))
        assertTrue(tracker.complete(tracker.lastActivityMs + 3000))
    }
}
