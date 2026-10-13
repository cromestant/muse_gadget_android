package com.cromestant.musegadget.common

/** Thrown on any malformed JSON input. */
class JsonParseException(msg: String) : RuntimeException(msg)

/**
 * Minimal deterministic JSON, mirroring Python's json.dumps(separators=(",", ":"))
 * and json.loads. No dependencies. Pure JVM: no Android imports.
 */
object Json {
    /** Compact stringify: separators exactly "," and ":". */
    fun stringify(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> appendString(sb, value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> sb.append(value.toString())
            is Float -> sb.append(value.toDouble().toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k !is String) throw IllegalArgumentException("JSON object keys must be strings")
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k)
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (e in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, e)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value: ${value::class}")
        }
    }

    /**
     * String escaping matching Python's default ensure_ascii=True:
     * `\"` and `\\`, control chars < 0x20 as \b \f \n \r \t or \u00xx,
     * every char >= 0x7F as \uXXXX (surrogate pairs for astral chars).
     */
    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ' || c >= '\u007F') {
                    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                        val cp = Character.toCodePoint(c, s[i + 1])
                        val v = cp - 0x10000
                        escapeU(sb, (0xD800 + (v shr 10)).toChar())
                        escapeU(sb, (0xDC00 + (v and 0x3FF)).toChar())
                        i++
                    } else {
                        escapeU(sb, c)
                    }
                } else {
                    sb.append(c)
                }
            }
            i++
        }
        sb.append('"')
    }

    private fun escapeU(sb: StringBuilder, c: Char) {
        sb.append("\\u")
        val v = c.code
        for (shift in 12 downTo 0 step 4) {
            sb.append("0123456789abcdef"[(v shr shift) and 0xF])
        }
    }

    /** Recursive-descent parse, max depth 64. Integral numbers become Long. */
    fun parse(text: String): Any? = Parser(text).parseTop()

    private class Parser(val text: String) {
        var pos = 0

        fun parseTop(): Any? {
            val v = parseValue(0)
            skipWs()
            if (pos != text.length) throw JsonParseException("trailing characters at $pos")
            return v
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 64) throw JsonParseException("nesting too deep")
            skipWs()
            if (pos >= text.length) throw JsonParseException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("unexpected character '$c' at $pos")
            }
        }

        private fun parseObject(depth: Int): LinkedHashMap<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') {
                    throw JsonParseException("expected string key at $pos")
                }
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') {
                    throw JsonParseException("expected ':' at $pos")
                }
                pos++
                map[key] = parseValue(depth)
                skipWs()
                if (pos >= text.length) throw JsonParseException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(depth: Int): ArrayList<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWs()
                if (pos >= text.length) throw JsonParseException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonParseException("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw JsonParseException("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonParseException("bad \\u escape")
                                val hi = hex4(pos)
                                pos += 4
                                if (hi.isHighSurrogate() && pos + 6 <= text.length &&
                                    text[pos] == '\\' && text[pos + 1] == 'u'
                                ) {
                                    val lo = hex4(pos + 2)
                                    if (lo.isLowSurrogate()) {
                                        sb.appendCodePoint(Character.toCodePoint(hi, lo))
                                        pos += 6
                                        continue
                                    }
                                }
                                sb.append(hi)
                            }
                            else -> throw JsonParseException("bad escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> {
                        if (c < ' ') throw JsonParseException("unescaped control char at ${pos - 1}")
                        sb.append(c)
                    }
                }
            }
        }

        private fun hex4(at: Int): Char {
            var v = 0
            for (i in 0 until 4) {
                val d = Character.digit(text[at + i], 16)
                if (d < 0) throw JsonParseException("bad \\u escape at $at")
                v = v * 16 + d
            }
            return v.toChar()
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw JsonParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun parseNumber(): Any? {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length) throw JsonParseException("bad number at $start")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                while (pos < text.length && text[pos] in '0'..'9') pos++
            } else {
                throw JsonParseException("bad number at $start")
            }
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') {
                    throw JsonParseException("bad fraction at $start")
                }
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') {
                    throw JsonParseException("bad exponent at $start")
                }
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            return if (!isDouble) {
                raw.toLongOrNull() ?: raw.toDoubleOrNull()
                    ?: throw JsonParseException("bad number at $start")
            } else {
                raw.toDoubleOrNull() ?: throw JsonParseException("bad number at $start")
            }
        }

        private fun skipWs() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                text[pos] == '\n' || text[pos] == '\r')
            ) {
                pos++
            }
        }
    }
}
