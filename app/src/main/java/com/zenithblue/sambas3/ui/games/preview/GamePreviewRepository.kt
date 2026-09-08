package com.zenithblue.sambas3.ui.games.preview

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameSourceMode
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.iso.DirectIsoManager
import com.zenithblue.sambas3.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small preview resolver — does not own scanner, does not import, does not trigger PPU.
 * Directory: tries PS3_GAME/ICON0.PNG then ICON0.PNG via SAF.
 * ISO: probes only ICON0.PNG via native iso_dev, size capped 16 MiB, cached.
 * Installed: resolves iconPath string to File/Uri with existence check.
 */
object GamePreviewRepository {
    private const val TAG = "GamePreview"
    private const val MAX_ICON_BYTES = 16L * 1024L * 1024L
    private const val CACHE_SUBDIR = "game_previews"

    fun resolveInstalledPreview(iconPath: String?): GamePreviewModel {
        if (iconPath.isNullOrBlank()) return GamePreviewModel.None
        return try {
            when {
                iconPath.startsWith("content://") -> GamePreviewModel.ContentUri(Uri.parse(iconPath))
                iconPath.startsWith("file://") -> {
                    val uri = Uri.parse(iconPath)
                    // Verify file exists if we can resolve path
                    GamePreviewModel.ContentUri(uri)
                }
                iconPath.startsWith("/") -> {
                    val f = File(iconPath)
                    if (f.isFile) {
                        GamePreviewModel.LocalFile(f)
                    } else {
                        Log.w(TAG, "Installed iconPath not found: $iconPath exists=${f.exists()} len=${if (f.exists()) f.length() else -1}")
                        GamePreviewModel.None
                    }
                }
                else -> {
                    // Try as file path fallback
                    val f = File(iconPath)
                    if (f.isFile) GamePreviewModel.LocalFile(f) else GamePreviewModel.None
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveInstalledPreview failed for $iconPath: ${e.message}")
            GamePreviewModel.None
        }
    }

    fun resolveInstalledBackground(iconPath: String?): GamePreviewModel {
        if (iconPath.isNullOrBlank()) return GamePreviewModel.None
        return try {
            val iconFile = File(iconPath)
            val parent = iconFile.parentFile ?: return GamePreviewModel.None

            // Check <name>_pic1.png (Direct ISO icon pattern: BLUS31584.png -> BLUS31584_pic1.png)
            val directIsoPic1 = File(parent, "${iconFile.nameWithoutExtension}_pic1.png")
            if (directIsoPic1.isFile && directIsoPic1.length() > 0) {
                return GamePreviewModel.LocalFile(directIsoPic1)
            }

            // Check PIC1.PNG / PIC1.png in same directory
            val pic1Upper = File(parent, "PIC1.PNG")
            if (pic1Upper.isFile && pic1Upper.length() > 0) {
                return GamePreviewModel.LocalFile(pic1Upper)
            }
            val pic1Lower = File(parent, "PIC1.png")
            if (pic1Lower.isFile && pic1Lower.length() > 0) {
                return GamePreviewModel.LocalFile(pic1Lower)
            }

            // Check parent directory if icon was in PS3_GAME/ICON0.PNG
            val grandParent = parent.parentFile
            if (grandParent != null) {
                val gpPic1 = File(grandParent, "PIC1.PNG")
                if (gpPic1.isFile && gpPic1.length() > 0) {
                    return GamePreviewModel.LocalFile(gpPic1)
                }
            }

            GamePreviewModel.None
        } catch (e: Exception) {
            Log.w(TAG, "resolveInstalledBackground failed for $iconPath: ${e.message}")
            GamePreviewModel.None
        }
    }

    suspend fun resolveBackground(context: Context, game: Game): GamePreviewModel = withContext(Dispatchers.IO) {
        val rawIconPath = game.info.iconPath.value
        val installedBg = resolveInstalledBackground(rawIconPath)
        if (installedBg !is GamePreviewModel.None) {
            return@withContext installedBg
        }

        // Try resolving from sourceUri (DIRECT_ISO or folder)
        val sourceUriStr = game.info.sourceUri.value
        if (!sourceUriStr.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(sourceUriStr) }.getOrNull()
            if (uri != null) {
                val isIso = game.info.sourceMode.value == GameSourceMode.DIRECT_ISO ||
                    sourceUriStr.endsWith(".iso", ignoreCase = true)

                if (isIso) {
                    val titleId = game.info.path.substringAfterLast('/')
                    val iconsDir = File(context.filesDir, "direct_iso_icons").apply { if (!exists()) mkdirs() }
                    val dest = File(iconsDir, "${titleId}_pic1.png")
                    if (dest.isFile && dest.length() > 0) {
                        return@withContext GamePreviewModel.LocalFile(dest)
                    }
                    if (DirectIsoManager.extractIsoPic1(context, uri, dest)) {
                        return@withContext GamePreviewModel.LocalFile(dest)
                    }
                } else {
                    // Directory game via SAF
                    val candidates = listOf("PS3_GAME/PIC1.PNG", "PIC1.PNG", "PS3_GAME/PIC1.png", "PIC1.png")
                    for (path in candidates) {
                        val doc = FileUtil.uriChild(context, uri, path)
                        if (doc != null && !doc.isDirectory) {
                            return@withContext GamePreviewModel.ContentUri(doc.uri)
                        }
                    }
                }
            }
        }

        GamePreviewModel.None
    }

    suspend fun resolveDirectoryPreview(context: Context, sourceUri: Uri): GamePreviewModel = withContext(Dispatchers.IO) {
        try {
            // Try PS3_GAME/ICON0.PNG first, then ICON0.PNG
            val candidates = listOf("PS3_GAME/ICON0.PNG", "ICON0.PNG")
            for (path in candidates) {
                val doc = FileUtil.uriChild(context, sourceUri, path)
                if (doc != null && !doc.isDirectory) {
                    Log.i(TAG, "Directory preview found $path for $sourceUri")
                    return@withContext GamePreviewModel.ContentUri(doc.uri)
                }
            }
            Log.d(TAG, "No directory icon for $sourceUri")
            GamePreviewModel.None
        } catch (e: Exception) {
            Log.w(TAG, "Directory preview failed $sourceUri: ${e.message}")
            GamePreviewModel.None
        }
    }

    /**
     * ISO preview: reads only PS3_GAME/ICON0.PNG via native iso_dev, writes to cache.
     * Does not copy ISO, does not install, does not trigger PPU.
     */
    suspend fun resolveIsoPreview(context: Context, sourceUri: Uri): GamePreviewModel = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { if (!exists()) mkdirs() }
            val key = sourceUri.toString().hashCode().toString(16) // simple stable key; hash collision low for preview cache
            // Also include last segment to avoid collisions and aid debugging
            val safeName = (sourceUri.lastPathSegment ?: "iso").replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
            val cachedFile = File(cacheDir, "${key}_${safeName}.png")
            if (cachedFile.isFile && cachedFile.length() > 0 && cachedFile.length() < MAX_ICON_BYTES) {
                Log.d(TAG, "ISO preview cache hit $cachedFile")
                return@withContext GamePreviewModel.LocalFile(cachedFile)
            }

            // Open FD via ContentResolver
            val pfd = try {
                context.contentResolver.openFileDescriptor(sourceUri, "r")
            } catch (e: Exception) {
                Log.w(TAG, "openFileDescriptor failed for ISO preview $sourceUri: ${e.message}")
                return@withContext GamePreviewModel.None
            }
            if (pfd == null) {
                Log.w(TAG, "openFileDescriptor null for $sourceUri")
                return@withContext GamePreviewModel.None
            }
            try {
                // Ensure parent exists
                cachedFile.parentFile?.mkdirs()
                // Native probe: extract only ICON0.PNG to cachedFile
                val ret = try {
                    RPCSX.instance.extractIsoPreview(pfd.fd, cachedFile.absolutePath)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native extractIsoPreview not available (old core): ${e.message}")
                    -999
                } catch (e: Exception) {
                    Log.w(TAG, "extractIsoPreview threw: ${e.message}", e)
                    -999
                }
                if (ret == 0 && cachedFile.isFile && cachedFile.length() > 0 && cachedFile.length() < MAX_ICON_BYTES) {
                    Log.i(TAG, "ISO preview extracted ${cachedFile.length()} bytes for $sourceUri")
                    GamePreviewModel.LocalFile(cachedFile)
                } else {
                    Log.d(TAG, "ISO preview not available ret=$ret for $sourceUri")
                    // Clean up failed file if empty
                    if (cachedFile.isFile && cachedFile.length() == 0L) cachedFile.delete()
                    GamePreviewModel.None
                }
            } finally {
                try { pfd.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveIsoPreview failed $sourceUri: ${e.message}", e)
            GamePreviewModel.None
        }
    }

    /**
     * Generic resolver keyed by source uri and kind — used by UI to trigger preview load.
     */
    suspend fun resolvePreview(context: Context, sourceUri: Uri?, sourceKind: com.zenithblue.sambas3.utils.GameSourceKind?): GamePreviewModel {
        if (sourceUri == null || sourceKind == null) return GamePreviewModel.None
        return when (sourceKind) {
            com.zenithblue.sambas3.utils.GameSourceKind.DIRECTORY -> resolveDirectoryPreview(context, sourceUri)
            com.zenithblue.sambas3.utils.GameSourceKind.ISO -> resolveIsoPreview(context, sourceUri)
        }
    }
}
