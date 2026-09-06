package com.zenithblue.sambas3.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogArtifactDiscoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun finds_rpcsx_and_rpcs3_candidates() {
        val root = tmp.newFolder("files")
        File(root, "RPCSX.log").writeText("x")
        File(root, "RPCS3.log").writeText("y")
        val found = LogArtifactDiscovery.scan(listOf(root))
        assertTrue(found.any { it.file.name == "RPCSX.log" && it.kind == LogSourceKind.RPCSX_BACKEND })
        assertTrue(found.any { it.file.name == "RPCS3.log" && it.kind == LogSourceKind.RPCS3_CORE })
    }

    @Test
    fun nested_log_directory_and_shaderlog() {
        val root = tmp.newFolder("cache")
        File(root, "logs").mkdirs()
        File(root, "logs/backend.log").writeText("b")
        File(root, "shaderlog").mkdirs()
        File(root, "shaderlog/dump.txt.log").writeText("s")
        val found = LogArtifactDiscovery.scan(listOf(root))
        assertTrue(found.any { it.file.name == "backend.log" })
        assertTrue(found.any { it.kind == LogSourceKind.SHADER })
    }

    @Test
    fun ignores_unrelated_binaries() {
        val root = tmp.newFolder("lib")
        File(root, "librpcsx.so").writeBytes(ByteArray(8))
        File(root, "photo.png").writeText("no")
        val found = LogArtifactDiscovery.scan(listOf(root))
        assertTrue(found.none { it.file.name.endsWith(".so") })
        assertTrue(found.none { it.file.name.endsWith(".png") })
    }

    @Test
    fun since_filter_skips_old_files() {
        val root = tmp.newFolder("old")
        val old = File(root, "RPCSX.log")
        old.writeText("old")
        old.setLastModified(1_000L)
        val found = LogArtifactDiscovery.scan(listOf(root), sinceMs = System.currentTimeMillis())
        assertEquals(0, found.size)
    }
}
