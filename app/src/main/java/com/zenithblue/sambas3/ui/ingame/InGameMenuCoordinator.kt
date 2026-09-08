package com.zenithblue.sambas3.ui.ingame

import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed interface InGameMenuIntent {
    data object Open : InGameMenuIntent
    data object Resume : InGameMenuIntent
    data object Back : InGameMenuIntent
    data object DismissOutside : InGameMenuIntent

    data object OpenSettings : InGameMenuIntent
    data object OpenMonitoring : InGameMenuIntent
    data object OpenLiveLogs : InGameMenuIntent
    data object OpenController : InGameMenuIntent
    data object OpenConfigureGame : InGameMenuIntent
    data object OpenTrophies : InGameMenuIntent
    data object OpenFriends : InGameMenuIntent
    data object OpenSaveStates : InGameMenuIntent

    data object RequestScreenshot : InGameMenuIntent
    data object ToggleRecording : InGameMenuIntent
    data object Restart : InGameMenuIntent
    data object Exit : InGameMenuIntent

    data class SaveState(val slot: Int) : InGameMenuIntent
    data class LoadState(val slot: Int) : InGameMenuIntent
    data class RequestSaveConfirm(val slot: Int) : InGameMenuIntent
    data object DismissSaveConfirm : InGameMenuIntent

    // Settings transaction
    data object SettingsSave : InGameMenuIntent
    data object SettingsDiscard : InGameMenuIntent
    data object SettingsSaveAndBack : InGameMenuIntent
    data object SettingsDiscardAndBack : InGameMenuIntent
    data object SettingsCancel : InGameMenuIntent
    data class SettingsNavigate(val route: String) : InGameMenuIntent
    data class SettingsTransientSet(val path: String, val value: String) : InGameMenuIntent
    data object RequestDirtyCheck : InGameMenuIntent
    data class ReportItemCount(val page: InGamePage, val count: Int) : InGameMenuIntent
    data class SelectIndex(val index: Int) : InGameMenuIntent
}

sealed interface InGameMenuHostEffect {
    data object ShowOverlay : InGameMenuHostEffect
    data object HideOverlay : InGameMenuHostEffect
    data object EnterPadMenuMode : InGameMenuHostEffect
    data object ExitPadMenuMode : InGameMenuHostEffect
    data object WaitForPhysicalNeutralThenArmGameplay : InGameMenuHostEffect
    data object ArmGameplayNow : InGameMenuHostEffect
    data object FinishGameActivity : InGameMenuHostEffect
    data class BeginSavestateTransition(
        val slot: Int,
        val preSaveMtimeMs: Long,
        val preSaveSizeBytes: Long,
        val savestatePath: String?,
        val suspendMode: Boolean
    ) : InGameMenuHostEffect
    data class SavestateRequestAccepted(val slot: Int) : InGameMenuHostEffect
    data class BeginSavestateLoadTransition(
        val requestId: Long,
        val slot: Int,
        val savestatePath: String,
        val previewPath: String?
    ) : InGameMenuHostEffect
    data class SavestateLoadAccepted(
        val requestId: Long,
        val slot: Int,
        val savestatePath: String
    ) : InGameMenuHostEffect
    data class SavestateTransitionFailed(val reason: String) : InGameMenuHostEffect
}

enum class CloseReason {
    Resume,
    OutsideDismiss,
    HomeToggle,
    Screenshot,
    Recording,
    Restart,
    Exit,
    SaveState,
    LoadState,
    ActivityDestroyed
}

sealed interface MenuSessionState {
    data object Closed : MenuSessionState
    data object Opening : MenuSessionState
    data class Open(
        val pauseOwned: Boolean,
        val pageStack: List<InGamePage>,
        val capabilities: InGameMenuCapabilities
    ) : MenuSessionState

    data class Closing(val reason: CloseReason) : MenuSessionState
}

