package com.zenithblue.sambas3.ui.ingame

import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun JSONObjectOf(json: String): JSONObject = JSONObject(json)

/**
 * Single ownership boundary between the Kotlin in-game menu UI and the RPCSX
 * core. Production implementation wraps the JNI surface. Composables must never
 * touch RPCSX directly — everything flows through this gateway on a dedicated
 * IO dispatcher.
 */
interface InGameMenuCoreGateway {
    suspend fun beginMenu(): Result<Boolean>
    suspend fun endMenu(resumeIfOwned: Boolean): Result<Unit>
    suspend fun isMenuOpen(): Boolean

    suspend fun capabilities(): Result<InGameMenuCapabilities>

    suspend fun requestScreenshot(): Result<Boolean>
    suspend fun toggleRecording(): Result<Boolean>
    suspend fun restart(): Result<Boolean>
    suspend fun gracefulShutdown(): Result<Boolean>

    suspend fun saveStateInfo(): Result<SaveStateCapabilities?>
    suspend fun saveState(slot: Int): Result<Boolean>
    suspend fun loadState(slot: Int): Result<Boolean>

    suspend fun trophies(): Result<TrophiesData?>
    suspend fun friends(): Result<FriendsData?>
    suspend fun friendAction(action: String, username: String): Result<Boolean>

    suspend fun beginSettings(): Result<Boolean>
    suspend fun setTransientSetting(path: String, value: String): Result<Boolean>
    suspend fun settingsTree(): Result<String?>
    suspend fun isSettingsDirty(): Result<Boolean>
    suspend fun commitSettings(): Result<Boolean>
    suspend fun discardSettings(): Result<Boolean>
    suspend fun endSettings(): Result<Unit>
}

/** Minimal surface of RPCSX needed by the gateway; keeps tests faking easy. */
interface RpcsxBridge {
    fun beginFrontendMenu(): Boolean
    fun endFrontendMenu(resumeIfOwned: Boolean)
    fun isFrontendMenuOpen(): Boolean
    fun inGameMenuCapabilities(): String
    fun requestScreenshot(): Boolean
    fun toggleRecording(): Boolean
    fun restartGame(): Boolean
    fun gracefulShutdown(): Boolean
    fun getSaveStateInfo(): String
    fun saveState(slot: Int): Boolean
    fun loadSaveState(slot: Int): Boolean
    fun getCurrentTrophies(): String
    fun getFriends(): String
    fun friendAction(action: String, username: String): Boolean
    fun beginInGameSettingsSession(): Boolean
    fun settingsSetTransient(path: String, value: String): Boolean
    fun commitInGameSettingsSession(): Boolean
    fun discardInGameSettingsSession(): Boolean
    fun hasDirtyInGameSettings(): Boolean
    fun endInGameSettingsSession()
    fun settingsGet(path: String): String
}

class RpcsxBridgeAdapter(private val rpcsx: RPCSX = RPCSX.instance) : RpcsxBridge {
    override fun beginFrontendMenu(): Boolean = rpcsx.beginFrontendMenu()
    override fun endFrontendMenu(resumeIfOwned: Boolean) = rpcsx.endFrontendMenu(resumeIfOwned)
    override fun isFrontendMenuOpen(): Boolean = rpcsx.isFrontendMenuOpen()
    override fun inGameMenuCapabilities(): String = rpcsx.inGameMenuCapabilities()
    override fun requestScreenshot(): Boolean = rpcsx.requestScreenshot()
    override fun toggleRecording(): Boolean = rpcsx.toggleRecording()
    override fun restartGame(): Boolean = rpcsx.restartGame()
    override fun gracefulShutdown(): Boolean = rpcsx.gracefulShutdown()
    override fun getSaveStateInfo(): String = rpcsx.getSaveStateInfo()
    override fun saveState(slot: Int): Boolean = rpcsx.saveState(slot)
    override fun loadSaveState(slot: Int): Boolean = rpcsx.loadSaveState(slot)
    override fun getCurrentTrophies(): String = rpcsx.getCurrentTrophies()
    override fun getFriends(): String = rpcsx.getFriends()
    override fun friendAction(action: String, username: String): Boolean = rpcsx.friendAction(action, username)
    override fun beginInGameSettingsSession(): Boolean = rpcsx.beginInGameSettingsSession()
    override fun settingsSetTransient(path: String, value: String): Boolean = rpcsx.settingsSetTransient(path, value)
    override fun commitInGameSettingsSession(): Boolean = rpcsx.commitInGameSettingsSession()
    override fun discardInGameSettingsSession(): Boolean = rpcsx.discardInGameSettingsSession()
    override fun hasDirtyInGameSettings(): Boolean = rpcsx.hasDirtyInGameSettings()
    override fun endInGameSettingsSession() = rpcsx.endInGameSettingsSession()
    override fun settingsGet(path: String): String = rpcsx.settingsGet(path)
}

