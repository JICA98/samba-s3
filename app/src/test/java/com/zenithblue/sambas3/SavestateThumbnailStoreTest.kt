package com.zenithblue.sambas3

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SavestateThumbnailStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var scope: CoroutineScope
    private lateinit var store: SavestateThumbnailStore
    private lateinit var savePath: File

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default)
        store = SavestateThumbnailStore(context, scope)
        savePath = File(context.cacheDir, "thumbnail-tests/slot-0.SAVESTAT.zst")
        savePath.parentFile?.mkdirs()
        savePath.writeBytes(byteArrayOf(1))
        store.previewPathFor(savePath.path).delete()
        File(savePath.path + ".preview.1.tmp.webp").delete()
        File(savePath.path + ".preview.2.tmp.webp").delete()
    }

    @After
    fun tearDown() {
        scope.cancel()
        store.previewPathFor(savePath.path).delete()
        File(savePath.path + ".preview.1.tmp.webp").delete()
        File(savePath.path + ".preview.2.tmp.webp").delete()
    }

    @Test
    fun successful_commit_publishes_staged_preview() {
        store.begin(1L, 0, savePath.path)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        store.stage(1L, 0, bitmap)
        bitmap.recycle()

        assertTrue(store.commit(1L, 0, savePath.path))
        val preview = awaitFile(store.previewPathFor(savePath.path))

        assertTrue(preview.isFile)
        assertTrue(preview.length() > 0L)
    }

    @Test
    fun failed_overwrite_keeps_old_preview_and_removes_temp() {
        val preview = store.previewPathFor(savePath.path)
        val oldBytes = byteArrayOf(9, 8, 7)
        preview.writeBytes(oldBytes)

        store.begin(2L, 0, savePath.path)
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        store.stage(2L, 0, bitmap)
        bitmap.recycle()
        store.discard(2L)

        Thread.sleep(150)
        assertArrayEquals(oldBytes, preview.readBytes())
        assertFalse(File(savePath.path + ".preview.2.tmp.webp").exists())
    }

    @Test
    fun capture_failure_removes_old_preview_only_after_commit() {
        val preview = store.previewPathFor(savePath.path)
        preview.writeBytes(byteArrayOf(4, 5, 6))

        store.begin(3L, 0, savePath.path)
        store.captureFailed(3L, 0)
        assertTrue(preview.exists())

        assertTrue(store.commit(3L, 0, savePath.path))
        assertFalse(preview.exists())
    }

    @Test
    fun stale_commit_is_ignored() {
        store.begin(4L, 0, savePath.path)

        assertFalse(store.commit(4L, 0, File(savePath.parentFile, "other.SAVESTAT.zst").path))
        assertFalse(store.previewPathFor(savePath.path).exists())
    }

    @Test
    fun mtime_metadata_and_preview_path_are_canonical() {
        val preview = store.previewPathFor(savePath.path)
        preview.writeBytes(byteArrayOf(1, 2, 3))

        val metadata = SavestateThumbnailStore.metadataForPath(savePath.path)

        assertNotEquals(null, metadata)
        assertEquals(preview.absolutePath, metadata?.path)
        assertTrue(metadata?.mtimeMs ?: 0L > 0L)
    }

    private fun awaitFile(file: File): File {
        repeat(100) {
            if (file.isFile && file.length() > 0L) return file
            Thread.sleep(20)
        }
        return file
    }
}