/** Per-page selection + exact actionable item counts; never hard-coded. */
data class InGameMenuUiState(
    val session: MenuSessionState = MenuSessionState.Closed,
    val settingsBackstack: List<String> = listOf(""),
    val settingsActive: Boolean = false,
    val settingsDirty: Boolean = false,
    val selectedIndex: Int = 0,
    val pageSelections: Map<InGamePage, Int> = emptyMap(),
    val itemCounts: Map<InGamePage, Int> = emptyMap(),
    val settingsTreeJson: String? = null,
    val settingsLoading: Boolean = false,
    val showDirtyDialog: Boolean = false,
    val settingsWriteError: String? = null,
    val saveConfirmSlot: Int? = null
) {
    val isOpen: Boolean get() = session is MenuSessionState.Opening || session is MenuSessionState.Open
    val currentPage: InGamePage? get() = (session as? MenuSessionState.Open)?.pageStack?.lastOrNull()
    val capabilities: InGameMenuCapabilities
        get() = (session as? MenuSessionState.Open)?.capabilities ?: InGameMenuCapabilities.EMPTY
}

/** Per §12: close reason -> {close UI, resume menu-owned pause, follow-up}. */
internal data class ClosePolicy(
    val resumePause: Boolean,
    val hideOverlay: Boolean = true
) {
    companion object {
        fun forReason(reason: CloseReason): ClosePolicy = when (reason) {
            CloseReason.Resume,
            CloseReason.OutsideDismiss,
            CloseReason.HomeToggle -> ClosePolicy(resumePause = true)

            CloseReason.Screenshot,
            CloseReason.Recording -> ClosePolicy(resumePause = true)

            // Destructive core transitions must never resume the old
            // execution before the native shutdown runs.
            CloseReason.LoadState,
            CloseReason.Restart,
            CloseReason.Exit,
            CloseReason.SaveState,
            CloseReason.ActivityDestroyed -> ClosePolicy(resumePause = false)
        }
    }
}

/** Single source of truth for the main menu rows (labels/icons + target intent). */
data class MainRowDescriptor(
    val labelRes: Int,
    val iconRes: Int,
    val showArrow: Boolean,
    val enabled: Boolean,
    val intent: InGameMenuIntent
)

fun mainRowDescriptors(cap: InGameMenuCapabilities): List<MainRowDescriptor> = buildList {
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_resume, com.zenithblue.sambas3.R.drawable.ic_play, false, true, InGameMenuIntent.Resume))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.configure_game, com.zenithblue.sambas3.R.drawable.tune, true, true, InGameMenuIntent.OpenConfigureGame))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_settings, com.zenithblue.sambas3.R.drawable.ic_settings, true, true, InGameMenuIntent.OpenSettings))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_monitoring, com.zenithblue.sambas3.R.drawable.ic_video, true, true, InGameMenuIntent.OpenMonitoring))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_live_logs, com.zenithblue.sambas3.R.drawable.ic_terminal, true, true, InGameMenuIntent.OpenLiveLogs))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_controller, com.zenithblue.sambas3.R.drawable.tune, true, true, InGameMenuIntent.OpenController))
    if (cap.friendsAvailable) {
        add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_friends, com.zenithblue.sambas3.R.drawable.ic_settings, true, true, InGameMenuIntent.OpenFriends))
    }
    // Keep Achievements visible even when the current core/game has no trophy
    // data. The page reports that state explicitly; hiding the entry made the
    // feature appear to be missing from the in-game settings menu.
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_achievements, com.zenithblue.sambas3.R.drawable.ic_star, true, true, InGameMenuIntent.OpenTrophies))
    add(
        MainRowDescriptor(
            com.zenithblue.sambas3.R.string.ingame_take_screenshot, com.zenithblue.sambas3.R.drawable.ic_video,
            false, cap.screenshot, InGameMenuIntent.RequestScreenshot
        )
    )
    if (cap.recordingSupported) {
        val recLabel = if (cap.recordingActive == true) {
            com.zenithblue.sambas3.R.string.ingame_stop_recording
        } else {
            com.zenithblue.sambas3.R.string.ingame_start_recording
        }
        add(MainRowDescriptor(recLabel, com.zenithblue.sambas3.R.drawable.ic_video, false, true, InGameMenuIntent.ToggleRecording))
    }
    if (cap.savestate?.supported == true) {
        add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_save_state, com.zenithblue.sambas3.R.drawable.ic_save, true, true, InGameMenuIntent.OpenSaveStates))
    }
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_restart_game, com.zenithblue.sambas3.R.drawable.ic_restore, false, true, InGameMenuIntent.Restart))
    add(MainRowDescriptor(com.zenithblue.sambas3.R.string.ingame_exit_game, com.zenithblue.sambas3.R.drawable.ic_stop, false, true, InGameMenuIntent.Exit))
}

