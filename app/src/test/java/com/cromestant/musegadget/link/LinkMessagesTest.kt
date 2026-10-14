package com.cromestant.musegadget.link

import com.cromestant.musegadget.common.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkMessagesTest {
    @Test
    fun encodeDecodeRoundTripSplitFeeds() {
        val m1 = linkedMapOf<String, Any?>("type" to "req", "id" to "1", "method" to "link.register")
        val m2 = linkedMapOf<String, Any?>("type" to "req", "id" to "2", "method" to "link.result")
        val wire = encodeLinkMessage(m1) + encodeLinkMessage(m2)
        val decoder = LinkMessageDecoder()
        // Split mid-message: nothing complete yet.
        val first = decoder.feed(wire.copyOfRange(0, 10))
        assertTrue(first.isEmpty())
        val rest = decoder.feed(wire.copyOfRange(10, wire.size))
        assertEquals(2, rest.size)
        assertEquals("1", rest[0]["id"])
        assertEquals("2", rest[1]["id"])
    }

    @Test
    fun keepaliveSkipped() {
        val decoder = LinkMessageDecoder()
        // Zero-length prefix = keepalive.
        val out = decoder.feed(byteArrayOf(0, 0, 0, 0))
        assertTrue(out.isEmpty())
    }

    @Test
    fun oversizeLengthThrows() {
        val decoder = LinkMessageDecoder()
        // 4 MiB + 1 little-endian.
        val size = MAX_LINK_MESSAGE + 1
        val prefix = byteArrayOf(
            (size and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 24) and 0xFF).toByte()
        )
        try {
            decoder.feed(prefix)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
        try {
            encodeLinkMessage(mapOf("big" to "x".repeat(MAX_LINK_MESSAGE)))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun malformedAndNonDictDropped() {
        val decoder = LinkMessageDecoder()
        fun framed(raw: ByteArray): ByteArray {
            val n = raw.size
            return byteArrayOf(
                (n and 0xFF).toByte(),
                ((n ushr 8) and 0xFF).toByte(),
                0, 0
            ) + raw
        }
        val bad = framed("{not json".toByteArray(Charsets.UTF_8))
        val list = framed("[1,2]".toByteArray(Charsets.UTF_8))
        val good = encodeLinkMessage(mapOf("ok" to true))
        val out = decoder.feed(bad + list + good)
        assertEquals(1, out.size)
        assertEquals(true, out[0]["ok"])
    }

    @Test
    fun noiseUrlEncoding() {
        assertEquals(
            "wss://hatch.metaaivm.com/v1/noise?vm_id=vm%20id%2F1",
            noiseUrl("hatch.metaaivm.com", "vm id/1")
        )
        // Safe chars pass through; non-ASCII becomes UTF-8 percent triples.
        assertEquals(
            "wss://h/v1/noise?vm_id=-_.!~*'()%C3%A9",
            noiseUrl("h", "-_.!~*'()é")
        )
    }

    @Test
    fun registerParamsShape() {
        val params = registerParams("homelink-abc123", "Kitchen", "1.0", defaultCommandsV2)
        assertEquals(
            listOf(
                "node_id", "display_name", "platform", "version",
                "device_family", "model_id", "is_wakeup_supported", "commands_v2"
            ),
            params.keys.toList()
        )
        assertEquals("linux", params["platform"])
        assertEquals("homehub", params["device_family"])
        assertEquals("linux", params["model_id"])
        // No value anywhere may read as a link-family or OTA device marker.
        for ((key, value) in params) {
            if (value is String) {
                assertTrue("$key must not be link/device.ota", value != "link" && value != "device.ota")
            }
        }
        // Single health command with the ESP32 add_command shape.
        assertEquals(setOf("device.health"), defaultCommandsV2.keys)
        val health = defaultCommandsV2["device.health"]!!
        assertEquals(setOf("description", "required", "optional"), health.keys)
        assertTrue((health["description"] as String).isNotEmpty())

        val message = registerMessage("homelink-abc123", "Kitchen", "1.0", defaultCommandsV2, id = "fixed-id")
        assertEquals("req", message["type"])
        assertEquals("fixed-id", message["id"])
        assertEquals("link.register", message["method"])
        assertEquals(params, message["params"])
        // The full message survives a JSON round-trip.
        val decoded = Json.parse(Json.stringify(message)) as Map<*, *>
        assertEquals("link.register", decoded["method"])
    }
}
