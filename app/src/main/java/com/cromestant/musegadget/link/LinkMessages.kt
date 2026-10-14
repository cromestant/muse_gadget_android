package com.cromestant.musegadget.link

import com.cromestant.musegadget.common.Json
import com.cromestant.musegadget.common.JsonParseException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control-stream JSON messages and device registration, mirroring the
 * non-network parts of
 * `ref/muse-gadget-sdk/linux/src/musegadget/link_client.py`.
 * Both directions of `/link-control` carry JSON messages each prefixed with
 * a little-endian u32 length (see `link_client.py:17-29`).
 */

/** Inbound/outbound message size cap, mirroring `MAX_INBOUND_MESSAGE` in `link_client.py:61`. */
const val MAX_LINK_MESSAGE = 4 * 1024 * 1024

/**
 * Length-prefixed compact-JSON encode, mirroring `encode_message` in
 * `link_client.py:101`. Maps must be insertion-ordered (`linkedMapOf`/`mapOf`)
 * so `Json.stringify` emits deterministic output. Messages over 4 MiB raise
 * [IllegalArgumentException] (mirrors the decode cap, applied to encode too).
 */
fun encodeLinkMessage(value: Map<String, Any?>): ByteArray {
    val json = Json.stringify(value).toByteArray(Charsets.UTF_8)
    if (json.size > MAX_LINK_MESSAGE) {
        throw IllegalArgumentException("outbound message too large: ${json.size}")
    }
    val out = ByteBuffer.allocate(4 + json.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(json.size)
    out.put(json)
    return out.array()
}

/**
 * Control-stream splitter, mirroring `MessageDecoder` in `link_client.py:106`.
 * A message may span chunks, so bytes buffer until complete. Empty payloads
 * are keepalives (skipped); malformed JSON and non-dict JSON are dropped
 * without throwing; an over-cap length raises [IllegalArgumentException].
 */
class LinkMessageDecoder {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<Map<String, Any?>> {
        buf += data
        val messages = ArrayList<Map<String, Any?>>()
        while (buf.size >= 4) {
            val length = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            // Length is an unsigned u32; a negative Int means >= 2^31, always over any sane use.
            val unsigned = length.toLong() and 0xFFFFFFFFL
            if (unsigned > MAX_LINK_MESSAGE) {
                throw IllegalArgumentException("inbound message too large: $unsigned")
            }
            val len = length // 0..MAX fits in Int
            if (buf.size < 4 + len) break
            val raw = buf.copyOfRange(4, 4 + len)
            buf = buf.copyOfRange(4 + len, buf.size)
            if (raw.isEmpty()) continue // keepalive
            val text = try {
                strictUtf8(raw)
            } catch (e: ProtoError) {
                continue // bytes that aren't UTF-8, like Python's ValueError path
            }
            val message = try {
                Json.parse(text)
            } catch (e: JsonParseException) {
                continue // bad JSON
            }
            if (message is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                messages.add(message as Map<String, Any?>)
            }
        }
        return messages
    }

    private fun strictUtf8(raw: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ProtoError("invalid utf-8 string")
        }
    }
}

/**
 * Noise upgrade URL, mirroring `noise_url` in `link_client.py:138`.
 * Percent-encodes every UTF-8 byte except `A-Za-z0-9` and `-_.!~*'()`
 * (mirrors Python `quote(vm_id, safe="-_.!~*'()")`, same as `encodeURIComponent`).
 */
fun noiseUrl(noiseHost: String, vmId: String): String {
    val safe = "-_.!~*'()"
    val sb = StringBuilder()
    for (byte in vmId.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || safe.indexOf(ch) >= 0) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append("0123456789ABCDEF"[c ushr 4])
            sb.append("0123456789ABCDEF"[c and 0xF])
        }
    }
    return "wss://$noiseHost/v1/noise?vm_id=$sb"
}

/**
 * Registration params, mirroring `DeviceDescription.register_params` in
 * `link_client.py:88`. Keys in this exact order: node_id, display_name,
 * platform, version, device_family, model_id, is_wakeup_supported, commands_v2.
 *
 * WARNING: NEVER register as platform/link, device_family link, or model
 * device.ota — the server pushes ESP32 OTA firmware to every "link" device
 * (see `link_client.py` docs and `ref/.../esp32` firmware).
 */
fun registerParams(
    nodeId: String,
    displayName: String,
    version: String,
    commandsV2: Map<String, Map<String, Any?>>
): Map<String, Any?> {
    val params = LinkedHashMap<String, Any?>()
    params["node_id"] = nodeId
    params["display_name"] = displayName
    params["platform"] = "linux"
    params["version"] = version
    params["device_family"] = "homehub"
    params["model_id"] = "linux"
    params["is_wakeup_supported"] = false
    params["commands_v2"] = commandsV2
    return params
}

/**
 * The single command we register.
 *
 * Choice rationale: this is a PTT-only gadget — the Linux CLI's shell/file
 * commands (system.run, file.read, file.write) must never be exposed from an
 * unattended appliance. The ESP32 firmware always registers `device.health`
 * (see `add_command(commands, "device.health", ...)` at
 * `ref/muse-gadget-sdk/esp32/main/noise_control.cpp:1362`; shape built by
 * `add_command` at `:1311` with `description`/`required`/`optional` keys).
 * Which `commands_v2` entries Muse requires before routing voice is still an
 * open unknown (PROTOCOL_RECON.md section 10), so register the minimal set
 * now and expand after the first live test.
 */
val defaultCommandsV2: Map<String, Map<String, Any?>> = mapOf(
    "device.health" to mapOf(
        "description" to "Report basic device health: model, firmware version, and whether " +
            "the gadget link is up. This device is mains-powered with no battery; " +
            "battery fields are null.",
        "required" to emptyMap<String, Any?>(),
        "optional" to emptyMap<String, Any?>()
    )
)

/**
 * Full `link.register` request message, mirroring the register send in
 * `LinkSession._open_control_stream` (`link_client.py:257-267`):
 * `{type:"req", id, method:"link.register", params: registerParams(...)}`.
 */
fun registerMessage(
    nodeId: String,
    displayName: String,
    version: String,
    commandsV2: Map<String, Map<String, Any?>> = defaultCommandsV2,
    id: String = java.util.UUID.randomUUID().toString()
): Map<String, Any?> {
    val message = LinkedHashMap<String, Any?>()
    message["type"] = "req"
    message["id"] = id
    message["method"] = "link.register"
    message["params"] = registerParams(nodeId, displayName, version, commandsV2)
    return message
}
