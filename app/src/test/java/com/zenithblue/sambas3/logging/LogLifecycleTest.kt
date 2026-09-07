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

@RunWith(RobolectricTestRunner::class)
class LogLifecycleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LogSessionStore.sessionsRoot(context).deleteRecursively()
    }

    @Test
    fun reconcileSkipsLiveCurrentProcessSession() {
        LogSessionStore.begin(
            context, "live", "/g", null, "Live", null, null, null, null, null, 1,
            processInstanceId = "proc-now",
        )
        LogSessionStore.begin(
            context, "old", "/o", null, "Old", null, null, null, null, null, 1,
            processInstanceId = "proc-old",
        )
        val updated = LogSessionStore.reconcileInterrupted(
            context,
            liveSessionId = "live",
            liveProcessInstanceId = "proc-now",
        )
        assertTrue(updated.none { it.sessionId == "live" })
        assertEquals(LogSessionTerminal.RUNNING, LogSessionStore.read(context, "live")?.terminalState)
        assertEquals(LogSessionTerminal.INTERRUPTED, LogSessionStore.read(context, "old")?.terminalState)
        assertEquals(CaptureState.RECOVERED_PARTIAL, LogSessionStore.read(context, "old")?.captureState)
    }

    @Test
    fun finalizeDoesNotDowngradeCrash() {
        LogSessionStore.begin(context, "s", "/g", null, "G", null, null, null, null, null, 1)
        LogSessionStore.finalize(context, "s", LogSessionTerminal.CRASHED, "fatal")
        val again = LogSessionStore.finalize(context, "s", LogSessionTerminal.CLEAN_STOP, "HomeStop")
        assertEquals(LogSessionTerminal.CRASHED, again?.terminalState)
        assertEquals("HomeStop", again?.stopReason)
    }

    @Test
    fun deleteEligibleSkipsActiveAndPinned() {
        LogSessionStore.begin(context, "active", "/a", null, "A", null, null, null, null, null, 1)
        LogSessionStore.begin(context, "sealed", "/s", null, "S", null, null, null, null, null, 1)
        LogSessionStore.finalize(context, "sealed", LogSessionTerminal.CLEAN_STOP, "HomeStop")
        LogSessionStore.pin("sealed")
        val deleted = LogSessionStore.deleteEligible(context, listOf("active", "sealed"), activeSessionId = "active")
        assertEquals(0, deleted)
        LogSessionStore.unpin("sealed")
        val deleted2 = LogSessionStore.deleteEligible(context, listOf("active", "sealed"), activeSessionId = "active")
        assertEquals(1, deleted2)
        assertNotNull(LogSessionStore.read(context, "active"))
    }

    @Test
    fun unknownEnumIsNotLiveRunning() {
        val dir = LogSessionStore.sessionDir(context, "legacy")
        java.io.File(dir, "manifest.json").writeText("""{"sessionId":"legacy","gamePath":"/g","startedAtMs":1,"terminalState":"WAT"}""")
        val read = LogSessionStore.read(context, "legacy")
        assertEquals("legacy", read?.sessionId)
        assertEquals(LogSessionTerminal.INTERRUPTED, read?.terminalState)
        assertEquals("WAT", read?.unknownTerminalRaw)
    }

    @Test
    fun displayClearDoesNotDeleteDisk() {
        LogSessionStore.begin(context, "keep", "/k", null, "Keep", null, null, null, null, null, 1)
        LogBroker.engine.clearView()
        assertNotNull(LogSessionStore.read(context, "keep"))
    }
}
