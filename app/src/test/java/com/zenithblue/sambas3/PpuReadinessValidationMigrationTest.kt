package com.zenithblue.sambas3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PpuReadinessValidationMigrationTest {

    private lateinit var ctx: Context
    private lateinit var oldRoot: String

    @Before
    fun setUp() {
        oldRoot = RPCSX.rootDirectory
        ctx = ApplicationProvider.getApplicationContext()
        val tmpRoot = ctx.filesDir.absolutePath + "/ppuMigrate_${System.nanoTime()}/"
        File(tmpRoot, "config/prefs").mkdirs()
        RPCSX.rootDirectory = tmpRoot
    }

    @After
    fun tearDown() {
        RPCSX.rootDirectory = oldRoot
    }

    @Test
    fun oldIdleJsonWithoutValidationField_loadsAsNotValidated() {
        val prefs = File(RPCSX.rootDirectory, "config/prefs")
        prefs.mkdirs()
        // Legacy shape: no validatedByRealBootFrame / readinessVersion fields
        File(prefs, "ppu_state.json").writeText(
            """
            {"version":1,"entries":{"BLUS30443":{"key":"BLUS30443","preRuntime":"READY","runtime":"IDLE_AFTER_COMPILE","fingerprint":"legacy","updatedMs":1}}}
            """.trimIndent()
        )
        PpuReadinessStore.load(ctx)
        assertEquals(RuntimePpuState.IDLE_AFTER_COMPILE, PpuReadinessStore.getRuntimeState(ctx, "BLUS30443"))
        assertEquals(PreRuntimePpuState.READY, PpuReadinessStore.getPreRuntimeState(ctx, "BLUS30443"))
        assertFalse(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
    }

    @Test
    fun markValidatedByRealBoot_persistsAndReloadsTrue() {
        PpuReadinessStore.load(ctx)
        PpuReadinessStore.setPreRuntimeState(ctx, "BLUS30443", PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(ctx, "BLUS30443", RuntimePpuState.NOT_STARTED)
        assertFalse(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
        PpuReadinessStore.markRuntimeValidatedByRealBoot(ctx, "BLUS30443")
        assertTrue(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
        assertEquals(RuntimePpuState.IDLE_AFTER_COMPILE, PpuReadinessStore.getRuntimeState(ctx, "BLUS30443"))

        // Force reload from disk
        PpuReadinessStore.load(ctx)
        assertTrue(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
        val raw = File(RPCSX.rootDirectory, "config/prefs/ppu_state.json").readText()
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(PpuStateFile.serializer(), raw)
        assertTrue(parsed.entries.getValue("BLUS30443").validatedByRealBootFrame)
        assertEquals(2, parsed.entries.getValue("BLUS30443").readinessVersion)
    }

    @Test
    fun setRuntimeIdleWithoutMark_doesNotValidate() {
        PpuReadinessStore.load(ctx)
        PpuReadinessStore.setPreRuntimeState(ctx, "BLUS30443", PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(ctx, "BLUS30443", RuntimePpuState.IDLE_AFTER_COMPILE)
        assertFalse(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
    }
}
