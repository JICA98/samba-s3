package com.zenithblue.sambas3

import android.content.Intent
import java.io.File

/** Parsed once at the Activity boundary so savestate intent cannot silently fall back to a game boot. */
data class EmulatorBootRequest(
    val mode: EmulatorBootMode,
    val originalGamePath: String,
    val savestatePath: String? = null,
    val slot: Int? = null,
    val requestId: Long? = null,
    val safeRetry: Boolean = false,
    val parseError: String? = null,
) {
    fun validationError(hasBootSavestateExport: Boolean): String? {
        if (parseError != null) return parseError
        if (originalGamePath.isBlank()) return "missing-original-game-path"
        if (mode == EmulatorBootMode.FreshGame) return null
        if (!hasBootSavestateExport) return "boot-savestate-export-missing"
        if (savestatePath.isNullOrBlank()) return "missing-savestate-path"
        if (!File(savestatePath).isFile || File(savestatePath).length() <= 0L) return "invalid-savestate-file"
        return null
    }

    companion object {
        fun fromIntent(intent: Intent, pending: PendingSavestateRecovery? = null): EmulatorBootRequest {
            val rawMode = intent.getStringExtra(RPCSXActivity.EXTRA_BOOT_MODE)
            val requestedMode = rawMode?.let { runCatching { EmulatorBootMode.valueOf(it) }.getOrNull() }
            val original = intent.getStringExtra(RPCSXActivity.EXTRA_ORIGINAL_GAME_PATH)
                ?: intent.getStringExtra("path")
                ?: pending?.originalGamePath.orEmpty()
            val recoveryPath = intent.getStringExtra(RPCSXActivity.EXTRA_SAVESTATE_PATH)
                ?.takeIf { requestedMode == EmulatorBootMode.DurableRecovery }
                ?: intent.getStringExtra(RPCSXActivity.EXTRA_RECOVERY_SAVESTATE)
                ?: pending?.takeIf { it.originalGamePath == original }?.savestatePath
            val userPath = intent.getStringExtra(RPCSXActivity.EXTRA_SAVESTATE_PATH)
                ?.takeIf { requestedMode == EmulatorBootMode.UserSelectedSavestate }
                ?: intent.getStringExtra(RPCSXActivity.EXTRA_USER_SAVESTATE)
            val mode = requestedMode ?: when {
                recoveryPath != null -> EmulatorBootMode.DurableRecovery
                userPath != null -> EmulatorBootMode.UserSelectedSavestate
                else -> EmulatorBootMode.FreshGame
            }
            val savePath = when (mode) {
                EmulatorBootMode.DurableRecovery -> recoveryPath
                EmulatorBootMode.UserSelectedSavestate -> userPath
                EmulatorBootMode.FreshGame -> null
            }
            return EmulatorBootRequest(
                mode = mode,
                originalGamePath = original,
                savestatePath = savePath,
                slot = intent.getIntExtra(
                    RPCSXActivity.EXTRA_SAVESTATE_SLOT,
                    intent.getIntExtra(RPCSXActivity.EXTRA_USER_SAVESTATE_SLOT, -1)
                ).takeIf { it >= 0 } ?: pending?.slot,
                requestId = intent.getLongExtra(RPCSXActivity.EXTRA_RECOVERY_REQUEST_ID, -1L).takeIf { it >= 0 }
                    ?: pending?.requestId,
                safeRetry = intent.getBooleanExtra(RPCSXActivity.EXTRA_SAFE_RETRY, false),
                parseError = if (rawMode != null && requestedMode == null) "invalid-boot-mode" else null,
            )
        }
    }
}
