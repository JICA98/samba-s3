package com.zenithblue.sambas3.ui.ingame

import com.zenithblue.sambas3.EmulatorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeGateway : InGameMenuCoreGateway {
    var beginCount = 0
    var endCount = 0
    var endResumes = mutableListOf<Boolean>()
    var settingsBeginCount = 0
    var settingsEndCount = 0
    var commitCount = 0
    var discardCount = 0
    var transientCount = 0
    var dirty = false
    var caps = InGameMenuCapabilities.EMPTY.copy(
        frontendOwnsHomeMenu = true,
        screenshot = true,
        recordingSupported = true,
        trophiesAvailable = true,
        savestate = SaveStateCapabilities(true, false, true, listOf(SaveSlot(0, false, "Last")))
    )

    override suspend fun beginMenu(): Result<Boolean> {
        beginCount++
        return Result.success(true)
    }

    override suspend fun endMenu(resumeIfOwned: Boolean): Result<Unit> {
        endCount++
        endResumes.add(resumeIfOwned)
        return Result.success(Unit)
    }

    override suspend fun isMenuOpen(): Boolean = false
    override suspend fun capabilities(): Result<InGameMenuCapabilities> = Result.success(caps)
    override suspend fun requestScreenshot(): Result<Boolean> = Result.success(true)
    override suspend fun toggleRecording(): Result<Boolean> = Result.success(true)
    override suspend fun restart(): Result<Boolean> = Result.success(true)
    override suspend fun gracefulShutdown(): Result<Boolean> = Result.success(true)
    override suspend fun saveStateInfo(): Result<SaveStateCapabilities?> = Result.success(caps.savestate)
    override suspend fun saveState(slot: Int): Result<Boolean> = Result.success(true)
    override suspend fun loadState(slot: Int): Result<Boolean> = Result.success(true)
    override suspend fun trophies(): Result<TrophiesData?> = Result.success(null)
    override suspend fun friends(): Result<FriendsData?> = Result.success(null)
    override suspend fun friendAction(action: String, username: String): Result<Boolean> = Result.success(true)

    override suspend fun beginSettings(): Result<Boolean> {
        settingsBeginCount++
        return Result.success(true)
    }

    override suspend fun setTransientSetting(path: String, value: String): Result<Boolean> {
        transientCount++
        dirty = true
        return Result.success(true)
    }

    override suspend fun settingsTree(): Result<String?> = Result.success("{}")
    override suspend fun isSettingsDirty(): Result<Boolean> = Result.success(dirty)

    override suspend fun commitSettings(): Result<Boolean> {
        commitCount++
        dirty = false
        return Result.success(true)
    }

    override suspend fun discardSettings(): Result<Boolean> {
        discardCount++
        dirty = false
        return Result.success(true)
    }

    override suspend fun endSettings(): Result<Unit> {
        settingsEndCount++
        return Result.success(Unit)
    }
}

class InGameMenuCoordinatorTest {

    private lateinit var gateway: FakeGateway
    private lateinit var coordinator: InGameMenuCoordinator

