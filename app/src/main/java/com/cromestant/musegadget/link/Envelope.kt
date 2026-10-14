package com.cromestant.musegadget.link

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Service envelope codecs, mirroring
 * `ref/muse-gadget-sdk/linux/src/musegadget/noise/envelope.py`.
 * Field numbers match Python exactly; unknown fields are skipped on decode
 * and wrong wire types raise [ProtoError], just like the reference.
 */

/** Service ids, mirroring `ServiceType` in `envelope.py:41`. */
object ServiceType {
    const val DAEMON = 0
    const val SENTINEL = 1
    const val VAULT = 2
    const val AUTHD = 3
}

/** Reset codes, mirroring `ResetCode` in `envelope.py:48`. */
object ResetCode {
    const val UNSPECIFIED = 0
    const val CANCELLED = 1
    const val TIMEOUT = 2
    const val PROTOCOL_ERROR = 3
    const val REFUSED_STREAM = 4
    const val INTERNAL_ERROR = 5
    const val SERVICE_UNAVAILABLE = 6
}

/** Oneof discriminator for [ServiceFrame], mirroring the `kind` strings in `envelope.py:96-116`. */
enum class FrameKind {
    REQUEST, RESPONSE, BODY_CHUNK, RESET
}

/** HTTP header, mirroring `Header` in `envelope.py:58`. */
data class Header(val key: String = "", val value: String = "")

/**
 * Application-layer HTTP request, mirroring `ApplicationRequest` in
 * `envelope.py:64`. Plain class because ByteArray needs content equality.
 */
class ApplicationRequest(
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other is ApplicationRequest && verb == other.verb && path == other.path &&
            headers == other.headers && body.contentEquals(other.body) && endBody == other.endBody

    override fun hashCode(): Int {
        var r = verb.hashCode()
        r = 31 * r + path.hashCode()
        r = 31 * r + headers.hashCode()
        r = 31 * r + body.contentHashCode()
        r = 31 * r + endBody.hashCode()
        return r
    }
}

/**
 * Application-layer HTTP response, mirroring `ApplicationResponse` in
 * `envelope.py:73`.
 */
class ApplicationResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other is ApplicationResponse && status == other.status &&
            headers == other.headers && body.contentEquals(other.body) && endBody == other.endBody

    override fun hashCode(): Int {
        var r = status
        r = 31 * r + headers.hashCode()
        r = 31 * r + body.contentHashCode()
        r = 31 * r + endBody.hashCode()
        return r
    }
}

/** Streaming body fragment, mirroring `BodyChunk` in `envelope.py:81`. */
class BodyChunk(
    val data: ByteArray = ByteArray(0),
    val endBody: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other is BodyChunk && data.contentEquals(other.data) && endBody == other.endBody

    override fun hashCode(): Int = 31 * data.contentHashCode() + endBody.hashCode()
}

/** Stream reset, mirroring `Reset` in `envelope.py:87` (code is a [ResetCode] value). */
data class Reset(val code: Int = ResetCode.UNSPECIFIED, val reason: String = "")

/**
 * One framed application message on a stream, mirroring `ServiceFrame` in
 * `envelope.py:96`. `value` must match `kind` (request->ApplicationRequest, etc.).
 */
class ServiceFrame(val streamId: Long = 0, val kind: FrameKind? = null, val value: Any? = null) {
    companion object {
        fun request(id: Long, request: ApplicationRequest) = ServiceFrame(id, FrameKind.REQUEST, request)
        fun response(id: Long, response: ApplicationResponse) = ServiceFrame(id, FrameKind.RESPONSE, response)
        fun bodyChunk(id: Long, chunk: BodyChunk) = ServiceFrame(id, FrameKind.BODY_CHUNK, chunk)
        fun reset(id: Long, reset: Reset) = ServiceFrame(id, FrameKind.RESET, reset)
    }

    override fun equals(other: Any?): Boolean =
        other is ServiceFrame && streamId == other.streamId && kind == other.kind && value == other.value

