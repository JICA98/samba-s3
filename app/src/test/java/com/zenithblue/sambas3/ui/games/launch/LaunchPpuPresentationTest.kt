package com.zenithblue.sambas3.ui.games.launch

import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPpuPresentationTest {

    private fun idleInputs(
        pre: PreRuntimePpuState = PreRuntimePpuState.NOT_DONE,
        rt: RuntimePpuState = RuntimePpuState.NOT_STARTED,
        install: CompileProgressBridge.CompileState = CompileProgressBridge.CompileState(),
        prelaunch: CompileProgressBridge.CompileState = CompileProgressBridge.CompileState(),
        runtime: CompileProgressBridge.CompileState = CompileProgressBridge.CompileState(),
        waiting: Boolean = false,
        deferred: Boolean = false,
        fgsDenied: Boolean = false,
        validated: Boolean = false,
    ) = LaunchRuntimeInputs(
        installPpu = install,
        prelaunchPpu = prelaunch,
        runtimePpu = runtime,
        emulatorState = EmulatorState.Stopped,
        activeGame = null,
        waitingForIdle = waiting,
        deferredForFgs = deferred,
        fgsStartDenied = fgsDenied,
        preRuntimeState = pre,
        runtimeReadyState = rt,
        validatedByRealBootFrame = validated,
    )

    @Test
    fun installActive_mapsInstallRowCompiling_notFakeReady() {
        val inputs = idleInputs(
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS30443",
                ppuPercent = 68,
                ppuMsg = "Progress: file 1 of 2",
            ),
            pre = PreRuntimePpuState.IN_PROGRESS,
            rt = RuntimePpuState.NOT_STARTED,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.PreparingPpu(null),
            inputs,
        )
        assertEquals(PpuPhaseState.Compiling, ui.installPpu.state)
        assertEquals(68, ui.installPpu.progress)
        assertEquals(PpuPhaseState.Waiting, ui.runtimePpu.state)
        assertFalse(ui.startEnabled)
        assertEquals(PrepareAction.PreparingInstall, ui.prepareAction)
    }

    @Test
    fun prelaunchActiveForCurrentTitle_mapsRuntimeRowCompiling() {
        val prelaunch = CompileProgressBridge.CompileState(
            ppuActive = true,
            titleId = "BLUS30443",
            ppuPercent = 31,
            ppuMsg = "Compiling modules",
        )
        val inputs = idleInputs(
            prelaunch = prelaunch,
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.COMPILING,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.PreparingPpu(prelaunch),
            inputs,
        )
        assertEquals(PpuPhaseState.Ready, ui.installPpu.state)
        assertEquals(PpuPhaseState.Compiling, ui.runtimePpu.state)
        assertEquals(31, ui.runtimePpu.progress)
        assertFalse(ui.startEnabled)
        assertEquals(PrepareAction.PreparingRuntime, ui.prepareAction)
    }

    @Test
    fun foreignTitlePrelaunch_notAttributedToCurrentTitle() {
        val inputs = idleInputs(
            prelaunch = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS99999",
                ppuPercent = 90,
            ),
            pre = PreRuntimePpuState.NOT_DONE,
            rt = RuntimePpuState.NOT_STARTED,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PpuPhaseState.Waiting, ui.runtimePpu.state)
        assertNull(ui.runtimePpu.progress)
        assertTrue(ui.runtimePpu.detail?.contains("Waiting") == true || ui.runtimePpu.detail == "Waiting")
        assertFalse(ui.startEnabled)
    }

    @Test
    fun validatedReady_startEnabled_runtimeReadyDetail() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.IDLE_AFTER_COMPILE,
            validated = true,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Ready,
            inputs,
        )
        assertEquals(PpuPhaseState.Ready, ui.installPpu.state)
        assertEquals(PpuPhaseState.Ready, ui.runtimePpu.state)
        assertEquals("Ready", ui.runtimePpu.detail)
        assertTrue(ui.startEnabled)
        assertNull(ui.prepareAction)
        assertEquals(PrimaryStartLabel.Start, ui.primaryStartLabel)
    }

    @Test
    fun installReadyRuntimeNotStarted_willPrepareOnStart() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.NOT_STARTED,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Ready,
            inputs,
        )
        assertEquals(PpuPhaseState.Ready, ui.installPpu.state)
        assertEquals("Will prepare on start", ui.runtimePpu.detail)
        assertTrue(ui.startEnabled)
        assertEquals(PrimaryStartLabel.StartAndPrepare, ui.primaryStartLabel)
        assertNull(ui.prepareAction)
    }

    @Test
    fun legacyIdleWithoutValidation_willPrepareOnStart() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.IDLE_AFTER_COMPILE,
            validated = false,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Ready,
            inputs,
        )
        assertEquals("Will prepare on start", ui.runtimePpu.detail)
        assertEquals(PrimaryStartLabel.StartAndPrepare, ui.primaryStartLabel)
    }

    @Test
    fun needsPreparation_showsReimportAction() {
        val inputs = idleInputs()
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PrepareAction.ReimportOrRebuild, ui.prepareAction)
        assertFalse(ui.startEnabled)
        assertEquals("Re-import required", ui.statusLine)
    }

    @Test
    fun installFailed_showsReimport_runtimeFailedWithInstallReady_retryOnStart() {
        val installFailed = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Failed(true, "Install PPU failed — re-import required"),
            idleInputs(pre = PreRuntimePpuState.FAILED, rt = RuntimePpuState.NOT_STARTED),
        )
        assertEquals(PrepareAction.ReimportOrRebuild, installFailed.prepareAction)
        assertFalse(installFailed.startEnabled)

        val runtimeFailed = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Ready,
            idleInputs(pre = PreRuntimePpuState.READY, rt = RuntimePpuState.FAILED),
        )
        assertEquals("Retry on start", runtimeFailed.runtimePpu.detail)
        assertTrue(runtimeFailed.startEnabled)
        assertEquals(PrimaryStartLabel.RetryOnStart, runtimeFailed.primaryStartLabel)
        assertNull(runtimeFailed.prepareAction)
    }

    @Test
    fun deferredFgs_showsWaitingToContinue() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.COMPILING,
            waiting = true,
            deferred = true,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.PreparingPpu(null),
            inputs,
        )
        assertEquals(PpuPhaseState.Deferred, ui.runtimePpu.state)
        assertEquals("Waiting to continue preparation", ui.runtimePpu.detail)
    }

    @Test
    fun emptySaves_compactFlagTrue() {
        assertTrue(LaunchPpuPresentation.compactEmptySaves(hasExistingSaves = false))
        assertFalse(LaunchPpuPresentation.compactEmptySaves(hasExistingSaves = true))
    }

    @Test
    fun transitionSequence_startOnlyEnabledAtReady() {
        val title = "BLUS30443"
        val needs = LaunchPpuPresentation.build(title, GameLaunchAvailability.NeedsPreparation, idleInputs())
        assertFalse(needs.startEnabled)
        assertNotNull(needs.prepareAction)

        val installActive = LaunchPpuPresentation.build(
            title,
            GameLaunchAvailability.PreparingPpu(null),
            idleInputs(
                install = CompileProgressBridge.CompileState(ppuActive = true, titleId = title, ppuPercent = 40),
                pre = PreRuntimePpuState.IN_PROGRESS,
            ),
        )
        assertFalse(installActive.startEnabled)
        assertEquals(PpuPhaseState.Compiling, installActive.installPpu.state)

        val runtimeActive = LaunchPpuPresentation.build(
            title,
            GameLaunchAvailability.PreparingPpu(
                CompileProgressBridge.CompileState(ppuActive = true, titleId = title, ppuPercent = 55),
            ),
            idleInputs(
                prelaunch = CompileProgressBridge.CompileState(ppuActive = true, titleId = title, ppuPercent = 55),
                pre = PreRuntimePpuState.READY,
                rt = RuntimePpuState.COMPILING,
            ),
        )
        assertFalse(runtimeActive.startEnabled)
        assertEquals(PpuPhaseState.Compiling, runtimeActive.runtimePpu.state)

        val ready = LaunchPpuPresentation.build(
            title,
            GameLaunchAvailability.Ready,
            idleInputs(
                pre = PreRuntimePpuState.READY,
                rt = RuntimePpuState.IDLE_AFTER_COMPILE,
                validated = true,
            ),
        )
        assertTrue(ready.startEnabled)
        assertEquals(PrimaryStartLabel.Start, ready.primaryStartLabel)
    }

    @Test
    fun invalidated_mapsInstallNotReady_notPreparing() {
        val inputs = idleInputs(pre = PreRuntimePpuState.INVALIDATED, rt = RuntimePpuState.NOT_STARTED)
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PpuPhaseState.NotReady, ui.installPpu.state)
        assertEquals("Needs preparation", ui.installPpu.detail)
    }
}
