package dev.bananajeans.pitwall.protocol

/**
 * Minimal, dependency-free JSON reader/writer for Pocket Pitwall control messages.
 *
 * Both the phone and the watch run this exact code, so wire compatibility is
 * covered by one unit-test suite instead of two platform-specific parsers
 * (the phone app keeps org.json only for its local session.json files).
 *
 * Supported: objects, arrays, strings (with escapes), numbers, booleans and
 * null. Anything else throws [JsonException]. Deliberately no streaming,
 * duplication of keys, or arbitrary depth: messages are small and shaped by
 * [Messages].
 */
public object PitwallJson {

    public class JsonException(message: String) : Exception(message)

    /** Parsed document. Numbers are [Double]; integral values keep precision via Long when possible. */
    public sealed interface Value {
        public data class Object(val members: Map<String, Value>) : Value {
            public fun string(name: String): String? = (members[name] as? Str)?.value
            public fun number(name: String): Number? = (members[name] as? Num)?.let {
                if (it.isIntegral && Math.abs(it.value) <= Long.MAX_VALUE.toDouble()) it.value.toLong() else it.value
            }
            public fun bool(name: String): Boolean? = (members[name] as? Bool)?.value
            public fun obj(name: String): Object? = members[name] as? Object
            public fun array(name: String): Array? = members[name] as? Array
            override fun toString(): String = write(this)
        }
        public data class Array(val items: List<Value>) : Value {
            public val size: Int get() = items.size
            operator fun get(index: Int): Value = items[index]
            override fun toString(): String = write(this)
        }
        public data class Str(val value: String) : Value { override fun toString(): String = "\"$value\"" }
        public data class Num(val value: Double) : Value {
            val isIntegral: Boolean get() = value == Math.floor(value) && !value.isInfinite() && Math.abs(value) <= 9.007199254740992E15
            override fun toString(): String = if (isIntegral) value.toLong().toString() else value.toString()
        }
        public data class Bool(val value: Boolean) : Value { override fun toString(): String = value.toString() }
        public data object Null : Value { override fun toString(): String = "null" }
    }

    public fun parse(text: String): Value {
        val parser = Parser(text)
        val value = parser.parseDocument()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Trailing content after JSON document at offset ${parser.pos}")
        return value
    }

    public fun write(value: Value): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(out: StringBuilder, value: Value) {
        when (value) {
            is Value.Object -> {
                out.append('{')
                var first = true
                for ((k, v) in value.members) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k); out.append(':'); writeTo(out, v)
                }
                out.append('}')
            }
            is Value.Array -> {
                out.append('[')
                var first = true
                for (v in value.items) {
                    if (!first) out.append(',')
                    first = false
                    writeTo(out, v)
                }
                out.append(']')
            }
            is Value.Str -> writeString(out, value.value)
            is Value.Num -> out.append(value.toString())
            is Value.Bool -> out.append(value.value.toString())
            Value.Null -> out.append("null")
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }

    private class Parser(val text: String) {
        var pos = 0
        fun atEnd(): Boolean = pos >= text.length
        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }
        fun parseDocument(): Value {
            skipWhitespace()
            if (atEnd()) throw JsonException("Empty JSON document")
            return parseValue()
        }
        fun parseValue(): Value {
            if (atEnd()) throw JsonException("Unexpected end of JSON at offset $pos")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Value.Str(parseString())
                't' -> parseLiteral("true", Value.Bool(true))
                'f' -> parseLiteral("false", Value.Bool(false))
                'n' -> parseLiteral("null", Value.Null)
                else -> parseNumber()
            }
        }
        fun parseLiteral(literal: String, value: Value): Value {
            if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal)
                throw JsonException("Invalid literal at offset $pos")
            pos += literal.length
            return value
        }
        fun parseObject(): Value.Object {
            expect('{')
            val members = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (peek() == '}') { pos++; return Value.Object(members) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                members[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return Value.Object(members) }
                    else -> throw JsonException("Expected ',' or '}' at offset $pos")
                }
            }
        }
        fun parseArray(): Value.Array {
            expect('[')
            val items = ArrayList<Value>()
            skipWhitespace()
            if (peek() == ']') { pos++; return Value.Array(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return Value.Array(items) }
                    else -> throw JsonException("Expected ',' or ']' at offset $pos")
                }
            }
        }
        fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonException("Truncated unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("Invalid unicode escape \\u$hex")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Invalid escape \\$e at offset ${pos - 1}")
                        }
                    }
                    c < ' ' -> throw JsonException("Raw control character 0x${c.code.toString(16)} in string")
                    else -> out.append(c)
                }
            }
        }
        fun parseNumber(): Value.Num {
            val start = pos
            if (peek() == '-') pos++
            var digits = 0
            while (!atEnd() && text[pos].isDigit()) { pos++; digits++ }
            var isDouble = false
            if (!atEnd() && text[pos] == '.') {
                isDouble = true; pos++
                var fractionDigits = 0
                while (!atEnd() && text[pos].isDigit()) { pos++; fractionDigits++ }
                if (fractionDigits == 0) throw JsonException("Malformed number at offset $start")
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true; pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                var exponentDigits = 0
                while (!atEnd() && text[pos].isDigit()) { pos++; exponentDigits++ }
                if (exponentDigits == 0) throw JsonException("Malformed number at offset $start")
            }
            if (digits == 0) throw JsonException("Malformed number at offset $start")
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonException("Unparseable number '$raw' at offset $start")
            if (!value.isFinite()) throw JsonException("Non-finite number '$raw' at offset $start")
            return Value.Num(value)
        }
        fun peek(): Char {
            if (atEnd()) throw JsonException("Unexpected end of JSON at offset $pos")
            return text[pos]
        }
        fun expect(c: Char) {
            if (peek() != c) throw JsonException("Expected '$c' at offset $pos")
            pos++
        }
    }

    // Convenience builders used by the message classes.
    public fun obj(vararg pairs: Pair<String, Value>): Value.Object = Value.Object(LinkedHashMap<String, Value>().apply { pairs.forEach { put(it.first, it.second) } })
    public fun arr(items: List<Value>): Value.Array = Value.Array(items)
    public fun s(value: String): Value.Str = Value.Str(value)
    public fun n(value: Double): Value.Num = Value.Num(value)
    public fun n(value: Long): Value.Num = Value.Num(value.toDouble())
    public fun b(value: Boolean): Value.Bool = Value.Bool(value)
}
