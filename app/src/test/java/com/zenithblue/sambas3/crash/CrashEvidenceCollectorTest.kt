package com.zenithblue.sambas3.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.logging.LogSessionTerminal
import com.zenithblue.sambas3.logging.LogSourceKind
import com.zenithblue.sambas3.logging.SessionDiagnostics
import com.zenithblue.sambas3.session.EmulationSessionRecord
import com.zenithblue.sambas3.session.EmulationSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashEvidenceCollectorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LogSessionStore.sessionsRoot(context).deleteRecursively()
    }

    @Test
    fun collectDoesNotTextConvertOrAttachCurrentRing() {
        LogSessionStore.begin(context, "s", "/g", "BLUS", "GTA", null, null, null, null, null, 1)
        val raw = File(LogSessionStore.sessionDir(context, "s"), "raw/native/1/RPCSX.log")
        raw.parentFile?.mkdirs()
        raw.writeText("timestamped exception\n    at native (pputhread.cpp:12)\n    at more (frame:2)\n")
        val art = LogSessionStore.registerOutput(context, "s", raw, LogSourceKind.RPCSX_BACKEND, "native", "1", true)!!
        LogSessionStore.finalize(context, "s", LogSessionTerminal.FAILED, "CrashExit", extraArtifacts = listOf(art))
        val session = EmulationSessionRecord(
            sessionId = "s",
            gamePath = "/g",
            titleId = "BLUS",
            gameName = "GTA",
            startedAtMs = 1L,
            lastHeartbeatMs = 2L,
            state = EmulationSessionState.FAILED,
            activityInstanceId = 1L,
            surfaceGeneration = 1L,
            driverLabel = null,
            cleanTermination = false,
        )
        val report = CrashEvidenceCollector.collect(context, session)
        val copied = report.sources.values.first { it.name.contains("RPCSX") || it.path.contains("RPCSX") }
        val text = copied.readText()
        assertTrue(text.contains("at native (pputhread.cpp:12)"))
        assertTrue(text.contains("at more (frame:2)"))
        assertFalse(report.sources.keys.any { it.contains("broker-ring") })
        assertEquals("s", report.sessionId)
    }

    @Test
    fun loadFailureWithoutReportStillHasSessionLink() {
        LogSessionStore.begin(context, "boot", "/g", "BLUS", "GTA", null, "FreshGame", null, null, null, 1)
        LogSessionStore.finalize(context, "boot", LogSessionTerminal.FAILED, "BootFailureCleanup")
        val manifest = LogSessionStore.read(context, "boot")!!
        val report = CrashEvidenceCollector.reportForManifest(context, manifest)
        assertEquals("boot", report.sessionId)
        assertEquals(com.zenithblue.sambas3.logging.SessionOutcome.BOOT_FAILURE, report.diagnostics?.outcome)
        assertTrue(report.sources.containsKey("manifest.json") || report.sources.isNotEmpty() || File(report.directory, "manifest.json").isFile)
    }

    @Test
    fun cleanInGameExitExcludedFromCrashFilter() {
        val manifest = LogSessionStore.begin(context, "clean", "/g", "BLUS", "GTA", null, null, null, null, null, 1)
        val finalized = LogSessionStore.finalize(context, "clean", LogSessionTerminal.CLEAN_STOP, "InGameExit")!!
        assertFalse(SessionDiagnostics.isCrashSession(finalized))
        assertEquals(manifest.sessionId, finalized.sessionId)
    }
}
