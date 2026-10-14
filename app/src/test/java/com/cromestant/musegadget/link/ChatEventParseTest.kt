package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEventParseTest {
    @Test
    fun blankLineIsNull() {
        assertNull(parseChatEventLine(ByteArray(0)))
        assertNull(parseChatEventLine("\n".toByteArray()))
        assertNull(parseChatEventLine("   \n".toByteArray()))
    }

    @Test
    fun malformedJsonIsNull() {
        assertNull(parseChatEventLine("{nope".toByteArray()))
        assertNull(parseChatEventLine("[1,2]".toByteArray()))
    }

    @Test
    fun nonEventTypeIsNull() {
        assertNull(parseChatEventLine("""{"type":"other"}""".toByteArray()))
    }

    @Test
    fun validEventReturnsMap() {
        val line = """{"type":"event","event":"delta.text_append","payload":{"text":"hi"}}""".toByteArray()
        val ev = parseChatEventLine(line)
        assertEquals("event", ev?.get("type"))
        assertEquals("delta.text_append", ev?.get("event"))
    }
}