    override fun hashCode(): Int {
        var r = streamId.hashCode()
        r = 31 * r + kind.hashCode()
        r = 31 * r + (value?.hashCode() ?: 0)
        return r
    }
}

/** Outer request wrapper selecting the service, mirroring `ServiceRequest` in `envelope.py:119`. */
class ServiceRequest(
    val service: Int = ServiceType.DAEMON,
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean =
        other is ServiceRequest && service == other.service && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * service + payload.contentHashCode()
}

/** Outer response wrapper, mirroring `ServiceResponse` in `envelope.py:125`. */
class ServiceResponse(val payload: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?): Boolean =
        other is ServiceResponse && payload.contentEquals(other.payload)

    override fun hashCode(): Int = payload.contentHashCode()
}

private fun decodeUtf8Strict(raw: ByteArray): String {
    if (raw.isEmpty()) return ""
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        val out: CharBuffer = decoder.decode(ByteBuffer.wrap(raw))
        return out.toString()
    } catch (e: CharacterCodingException) {
        throw ProtoError("invalid utf-8 string")
    }
}

private fun coerceService(value: Long): Int {
    if (value < 0 || value > ServiceType.AUTHD) throw ProtoError("unknown service type")
    return value.toInt()
}

private fun coerceResetCode(value: Int): Int {
    if (value < ResetCode.UNSPECIFIED || value > ResetCode.SERVICE_UNAVAILABLE) {
        throw ProtoError("unknown reset code")
    }
    return value
}

/** Header codec, mirroring `encode_header`/`decode_header` in `envelope.py:155,164`. */
fun encodeHeader(header: Header): ByteArray {
    var out = ByteArray(0)
    if (header.key.isNotEmpty()) out += stringField(1, header.key)
    if (header.value.isNotEmpty()) out += stringField(2, header.value)
    return out
}

/** Header codec, mirroring `encode_header`/`decode_header` in `envelope.py:155,164`. */
fun decodeHeader(data: ByteArray): Header {
    var key = ""
    var value = ""
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("Header.key wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                key = decodeUtf8Strict(raw)
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("Header.value wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                value = decodeUtf8Strict(raw)
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return Header(key, value)
}

/**
 * ApplicationRequest codec (fields 1:verb/2:path/3:headers/4:body/5:end_body,
 * omit-when-default), mirroring `encode/decode_application_request` in
 * `envelope.py:185,201`.
 */
fun encodeApplicationRequest(request: ApplicationRequest): ByteArray {
    var out = ByteArray(0)
    if (request.verb.isNotEmpty()) out += stringField(1, request.verb)
    if (request.path.isNotEmpty()) out += stringField(2, request.path)
    for (header in request.headers) out += delimitedField(3, encodeHeader(header))
    if (request.body.isNotEmpty()) out += bytesField(4, request.body)
    if (request.endBody) out += boolField(5, true)
    return out
}

/**
 * ApplicationRequest codec (fields 1:verb/2:path/3:headers/4:body/5:end_body,
 * omit-when-default), mirroring `encode/decode_application_request` in
 * `envelope.py:185,201`.
 */
fun decodeApplicationRequest(data: ByteArray): ApplicationRequest {
    var verb = ""
    var path = ""
    val headers = ArrayList<Header>()
    var body = ByteArray(0)
    var endBody = false
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationRequest.verb wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                verb = decodeUtf8Strict(raw)
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationRequest.path wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                path = decodeUtf8Strict(raw)
            }
            3 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationRequest.headers wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                headers.add(decodeHeader(raw))
            }
            4 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationRequest.body wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                body = raw
            }
            5 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("ApplicationRequest.end_body wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                endBody = raw != 0L
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return ApplicationRequest(verb, path, headers, body, endBody)
}

/**
 * ApplicationResponse codec (fields 1:status/2:headers/3:body/4:end_body),
 * mirroring `encode/decode_application_response` in `envelope.py:245,259`.
 */