class RpcsxInGameMenuCoreGateway(private val bridge: RpcsxBridge) : InGameMenuCoreGateway {

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching(block)
    }

    override suspend fun beginMenu(): Result<Boolean> = io { bridge.beginFrontendMenu() }
    override suspend fun endMenu(resumeIfOwned: Boolean): Result<Unit> = io { bridge.endFrontendMenu(resumeIfOwned) }
    override suspend fun isMenuOpen(): Boolean = io { runCatching { bridge.isFrontendMenuOpen() }.getOrDefault(false) }
        .getOrDefault(false)

    override suspend fun capabilities(): Result<InGameMenuCapabilities> = io {
        InGameMenuCapabilities.fromJson(bridge.inGameMenuCapabilities())
    }

    override suspend fun requestScreenshot(): Result<Boolean> = io { bridge.requestScreenshot() }
    override suspend fun toggleRecording(): Result<Boolean> = io { bridge.toggleRecording() }
    override suspend fun restart(): Result<Boolean> = io { bridge.restartGame() }
    override suspend fun gracefulShutdown(): Result<Boolean> = io { bridge.gracefulShutdown() }

    override suspend fun saveStateInfo(): Result<SaveStateCapabilities?> = io {
        bridge.getSaveStateInfo().takeIf { it.isNotBlank() }?.let { SaveStateCapabilities.fromJson(JSONObjectOf(it)) }
    }

    override suspend fun saveState(slot: Int): Result<Boolean> = io { bridge.saveState(slot) }
    override suspend fun loadState(slot: Int): Result<Boolean> = io { bridge.loadSaveState(slot) }

    override suspend fun trophies(): Result<TrophiesData?> = io {
        bridge.getCurrentTrophies().takeIf { it.isNotBlank() }?.let { TrophiesData.fromJson(it) }
    }

    override suspend fun friends(): Result<FriendsData?> = io {
        bridge.getFriends().takeIf { it.isNotBlank() }?.let { FriendsData.fromJson(it) }
    }

    override suspend fun friendAction(action: String, username: String): Result<Boolean> =
        io { bridge.friendAction(action, username) }

    override suspend fun beginSettings(): Result<Boolean> = io { bridge.beginInGameSettingsSession() }
    override suspend fun setTransientSetting(path: String, value: String): Result<Boolean> =
        io { bridge.settingsSetTransient(path, value) }

    override suspend fun settingsTree(): Result<String?> = io {
        bridge.settingsGet("").takeIf { it.isNotBlank() }
    }

    override suspend fun isSettingsDirty(): Result<Boolean> = io { bridge.hasDirtyInGameSettings() }
    override suspend fun commitSettings(): Result<Boolean> = io { bridge.commitInGameSettingsSession() }
    override suspend fun discardSettings(): Result<Boolean> = io { bridge.discardInGameSettingsSession() }
    override suspend fun endSettings(): Result<Unit> = io { bridge.endInGameSettingsSession() }
}
