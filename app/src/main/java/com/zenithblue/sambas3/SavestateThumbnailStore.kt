package com.zenithblue.sambas3

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publishes save-state previews independently from the savestate payload.
 *
 * The active request owns a temp sidecar until the native COMMITTED event names
 * the exact durable slot. A failed request can therefore never overwrite the
 * preview belonging to the previous valid slot.
 */
class SavestateThumbnailStore(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private data class Pending(
        val requestId: Long,
        val slot: Int,
        val expectedSavestatePath: String?,
        val tempPath: File,
        var staged: Boolean = false,
        var captureFailed: Boolean = false,
        var committedPath: String? = null,
        var cancelled: Boolean = false
    )

    private val lock = Any()
    private val pending = mutableMapOf<Long, Pending>()

    fun begin(requestId: Long, slot: Int, savestatePath: String?) {
        synchronized(lock) {
            pending.remove(requestId)?.tempPath?.delete()
            val expected = savestatePath?.takeIf { it.isNotBlank() }
            val temp = if (expected != null) {
                File("$expected.preview.$requestId.tmp.webp")
            } else {
                File(context.cacheDir, "savestate_thumbnails/$requestId.tmp.webp")
            }
            temp.parentFile?.mkdirs()
            pending[requestId] = Pending(requestId, slot, expected, temp)
        }
        Log.i(TAG, "S3THUMB capture begin request=$requestId slot=$slot")
    }

    /** Downscale immediately, then encode on Dispatchers.IO. */
    fun stage(requestId: Long, slot: Int, bitmap: Bitmap) {
        val preview = runCatching { downscale(bitmap) }.getOrNull()
        if (preview == null) {
            captureFailed(requestId, slot)
            return
        }
        scope.launch(Dispatchers.IO) {
            val item = synchronized(lock) {
                pending[requestId]?.takeIf { it.slot == slot && !it.cancelled }
            }
            if (item == null) {
                preview.recycle()
                return@launch
            }

            val wrote = runCatching {
                item.tempPath.parentFile?.mkdirs()
                FileOutputStream(item.tempPath).use { output ->
                    check(preview.compress(Bitmap.CompressFormat.WEBP, 84, output)) {
                        "WEBP compression returned false"
                    }
                    output.fd.sync()
                }
                item.tempPath.isFile && item.tempPath.length() > 0L
            }.getOrElse {
                Log.w(TAG, "S3THUMB capture-failed request=$requestId: " + it.message)
                false
            }
            preview.recycle()

            synchronized(lock) {
                val current = pending[requestId]
                if (current == null || current !== item || current.cancelled) {
                    item.tempPath.delete()
                    return@synchronized
                }
                if (!wrote) {
                    current.captureFailed = true
                    item.tempPath.delete()
                } else {
                    current.staged = true
                    Log.i(TAG, "S3THUMB temp written request=$requestId path=" + item.tempPath)
                }
                publishIfReadyLocked(current)
            }
        }
    }

    fun captureFailed(requestId: Long, slot: Int) {
        synchronized(lock) {
            val item = pending[requestId]?.takeIf { it.slot == slot && !it.cancelled } ?: return
            item.captureFailed = true
            item.tempPath.delete()
            Log.w(TAG, "S3THUMB capture-failed request=$requestId slot=$slot")
            publishIfReadyLocked(item)
        }
    }

    /**
     * Marks the exact native save as durable. Only a matching request may
     * publish its preview. If capture failed, the old final preview is removed
     * after commit so it cannot masquerade as the new save.
     */
    fun commit(requestId: Long, slot: Int, savestatePath: String): Boolean {
        synchronized(lock) {
            val item = pending[requestId] ?: return false
            if (item.slot != slot || item.cancelled || savestatePath.isBlank()) return false
            if (item.expectedSavestatePath != null &&
                normalized(item.expectedSavestatePath) != normalized(savestatePath)
            ) {
                Log.w(TAG, "S3THUMB stale commit ignored request=$requestId slot=$slot path=$savestatePath")
                item.cancelled = true
                item.tempPath.delete()
                pending.remove(requestId)
                return false
            }
            item.committedPath = savestatePath
            publishIfReadyLocked(item)
            return true
        }
    }

    /** Save failed before durable commit: discard only request-owned temp data. */
    fun discard(requestId: Long) {
        synchronized(lock) {
            pending.remove(requestId)?.let {
                it.cancelled = true
                it.tempPath.delete()
                Log.i(TAG, "S3THUMB temp discarded request=$requestId")
            }
        }
    }

    /** Recover a temp preview left by process death after the native commit. */
    fun recoverCommitted(requestId: Long, slot: Int, savestatePath: String) {
        val temp = File("$savestatePath.preview.$requestId.tmp.webp")
        if (!temp.isFile || temp.length() <= 0L) return
        val finalPath = previewPathFor(savestatePath)
        val published = runCatching {
            finalPath.parentFile?.mkdirs()
            try {
                Files.move(
                    temp.toPath(),
                    finalPath.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    finalPath.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            finalPath.isFile && finalPath.length() > 0L
        }.getOrDefault(false)
        if (published) {
            Log.i(TAG, "S3THUMB recovered request=$requestId slot=$slot final=$finalPath")
        }
    }

    fun previewPathFor(savestatePath: String): File = previewPathForPath(savestatePath)

    fun metadataFor(savestatePath: String?): PreviewMetadata? = metadataForPath(savestatePath)

    private fun publishIfReadyLocked(item: Pending) {
        val committed = item.committedPath ?: return
        if (item.captureFailed) {
            previewPathFor(committed).delete()
            pending.remove(item.requestId)
            Log.i(TAG, "S3THUMB committed without preview request=" + item.requestId + "; old preview removed")
            return
        }
        if (!item.staged || !item.tempPath.isFile || item.tempPath.length() <= 0L) return

        val finalPath = previewPathFor(committed)
        finalPath.parentFile?.mkdirs()
        val published = runCatching {
            try {
                Files.move(
                    item.tempPath.toPath(),
                    finalPath.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    item.tempPath.toPath(),
                    finalPath.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            finalPath.isFile && finalPath.length() > 0L
        }.getOrElse {
            Log.e(TAG, "S3THUMB publish-failed request=" + item.requestId + ": " + it.message)
            false
        }
        if (published) {
            Log.i(TAG, "S3THUMB committed request=" + item.requestId + " final=$finalPath")
        } else {
            // The save is already durable, so never leave an older preview
            // that could be mistaken for this newly committed slot.
            finalPath.delete()
            Log.e(TAG, "S3THUMB final preview removed after publish failure request=" + item.requestId)
        }
        pending.remove(item.requestId)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxLongEdge = 720
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val width = maxOf(1, (bitmap.width * scale).toInt())
        val height = maxOf(1, (bitmap.height * scale).toInt())
        return if (width == bitmap.width && height == bitmap.height) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }

    private fun normalized(path: String): String = runCatching {
        File(path).canonicalFile.path
    }.getOrDefault(File(path).absolutePath)

    companion object {
        private const val TAG = "S3THUMB"

        @JvmStatic
        fun previewPathForPath(savestatePath: String): File =
            File("$savestatePath.preview.webp")

        @JvmStatic
        fun metadataForPath(savestatePath: String?): PreviewMetadata? {
            val path = savestatePath?.takeIf { it.isNotBlank() } ?: return null
            val file = previewPathForPath(path)
            return file.takeIf { it.isFile && it.length() > 0L }?.let {
                PreviewMetadata(it.absolutePath, it.lastModified())
            }
        }
    }

    data class PreviewMetadata(val path: String, val mtimeMs: Long)
}
