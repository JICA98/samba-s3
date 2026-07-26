package com.zenithblue.sambas3.ui.settings

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Nav route for the advanced settings graph (single destination; path is encoded). */
const val ADVANCED_SETTINGS_ROUTE = "advanced_settings/{encodedPath}"

/** Sentinel for the advanced-settings root (empty cfg path). */
const val ADVANCED_SETTINGS_ROOT_TOKEN = "_"

/**
 * Encode a settings path for use as a single Nav Compose path segment.
 * Paths may contain `/` (e.g. `@@Input/Output`) which would otherwise break routing.
 */
fun encodeAdvancedSettingsPath(path: String): String {
    val normalized = normalizeAdvancedSettingsPath(path)
    if (normalized.isEmpty()) return ADVANCED_SETTINGS_ROOT_TOKEN
    return Base64.encodeToString(
        normalized.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

fun decodeAdvancedSettingsPath(encoded: String?): String {
    if (encoded.isNullOrEmpty() || encoded == ADVANCED_SETTINGS_ROOT_TOKEN) return ""
    return try {
        String(
            Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            ),
            StandardCharsets.UTF_8
        )
    } catch (_: IllegalArgumentException) {
        // Backward-compatible plain path if ever navigated without encoding
        encoded
    }
}

/**
 * Normalize any of:
 * - `settings@@$` / empty → root
 * - `settings@@Video@@Vulkan` → `@@Video@@Vulkan`
 * - `@@Video` → `@@Video`
 */
fun normalizeAdvancedSettingsPath(path: String): String {
    if (path.isEmpty() || path == "settings@@$" || path == "settings") return ""
    return if (path.startsWith("settings")) path.removePrefix("settings") else path
}

fun advancedSettingsRoute(path: String = ""): String =
    "advanced_settings/${encodeAdvancedSettingsPath(path)}"

/**
 * Whether [route] targets the advanced settings graph (root or nested).
 * Note: plain `"settings"` is the main settings screen, not advanced.
 */
fun isAdvancedSettingsRoute(route: String): Boolean =
    route == "settings@@$" ||
        route.startsWith("settings@@") ||
        route.startsWith("advanced_settings/")

/**
 * Walk a cfg JSON tree by `@@`-separated path segments.
 */
fun getNestedSettings(root: JSONObject, path: String): JSONObject {
    val normalized = normalizeAdvancedSettingsPath(path)
    if (normalized.isEmpty()) return root
    val parts = normalized.split("@@").filter { it.isNotEmpty() }
    var current = root
    for (part in parts) {
        current = current.optJSONObject(part) ?: return current
    }
    return current
}

/** Folder nodes are JSON objects without a leaf `type` field. */
fun isSettingsFolder(obj: JSONObject?): Boolean =
    obj != null && !obj.has("type")
