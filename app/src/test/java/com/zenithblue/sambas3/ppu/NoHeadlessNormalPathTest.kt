package com.zenithblue.sambas3.ppu

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameInfoStore
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
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
class NoHeadlessNormalPathTest {

    private lateinit var ctx: Context
    private lateinit var oldRoot: String

    @Before
    fun setUp() {
        oldRoot = RPCSX.rootDirectory
        ctx = ApplicationProvider.getApplicationContext()
        val tmpRoot = ctx.filesDir.absolutePath + "/noHeadless_${System.nanoTime()}/"
        File(tmpRoot).mkdirs()
        RPCSX.rootDirectory = tmpRoot
        PpuReadinessStore.load(ctx)
        ImportPpuPreparationCoordinator.resetHeadlessInvocationCountForTest()
        listOf("BLUS30443", "BLUS11111", "BLUS22222").forEach {
            PpuReadinessStore.removeEntry(ctx, it)
        }
    }

    @After
    fun tearDown() {
        RPCSX.rootDirectory = oldRoot
    }

    private fun game(title: String) = Game(
        GameInfoStore(
            "/files/config/games/$title",
            mutableStateOf("Test $title"),
            mutableStateOf(null),
            mutableIntStateOf(0),
        )
    )

    @Test
    fun postInstallTerminal_zeroHeadless_andDoesNotManufactureRuntimeReady() {
        ImportPpuPreparationCoordinator.onInstallPpuSuccess(ctx, "BLUS30443")
        assertEquals(0, ImportPpuPreparationCoordinator.headlessInvocationCount)
        assertEquals(PreRuntimePpuState.READY, PpuReadinessStore.getPreRuntimeState(ctx, "BLUS30443"))
        assertEquals(RuntimePpuState.NOT_STARTED, PpuReadinessStore.getRuntimeState(ctx, "BLUS30443"))
        assertFalse(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
    }

    @Test
    fun prepareFromNotDone_doesNotManufactureReady_zeroHeadless() {
        PpuReadinessStore.setPreRuntimeState(ctx, "BLUS11111", PreRuntimePpuState.NOT_DONE)
        PpuReadinessStore.setRuntimeState(ctx, "BLUS11111", RuntimePpuState.NOT_STARTED)
        val action = ImportPpuPreparationCoordinator.requestPreparation(ctx, game("BLUS11111"))
        assertEquals(PpuUserAction.REIMPORT_OR_REBUILD_INSTALL_PPU, action)
        assertEquals(PreRuntimePpuState.NOT_DONE, PpuReadinessStore.getPreRuntimeState(ctx, "BLUS11111"))
        assertEquals(0, ImportPpuPreparationCoordinator.headlessInvocationCount)
    }

    @Test
    fun prepareFromInvalidatedAndFailed_doesNotManufactureReady() {
        for (pre in listOf(PreRuntimePpuState.INVALIDATED, PreRuntimePpuState.FAILED)) {
            val key = if (pre == PreRuntimePpuState.INVALIDATED) "BLUS11111" else "BLUS22222"
            PpuReadinessStore.setPreRuntimeState(ctx, key, pre)
            val before = PpuReadinessStore.getPreRuntimeState(ctx, key)
            val action = ImportPpuPreparationCoordinator.requestPreparation(ctx, game(key))
            assertEquals(PpuUserAction.REIMPORT_OR_REBUILD_INSTALL_PPU, action)
            assertEquals(before, PpuReadinessStore.getPreRuntimeState(ctx, key))
            assertEquals(0, ImportPpuPreparationCoordinator.headlessInvocationCount)
        }
    }

    @Test
    fun startAndPrepareFromReadyNotStarted_zeroHeadless() {
        PpuReadinessStore.setPreRuntimeState(ctx, "BLUS30443", PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(ctx, "BLUS30443", RuntimePpuState.NOT_STARTED)
        val action = ImportPpuPreparationCoordinator.requestPreparation(ctx, game("BLUS30443"))
        assertEquals(PpuUserAction.START_AND_PREPARE_RUNTIME, action)
        assertTrue(PpuUserActionDecision.canEnterRealBoot(action))
        assertEquals(0, ImportPpuPreparationCoordinator.headlessInvocationCount)
        assertFalse(PpuReadinessStore.isRuntimeValidated(ctx, "BLUS30443"))
    }
}
