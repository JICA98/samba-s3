package com.zenithblue.sambas3.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GameCacheManagerTest {
    @Test
    fun clearFilesRemovesOnlySelectedTitleAndManifest() {
        val root = Files.createTempDirectory("game-cache-clear").toFile()
        try {
            val selected = root.resolve("cache/cache/BLUS31584/module.obj").apply {
                parentFile?.mkdirs()
                writeText("selected")
            }
            val other = root.resolve("cache/cache/BLUS30109/module.obj").apply {
                parentFile?.mkdirs()
                writeText("other")
            }
            val manifest = root.resolve("cache/cache/ppu_manifest/BLUS31584.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }

            assertTrue(GameCacheManager.clearFiles(root, "blus31584"))
            assertFalse(selected.exists())
            assertFalse(manifest.exists())
            assertTrue(other.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheArtifactsRejectsTraversal() {
        GameCacheManager.cacheArtifacts(Files.createTempDirectory("game-cache-path").toFile(), "../cache")
    }

    @Test
    fun clearFilesIsSuccessfulWhenCacheDoesNotExist() {
        val root = Files.createTempDirectory("game-cache-empty").toFile()
        try {
            assertTrue(GameCacheManager.clearFiles(root, "BLUS31584"))
        } finally {
            root.deleteRecursively()
        }
    }
}
