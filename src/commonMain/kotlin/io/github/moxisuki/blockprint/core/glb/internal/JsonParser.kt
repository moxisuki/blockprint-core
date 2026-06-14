package io.github.moxisuki.blockprint.core.glb.internal

internal object JsonParser {

    fun parse(json: String): Any? {
        val reader = Reader(json)
        reader.skipWhitespace()
        return reader.readValue()
    }

    fun parseObject(json: String): Map<String, Any?> {
        val result = parse(json)
        return result as? Map<String, Any?> ?: emptyMap()
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // skip {
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                check(i < s.length && s[i] == ':') { "Expected ':' at $i" }
                i++ // skip :
                map[key] = readValue()
                skipWhitespace()
                if (i >= s.length) break
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; break }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++ // skip [
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                if (i >= s.length) break
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; break }
            }
            return list
        }

        private fun readString(): String {
            check(i < s.length && s[i] == '"') { "Expected '\"' at $i" }
            i++ // skip opening "
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    check(i < s.length) { "Unterminated escape at $i" }
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(s[i])
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            throw IllegalArgumentException("Unterminated string at $i")
        }

        private fun readBoolean(): Boolean {
            return if (s.startsWith("true", i)) {
                i += 4; true
            } else {
                i += 5; false
            }
        }

        private fun readNull(): Any? {
            i += 4; return null
        }

        private fun readNumber(): Number {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val numStr = s.substring(start, i)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E'))
                numStr.toDouble() else numStr.toInt()
        }
    }

    fun Any?.asObject(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
    fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
    fun Any?.asString(): String = this as? String ?: ""
    fun Any?.asDouble(): Double = (this as? Number)?.toDouble() ?: 0.0
    fun Any?.asInt(): Int = (this as? Number)?.toInt() ?: 0
}
