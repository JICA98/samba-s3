package com.zenithblue.sambas3.ui.ingame

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class InGameMenuController(
    private val core: RPCSX = RPCSX.instance
) {
    var stack: MutableList<InGamePage> = mutableListOf()
        private set

    var capabilities by mutableStateOf(InGameMenuCapabilities.EMPTY)
        private set

    var selectedIndex by mutableIntStateOf(0)
        private set

    var isOpen: Boolean by mutableStateOf(false)
        private set

    // Settings backstack for nested settings navigation
    val settingsBackstack = mutableListOf<String>()

    var hasDirtySettings by mutableStateOf(false)
        private set

    fun isMenuOpen(): Boolean = isOpen && stack.isNotEmpty()

    suspend fun openMain() {
        // Verify running or paused before opening
        val state = try { RPCSX.getState() } catch (_: Exception) { null }
        if (state != com.zenithblue.sambas3.EmulatorState.Running && state != com.zenithblue.sambas3.EmulatorState.Paused) {
            Log.w("InGameMenu", "openMain rejected state=$state")
            return
        }
        // Try begin frontend menu session
        val began = try { core.beginFrontendMenu() } catch (e: Exception) { Log.w("InGameMenu", "beginFrontendMenu failed ${e.message}"); false }
        Log.i("InGameMenu", "beginFrontendMenu=$began")
        // Load capabilities (with fallback for old core)
        capabilities = try {
            InGameMenuCapabilities.fromJson(core.inGameMenuCapabilities())
        } catch (e: Exception) {
            Log.w("InGameMenu", "capabilities parse failed ${e.message}")
            InGameMenuCapabilities.EMPTY
        }
        if (!capabilities.frontendOwnsHomeMenu) {
            Log.w("InGameMenu", "frontendOwnsHomeMenu=false, showing Kotlin subset")
        }
        stack.clear()
        stack.add(InGamePage.Main)
        selectedIndex = 0
        isOpen = true
        settingsBackstack.clear()
        settingsBackstack.add("")
        Log.i("InGameMenu", "opened Main capabilities=$capabilities")
    }

    fun push(page: InGamePage) {
        stack.add(page)
        selectedIndex = 0
        if (page == InGamePage.Settings) {
            // Begin settings transaction
            try { core.beginInGameSettingsSession() } catch (e: Exception) { Log.w("InGameMenu", "beginSettings ${e.message}") }
            hasDirtySettings = false
            settingsBackstack.clear()
            settingsBackstack.add("")
        }
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        val current = stack.last()
        if (current == InGamePage.Settings && settingsBackstack.size > 1) {
            settingsBackstack.removeAt(settingsBackstack.lastIndex)
            return true // consumed sub-page pop
        }
        if (current == InGamePage.Settings && hasDirtySettings) {
            // Caller should show dirty dialog; we don't auto-discard
            return false // signal dirty needs confirmation
        }
        if (stack.size == 1) {
            // Main -> resume
            resume()
            return true
        }
        // Pop page
        stack.removeAt(stack.lastIndex)
        selectedIndex = 0
        if (current == InGamePage.Settings) {
            // Clear settings session if popped without save/discard? Discard by default? But plan says discard must be explicit.
            // For now, if dirty we leave session open for dialog; otherwise end session.
            if (!hasDirtySettings) {
                try { core.endInGameSettingsSession() } catch (_: Exception) {}
            }
        }
        return true
    }

    fun resume() {
        // Hide compose before resuming
        stack.clear()
        isOpen = false
        selectedIndex = 0
        try { core.endFrontendMenu(true) } catch (e: Exception) { Log.w("InGameMenu", "endFrontendMenu resume failed ${e.message}") }
        Log.i("InGameMenu", "resume endFrontendMenu(true)")
    }

    fun closeWithoutResume() {
        stack.clear()
        isOpen = false
        selectedIndex = 0
        try { core.endFrontendMenu(false) } catch (e: Exception) { Log.w("InGameMenu", "endFrontendMenu false failed ${e.message}") }
        Log.i("InGameMenu", "closeWithoutResume endFrontendMenu(false)")
    }

    fun setSelected(index: Int) {
        selectedIndex = index
    }

    fun moveSelection(delta: Int, max: Int) {
        if (max <= 0) return
        selectedIndex = ((selectedIndex + delta) % max + max) % max
    }

    fun setDirty(dirty: Boolean) {
        hasDirtySettings = dirty
        try {
            // Poll backend dirty? But we maintain local.
        } catch (_: Exception) {}
    }

    suspend fun refreshDirtyFromBackend() {
        hasDirtySettings = try { withContext(Dispatchers.IO) { core.hasDirtyInGameSettings() } } catch (_: Exception) { hasDirtySettings }
    }

    fun onSettingsSaved() {
        hasDirtySettings = false
        try { core.commitInGameSettingsSession(); core.endInGameSettingsSession() } catch (e: Exception) { Log.w("InGameMenu", "commit failed ${e.message}") }
        // Pop settings page
        if (stack.lastOrNull() == InGamePage.Settings) {
            stack.removeAt(stack.lastIndex)
            selectedIndex = 0
        }
        if (stack.isEmpty()) {
            isOpen = false
            try { core.endFrontendMenu(true) } catch (_: Exception) {}
        }
    }

    fun onSettingsDiscarded() {
        hasDirtySettings = false
        try { core.discardInGameSettingsSession(); core.endInGameSettingsSession() } catch (e: Exception) { Log.w("InGameMenu", "discard failed ${e.message}") }
        if (stack.lastOrNull() == InGamePage.Settings) {
            stack.removeAt(stack.lastIndex)
            selectedIndex = 0
        }
        if (stack.isEmpty()) {
            isOpen = false
            try { core.endFrontendMenu(true) } catch (_: Exception) {}
        }
    }

    fun onSettingsCancelDiscard() {
        // Stay on page
    }

    // For testing without native
    fun openForTest(cap: InGameMenuCapabilities = InGameMenuCapabilities.EMPTY) {
        capabilities = cap
        stack.clear()
        stack.add(InGamePage.Main)
        isOpen = true
    }
}
