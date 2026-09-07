package com.zenithblue.sambas3.utils

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.ppu.PpuBatchKind
import com.zenithblue.sambas3.ppu.PpuInstallSessionStore
import java.io.File

/** Clears only generated cache owned by one title. Game data, saves and trophies are untouched. */
object GameCacheManager {
    private const val TAG = "GameCacheManager"
    private val titleIdPattern = Regex("[A-Za-z]{4}\\d{5}")

    internal fun cacheArtifacts(root: File, titleId: String): List<File> {
        require(titleIdPattern.matches(titleId)) { "Invalid title ID" }
        val safeTitle = titleId.uppercase()
        val cacheRoot = File(root, "cache/cache")
        return listOf(
            File(cacheRoot, safeTitle),
            File(cacheRoot, "ppu_manifest/$safeTitle.json"),
            File(cacheRoot, "ppu_manifest/$safeTitle.json.tmp"),
        )
    }

    internal fun clearFiles(root: File, titleId: String): Boolean {
        val canonicalRoot = root.canonicalFile
        return cacheArtifacts(canonicalRoot, titleId).all { artifact ->
            val target = artifact.canonicalFile
            val withinRoot = target.path.startsWith(canonicalRoot.path + File.separator)
            if (!withinRoot) return@all false
            !target.exists() || target.deleteRecursively()
        }
    }

    /** Run from an IO dispatcher. */
    fun clear(context: Context, titleId: String): Boolean {
        if (!titleIdPattern.matches(titleId)) {
            Log.w(TAG, "Refusing cache clear for invalid title ID")
            return false
        }
        val safeTitle = titleId.uppercase()
        val root = File(RPCSX.rootDirectory)
        val deleted = runCatching { clearFiles(root, safeTitle) }
            .onFailure { Log.e(TAG, "Cache clear failed for $safeTitle", it) }
            .getOrDefault(false)
        if (!deleted) return false

        PpuReadinessStore.removeEntry(context, safeTitle)
        PpuInstallSessionStore.clearIfTitle(context, safeTitle, PpuBatchKind.INSTALL)
        PpuInstallSessionStore.clearIfTitle(context, safeTitle, PpuBatchKind.RUNTIME)
        Log.i(TAG, "Cleared generated cache and readiness for $safeTitle")
        return true
    }
}