    @Before
    fun setup() {
        gateway = FakeGateway()
        coordinator = InGameMenuCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            core = gateway,
            stateProvider = { EmulatorState.Running }
        )
    }

    private fun awaitCondition(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    private fun openMain() = runBlocking {
        coordinator.dispatch(InGameMenuIntent.Open)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Open }
    }

    // ── Session open/close ─────────────────────────────────────────────────

    @Test
    fun open_once_begins_menu_and_reports_open() {
        openMain()
        assertEquals(1, gateway.beginCount)
        val s = coordinator.state.value
        assertTrue(s.isOpen)
        assertEquals(listOf(InGamePage.Main), (s.session as MenuSessionState.Open).pageStack)
    }

    @Test
    fun duplicate_open_is_ignored() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.Open)
        Thread.sleep(50)
        assertEquals(1, gateway.beginCount)
    }

    @Test
    fun open_rejected_in_non_gameplay_state() = runBlocking {
        val c = InGameMenuCoordinator(CoroutineScope(Dispatchers.Unconfined), gateway, { EmulatorState.Stopped })
        c.dispatch(InGameMenuIntent.Open)
        Thread.sleep(50)
        assertFalse(c.state.value.isOpen)
        assertEquals(0, gateway.beginCount)
    }

    @Test
    fun resume_closes_with_resume_policy() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.Resume)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(true), gateway.endResumes)
        assertFalse(coordinator.state.value.isOpen)
    }

    @Test
    fun outside_dismiss_closes_with_resume_policy() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.DismissOutside)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(true), gateway.endResumes)
    }

    @Test
    fun back_from_main_closes_with_resume_policy() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.Back)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(true), gateway.endResumes)
    }

    @Test
    fun restart_closes_without_resume() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.Restart)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(false), gateway.endResumes)
    }

    @Test
    fun exit_closes_without_resume() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.Exit)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(false), gateway.endResumes)
    }

    @Test
    fun save_state_closes_without_resume_by_default() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.SaveState(0))
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(false), gateway.endResumes)
    }

    @Test
    fun load_state_resumes_like_native_home_menu() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.LoadState(0))
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(listOf(true), gateway.endResumes)
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    @Test
    fun navigation_is_observable_without_side_effects() {
        openMain()
        val page = InGamePage.Settings
        coordinator.dispatch(InGameMenuIntent.ReportItemCount(InGamePage.Main, 10))
        assertEquals(10, coordinator.state.value.itemCounts[InGamePage.Main])
        assertTrue(coordinator.moveSelection(1))
        assertEquals(1, coordinator.state.value.selectedIndex)
        assertTrue(coordinator.moveSelection(-1))
        assertEquals(0, coordinator.state.value.selectedIndex)
        // Push and pop must not depend on selection mutations for recomposition:
        coordinator.dispatch(InGameMenuIntent.OpenTrophies)
        assertEquals(InGamePage.Trophies, coordinator.state.value.currentPage)
        coordinator.dispatch(InGameMenuIntent.Back)
        assertEquals(InGamePage.Main, coordinator.state.value.currentPage)
        coordinator.dispatch(InGameMenuIntent.OpenSettings)
        assertEquals(InGamePage.Settings, coordinator.state.value.currentPage)
        // Settings-root back returns to Main (§12 table), not close:
        coordinator.dispatch(InGameMenuIntent.Back)
        assertEquals(InGamePage.Main, coordinator.state.value.currentPage)
        // Back from Main closes:
        coordinator.dispatch(InGameMenuIntent.Back)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertNull(page.let { coordinator.state.value.currentPage })
    }

    @Test
    fun selection_wraps_within_exact_item_count() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.ReportItemCount(InGamePage.Main, 5))
        assertTrue(coordinator.moveSelection(1))
        assertTrue(coordinator.moveSelection(4))
        assertEquals(0, coordinator.state.value.selectedIndex)
        assertTrue(coordinator.moveSelection(-1))
        assertEquals(4, coordinator.state.value.selectedIndex)
    }

    @Test
    fun selection_rejected_without_reported_item_count() {
        openMain()
        assertFalse(coordinator.moveSelection(1))
        assertEquals(0, coordinator.state.value.selectedIndex)
    }

    @Test
    fun activate_selected_resolves_main_row_intent() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.ReportItemCount(InGamePage.Main, mainRowDescriptors(coordinator.state.value.capabilities).size))
        coordinator.dispatch(InGameMenuIntent.OpenTrophies)
        coordinator.dispatch(InGameMenuIntent.Back)
        // Back on Main page: row 0 is RESUME (enabled) — activate resolves its intent.
        assertEquals(InGameMenuIntent.Resume, coordinator.activateSelectedIntent())
    }

    // ── Settings transaction (exactly-once) ────────────────────────────────

    @Test
    fun settings_begin_once_edit_dirty_save_once() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.OpenSettings)
        awaitCondition { coordinator.state.value.settingsActive }
        coordinator.dispatch(InGameMenuIntent.SettingsTransientSet("video/x", "1"))
        awaitCondition { coordinator.state.value.settingsDirty }
        coordinator.dispatch(InGameMenuIntent.Back)
        // Dirty back must surface the dialog, not close.
        assertTrue(coordinator.state.value.showDirtyDialog)
        assertTrue(coordinator.state.value.isOpen)
        coordinator.dispatch(InGameMenuIntent.SettingsSaveAndBack)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(1, gateway.commitCount)
        assertEquals(1, gateway.transientCount)
        assertEquals(1, gateway.settingsBeginCount)
        assertEquals(1, gateway.settingsEndCount)
    }

    @Test
    fun settings_discard_once() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.OpenSettings)
        awaitCondition { coordinator.state.value.settingsActive }
        coordinator.dispatch(InGameMenuIntent.SettingsTransientSet("video/x", "2"))
        awaitCondition { coordinator.state.value.settingsDirty }
        coordinator.dispatch(InGameMenuIntent.SettingsDiscardAndBack)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(1, gateway.discardCount)
        assertEquals(0, gateway.commitCount)
    }

    @Test
    fun settings_clean_back_ends_session_once() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.OpenSettings)
        awaitCondition { coordinator.state.value.settingsActive }
        coordinator.dispatch(InGameMenuIntent.Back)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        assertEquals(1, gateway.settingsEndCount)
        assertFalse(coordinator.state.value.settingsActive)
    }

    @Test
    fun settings_dirty_back_cancel_stays_on_page() {
        openMain()
        coordinator.dispatch(InGameMenuIntent.OpenSettings)
        awaitCondition { coordinator.state.value.settingsActive }
        coordinator.dispatch(InGameMenuIntent.SettingsTransientSet("video/x", "3"))
        awaitCondition { coordinator.state.value.settingsDirty }
        coordinator.dispatch(InGameMenuIntent.Back)
        coordinator.dispatch(InGameMenuIntent.SettingsCancel)
        assertTrue(coordinator.state.value.isOpen)
        assertEquals(InGamePage.Settings, coordinator.state.value.currentPage)
    }

    // ── Capability-driven rows ─────────────────────────────────────────────

    @Test
    fun recording_row_hidden_when_core_reports_unsupported() {
        val rows = mainRowDescriptors(InGameMenuCapabilities.EMPTY.copy(recordingSupported = false))
        assertTrue(rows.none { it.labelRes == com.zenithblue.sambas3.R.string.ingame_start_recording })
        assertTrue(rows.none { it.labelRes == com.zenithblue.sambas3.R.string.ingame_stop_recording })
    }

    @Test
    fun savestate_row_hidden_when_core_reports_unsupported() {
        val rows = mainRowDescriptors(InGameMenuCapabilities.EMPTY.copy(savestate = SaveStateCapabilities(false, false, true, emptyList())))
        assertTrue(rows.none { it.labelRes == com.zenithblue.sambas3.R.string.ingame_save_state })
    }

    @Test
    fun core_home_menu_row_never_appears() {
        val rows = mainRowDescriptors(InGameMenuCapabilities.EMPTY)
        assertTrue(rows.none { it.intent.toString().contains("Core", ignoreCase = true) })
    }

    @Test
    fun conditional_rows_follow_capabilities() {
        val rows = mainRowDescriptors(InGameMenuCapabilities.EMPTY.copy(trophiesAvailable = false, friendsAvailable = false))
        assertTrue(rows.none { it.labelRes == com.zenithblue.sambas3.R.string.ingame_trophies })
        assertTrue(rows.none { it.labelRes == com.zenithblue.sambas3.R.string.ingame_friends })
    }

    @Test
    fun effects_emit_show_then_hide_on_full_cycle() = runBlocking {
        val effects = mutableListOf<InGameMenuHostEffect>()
        val job = launch {
            coordinator.effects.collect { effects.add(it) }
        }
        openMain()
        coordinator.dispatch(InGameMenuIntent.Resume)
        awaitCondition { coordinator.state.value.session is MenuSessionState.Closed }
        withTimeout(2000) {
            while (!(effects.contains(InGameMenuHostEffect.ShowOverlay) && effects.contains(InGameMenuHostEffect.HideOverlay))) {
                kotlinx.coroutines.delay(10)
            }
        }
        job.cancel()
        assertTrue(effects.contains(InGameMenuHostEffect.WaitForPhysicalNeutralThenArmGameplay))
    }
}

class ClosePolicyTest {
    @Test
    fun `resume-like reasons resume the owned pause`() {
        for (r in listOf(CloseReason.Resume, CloseReason.OutsideDismiss, CloseReason.HomeToggle, CloseReason.Screenshot, CloseReason.Recording, CloseReason.LoadState)) {
            assertTrue("reason=$r", ClosePolicy.forReason(r).resumePause)
        }
    }

    @Test
    fun `actions owning kill-restart never resume`() {
        for (r in listOf(CloseReason.Restart, CloseReason.Exit, CloseReason.SaveState, CloseReason.ActivityDestroyed)) {
            assertFalse("reason=$r", ClosePolicy.forReason(r).resumePause)
        }
    }
}