class InGameMenuCoordinator(
    private val scope: CoroutineScope,
    private val core: InGameMenuCoreGateway,
    private val stateProvider: () -> EmulatorState = { RPCSX.getState() },
    private val onGameplayResumed: () -> Unit = {}
) {
    private val _state = MutableStateFlow(InGameMenuUiState())
    val state: StateFlow<InGameMenuUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<InGameMenuHostEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<InGameMenuHostEffect> = _effects.asSharedFlow()

    // Internal quick-lookup mirrors (authoritative state lives in _state).
    // A destructive core transition (restart/exit/save/load) is pending;
    // duplicates are rejected until the menu is re-opened.
    @Volatile
    private var destructivePending = false
    private val nextLoadRequestId = AtomicLong(System.currentTimeMillis())

    fun dispatch(intent: InGameMenuIntent) {
        when (intent) {
            is InGameMenuIntent.Open -> scope.launch { open() }
            is InGameMenuIntent.Resume -> close(CloseReason.Resume)
            is InGameMenuIntent.DismissOutside -> close(CloseReason.OutsideDismiss)
            is InGameMenuIntent.Back -> handleBackInternal()
            is InGameMenuIntent.OpenSettings -> pushPage(InGamePage.Settings)
            is InGameMenuIntent.OpenMonitoring -> pushPage(InGamePage.Monitoring)
            is InGameMenuIntent.OpenLiveLogs -> pushPage(InGamePage.LiveLogs)
            is InGameMenuIntent.OpenController -> pushPage(InGamePage.Controller)
            is InGameMenuIntent.OpenConfigureGame -> pushPage(InGamePage.ConfigureGame)
            is InGameMenuIntent.OpenTrophies -> pushPage(InGamePage.Trophies)
            is InGameMenuIntent.OpenFriends -> pushPage(InGamePage.Friends)
            is InGameMenuIntent.OpenSaveStates -> pushPage(InGamePage.SaveStates)
            is InGameMenuIntent.RequestScreenshot -> closeAndRun(CloseReason.Screenshot) { _ -> core.requestScreenshot() }
            is InGameMenuIntent.ToggleRecording -> closeAndRun(CloseReason.Recording) { _ -> core.toggleRecording() }
            is InGameMenuIntent.Restart -> closeAndRun(CloseReason.Restart) { _ -> core.restart() }
            // Shutdown ownership belongs to EmulatorStopCoordinator. The
            // Activity effect below waits for native Stopped, so this intent
            // only closes the menu and requests that single terminal path.
            is InGameMenuIntent.Exit -> closeAndRun(CloseReason.Exit) { _ -> Result.success(true) }
            is InGameMenuIntent.SaveState -> closeAndRun(CloseReason.SaveState, intent.slot) { _ -> core.saveState(intent.slot) }
            is InGameMenuIntent.LoadState -> closeAndRun(CloseReason.LoadState, intent.slot) { requestId ->
                core.loadState(intent.slot, checkNotNull(requestId))
            }

            is InGameMenuIntent.RequestSaveConfirm -> _state.update {
                val open = it.session as? MenuSessionState.Open ?: return@update it
                val savestate = it.capabilities.savestate
                val valid = open.pageStack.lastOrNull() == InGamePage.SaveStates &&
                    savestate?.canSave != false &&
                    (savestate?.suspendMode == true || savestate?.slots?.any { s -> s.slot == intent.slot } == true)
                if (valid) it.copy(saveConfirmSlot = intent.slot) else it
            }

            is InGameMenuIntent.DismissSaveConfirm -> _state.update { it.copy(saveConfirmSlot = null) }

            is InGameMenuIntent.SettingsSave -> scope.launch { settingsSave() }
            is InGameMenuIntent.SettingsDiscard -> scope.launch { settingsDiscard() }
            is InGameMenuIntent.SettingsSaveAndBack -> scope.launch {
                settingsSave()
                if (!_state.value.settingsDirty) handleBackInternal()
            }

            is InGameMenuIntent.SettingsDiscardAndBack -> scope.launch {
                settingsDiscard()
                if (!_state.value.settingsDirty) handleBackInternal()
            }

            is InGameMenuIntent.SettingsCancel -> _state.update { it.copy(showDirtyDialog = false) }
            is InGameMenuIntent.SettingsNavigate -> scope.launch { settingsNavigate(intent.route) }
            is InGameMenuIntent.SettingsTransientSet -> scope.launch { settingsTransientSet(intent.path, intent.value) }
            is InGameMenuIntent.RequestDirtyCheck -> scope.launch { refreshDirty() }

            is InGameMenuIntent.ReportItemCount -> _state.update {
                it.copy(itemCounts = it.itemCounts + (intent.page to intent.count))
            }

            is InGameMenuIntent.SelectIndex -> _state.update {
                val curPage = (it.session as? MenuSessionState.Open)?.pageStack?.lastOrNull()
                it.copy(
                    selectedIndex = intent.index,
                    pageSelections = if (curPage != null) it.pageSelections + (curPage to intent.index) else it.pageSelections
                )
            }
        }
    }

    // ── Open ────────────────────────────────────────────────────────────────

    private suspend fun open() {
        val s = _state.value
        if (s.session !is MenuSessionState.Closed) return // duplicate open ignored
        val st = stateProvider()
        if (st != EmulatorState.Running && st != EmulatorState.Paused) return
        _state.value = s.copy(session = MenuSessionState.Opening)
        val began = core.beginMenu().getOrDefault(false)
        val caps = core.capabilities().getOrDefault(InGameMenuCapabilities.EMPTY)
        _state.value = InGameMenuUiState(
            session = MenuSessionState.Open(pauseOwned = began, pageStack = listOf(InGamePage.Main), capabilities = caps),
            settingsBackstack = listOf("")
        )
        destructivePending = false
        _effects.emit(InGameMenuHostEffect.ShowOverlay)
        _effects.emit(InGameMenuHostEffect.EnterPadMenuMode)
    }

    fun closeForActivityDestroy() {
        val s = _state.value
        if (s.session is MenuSessionState.Open || s.session is MenuSessionState.Opening) {
            scope.launch { closeInternal(CloseReason.ActivityDestroyed) }
        }
        scope.launch { runCatching { core.endSettings() } }
    }

    // ── Close ───────────────────────────────────────────────────────────────

    private fun close(reason: CloseReason) {
        val s = _state.value
        if (s.session !is MenuSessionState.Open && s.session !is MenuSessionState.Opening) return
        scope.launch { closeInternal(reason) }
    }

    private suspend fun closeInternal(reason: CloseReason) {
        if (_state.value.session is MenuSessionState.Closing) return
        _state.update { it.copy(session = MenuSessionState.Closing(reason)) }
        val policy = ClosePolicy.forReason(reason)
        if (_state.value.settingsActive) {
            // Leaving settings implicitly: discard-less end is only allowed when clean;
            // dirty state is resolved by explicit Save/Discard before close reasons fire.
            core.endSettings()
            _state.update { it.copy(settingsActive = false, settingsDirty = false, settingsTreeJson = null) }
        }
        runCatching { core.endMenu(policy.resumePause) }
        _state.value = InGameMenuUiState()
        if (policy.hideOverlay) {
            _effects.emit(InGameMenuHostEffect.HideOverlay)
            _effects.emit(InGameMenuHostEffect.ExitPadMenuMode)
            if (policy.resumePause) {
                _effects.emit(InGameMenuHostEffect.WaitForPhysicalNeutralThenArmGameplay)
                onGameplayResumed()
            }
        }
    }

    private fun closeAndRun(
        reason: CloseReason,
        saveSlot: Int? = null,
        action: suspend (requestId: Long?) -> Result<Boolean>
    ) {
        val s = _state.value
        if (s.session !is MenuSessionState.Open && s.session !is MenuSessionState.Opening) return
        val destructive = reason == CloseReason.Restart ||
            reason == CloseReason.Exit ||
            reason == CloseReason.SaveState ||
            reason == CloseReason.LoadState
        // Exactly one destructive core action per user-confirmed action.
        if (destructive && destructivePending) return
        if (destructive) destructivePending = true
        scope.launch {
            val loadRequestId = if (reason == CloseReason.LoadState) {
                nextLoadRequestId.incrementAndGet()
            } else {
                null
            }
            val slotInfo = saveSlot?.let { slot ->
                (s.session as? MenuSessionState.Open)?.capabilities?.savestate
                    ?.slots?.firstOrNull { it.slot == slot }
            }
            if (reason == CloseReason.SaveState && saveSlot != null) {
                val save = (s.session as? MenuSessionState.Open)?.capabilities?.savestate
                _effects.emit(InGameMenuHostEffect.BeginSavestateTransition(
                    saveSlot,
                    slotInfo?.mtimeMs ?: 0L,
                    slotInfo?.sizeBytes ?: 0L,
                    slotInfo?.path,
                    save?.suspendMode == true
                ))
            }
            if (reason == CloseReason.LoadState && slotInfo?.path != null) {
                _effects.emit(InGameMenuHostEffect.BeginSavestateLoadTransition(
                    checkNotNull(loadRequestId),
                    slotInfo.slot,
                    slotInfo.path,
                    slotInfo.previewPath
                ))
            }
            closeInternal(reason)
            val ok = action(loadRequestId).getOrDefault(false)
            if (reason == CloseReason.SaveState && ok) {
                _effects.emit(InGameMenuHostEffect.SavestateRequestAccepted(saveSlot ?: 0))
            }
            if (reason == CloseReason.LoadState && ok) {
                slotInfo?.path?.takeIf { it.isNotBlank() }?.let { path ->
                    _effects.emit(InGameMenuHostEffect.SavestateLoadAccepted(
                        checkNotNull(loadRequestId),
                        slotInfo.slot,
                        path
                    ))
                }
            }
            if ((reason == CloseReason.SaveState || reason == CloseReason.LoadState) && !ok) {
                _effects.emit(InGameMenuHostEffect.SavestateTransitionFailed("request-rejected"))
            }
            // Host follow-ups that need the Activity:
            //  - Exit: finish once shutdown completes
            //  - Suspend-mode SaveState: backend owns kill; finish when stopped
            val suspendSave = reason == CloseReason.SaveState &&
                (s.session as? MenuSessionState.Open)?.capabilities?.savestate?.suspendMode == true
            if (reason == CloseReason.Exit || (suspendSave && ok)) {
                _effects.emit(InGameMenuHostEffect.FinishGameActivity)
            }
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun pushPage(page: InGamePage) {
        val s = _state.value
        val open = s.session as? MenuSessionState.Open ?: return
        val curPage = open.pageStack.lastOrNull()
        if (curPage == page) return
        _state.update { st ->
            val stack = (st.session as? MenuSessionState.Open)?.pageStack ?: return@update st
            val updatedSelections = if (curPage != null) {
                st.pageSelections + (curPage to st.selectedIndex)
            } else {
                st.pageSelections
            }
            st.copy(
                session = open.copy(pageStack = stack + page),
                pageSelections = updatedSelections,
                selectedIndex = updatedSelections[page] ?: 0,
                saveConfirmSlot = null
            )
        }
        if (page == InGamePage.Settings) scope.launch { enterSettings() }
    }

    private fun handleBackInternal() {
        val s = _state.value
        val open = s.session as? MenuSessionState.Open ?: return
        if (s.saveConfirmSlot != null) {
            _state.update { it.copy(saveConfirmSlot = null) }
            return
        }
        when (open.pageStack.lastOrNull()) {
            InGamePage.Settings -> {
                if (s.showDirtyDialog) return
                val backstack = s.settingsBackstack
                if (backstack.size > 1) {
                    _state.update { it.copy(settingsBackstack = backstack.dropLast(1), selectedIndex = 0) }
                } else if (s.settingsDirty) {
                    _state.update { it.copy(showDirtyDialog = true) }
                } else {
                    scope.launch {
                        exitSettings(endSession = true)
                        popPage()
                    }
                }
            }

            InGamePage.Main -> close(CloseReason.Resume)
            else -> popPage()
        }
    }

    private fun popPage() {
        _state.update { st ->
            val open = st.session as? MenuSessionState.Open ?: return@update st
            if (open.pageStack.size <= 1) return@update st
            val curPage = open.pageStack.lastOrNull()
            val newStack = open.pageStack.dropLast(1)
            val returnPage = newStack.lastOrNull()
            val updatedSelections = if (curPage != null) {
                st.pageSelections + (curPage to st.selectedIndex)
            } else {
                st.pageSelections
            }
            val restoredIndex = if (returnPage != null) {
                updatedSelections[returnPage] ?: 0
            } else 0
            st.copy(
                session = open.copy(pageStack = newStack),
                pageSelections = updatedSelections,
                selectedIndex = restoredIndex,
                saveConfirmSlot = null
            )
        }
    }

    /** Semantic selection move; count comes from the reported exact item count. */
    fun moveSelection(delta: Int): Boolean {
        val s = _state.value
        val page = s.currentPage ?: return false
        val count = s.itemCounts[page] ?: return false
        if (count <= 0) return false
        val next = ((s.selectedIndex + delta) % count + count) % count
        _state.update {
            it.copy(
                selectedIndex = next,
                pageSelections = it.pageSelections + (page to next)
            )
        }
        return true
    }

    /** 2D grid selection move for multi-column menus (e.g. 2-column Main panel). */
    fun moveSelection2D(dx: Int, dy: Int, columns: Int = 2): Boolean {
        val s = _state.value
        val page = s.currentPage ?: return false
        val count = s.itemCounts[page] ?: return false
        if (count <= 0) return false
        val cur = s.selectedIndex.coerceIn(0, count - 1)
        val curRow = cur / columns
        val curCol = cur % columns
        val totalRows = (count + columns - 1) / columns

        var nextCol = curCol + dx
        var nextRow = curRow + dy

        if (dx != 0) {
            nextCol = nextCol.coerceIn(0, columns - 1)
        }
        if (dy != 0) {
            nextRow = ((nextRow % totalRows) + totalRows) % totalRows
        }

        var next = nextRow * columns + nextCol
        if (next >= count) {
            next = if (dx > 0) cur else count - 1
        }
        _state.update {
            it.copy(
                selectedIndex = next,
                pageSelections = it.pageSelections + (page to next)
            )
        }
        return true
    }

    fun jumpSelection(delta: Int): Boolean = moveSelection(delta)

    fun activateSelected(): Boolean {
        val s = _state.value
        val page = s.currentPage ?: return false
        val count = s.itemCounts[page] ?: return false
        return s.selectedIndex in 0 until count
    }

    /** Resolve the currently selected main-menu row to its intent. */
    fun activateSelectedIntent(): InGameMenuIntent? {
        val s = _state.value
        return when (s.currentPage) {
            InGamePage.Main -> mainRowDescriptors(s.capabilities)
                .getOrNull(s.selectedIndex)
                ?.takeIf { it.enabled }?.intent

            InGamePage.SaveStates -> {
                val savestate = s.capabilities.savestate ?: return null
                if (savestate.canSave == false) return null
                val count = s.itemCounts[InGamePage.SaveStates] ?: return null
                if (s.selectedIndex !in 0 until count) return null
                val slot = if (savestate.suspendMode) {
                    0
                } else {
                    savestate.slots.getOrNull(s.selectedIndex)?.slot ?: return null
                }
                InGameMenuIntent.RequestSaveConfirm(slot)
            }

            else -> null
        }
    }

    /** While a save-confirm prompt is open it owns all menu commands. */
    fun handleSaveConfirmCommand(command: MenuCommand): Boolean {
        val slot = _state.value.saveConfirmSlot ?: return false
        when (command) {
            is MenuCommand.Activate -> {
                _state.update { it.copy(saveConfirmSlot = null) }
                dispatch(InGameMenuIntent.SaveState(slot))
            }

            is MenuCommand.Back -> _state.update { it.copy(saveConfirmSlot = null) }
            else -> Unit
        }
        return true
    }

    // ── Settings transaction (exactly-once semantics, §14) ──────────────────

    private suspend fun enterSettings() {
        val s = _state.value
        if (s.settingsActive) return
        _state.update { it.copy(settingsActive = true, settingsLoading = true) }
        val began = core.beginSettings().getOrDefault(false)
        if (!began) {
            _state.update { it.copy(settingsActive = false, settingsLoading = false) }
            return
        }
        val tree = core.settingsTree().getOrNull()
        val dirty = core.isSettingsDirty().getOrDefault(false)
        _state.update { it.copy(settingsLoading = false, settingsTreeJson = tree, settingsDirty = dirty) }
    }

    private suspend fun exitSettings(endSession: Boolean) {
        val s = _state.value
        if (!s.settingsActive) return
        if (endSession) {
            core.endSettings()
            _state.update { it.copy(settingsActive = false, settingsDirty = false, settingsTreeJson = null) }
        }
    }

    private suspend fun settingsNavigate(route: String) {
        _state.update { it.copy(settingsBackstack = it.settingsBackstack + route, selectedIndex = 0) }
    }

    private suspend fun settingsTransientSet(path: String, value: String) {
        val ok = core.setTransientSetting(path, value).getOrDefault(false)
        val tree = core.settingsTree().getOrNull()
        _state.update {
            it.copy(
                settingsTreeJson = tree ?: it.settingsTreeJson,
                settingsWriteError = if (ok) null else "Setting was rejected by the emulator"
            )
        }
        if (ok) refreshDirty()
    }

    private suspend fun refreshDirty() {
        val dirty = core.isSettingsDirty().getOrDefault(false)
        _state.update { it.copy(settingsDirty = dirty) }
    }

    private suspend fun settingsSave() {
        val s = _state.value
        if (!s.settingsActive) return
        val ok = core.commitSettings().getOrDefault(false)
        if (!ok) return
        // Native parity: remain on Settings root; session stays open for further edits.
        val tree = core.settingsTree().getOrNull()
        _state.update { it.copy(settingsDirty = false, showDirtyDialog = false, settingsTreeJson = tree) }
    }

    private suspend fun settingsDiscard() {
        val s = _state.value
        if (!s.settingsActive) return
        val ok = core.discardSettings().getOrDefault(false)
        if (!ok) return
        val tree = core.settingsTree().getOrNull()
        _state.update {
            it.copy(settingsDirty = false, showDirtyDialog = false, settingsTreeJson = tree)
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    fun debugSetState(s: InGameMenuUiState) {
        _state.value = s
    }
}
