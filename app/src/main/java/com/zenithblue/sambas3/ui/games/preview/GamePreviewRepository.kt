package com.zenithblue.sambas3.ui.games.preview

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zenithblue.sambas3.RPCSX
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
