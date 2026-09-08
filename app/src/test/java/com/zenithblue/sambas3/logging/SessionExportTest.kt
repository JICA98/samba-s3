package com.zenithblue.sambas3.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class SessionExportTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LogSessionStore.sessionsRoot(context).deleteRecursively()
    }

    @Test
    fun sealedSessionAUnchangedAfterBStarts() {
        LogSessionStore.begin(context, "A", "/a", "BLUS1", "A", null, null, null, "1", "core", 1, nowMs = 1L)
        val gzip = File(context.cacheDir, "global-RPCSX.log.gz")
        GZIPOutputStream(gzip.outputStream()).use { it.write("session-a-raw".toByteArray()) }
        val icon = File(LogSessionStore.sessionDir(context, "A"), "game_icon.bin")
        icon.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val artA = LogSessionStore.registerOutput(context, "A", gzip, LogSourceKind.RPCSX_BACKEND, "native", "1", true)!!
        val artIcon = LogArtifact(
            id = "icon",
            source = LogSourceKind.OTHER,
            path = icon.absolutePath,
            detectedAtMs = 1L,
            bytes = icon.length(),
            compressed = false,
            liveTailSupported = false,
            finalStatus = "sealed",
            relativePath = "game_icon.bin",
            originalFilename = "game_icon.bin",
            role = "icon",
            sealed = true,
            sha256 = SessionExport.sha256(icon),
        )
        LogSessionStore.finalize(
            context, "A", LogSessionTerminal.CLEAN_STOP, "InGameExit",
            extraArtifacts = listOf(artA, artIcon),
        )
        val sealed = File(LogSessionStore.sessionDir(context, "A"), artA.relativePath!!)
        val hashBefore = SessionExport.sha256(sealed)
        val exportA1 = SessionExport.export(context, "A")!!

        LogSessionStore.begin(context, "B", "/b", "BLUS2", "B", null, null, null, "1", "core", 2, nowMs = 2L)
        gzip.writeBytes("session-b-overwrite".toByteArray())
        val exportA2 = SessionExport.export(context, "A")!!
        assertEquals(hashBefore, SessionExport.sha256(sealed))
        assertEquals(exportA1.hashes.filterKeys { it.contains("RPCSX") }, exportA2.hashes.filterKeys { it.contains("RPCSX") })
        ZipFile(exportA2.zip).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.any { it.contains("RPCSX.log.gz") })
            assertTrue(names.contains("game_icon.bin") || names.any { it.contains("game_icon") })
            val gz = zip.getInputStream(zip.entries().toList().first { it.name.contains("RPCSX.log.gz") }).readBytes()
            assertTrue(gz.isNotEmpty())
            assertNotEquals("session-b-overwrite", String(gz))
        }
    }

    @Test
    fun basenameCollisionKeepsDistinctIds() {
        LogSessionStore.begin(context, "C", "/c", null, "C", null, null, null, null, null, 1)
        val one = File(LogSessionStore.sessionDir(context, "C"), "raw/native/g1/RPCSX.log")
        val two = File(LogSessionStore.sessionDir(context, "C"), "raw/worker/g1/RPCSX.log")
        one.parentFile?.mkdirs()
        two.parentFile?.mkdirs()
        one.writeText("native")
        two.writeText("worker")
        val a1 = LogSessionStore.registerOutput(context, "C", one, LogSourceKind.RPCSX_BACKEND, "native", "g1", true)!!
        val a2 = LogSessionStore.registerOutput(context, "C", two, LogSourceKind.RPCSX_BACKEND, "worker", "g1", true)!!
        assertNotEquals(a1.id, a2.id)
        val finalized = LogSessionStore.finalize(context, "C", LogSessionTerminal.FAILED, "CrashExit", extraArtifacts = listOf(a1, a2))
        assertEquals(2, finalized!!.artifacts.size)
        val export = SessionExport.export(context, "C")!!
        assertTrue(export.membership.count { it.contains("RPCSX.log") } >= 2)
    }

    @Test
    fun currentRingNotAttachedToOldSession() {
        LogSessionStore.begin(context, "old", "/o", null, "Old", null, null, null, null, null, 1)
        LogSessionStore.finalize(context, "old", LogSessionTerminal.CLEAN_STOP, "HomeStop")
        val export = SessionExport.export(context, "old")!!
        assertTrue(export.membership.none { it.contains("broker-ring-tail") })
    }
}
