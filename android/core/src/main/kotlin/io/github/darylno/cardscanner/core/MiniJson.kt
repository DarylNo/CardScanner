package io.github.darylno.cardscanner.core

import java.math.BigInteger

/**
 * A minimal JSON reader/writer with PYTHON's value model, so ports of the
 * server's dict-walking code can mirror it line for line:
 *
 *  - objects -> [LinkedHashMap] (key order kept, like a Python dict),
 *    arrays -> [ArrayList], strings -> [String], true/false -> [Boolean],
 *    null -> null;
 *  - an integer literal (no `.`/`e`) -> [Long] (or [BigInteger] past 64 bits)
 *    and anything else -> [Double], exactly as `json.loads` splits int/float.
 *
 * Pure JVM (no org.json: Android's platform copy differs from the desktop jar
 * in number handling and key order, and :core must behave the same on both).
 */
object MiniJson {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("trailing data at ${p.i}")
        return v
    }

    /** Compact JSON. Doubles use Java's round-trip form (parses back to the same bits). */
    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Long, is Int, is BigInteger -> sb.append(v.toString())
            is Double -> { require(v.isFinite()) { "non-finite double" }; sb.append(v.toString()) }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k as String); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value: ${v::class}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++ }

        fun fail(what: String): Nothing = throw ParseException("$what at $i")

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++; ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected ',' or '}'") }
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> fail("expected ',' or ']'") }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isFloat = false
            if (i < s.length && s[i] == '.') { isFloat = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (t == "-" || t.isEmpty()) fail("bad number")
            return if (isFloat) t.toDouble() else (t.toLongOrNull() ?: BigInteger(t))
        }
    }
}

/**
 * Python semantics the ported server code leans on. Kept tiny and explicit so
 * each call site reads like the line it transliterates.
 */
internal object Py {
    /** `bool(v)` */
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Long -> v != 0L
        is Int -> v != 0
        is Double -> v != 0.0
        is BigInteger -> v.signum() != 0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** `isinstance(v, (int, float))` — bool IS an int in Python. */
    fun isNumber(v: Any?): Boolean = v is Long || v is Int || v is Double || v is BigInteger || v is Boolean

    /** `int(v)` for a number (truncates floats toward zero; True -> 1). */
    fun toInt(v: Any?): Long = when (v) {
        is Boolean -> if (v) 1L else 0L
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is BigInteger -> v.toLong()
        else -> throw IllegalArgumentException("not a number: $v")
    }

    /** `str(v)` for the scalar values these ports format. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is Boolean -> if (v) "True" else "False"
        else -> v.toString()
    }

    /** `a < b` / `a > b` for Python numbers (int vs float compared by value). */
    fun compareNum(a: Any?, b: Any?): Int {
        if ((a is Long || a is Int) && (b is Long || b is Int)) return (a as Number).toLong().compareTo((b as Number).toLong())
        return (a as Number).toDouble().compareTo((b as Number).toDouble()).let {
            // compareTo orders -0.0 < 0.0; Python treats them equal.
            if ((a.toDouble() == b.toDouble())) 0 else it
        }
    }

    /** `a - b` for Python numbers. */
    fun minus(a: Any?, b: Any?): Any =
        if ((a is Long || a is Int) && (b is Long || b is Int)) (a as Number).toLong() - (b as Number).toLong()
        else (a as Number).toDouble() - (b as Number).toDouble()

    /** `d.get(key, default)`: the stored value (even null) when the key exists. */
    fun get(m: Map<String, Any?>?, key: String, default: Any? = null): Any? =
        if (m != null && m.containsKey(key)) m[key] else default

    @Suppress("UNCHECKED_CAST")
    fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
}
