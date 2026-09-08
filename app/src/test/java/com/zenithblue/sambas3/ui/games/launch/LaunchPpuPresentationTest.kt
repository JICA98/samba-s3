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
        assertEquals(PrepareAction.Stop, ui.prepareAction)
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
        assertEquals(PrepareAction.Stop, ui.prepareAction)
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
        assertTrue(ui.runtimePpu.detail?.contains("Waiting") == true)
        assertFalse(ui.startEnabled)
        assertEquals(PrepareAction.Locked, ui.prepareAction)
        assertEquals("Another game is compiling PPU", ui.statusLine)
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
    fun installReadyRuntimeNotStarted_requiresPreparation() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.NOT_STARTED,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PpuPhaseState.Ready, ui.installPpu.state)
        assertEquals("Needs preparation", ui.runtimePpu.detail)
        assertFalse(ui.startEnabled)
        assertEquals(PrepareAction.Prepare, ui.prepareAction)
    }

    @Test
    fun kotlinPreparedIdleWithoutValidation_isReadyToStart() {
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
        assertEquals("Ready", ui.runtimePpu.detail)
        assertEquals(PrimaryStartLabel.Start, ui.primaryStartLabel)
    }

    @Test
    fun needsPreparation_showsPrepareAction() {
        val inputs = idleInputs()
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PrepareAction.Prepare, ui.prepareAction)
        assertFalse(ui.startEnabled)
        assertEquals("PPU preparation required", ui.statusLine)
    }

    @Test
    fun failedPhases_showRetryPreparation() {
        val installFailed = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Failed(true, "Install PPU failed — retry preparation"),
            idleInputs(pre = PreRuntimePpuState.FAILED, rt = RuntimePpuState.NOT_STARTED),
        )
        assertEquals(PrepareAction.Retry, installFailed.prepareAction)
        assertFalse(installFailed.startEnabled)

        val runtimeFailed = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Failed(true, "Runtime PPU failed — retry preparation"),
            idleInputs(pre = PreRuntimePpuState.READY, rt = RuntimePpuState.FAILED),
        )
        assertEquals("Preparation failed", runtimeFailed.runtimePpu.detail)
        assertFalse(runtimeFailed.startEnabled)
        assertEquals(PrepareAction.Retry, runtimeFailed.prepareAction)
    }

    @Test
    fun deferredFgs_showsWaitingToContinue() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.COMPILING,
            waiting = true,
            deferred = true,
        ).copy(activeCompileTitleId = "BLUS30443")
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
    fun homeOverlay_includesInstallPpuWithoutImportRow() {
        assertTrue(
            LaunchPpuPresentation.homeCardShowsCompileOverlay(
                isImporting = false,
                isRuntimeGameCompile = false,
                usingPrelaunchPpu = false,
                usingInstallPpu = true,
            )
        )
        assertFalse(
            LaunchPpuPresentation.homeCardShowsCompileOverlay(
                isImporting = false,
                isRuntimeGameCompile = false,
                usingPrelaunchPpu = false,
                usingInstallPpu = false,
            )
        )
    }

    @Test
    fun phaseStatusText_compilingPrefersModuleDetailOverZeroPercent() {
        val compiling = PpuPhaseUi("Install PPU", PpuPhaseState.Compiling, 0, "module 7")
        assertEquals("module 7", LaunchPpuPresentation.phaseStatusText(compiling))
        val withPct = PpuPhaseUi("Install PPU", PpuPhaseState.Compiling, 42, "module 12 of 80")
        assertEquals("module 12 of 80", LaunchPpuPresentation.phaseStatusText(withPct))
        val ready = PpuPhaseUi("Runtime PPU", PpuPhaseState.Ready, null, "Ready")
        assertEquals("Ready", LaunchPpuPresentation.phaseStatusText(ready))
    }

    @Test
    fun compilingInstall_carriesRemainingLabelWithoutReplacingModuleDetail() {
        val inputs = idleInputs(
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS31584",
                ppuPercent = 35,
                ppuMsg = "module 25 of 71",
                moduleDone = 25,
                moduleTotal = 71,
                remainingLabel = "~8 min remaining",
            ),
            pre = PreRuntimePpuState.IN_PROGRESS,
        )
        val ui = LaunchPpuPresentation.build(
            "BLUS31584",
            GameLaunchAvailability.PreparingPpu(null),
            inputs,
        )
        assertEquals(PpuPhaseState.Compiling, ui.installPpu.state)
        assertEquals("module 25 of 71", ui.installPpu.detail)
        assertEquals("~8 min remaining", ui.installPpu.remainingLabel)
        assertEquals("module 25 of 71", LaunchPpuPresentation.phaseStatusText(ui.installPpu))
        assertEquals(
            "module 25 of 71 · ~8 min remaining",
            LaunchPpuPresentation.phaseStatusLine(ui.installPpu),
        )
        assertEquals(
            "module 25 of 71 · ~8 min remaining",
            LaunchPpuPresentation.compileProgressLine(inputs.installPpu),
        )
    }

    @Test
    fun compileProgressLine_omitsRemainingWhenUnknown() {
        val state = CompileProgressBridge.CompileState(
            ppuActive = true,
            ppuMsg = "module 7 of 80",
            moduleDone = 7,
            moduleTotal = 80,
        )
        assertEquals("module 7 of 80", LaunchPpuPresentation.compileProgressLine(state))
    }

    @Test
    fun compileProgressPercent_usesModuleCounts() {
        val state = CompileProgressBridge.CompileState(
            ppuActive = true,
            ppuPercent = 0,
            moduleDone = 7,
            moduleTotal = 80,
            ppuMsg = "module 7 of 80",
        )
        assertEquals(8, LaunchPpuPresentation.compileProgressPercent(state))
        assertEquals("module 7 of 80", LaunchPpuPresentation.compileProgressDetail(state))
        val unknown = CompileProgressBridge.CompileState(
            ppuActive = true,
            ppuPercent = 0,
            moduleDone = 7,
            moduleTotal = 0,
            ppuMsg = "module 7",
        )
        assertEquals(null, LaunchPpuPresentation.compileProgressPercent(unknown))
        assertEquals("module 7", LaunchPpuPresentation.compileProgressDetail(unknown))
    }

    @Test
    fun orphanedInstallInProgress_showsRetryNotPreparing() {
        val ui = LaunchPpuPresentation.build(
            "BCUS98125",
            GameLaunchAvailability.Failed(true, "Install PPU interrupted — retry to resume"),
            idleInputs(pre = PreRuntimePpuState.IN_PROGRESS, rt = RuntimePpuState.NOT_STARTED),
        )
        assertEquals(PpuPhaseState.Failed, ui.installPpu.state)
        assertEquals("Interrupted — retry to resume", ui.installPpu.detail)
        assertEquals(PpuPhaseState.Waiting, ui.runtimePpu.state)
        assertEquals(PrepareAction.Retry, ui.prepareAction)
        assertFalse(ui.startEnabled)
        assertEquals("Install PPU interrupted — retry to resume", ui.statusLine)
    }

    @Test
    fun orphanedRuntimeCompiling_showsRetryNotFinalizing() {
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Failed(true, "Runtime PPU interrupted — retry to resume"),
            idleInputs(pre = PreRuntimePpuState.READY, rt = RuntimePpuState.COMPILING),
        )
        assertEquals(PpuPhaseState.Failed, ui.runtimePpu.state)
        assertEquals("Interrupted — retry to resume", ui.runtimePpu.detail)
        assertEquals(PrepareAction.Retry, ui.prepareAction)
        assertFalse(ui.startEnabled)
        assertEquals("Runtime PPU interrupted — retry to resume", ui.statusLine)
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

    @Test
    fun foreignInstall_locksPrepareAndDisablesStart() {
        val inputs = idleInputs(
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS99999",
                ppuPercent = 40,
                ppuMsg = "module 10 of 40",
            ),
            pre = PreRuntimePpuState.NOT_DONE,
        ).copy(activeCompileTitleId = "BLUS99999")
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.NeedsPreparation,
            inputs,
        )
        assertEquals(PrepareAction.Locked, ui.prepareAction)
        assertEquals(PpuPhaseState.Waiting, ui.installPpu.state)
        assertEquals("Waiting — another game is compiling", ui.installPpu.detail)
        assertEquals("Another game is compiling PPU", ui.statusLine)
        assertFalse(ui.startEnabled)
    }

    @Test
    fun thisTitleCompiling_showsStopNotPrepare() {
        val inputs = idleInputs(
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS30443",
                ppuPercent = 40,
            ),
            pre = PreRuntimePpuState.IN_PROGRESS,
        ).copy(activeCompileTitleId = "BLUS30443")
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.PreparingPpu(null),
            inputs,
        )
        assertEquals(PrepareAction.Stop, ui.prepareAction)
        assertEquals(PpuPhaseState.Compiling, ui.installPpu.state)
        assertFalse(ui.startEnabled)
    }

    @Test
    fun stoppingThisTitle_showsStopping() {
        val inputs = idleInputs(
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLUS30443",
            ),
            pre = PreRuntimePpuState.IN_PROGRESS,
        ).copy(activeCompileTitleId = "BLUS30443", stoppingCompile = true)
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.PreparingPpu(null),
            inputs,
        )
        assertEquals(PrepareAction.Stopping, ui.prepareAction)
        assertEquals("Stopping PPU compilation", ui.statusLine)
        assertFalse(ui.startEnabled)
    }

    @Test
    fun readyTitle_lockedWhileOtherCompiles() {
        val inputs = idleInputs(
            pre = PreRuntimePpuState.READY,
            rt = RuntimePpuState.IDLE_AFTER_COMPILE,
            validated = true,
            install = CompileProgressBridge.CompileState(
                ppuActive = true,
                titleId = "BLES00001",
            ),
        ).copy(activeCompileTitleId = "BLES00001")
        val ui = LaunchPpuPresentation.build(
            "BLUS30443",
            GameLaunchAvailability.Ready,
            inputs,
        )
        assertEquals(PrepareAction.Locked, ui.prepareAction)
        assertFalse(ui.startEnabled)
        assertEquals("Another game is compiling PPU", ui.statusLine)
        assertEquals(PpuPhaseState.Ready, ui.installPpu.state)
    }
}
