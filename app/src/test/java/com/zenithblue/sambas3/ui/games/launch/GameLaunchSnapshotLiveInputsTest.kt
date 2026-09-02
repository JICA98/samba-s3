package com.zenithblue.sambas3.ui.games.launch

import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
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
class GameLaunchSnapshotLiveInputsTest {

    private lateinit var ctx: android.content.Context
    private lateinit var oldRoot: String
    private val title = "BLUS30443"

    @Before
    fun setUp() {
        oldRoot = RPCSX.rootDirectory
        ctx = ApplicationProvider.getApplicationContext()
        val tmpRoot = ctx.filesDir.absolutePath + "/launchSnap_${System.nanoTime()}/"
        File(tmpRoot).mkdirs()
        File(tmpRoot, "config/games/$title").mkdirs()
        RPCSX.rootDirectory = tmpRoot
        com.zenithblue.sambas3.utils.GeneralSettings.init(ctx)
        PpuReadinessStore.load(ctx)
        PpuReadinessStore.removeEntry(ctx, title)
        CompileProgressBridge.clearForTest()
    }

    @After
    fun tearDown() {
        CompileProgressBridge.clearForTest()
        RPCSX.rootDirectory = oldRoot
    }

    private fun game(): Game = Game(
        GameInfoStore(
            "/files/config/games/$title",
            androidx.compose.runtime.mutableStateOf("Demon's Souls"),
            androidx.compose.runtime.mutableStateOf(null),
            androidx.compose.runtime.mutableIntStateOf(0),
        )
    )

    @Test
    fun liveInstallActive_snapshotNotNeedsPreparationOnly() {
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = title,
                ppuPercent = 42,
            ),
            prelaunchPpu = CompileProgressBridge.CompileState(),
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
            preRuntimeState = PreRuntimePpuState.IN_PROGRESS,
            runtimeReadyState = RuntimePpuState.NOT_STARTED,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertEquals(PpuPhaseState.Compiling, snap.ppuUi.installPpu.state)
        assertEquals(42, snap.ppuUi.installPpu.progress)
        assertFalse(snap.canPlayFresh)
        assertFalse(snap.ppuUi.startEnabled)
        assertTrue(snap.ppuStatus.contains("Preparing") || snap.ppuStatus == "Preparing PPU")
    }

    @Test
    fun livePrelaunchActive_exposesRuntimeCompiling() {
        val prelaunch = CompileProgressBridge.CompileState(
            ppuActive = true,
            titleId = title,
            ppuPercent = 77,
        )
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(),
            prelaunchPpu = prelaunch,
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
            preRuntimeState = PreRuntimePpuState.READY,
            runtimeReadyState = RuntimePpuState.COMPILING,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertEquals(PpuPhaseState.Compiling, snap.ppuUi.runtimePpu.state)
        assertEquals(77, snap.ppuUi.runtimePpu.progress)
        assertFalse(snap.canPlayFresh)
    }

    @Test
    fun foreignPrelaunch_doesNotEnableForeignPercentOnCurrentTitle() {
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(),
            prelaunchPpu = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLES00000",
                ppuPercent = 99,
            ),
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
            preRuntimeState = PreRuntimePpuState.NOT_DONE,
            runtimeReadyState = RuntimePpuState.NOT_STARTED,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertTrue(snap.ppuUi.runtimePpu.progress == null || snap.ppuUi.runtimePpu.progress != 99)
        assertEquals(PpuPhaseState.Waiting, snap.ppuUi.runtimePpu.state)
    }

    @Test
    fun bothPhasesReady_startEnabled() {
        PpuReadinessStore.setPreRuntimeState(ctx, title, PreRuntimePpuState.READY)
        PpuReadinessStore.markRuntimeValidatedByRealBoot(ctx, title)
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(),
            prelaunchPpu = CompileProgressBridge.CompileState(),
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
            preRuntimeState = PreRuntimePpuState.READY,
            runtimeReadyState = RuntimePpuState.IDLE_AFTER_COMPILE,
            validatedByRealBootFrame = true,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertTrue(snap.ppuUi.startEnabled)
        assertTrue(snap.canPlayFresh)
        assertEquals(PpuPhaseState.Ready, snap.ppuUi.installPpu.state)
        assertEquals(PpuPhaseState.Ready, snap.ppuUi.runtimePpu.state)
    }

    @Test
    fun installReadyWithoutValidation_willPrepareOnStart() {
        PpuReadinessStore.setPreRuntimeState(ctx, title, PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(ctx, title, RuntimePpuState.NOT_STARTED)
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(),
            prelaunchPpu = CompileProgressBridge.CompileState(),
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
            preRuntimeState = PreRuntimePpuState.READY,
            runtimeReadyState = RuntimePpuState.NOT_STARTED,
            validatedByRealBootFrame = false,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertTrue(snap.ppuUi.startEnabled)
        assertEquals("Will prepare on start", snap.ppuUi.runtimePpu.detail)
        assertEquals(PrimaryStartLabel.StartAndPrepare, snap.ppuUi.primaryStartLabel)
    }

    @Test
    fun emptySaves_compactEmptySavesTrue() {
        val inputs = LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.CompileState(),
            prelaunchPpu = CompileProgressBridge.CompileState(),
            runtimePpu = CompileProgressBridge.CompileState(),
            emulatorState = EmulatorState.Stopped,
            activeGame = null,
        )
        val snap = GameLaunchRepository.snapshot(ctx, game(), inputs)
        assertTrue(snap.compactEmptySaves)
        assertTrue(snap.saveSlots.none { it.exists })
    }
}
