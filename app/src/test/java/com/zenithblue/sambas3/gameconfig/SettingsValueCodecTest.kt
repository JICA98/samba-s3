package com.zenithblue.sambas3.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValueCodecTest {

    private fun spec(type: String, default: String? = null) =
        SettingsValueCodec.SettingNodeSpec(type = type, default = default)

    // ── encodedFromNode ──────────────────────────────────────────────────────

    @Test
    fun bool_values_encode_as_bare_true_false() {
        assertEquals("true", SettingsValueCodec.encodedFromNode(spec("bool"), "true"))
        assertEquals("false", SettingsValueCodec.encodedFromNode(spec("bool"), "false"))
        assertEquals("false", SettingsValueCodec.encodedFromNode(spec("bool"), "0"))
        assertEquals("true", SettingsValueCodec.encodedFromNode(spec("bool"), "TRUE"))
    }

    @Test
    fun bool_commit_request_uses_the_new_value_in_both_directions() {
        val backendRequests = mutableListOf<String>()
        fun commit(newValue: Boolean) {
            backendRequests += SettingsValueCodec.encodedFromNode(
                spec("bool"), newValue.toString()
            )
        }

        commit(true)
        commit(false)

        assertEquals(listOf("true", "false"), backendRequests)
    }

    @Test
    fun numeric_values_encode_raw_without_quotes() {
        assertEquals(
            "2",
            SettingsValueCodec.encodedFromNode(spec("uint"), "2")
        )
        assertEquals(
            "-17",
            SettingsValueCodec.encodedFromNode(spec("int"), "-17")
        )
        assertEquals(
            "1.5",
            SettingsValueCodec.encodedFromNode(spec("float"), "1.5")
        )
        assertEquals(
            "0.5",
            SettingsValueCodec.encodedFromNode(spec("float"), " 0.5 ")
        )
    }

    @Test
    fun enum_values_encode_quoted() {
        assertEquals(
            "\"Auto\"",
            SettingsValueCodec.encodedFromNode(spec("enum"), "Auto")
        )
        assertEquals(
            "\"Async Recompiler (multi-threaded)\"",
            SettingsValueCodec.encodedFromNode(
                spec("enum"),
                "Async Recompiler (multi-threaded)"
            )
        )
    }

    @Test
    fun encoded_default_matches_encoded_from_node() {
        val enumSpec = spec("enum", default = "Auto")
        assertEquals(
            SettingsValueCodec.encodedFromNode(enumSpec, "Auto"),
            SettingsValueCodec.encodedDefault(enumSpec)
        )
        val boolSpec = spec("bool", default = "false")
        assertEquals(
            "false",
            SettingsValueCodec.encodedDefault(boolSpec)
        )
    }

    // ── quoteCfgString (nlohmann/JSONObject.quote-compatible escaping) ───────

    @Test
    fun plain_string_round_trips_through_quotes() {
        assertEquals("\"Shader Mode\"", SettingsValueCodec.quoteCfgString("Shader Mode"))
    }

    @Test
    fun backslash_and_quote_are_escaped() {
        assertEquals("\"a\\\\b\"", SettingsValueCodec.quoteCfgString("a\\b"))
        assertEquals("\"say \\\"hi\\\"\"", SettingsValueCodec.quoteCfgString("say \"hi\""))
    }

    @Test
    fun whitespace_escapes_are_named() {
        assertEquals("\"l\\nr\"", SettingsValueCodec.quoteCfgString("l\nr"))
        assertEquals("\"c\\r\"", SettingsValueCodec.quoteCfgString("c\r"))
        assertEquals("\"t\\t\"", SettingsValueCodec.quoteCfgString("t\t"))
        assertEquals("\"b\\b f\\fc\"", SettingsValueCodec.quoteCfgString("b\b f\u000Cc"))
    }

    @Test
    fun control_characters_escape_as_unicode_hex() {
        assertEquals("\"\\u0001\"", SettingsValueCodec.quoteCfgString("\u0001"))
        assertEquals("\"x\\u001fy\"", SettingsValueCodec.quoteCfgString("x\u001Fy"))
    }

    @Test
    fun printable_non_ascii_passes_through_unescaped() {
        // Engine values are ASCII config enums; non-ASCII must not be mangled.
        assertEquals("\"é\"", SettingsValueCodec.quoteCfgString("é"))
    }

    // ── decodeToDisplay ──────────────────────────────────────────────────────

    @Test
    fun decode_to_display_strips_and_unescapes_enums() {
        assertEquals("Auto", SettingsValueCodec.decodeToDisplay("\"Auto\""))
        assertEquals(
            "say \"hi\"",
            SettingsValueCodec.decodeToDisplay(SettingsValueCodec.quoteCfgString("say \"hi\""))
        )
        assertEquals("2", SettingsValueCodec.decodeToDisplay("2"))
        assertEquals("2", SettingsValueCodec.decodeToDisplay(2.toString()))
    }

    // ── encodeOverrideMap / decodeOverrideMap round-trips ────────────────────

    @Test
    fun override_map_round_trips_plain_paths() {
        val source = linkedMapOf(
            "Video@@Shader Mode" to "\"Async Recompiler (multi-threaded)\"",
            "Core@@Max LLVM Compile Threads" to "2",
            "Video@@Write Color Buffers" to "true"
        )
        val encoded = SettingsValueCodec.encodeOverrideMap(source)
        assertEquals(source, SettingsValueCodec.decodeOverrideMap(encoded))
    }

    @Test
    fun override_map_survives_keys_containing_quotes_and_backslashes() {
        val source = linkedMapOf(
            "Weird@@Path With \"Quotes\"" to "1",
            "Back\\slash@@Key" to "\"v\""
        )
        val encoded = SettingsValueCodec.encodeOverrideMap(source)
        assertEquals(source, SettingsValueCodec.decodeOverrideMap(encoded))
    }

    @Test
    fun empty_and_blank_maps_encode_decode_stably() {
        assertEquals(emptyMap<String, String>(), SettingsValueCodec.decodeOverrideMap("{}"))
        assertEquals(emptyMap<String, String>(), SettingsValueCodec.decodeOverrideMap(""))
        assertEquals("{}", SettingsValueCodec.encodeOverrideMap(emptyMap()))
    }

    @Test
    fun insertion_order_is_preserved_for_replay_determinism() {
        val source = linkedMapOf(
            "A@@First" to "1",
            "B@@Second" to "2",
            "C@@Third" to "3"
        )
        val decoded = SettingsValueCodec.decodeOverrideMap(
            SettingsValueCodec.encodeOverrideMap(source)
        )
        assertEquals(listOf("A@@First", "B@@Second", "C@@Third"), decoded.keys.toList())
    }
}
