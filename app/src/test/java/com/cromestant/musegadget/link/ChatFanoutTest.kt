package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Shared chat feed: one subscription fans out to background + voice taps,
 * late joiners get the replay buffer. Guards the dual-subscribe fix (two
 * `subscribeChat` calls on one session split events server-side, so neither
 * side completes).
 */
class ChatFanoutTest {

    private fun ev(id: String): Map<String, Any?> =
        mapOf("type" to "event", "event" to "delta.text_append", "id" to id)

    @Test
    fun livePublishReachesAllTaps() {
        val bus = ChatFanout()
        val bg = bus.subscribe()
        val voice = bus.subscribe()
        bus.publish(ev("m1"))
        assertEquals("m1", bg.poll(1, TimeUnit.SECONDS)?.get("id"))
        assertEquals("m1", voice.poll(1, TimeUnit.SECONDS)?.get("id"))
    }

    @Test
    fun lateJoinerGetsReplayThenLive() {
        val bus = ChatFanout()
        bus.publish(ev("m1"))
        bus.publish(ev("m2"))
        val late = bus.subscribe()
        assertEquals("m1", late.poll(1, TimeUnit.SECONDS)?.get("id"))
        assertEquals("m2", late.poll(1, TimeUnit.SECONDS)?.get("id"))
        bus.publish(ev("m3"))
        assertEquals("m3", late.poll(1, TimeUnit.SECONDS)?.get("id"))
    }

    @Test
    fun unsubscribedTapStopsReceiving() {
        val bus = ChatFanout()
        val tap = bus.subscribe()
        bus.unsubscribe(tap)
        bus.publish(ev("m1"))
        assertNull(tap.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun replayBufferIsCapped() {
        val bus = ChatFanout(replayCap = 2)
        bus.publish(ev("m1"))
        bus.publish(ev("m2"))
        bus.publish(ev("m3"))
        val late = bus.subscribe()
        assertEquals("m2", late.poll(1, TimeUnit.SECONDS)?.get("id"))
        assertEquals("m3", late.poll(1, TimeUnit.SECONDS)?.get("id"))
        assertNull(late.poll(200, TimeUnit.MILLISECONDS))
    }
}
