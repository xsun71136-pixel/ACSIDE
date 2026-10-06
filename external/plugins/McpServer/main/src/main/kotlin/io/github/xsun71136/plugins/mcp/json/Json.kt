package io.github.xsun71136.plugins.mcp.json

/**
 * Minimal, dependency-free JSON codec.
 *
 * The plugin must not bundle a JSON library: the IDE already ships one and a
 * duplicated copy could be resolved from the wrong classloader. Parsed values
 * use plain Kotlin types only:
 *
 *   object  -> Map<String, Any?>   (LinkedHashMap, insertion ordered)
 *   array   -> List<Any?>
 *   string  -> String
 *   number  -> Long when integral and small enough, otherwise Double
 *   true/false -> Boolean
 *   null    -> null
 */
object Json {

    // ---------------------------------------------------------------- parse

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        return v
    }

    /** Same as [parse] but returns null instead of throwing on malformed input. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (t: Throwable) {
        null
    }

    // -------------------------------------------------------------- stringify

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    /** Human readable form used by the Output & Debugging console and by tool results. */
    fun pretty(value: Any?, indent: Int = 0): String {
        val sb = StringBuilder()
        writePretty(sb, value, indent)
        return sb.toString()
    }

    // -------------------------------------------------------------- builders

    fun obj(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>(pairs.size * 2)
        for (p in pairs) m[p.first] = p.second
        return m
    }

    fun arr(vararg items: Any?): MutableList<Any?> {
        val l = ArrayList<Any?>(items.size)
        for (i in items) l.add(i)
        return l
    }

    /** JSON Schema helper for tool input schemas. */
    fun schema(props: List<Triple<String, Any?, String?>>, required: List<String> = emptyList()): Map<String, Any?> {
        val p = LinkedHashMap<String, Any?>()
        for ((name, type, desc) in props) {
            val def = LinkedHashMap<String, Any?>()
            when (type) {
                is String -> def["type"] = type
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") def.putAll(type as Map<String, Any?>)
                is List<*> -> def["type"] = type
            }
            if (desc != null) def["description"] = desc
            p[name] = def
        }
        val s = LinkedHashMap<String, Any?>()
        s["type"] = "object"
        s["properties"] = p
        if (required.isNotEmpty()) s["required"] = required
        s["additionalProperties"] = false
        return s
    }

    // ------------------------------------------------------------ accessors

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): Map<String, Any?> = if (v is Map<*, *>) v as Map<String, Any?> else emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(v: Any?): List<Any?> = if (v is List<*>) v as List<Any?> else emptyList()

    fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Boolean -> v.toString()
        is Long -> v.toString()
        is Double -> numToStr(v)
        else -> v.toString()
    }

    fun strOf(m: Map<String, Any?>, key: String, default: String? = null): String? = str(m[key]) ?: default

    fun reqStr(m: Map<String, Any?>, key: String): String =
        str(m[key]) ?: throw IllegalArgumentException("missing required string argument '$key'")

    fun boolOf(m: Map<String, Any?>, key: String, default: Boolean = false): Boolean = when (val v = m[key]) {
        null -> default
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1"
        is Number -> v.toDouble() != 0.0
        else -> default
    }

    fun longOf(m: Map<String, Any?>, key: String, default: Long = 0L): Long = when (val v = m[key]) {
        null -> default
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: default
        else -> default
    }

    fun intOf(m: Map<String, Any?>, key: String, default: Int = 0): Int = longOf(m, key, default.toLong()).toInt()

    fun dblOf(m: Map<String, Any?>, key: String, default: Double = 0.0): Double = when (val v = m[key]) {
        null -> default
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: default
        else -> default
    }

    fun strList(m: Map<String, Any?>, key: String): List<String> {
        val v = m[key] ?: return emptyList()
        if (v is String) return if (v.isBlank()) emptyList() else v.split(Regex("[,\\n;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        return asList(v).mapNotNull { str(it)?.trim() }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- write

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(numToStr(v))
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, vv)
                }
                sb.append('}')
            }
            is Array<*> -> write(sb, v.toList())
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writePretty(sb: StringBuilder, v: Any?, depth: Int) {
        val pad = "  ".repeat(depth)
        val padIn = "  ".repeat(depth + 1)
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, vv) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    writeString(sb, k.toString())
                    sb.append(": ")
                    writePretty(sb, vv, depth + 1)
                }
                sb.append('\n').append(pad).append('}')
            }
            is Iterable<*> -> {
                val it = v.iterator()
                if (!it.hasNext()) { sb.append("[]"); return }
                sb.append("[\n")
                var first = true
                while (it.hasNext()) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    writePretty(sb, it.next(), depth + 1)
                }
                sb.append('\n').append(pad).append(']')
            }
            else -> write(sb, v)
        }
    }

    private fun numToStr(n: Number): String {
        if (n is Double || n is Float) {
            val d = n.toDouble()
            if (d.isNaN() || d.isInfinite()) return "null"
            if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1.0e15) return d.toLong().toString()
            return d.toString()
        }
        return n.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ' || ch == '\u2028' || ch == '\u2029') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    // --------------------------------------------------------------- parser

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of JSON input")
            val c = s[i]
            if (c == '{') return readObject()
            if (c == '[') return readArray()
            if (c == '"') return readString()
            if (c == 't') { expect("true"); return true }
            if (c == 'f') { expect("false"); return false }
            if (c == 'n') { expect("null"); return null }
            return readNumber()
        }

        private fun readObject(): Map<String, Any?> {
            i++ // '{'
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected ':' at $i")
                i++
                val v = readValue()
                m[key] = v
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // '['
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(readValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $i")
                }
            }
        }

        private fun readString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i]
                if (c == '"') { i++; return sb.toString() }
                if (c != '\\') { sb.append(c); i++; continue }
                i++
                if (i >= s.length) throw IllegalArgumentException("unterminated escape")
                when (val e = s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) throw IllegalArgumentException("bad unicode escape")
                        val hex = s.substring(i + 1, i + 5)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("invalid escape \\$e")
                }
                i++
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var isFloat = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') { i++; continue }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') { isFloat = isFloat || c == '.' || c == 'e' || c == 'E'; i++; continue }
                break
            }
            val text = s.substring(start, i)
            if (text.isEmpty()) throw IllegalArgumentException("invalid number at $start")
            if (!isFloat) {
                text.toLongOrNull()?.let { return it }
            }
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number '$text'")
        }

        private fun expect(literal: String) {
            if (i + literal.length > s.length || s.substring(i, i + literal.length) != literal) {
                throw IllegalArgumentException("expected '$literal' at $i")
            }
            i += literal.length
        }
    }
}