fun encodeApplicationResponse(response: ApplicationResponse): ByteArray {
    var out = ByteArray(0)
    if (response.status != 0) out += int32Field(1, response.status)
    for (header in response.headers) out += delimitedField(2, encodeHeader(header))
    if (response.body.isNotEmpty()) out += bytesField(3, response.body)
    if (response.endBody) out += boolField(4, true)
    return out
}

/**
 * ApplicationResponse codec (fields 1:status/2:headers/3:body/4:end_body),
 * mirroring `encode/decode_application_response` in `envelope.py:245,259`.
 */
fun decodeApplicationResponse(data: ByteArray): ApplicationResponse {
    var status = 0
    val headers = ArrayList<Header>()
    var body = ByteArray(0)
    var endBody = false
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("ApplicationResponse.status wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                status = wireToInt32(raw)
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationResponse.headers wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                headers.add(decodeHeader(raw))
            }
            3 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ApplicationResponse.body wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                body = raw
            }
            4 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("ApplicationResponse.end_body wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                endBody = raw != 0L
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return ApplicationResponse(status, headers, body, endBody)
}

/**
 * BodyChunk codec (fields 1:data/2:end_body), mirroring
 * `encode/decode_body_chunk` in `envelope.py:296,306`.
 */
fun encodeBodyChunk(chunk: BodyChunk): ByteArray {
    var out = ByteArray(0)
    if (chunk.data.isNotEmpty()) out += bytesField(1, chunk.data)
    if (chunk.endBody) out += boolField(2, true)
    return out
}

/**
 * BodyChunk codec (fields 1:data/2:end_body), mirroring
 * `encode/decode_body_chunk` in `envelope.py:296,306`.
 */
