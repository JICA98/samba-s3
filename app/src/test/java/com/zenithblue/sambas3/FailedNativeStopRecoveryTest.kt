package com.zenithblue.sambas3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.logging.CaptureState
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.logging.LogSessionTerminal
import com.zenithblue.sambas3.logging.SessionDiagnostics
import com.zenithblue.sambas3.session.EmulationSessionJournal
import com.zenithblue.sambas3.session.FailedNativeStopRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class FailedNativeStopRecoveryTest {
    @Test
    fun frame_timeout_finalizes_manifest_before_scheduled_process_kill() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = EmulationSessionJournal.begin(context, "/game", "BCUS98123", "Game", 1L, 1L)
        LogSessionStore.begin(context, session.sessionId, "/game", "BCUS98123", "Game",
            null, "FreshGame", null, "test", "core", 1)
        val evidence = "frame-timeout no-produced-frame-for=120000ms presented=1081 state=Running"
        val nativeLog = File(context.cacheDir, "RPCSX.log").apply { writeText("RSX timeout test\n") }
        EmulationSessionJournal.markFailure(context, "timeout-test", reason = evidence)

        FailedNativeStopRecovery.scheduleAfterFrameTimeout(context, evidence)

        val persisted = requireNotNull(LogSessionStore.read(context, session.sessionId))
        assertEquals(LogSessionTerminal.FAILED, persisted.terminalState)
        assertEquals(evidence, persisted.stopReason)
        assertEquals(CaptureState.RECOVERED_PARTIAL, persisted.captureState)
        assertTrue(requireNotNull(persisted.endedAtMs) > 0L)
        assertEquals("FRAME_TIMEOUT", SessionDiagnostics.project(persisted).diagnosticCause?.name)
        val snapshot = persisted.artifacts.first { it.originalFilename == "RPCSX.log" }
        assertEquals(nativeLog.readText(), File(snapshot.path).readText())
    }
}
