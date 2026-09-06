package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class PpuCompilePathResolverTest {

    @Test
    fun readableFile_isPassedThrough() {
        val iso = File.createTempFile("sambas3-direct", ".iso")
        try {
            iso.writeText("ISO")
            val resolved = PpuCompilePathResolver.resolveForWorker(iso.absolutePath, "BLUS31584")
            assertEquals(iso.absolutePath, resolved)
        } finally {
            iso.delete()
        }
    }

    @Test
    fun contentUri_isPassedThrough() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Agame.iso"
        assertEquals(uri, PpuCompilePathResolver.resolveForWorker(uri, "BLUS30758"))
    }

    @Test
    fun virtualDirectIsoPath_withoutRepository_staysVirtual() {
        val virtual = "direct_iso/BLUS31584"
        assertEquals(virtual, PpuCompilePathResolver.resolveForWorker(virtual, "BLUS31584"))
    }

    @Test
    fun emptyCacheDir_isNotCompiled() {
        assertFalse(PpuCompilePathResolver.hasCompiledCache(""))
        assertFalse(PpuCompilePathResolver.hasCompiledCache("MISSING_TITLE"))
    }
}
