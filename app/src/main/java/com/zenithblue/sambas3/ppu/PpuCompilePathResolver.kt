package com.zenithblue.sambas3.ppu

import android.content.Context
import android.net.Uri
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.iso.DirectIsoSession
import java.io.File

/**
 * Maps a library path (installed dir or virtual `direct_iso/<TITLE>`) to a
 * path the isolated PPU worker can compile. Direct ISO never extracts; the
 * worker opens the persistable source URI and compiles `/proc/self/fd/<n>`.
 */
object PpuCompilePathResolver {

    fun resolveForWorker(gamePath: String, titleId: String): String {
        if (gamePath.isBlank()) return gamePath
        val asFile = File(gamePath)
        if (asFile.isDirectory || (asFile.isFile && asFile.canRead())) {
            return asFile.absolutePath
        }
        if (gamePath.startsWith("content://") || gamePath.startsWith("file://")) {
            return gamePath
        }
        val game = runCatching {
            GameRepository.list().firstOrNull { g ->
                GameIdentity.titleIdOrNull(g.info.path, g.info.name.value)
                    ?.equals(titleId, ignoreCase = true) == true ||
                    g.info.path.equals(gamePath, ignoreCase = true)
            }
        }.getOrNull()
        val uri = game?.info?.sourceUri?.value
        if (!uri.isNullOrBlank()) return uri
        return gamePath
    }

    fun materializeNativePath(context: Context, gamePath: String): Pair<String, (() -> Unit)?> {
        if (gamePath.isBlank()) return gamePath to null
        val file = File(gamePath)
        if (file.isDirectory || (file.isFile && file.canRead())) {
            return gamePath to null
        }
        val uri = when {
            gamePath.startsWith("content://") || gamePath.startsWith("file://") ->
                Uri.parse(gamePath)
            gamePath.endsWith(".iso", ignoreCase = true) && gamePath.startsWith("/") ->
                Uri.fromFile(File(gamePath))
            else -> null
        } ?: return gamePath to null
        val session = DirectIsoSession.acquire(context, uri)
        return session.procFdPath to { DirectIsoSession.release("ppu-batch") }
    }

    fun hasCompiledCache(titleId: String): Boolean {
        if (titleId.isBlank()) return false
        var root = RPCSX.rootDirectory
        if (root.isNotEmpty() && !root.endsWith("/")) root += "/"
        val dir = File(root, "cache/cache/$titleId")
        if (!dir.isDirectory) return false
        return dir.walkTopDown().any { it.isFile && (it.name.endsWith(".obj") || it.name.endsWith(".gz")) }
    }
}
