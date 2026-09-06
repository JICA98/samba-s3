package com.zenithblue.sambas3.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogSessionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LogSessionStore.sessionsRoot(context).deleteRecursively()
    }

    @Test
    fun begin_running_clean_finalize_round_trip() {
        val started = LogSessionStore.begin(
            context, "s1", "/games/BLUS31584", "BLUS31584", "GTA San Andreas",
            iconPath = null, bootMode = "FreshGame", driverLabel = "turnip",
            appVersion = "test", coreBuildId = "debug", pid = 99, nowMs = 1000L,
        )
        assertEquals(LogSessionTerminal.RUNNING, started.terminalState)
        assertEquals("GTA San Andreas", started.gameTitleSnapshot)
        val finalized = LogSessionStore.finalize(context, "s1", LogSessionTerminal.CLEAN_STOP, "HomeStop", nowMs = 2000L)
        assertNotNull(finalized)
        assertEquals(LogSessionTerminal.CLEAN_STOP, finalized!!.terminalState)
        val cold = LogSessionStore.read(context, "s1")
        assertEquals("GTA San Andreas", cold?.gameTitleSnapshot)
        assertEquals("BLUS31584", cold?.titleId)
        assertEquals(LogSessionTerminal.CLEAN_STOP, cold?.terminalState)
    }

    @Test
    fun failed_finalize_is_retained() {
        LogSessionStore.begin(context, "s2", "/g", null, "Title", null, null, null, null, null, 1)
        LogSessionStore.finalize(context, "s2", LogSessionTerminal.FAILED, "CrashExit")
        assertEquals(LogSessionTerminal.FAILED, LogSessionStore.read(context, "s2")?.terminalState)
        assertEquals("CrashExit", LogSessionStore.read(context, "s2")?.stopReason)
    }

    @Test
    fun interrupted_write_recovers_from_tmp() {
        val dir = LogSessionStore.sessionDir(context, "s3")
        val manifest = LogSessionManifest(
            sessionId = "s3",
            gamePath = "/g",
            titleId = "BLUS1",
            gameTitleSnapshot = "Kept",
            gameIconSnapshotPath = null,
            startedAtMs = 1L,
        )
        File(dir, "manifest.json.tmp").writeText(LogSessionStore.toJson(manifest).toString())
        val read = LogSessionStore.readManifest(dir)
        assertEquals("Kept", read?.gameTitleSnapshot)
    }

    @Test
    fun cleanup_never_deletes_active_session() {
        LogSessionStore.begin(context, "active", "/a", null, "Active", null, null, null, null, null, 1, nowMs = 9_000L)
        repeat(20) { i ->
            LogSessionStore.begin(context, "old$i", "/o$i", null, "Old$i", null, null, null, null, null, 1, nowMs = i.toLong())
            LogSessionStore.finalize(context, "old$i", LogSessionTerminal.CLEAN_STOP, "done")
        }
        LogSessionStore.cleanupOld(context, activeSessionId = "active")
        assertNotNull(LogSessionStore.read(context, "active"))
        assertEquals(LogSessionTerminal.RUNNING, LogSessionStore.read(context, "active")?.terminalState)
    }

    @Test
    fun schema_defaults_when_fields_missing() {
        val dir = LogSessionStore.sessionDir(context, "legacy")
        File(dir, "manifest.json").writeText("""{"sessionId":"legacy","gamePath":"/g","startedAtMs":1}""")
        val read = LogSessionStore.read(context, "legacy")
        assertEquals("legacy", read?.sessionId)
        assertEquals(LogSessionTerminal.RUNNING, read?.terminalState)
        assertTrue(read?.gameTitleSnapshot?.isNotBlank() == true)
    }
}