fun decodeBodyChunk(data: ByteArray): BodyChunk {
    var chunkData = ByteArray(0)
    var endBody = false
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("BodyChunk.data wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                chunkData = raw
            }
            2 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("BodyChunk.end_body wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                endBody = raw != 0L
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return BodyChunk(chunkData, endBody)
}

/**
 * Reset codec (fields 1:code/2:reason, code omitted when UNSPECIFIED),
 * mirroring `encode/decode_reset` in `envelope.py:326,336`.
 */
fun encodeReset(reset: Reset): ByteArray {
    var out = ByteArray(0)
    val code = coerceResetCode(reset.code)
    if (code != ResetCode.UNSPECIFIED) out += int32Field(1, code)
    if (reset.reason.isNotEmpty()) out += stringField(2, reset.reason)
    return out
}

/**
 * Reset codec (fields 1:code/2:reason, code omitted when UNSPECIFIED),
 * mirroring `encode/decode_reset` in `envelope.py:326,336`.
 */
fun decodeReset(data: ByteArray): Reset {
    var code = ResetCode.UNSPECIFIED
    var reason = ""
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("Reset.code wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                code = coerceResetCode(wireToInt32(raw))
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("Reset.reason wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                reason = decodeUtf8Strict(raw)
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return Reset(code, reason)
}

/**
 * ServiceFrame codec (fields 1:stream_id/2:request/3:response/4:body_chunk/5:reset),
 * mirroring `encode/decode_service_frame` in `envelope.py:357,388`.
 */
fun encodeServiceFrame(frame: ServiceFrame): ByteArray {
    var out = ByteArray(0)
    if (frame.streamId != 0L) out += int64Field(1, frame.streamId)
    when (frame.kind) {
        null -> {
            if (frame.value != null) throw ProtoError("ServiceFrame value provided without kind")
        }
        FrameKind.REQUEST -> {
            val v = frame.value as? ApplicationRequest
                ?: throw ProtoError("ServiceFrame request value has wrong type")
            out += delimitedField(2, encodeApplicationRequest(v))
        }
        FrameKind.RESPONSE -> {
            val v = frame.value as? ApplicationResponse
                ?: throw ProtoError("ServiceFrame response value has wrong type")
            out += delimitedField(3, encodeApplicationResponse(v))
        }
        FrameKind.BODY_CHUNK -> {
            val v = frame.value as? BodyChunk
                ?: throw ProtoError("ServiceFrame body_chunk value has wrong type")
            out += delimitedField(4, encodeBodyChunk(v))
        }
        FrameKind.RESET -> {
            val v = frame.value as? Reset
                ?: throw ProtoError("ServiceFrame reset value has wrong type")
            out += delimitedField(5, encodeReset(v))
        }
    }
    return out
}

/**
 * ServiceFrame codec (fields 1:stream_id/2:request/3:response/4:body_chunk/5:reset),
 * mirroring `encode/decode_service_frame` in `envelope.py:357,388`.
 * A repeated oneof member keeps the last occurrence, like Python.
 */
fun decodeServiceFrame(data: ByteArray): ServiceFrame {
    var streamId = 0L
    var kind: FrameKind? = null
    var value: Any? = null
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("ServiceFrame.stream_id wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                streamId = wireToInt64(raw)
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceFrame.request wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                kind = FrameKind.REQUEST
                value = decodeApplicationRequest(raw)
            }
            3 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceFrame.response wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                kind = FrameKind.RESPONSE
                value = decodeApplicationResponse(raw)
            }
            4 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceFrame.body_chunk wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                kind = FrameKind.BODY_CHUNK
                value = decodeBodyChunk(raw)
            }
            5 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceFrame.reset wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                kind = FrameKind.RESET
                value = decodeReset(raw)
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return ServiceFrame(streamId, kind, value)
}

/**
 * ServiceRequest codec (fields 1:service/2:payload, service omitted when
 * DAEMON), mirroring `encode/decode_service_request` in `envelope.py:429,440`.
 */
fun encodeServiceRequest(request: ServiceRequest): ByteArray {
    var out = ByteArray(0)
    val service = coerceService(request.service.toLong())
    if (service != ServiceType.DAEMON) out += varintField(1, service.toLong())
    if (request.payload.isNotEmpty()) out += bytesField(2, request.payload)
    return out
}

/**
 * ServiceRequest codec (fields 1:service/2:payload, service omitted when
 * DAEMON), mirroring `encode/decode_service_request` in `envelope.py:429,440`.
 */
fun decodeServiceRequest(data: ByteArray): ServiceRequest {
    var service = ServiceType.DAEMON
    var payload = ByteArray(0)
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_VARINT) throw ProtoError("ServiceRequest.service wrong wire type")
                val (raw, next) = decodeVarint(data, offset)
                offset = next
                service = coerceService(wireToUint32(raw))
            }
            2 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceRequest.payload wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                payload = raw
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return ServiceRequest(service, payload)
}

/**
 * ServiceResponse codec (field 1:payload, empty encodes to empty),
 * mirroring `encode/decode_service_response` in `envelope.py:460,467`.
 */
fun encodeServiceResponse(response: ServiceResponse): ByteArray {
    if (response.payload.isEmpty()) return ByteArray(0)
    return bytesField(1, response.payload)
}

/**
 * ServiceResponse codec (field 1:payload, empty encodes to empty),
 * mirroring `encode/decode_service_response` in `envelope.py:460,467`.
 */
fun decodeServiceResponse(data: ByteArray): ServiceResponse {
    var payload = ByteArray(0)
    var offset = 0
    while (offset < data.size) {
        val (fieldNumber, wireType, off) = readKey(data, offset)
        offset = off
        when (fieldNumber) {
            1 -> {
                if (wireType != WIRE_DELIMITED) throw ProtoError("ServiceResponse.payload wrong wire type")
                val (raw, next) = readDelimited(data, offset)
                offset = next
                payload = raw
            }
            else -> offset = skipField(data, offset, wireType)
        }
    }
    return ServiceResponse(payload)
}
