package com.zenithblue.sambas3.gameconfig

/**
 * org.json-free codec for engine cfg values and persisted override maps.
 *
 * Deliberately dependency-free (no org.json imports anywhere in gameconfig sources
 * or tests): android.jar stubs make org.json unusable under
 * `unitTests.isReturnDefaultValues = true`, so every gameconfig JVM test runs
 * without the NDK or any new gradle dependency.
 *
 * Value encoding mirrors the engine tree's JSON literals:
 * - bool -> `true` / `false`
 * - int / uint / float -> raw digits as emitted by [SettingsScreen] editors
 * - enum / string -> hand-rolled quoted string ([quoteCfgString]) whose output is
 *   accepted by nlohmann's parser and byte-compatible with JSONObject.quote for all
 *   engine values (`\`, `"`, `\n`, `\r`, `\t`, `\b`, `'\u000C'`, control chars < 0x20 as `\u00XX`).
 */
object SettingsValueCodec {

    /** Engine-tree node fields needed to encode a committed UI value. */
    data class SettingNodeSpec(
        val type: String,
        val min: String? = null,
        val max: String? = null,
        val default: String? = null
    )

    /** Encode a raw UI value for a node of [spec.type]. */
    fun encodedFromNode(spec: SettingNodeSpec, newValue: String): String = when (spec.type.lowercase()) {
        "bool" -> if (newValue.equals("true", ignoreCase = true) || newValue == "1") "true" else "false"
        "enum", "string" -> quoteCfgString(newValue)
        else -> newValue.trim()
    }

    /** Encode a node's raw default value (used by reset flows). */
    fun encodedDefault(spec: SettingNodeSpec): String? =
        spec.default?.let { encodedFromNode(spec, it) }

    /** Inverse of [encodedFromNode]: strip quotes/unescapes enums, pass numerics through. */
    fun decodeToDisplay(encoded: String): String {
        if (encoded.length >= 2 && encoded.first() == '"' && encoded.last() == '"') {
            return unescapeStringBody(encoded)
        }
        return encoded
    }

    /**
     * JSON-string escaping compatible with org.json's quote(): escapes backslash,
     * double quote, \n, \r, \t, \b, \f and control characters < 0x20 as \u00XX.
     */
    fun quoteCfgString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code))
                    else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Persist a tier map as a flat hand-built JSON object of escaped-key to
     * escaped-value pairs. Values are stored as escaped strings so ANY engine value
     * (quoted enums, digits, empty strings) round-trips losslessly.
     */
    fun encodeOverrideMap(values: Map<String, String>): String {
        val sb = StringBuilder(values.size * 16 + 2)
        sb.append('{')
        values.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append(quoteCfgString(key)).append(':').append(quoteCfgString(value))
        }
        sb.append('}')
        return sb.toString()
    }

    /** Inverse of [encodeOverrideMap]; tolerates blank/garbage input as an empty map. */
    fun decodeOverrideMap(encoded: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = encoded.length

        fun skipWhitespace() {
            while (i < n && encoded[i].isWhitespace()) i++
        }

        fun parseEscapedString(): String? {
            if (i >= n || encoded[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < n) {
                when (val c = encoded[i]) {
                    '\\' -> {
                        i++
                        if (i >= n) return null
                        when (val esc = encoded[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 >= n) return null
                                val hex = encoded.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }

        skipWhitespace()
        if (i >= n || encoded[i] != '{') return result
        i++
        while (i < n) {
            skipWhitespace()
            if (i < n && encoded[i] == '}') break
            if (i < n && encoded[i] == ',') { i++; continue }
            val key = parseEscapedString() ?: break
            skipWhitespace()
            if (i >= n || encoded[i] != ':') break
            i++
            skipWhitespace()
            val value = parseEscapedString() ?: break
            result[key] = value
        }
        return result
    }

    /** Strip the outer quotes of a [quoteCfgString]-produced literal and unescape. */
    private fun unescapeStringBody(literal: String): String {
        val inner = literal.substring(1, literal.length - 1)
        if (!inner.contains('\\')) return inner
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val esc = inner[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 5 < inner.length) {
                            inner.substring(i + 2, i + 6).toIntOrNull(16)?.let { code ->
                                sb.append(code.toChar())
                                i += 4
                            } ?: sb.append(esc)
                        } else {
                            sb.append(esc)
                        }
                    }
                    else -> sb.append(esc)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
