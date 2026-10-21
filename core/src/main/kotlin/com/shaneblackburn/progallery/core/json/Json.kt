package com.shaneblackburn.progallery.core.json

/**
 * Minimal JSON reader/writer for ProGallery's own documents (saved video projects,
 * memories index). Values map to Kotlin types as:
 * object -> Map<String, Any?> (insertion ordered), array -> List<Any?>, string -> String,
 * number -> Double (Long when written from an integer), true/false -> Boolean, null -> null.
 *
 * Kept dependency-free so the pure-Kotlin core can be built and tested anywhere.
 */
object Json {

    class ParseException(message: String, val position: Int) : Exception("$message at $position")

    // ------------------------------------------------------------------ writing

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, v.asList())
            else -> throw IllegalArgumentException("Can't write ${v::class.simpleName} as JSON")
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        require(!d.isNaN() && !d.isInfinite()) { "JSON can't represent $d" }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ------------------------------------------------------------------ reading

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw ParseException("Unexpected trailing content", p.i)
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw ParseException("Unexpected end", i)
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Unexpected '$c'", i)
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Expected $word", i)
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("Expected key", i)
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw ParseException("Expected , or }", i)
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                skipWs()
                l += value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw ParseException("Expected , or ]", i)
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("Unterminated string", i)
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseException("Bad escape", i)
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("Bad unicode escape", i)
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseException("Bad unicode escape", i))
                                i += 4
                            }
                            else -> throw ParseException("Bad escape \\$e", i)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseException("Bad number", start)
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw ParseException("Unexpected end", i)
        private fun expect(c: Char) { if (peek() != c) throw ParseException("Expected '$c'", i); i++ }
    }
}

/** Typed, defaulting accessors for parsed JSON objects (missing or wrong-typed -> default). */
@Suppress("UNCHECKED_CAST")
class JsonObj(val map: Map<String, Any?>) {
    fun str(k: String, d: String = ""): String = map[k] as? String ?: d
    fun strOrNull(k: String): String? = map[k] as? String
    fun long(k: String, d: Long = 0L): Long = (map[k] as? Number)?.toLong() ?: d
    fun int(k: String, d: Int = 0): Int = (map[k] as? Number)?.toInt() ?: d
    fun float(k: String, d: Float = 0f): Float = (map[k] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: d
    fun bool(k: String, d: Boolean = false): Boolean = map[k] as? Boolean ?: d
    fun obj(k: String): JsonObj? = (map[k] as? Map<String, Any?>)?.let(::JsonObj)
    fun objs(k: String): List<JsonObj> = (map[k] as? List<*>)?.mapNotNull { (it as? Map<String, Any?>)?.let(::JsonObj) } ?: emptyList()
    fun has(k: String) = map.containsKey(k)

    companion object {
        fun parse(text: String): JsonObj =
            JsonObj(Json.parse(text) as? Map<String, Any?> ?: throw Json.ParseException("Not a JSON object", 0))
    }
}
