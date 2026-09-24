package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PatchTitleScopeTest {
    @Test fun sharedPatchKeepsEachTitlesEnabledState() {
        val shared = Patch(name = "Shared", serials = listOf("BCUS98111", "BCUS98125"), enabled = true, enabledSerials = listOf("BCUS98125"))
        val unrelated = Patch(name = "Other", serials = listOf("BLUS30443"), enabled = true)
        val selected = PatchRepository.forTitle(listOf(shared, unrelated), "BCUS98111")
        assertEquals(listOf("Shared"), selected.map { it.name })
        assertFalse(selected.single().enabled)
        assertEquals(true, PatchRepository.forTitle(listOf(shared), "BCUS98125").single().enabled)
    }

    @Test
    fun importLocalSavesCorrectFileName() {
        val tempDir = java.nio.file.Files.createTempDirectory("samba_patch_test").toFile()
        try {
            RPCSX.rootDirectory = tempDir.absolutePath + "/"
            val content = "test: 1"
            PatchRepository.importLocal(content)
            val globalFile = java.io.File(PatchRepository.patchesDir(), "imported_patch.yml")
            assertEquals(true, globalFile.exists())
            assertEquals(content, globalFile.readText())

            PatchRepository.importLocal(content, "BCUS98111")
            val perTitleFile = java.io.File(PatchRepository.patchesDir(), "BCUS98111_patch.yml")
            assertEquals(true, perTitleFile.exists())
            assertEquals(content, perTitleFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
