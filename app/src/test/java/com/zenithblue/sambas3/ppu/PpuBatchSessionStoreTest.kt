package com.zenithblue.sambas3.ppu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PpuBatchSessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun installAndRuntimeSessionsAreIndependent() {
        PpuInstallSessionStore.clear(context, PpuBatchKind.INSTALL)
        PpuInstallSessionStore.clear(context, PpuBatchKind.RUNTIME)
        val install = PpuInstallSession(
            kind = PpuBatchKind.INSTALL,
            sessionId = 10,
            jobId = 11,
            titleId = "BLUS30443",
            gamePath = "/game",
            manifestKey = "install",
        )
        val runtime = install.copy(
            kind = PpuBatchKind.RUNTIME,
            sessionId = 20,
            jobId = 21,
            manifestKey = "runtime",
        )

        PpuInstallSessionStore.save(context, install)
        PpuInstallSessionStore.save(context, runtime)

        assertEquals(10L, PpuInstallSessionStore.load(context, PpuBatchKind.INSTALL)?.sessionId)
        assertEquals(20L, PpuInstallSessionStore.load(context, PpuBatchKind.RUNTIME)?.sessionId)
        PpuInstallSessionStore.clear(context, PpuBatchKind.RUNTIME)
        assertNull(PpuInstallSessionStore.load(context, PpuBatchKind.RUNTIME))
        assertEquals(10L, PpuInstallSessionStore.load(context, PpuBatchKind.INSTALL)?.sessionId)
    }
}
