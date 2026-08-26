package com.zenithblue.sambas3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import com.zenithblue.sambas3.ppu.GameRunEligibilityHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PpuReadinessStoreRecoveryTest {

    private lateinit var ctx: Context
    private lateinit var oldRoot: String

    @Before
    fun setUp() {
        oldRoot = RPCSX.rootDirectory
        ctx = ApplicationProvider.getApplicationContext()
        val tmpRoot = ctx.filesDir.absolutePath + "/sambaRecoveryTest_${System.nanoTime()}/"
        File(tmpRoot).mkdirs()
        RPCSX.rootDirectory = tmpRoot
        PpuReadinessStore.load(ctx)
        // clear any existing entries for our test keys
        listOf("TEST_COMP", "TEST_IDLE", "TEST_NOTSTARTED", "TEST_FAILED", "TEST_RECOVERY").forEach {
            PpuReadinessStore.removeEntry(ctx, it)
        }
    }

    @After
    fun tearDown() {
        RPCSX.rootDirectory = oldRoot
    }

    @Test
    fun compiling_becomes_failed_after_recovery() {
        PpuReadinessStore.setRuntimeState(ctx, "TEST_COMP", RuntimePpuState.COMPILING)
        assertEquals(RuntimePpuState.COMPILING, PpuReadinessStore.getRuntimeState(ctx, "TEST_COMP"))
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(ctx)
        assertTrue(recovered.contains("TEST_COMP"))
        assertEquals(RuntimePpuState.FAILED, PpuReadinessStore.getRuntimeState(ctx, "TEST_COMP"))
    }

    @Test
    fun idle_after_compile_stays_idle() {
        PpuReadinessStore.setRuntimeState(ctx, "TEST_IDLE", RuntimePpuState.IDLE_AFTER_COMPILE)
        PpuReadinessStore.setPreRuntimeState(ctx, "TEST_IDLE", PreRuntimePpuState.READY)
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(ctx)
        assertTrue(!recovered.contains("TEST_IDLE"))
        assertEquals(RuntimePpuState.IDLE_AFTER_COMPILE, PpuReadinessStore.getRuntimeState(ctx, "TEST_IDLE"))
        assertEquals(PreRuntimePpuState.READY, PpuReadinessStore.getPreRuntimeState(ctx, "TEST_IDLE"))
    }

    @Test
    fun not_started_stays_not_started() {
        PpuReadinessStore.setRuntimeState(ctx, "TEST_NOTSTARTED", RuntimePpuState.NOT_STARTED)
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(ctx)
        assertTrue(!recovered.contains("TEST_NOTSTARTED"))
        assertEquals(RuntimePpuState.NOT_STARTED, PpuReadinessStore.getRuntimeState(ctx, "TEST_NOTSTARTED"))
    }

    @Test
    fun failed_stays_failed() {
        PpuReadinessStore.setRuntimeState(ctx, "TEST_FAILED", RuntimePpuState.FAILED)
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(ctx)
        assertTrue(!recovered.contains("TEST_FAILED"))
        assertEquals(RuntimePpuState.FAILED, PpuReadinessStore.getRuntimeState(ctx, "TEST_FAILED"))
    }

    @Test
    fun stale_compiling_after_reconciliation_is_failed_not_preparing() {
        val key = "TEST_RECOVERY"
        // simulate fresh game with BLUS style key via direct store
        PpuReadinessStore.setPreRuntimeState(ctx, key, PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(ctx, key, RuntimePpuState.COMPILING)
        // reconcile (no live job, no prelaunch, Stopped)
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(ctx)
        assertTrue(recovered.contains(key))
        // eligibility should now be Failed, not PreparingPpu
        val game = Game(GameInfoStore("/files/config/games/$key", androidx.compose.runtime.mutableStateOf("Test"), androidx.compose.runtime.mutableStateOf(null), androidx.compose.runtime.mutableIntStateOf(0)))
        val availability = GameRunEligibilityHelper.evaluateAvailability(
            ctx, game, installPpuActive = false,
            prelaunchState = com.zenithblue.sambas3.CompileProgressBridge.CompileState(ppuActive = false),
            runtimeState = com.zenithblue.sambas3.CompileProgressBridge.CompileState(ppuActive = false),
            emulatorState = EmulatorState.Stopped,
            activeGame = null
        )
        assertTrue(availability is GameLaunchAvailability.Failed)
        assertTrue((availability as GameLaunchAvailability.Failed).retryable)
    }
}
