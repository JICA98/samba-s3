package com.zenithblue.sambas3.gameconfig

import org.json.JSONObject

/** Lifecycle contract shown beside every editable native setting. */
enum class SettingApplyPhase {
    LIVE,
    NEXT_EMULATION_BOOT,
    APP_RESTART,
    UNSUPPORTED
}

data class KnownSetting(
    val path: String,
    val expectedType: String,
    val phase: SettingApplyPhase = SettingApplyPhase.NEXT_EMULATION_BOOT
)

data class RuntimeSettingNode(
    val path: String,
    val type: String
)

data class SettingsTreeAudit(
    val leaves: List<RuntimeSettingNode>,
    val missingKnownPaths: List<KnownSetting>,
    val typeMismatches: List<Pair<KnownSetting, RuntimeSettingNode>>,
    val duplicatePaths: List<String>,
    val unsupportedPaths: List<RuntimeSettingNode>
) {
    val isValid: Boolean
        get() = missingKnownPaths.isEmpty() &&
            typeMismatches.isEmpty() &&
            duplicatePaths.isEmpty()
}

/**
 * Build-specific path/type verifier and the small amount of lifecycle metadata
 * the Compose editor needs. The JSON tree itself remains the source of truth;
 * this catalog only catches stale frontend assumptions and labels apply timing.
 */
object SettingsBackendAudit {
    private val supportedEditorTypes = setOf("bool", "enum", "uint", "int", "float")

    // These are the paths used by the normal settings surfaces and validation
    // matrix. Paths are normalized without the leading @@ used by JNI callers.
    val knownSettings: List<KnownSetting> = listOf(
        KnownSetting("Core@@PPU Decoder", "enum"),
        KnownSetting("Core@@PPU Threads", "int"),
        KnownSetting("Core@@PPU LLVM Codegen Mode", "enum"),
        KnownSetting("Core@@Max LLVM Compile Threads", "int"),
        KnownSetting("Core@@PPU Foreground Compile Threads", "int"),
        KnownSetting("Core@@PPU LLVM Greedy Mode", "bool"),
        KnownSetting("Core@@LLVM Precompilation", "bool"),
        KnownSetting("Core@@SPU Decoder", "enum"),
        KnownSetting("Core@@SPU Block Size", "enum"),
        KnownSetting("VFS@@Enable /host_root/", "bool"),
        KnownSetting("VFS@@Initialize Directories", "bool"),
        KnownSetting("VFS@@Limit disk cache size", "bool"),
        KnownSetting("VFS@@Disk cache maximum size (MB)", "int"),
        KnownSetting("Video@@Renderer", "enum"),
        KnownSetting("Video@@Resolution", "enum"),
        KnownSetting("Video@@Aspect ratio", "enum"),
        KnownSetting("Video@@Frame limit", "enum"),
        KnownSetting("Video@@MSAA", "enum"),
        KnownSetting("Video@@Shader Mode", "enum"),
        KnownSetting("Video@@Write Color Buffers", "bool"),
        KnownSetting("Video@@Read Color Buffers", "bool"),
        KnownSetting("Video@@VSync", "bool"),
        KnownSetting("Video@@Resolution Scale", "int"),
        KnownSetting("Video@@Anisotropic Filter Override", "uint"),
        KnownSetting("Video@@Output Scaling Mode", "enum"),
        KnownSetting("Audio@@Renderer", "enum"),
        KnownSetting("Audio@@Audio Provider", "enum"),
        KnownSetting("Audio@@RSXAudio Avport", "enum"),
        KnownSetting("Audio@@Audio Format", "enum"),
        KnownSetting("Audio@@Master Volume", "int", SettingApplyPhase.LIVE),
        KnownSetting("Audio@@Enable Buffering", "bool"),
        KnownSetting("Audio@@Desired Audio Buffer Duration", "int"),
        KnownSetting("Input/Output@@Keyboard", "enum"),
        KnownSetting("Input/Output@@Mouse", "enum"),
        KnownSetting("Input/Output@@Pad handler mode", "enum"),
        KnownSetting("Input/Output@@Keep pads connected", "bool", SettingApplyPhase.LIVE),
        KnownSetting("Input/Output@@Background input enabled", "bool", SettingApplyPhase.LIVE),
        KnownSetting("System@@Language", "enum", SettingApplyPhase.APP_RESTART),
        KnownSetting("System@@Keyboard Type", "enum"),
        KnownSetting("System@@Enter button assignment", "enum"),
        KnownSetting("Net@@Internet enabled", "enum"),
        KnownSetting("Net@@UPNP Enabled", "bool"),
        KnownSetting("Savestate@@Start Paused", "bool"),
        KnownSetting("Savestate@@Suspend Emulation Savestate Mode", "bool"),
        KnownSetting("Savestate@@Compatible Savestate Mode", "bool"),
        KnownSetting("Miscellaneous@@Automatically start games after boot", "bool"),
        KnownSetting(
            "Miscellaneous@@Prevent display sleep while running games",
            "bool",
            SettingApplyPhase.LIVE
        ),
        KnownSetting("Miscellaneous@@Show trophy popups", "bool", SettingApplyPhase.LIVE),
        KnownSetting("Miscellaneous@@Pause Emulation During Home Menu", "bool", SettingApplyPhase.LIVE)
    )

    fun normalizePath(path: String): String = path.removePrefix("@@")

    fun descriptorFor(path: String): KnownSetting? {
        val normalized = normalizePath(path)
        return knownSettings.firstOrNull { it.path == normalized }
    }

    fun phaseFor(path: String, actualType: String? = null): SettingApplyPhase {
        val descriptor = descriptorFor(path)
        if (actualType != null && actualType !in supportedEditorTypes) {
            return SettingApplyPhase.UNSUPPORTED
        }
        return descriptor?.phase ?: SettingApplyPhase.NEXT_EMULATION_BOOT
    }

    fun applyHint(path: String, inGame: Boolean, actualType: String? = null): String =
        when (phaseFor(path, actualType)) {
            SettingApplyPhase.LIVE -> if (inGame) "APPLIES NOW · SAVED TO THIS GAME" else "APPLIES NOW"
            SettingApplyPhase.NEXT_EMULATION_BOOT ->
                if (inGame) "APPLIES AFTER THIS GAME RESTART" else "APPLIES AFTER NEXT GAME BOOT"
            SettingApplyPhase.APP_RESTART -> "APPLIES AFTER SAMBAS3 RESTART"
            SettingApplyPhase.UNSUPPORTED -> "NOT AVAILABLE ON ANDROID"
        }

    fun flatten(root: JSONObject): List<RuntimeSettingNode> {
        val result = mutableListOf<RuntimeSettingNode>()

        fun walk(obj: JSONObject, prefix: String) {
            val keys = obj.keys() ?: return
            while (keys.hasNext()) {
                val key = keys.next()
                val child = runCatching { obj.getJSONObject(key) }.getOrNull() ?: continue
                val path = if (prefix.isEmpty()) "@@$key" else "$prefix@@$key"
                if (child.has("type")) {
                    result += RuntimeSettingNode(normalizePath(path), child.optString("type"))
                } else {
                    walk(child, path)
                }
            }
        }

        walk(root, "")
        return result
    }

    fun audit(root: JSONObject): SettingsTreeAudit {
        return audit(flatten(root))
    }

    /** Pure overload used by JVM tests and by tooling that already flattened JSON. */
    fun audit(leaves: List<RuntimeSettingNode>): SettingsTreeAudit {
        val byPath = leaves.groupBy { it.path }
        val mismatches = knownSettings.mapNotNull { expected ->
            val actual = byPath[expected.path].orEmpty().firstOrNull() ?: return@mapNotNull null
            actual.takeIf { it.type != expected.expectedType }?.let { expected to it }
        }
        return SettingsTreeAudit(
            leaves = leaves,
            missingKnownPaths = knownSettings.filter { byPath[it.path].isNullOrEmpty() },
            typeMismatches = mismatches,
            duplicatePaths = byPath.filterValues { it.size > 1 }.keys.sorted(),
            unsupportedPaths = leaves.filter { it.type !in supportedEditorTypes }
        )
    }

    fun compactLog(audit: SettingsTreeAudit): String = buildString {
        append("leaves=").append(audit.leaves.size)
        append(" missing=").append(audit.missingKnownPaths.size)
        append(" typeMismatch=").append(audit.typeMismatches.size)
        append(" duplicate=").append(audit.duplicatePaths.size)
        append(" unsupported=").append(audit.unsupportedPaths.size)
        if (audit.missingKnownPaths.isNotEmpty()) {
            append(" missingPaths=").append(audit.missingKnownPaths.joinToString("|") { it.path })
        }
        if (audit.typeMismatches.isNotEmpty()) {
            append(" mismatches=").append(
                audit.typeMismatches.joinToString("|") { (expected, actual) ->
                    "${expected.path}:${expected.expectedType}->${actual.type}"
                }
            )
        }
        if (audit.unsupportedPaths.isNotEmpty()) {
            append(" unsupportedPaths=").append(
                audit.unsupportedPaths.joinToString("|") { "${it.path}:${it.type}" }
            )
        }
    }
}
