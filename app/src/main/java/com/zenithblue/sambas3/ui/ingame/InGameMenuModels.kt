package com.zenithblue.sambas3.ui.ingame

import org.json.JSONObject
import com.zenithblue.sambas3.SavestateThumbnailStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class InGameMenuCapabilities(
    val apiVersion: Int,
    val frontendOwnsHomeMenu: Boolean,
    val pauseDuringMenu: Boolean,
    val screenshot: Boolean,
    val recordingSupported: Boolean,
    val recordingActive: Boolean?,
    val trophiesAvailable: Boolean,
    val friendsAvailable: Boolean,
    val savestate: SaveStateCapabilities?,
    val fullscreen: Boolean
) {
    companion object {
        val EMPTY = InGameMenuCapabilities(
            apiVersion = 1,
            frontendOwnsHomeMenu = false,
            pauseDuringMenu = false,
            screenshot = false,
            recordingSupported = false,
            recordingActive = null,
            trophiesAvailable = false,
            friendsAvailable = false,
            savestate = null,
            fullscreen = false
        )

        fun fromJson(jsonStr: String?): InGameMenuCapabilities {
            if (jsonStr.isNullOrBlank()) return EMPTY
            return try {
                val j = JSONObject(jsonStr)
                val rec = j.optJSONObject("recording")
                val trophies = j.optJSONObject("trophies")
                val friends = j.optJSONObject("friends")
                val savestateObj = j.optJSONObject("savestate")
                InGameMenuCapabilities(
                    apiVersion = j.optInt("apiVersion", 1),
                    frontendOwnsHomeMenu = j.optBoolean("frontendOwnsHomeMenu", false),
                    pauseDuringMenu = j.optBoolean("pauseDuringMenu", j.optBoolean("pauseDuringMenu", false)),
                    screenshot = j.optBoolean("screenshot", false),
                    recordingSupported = rec?.optBoolean("supported", false) ?: j.optBoolean("recordingSupported", false),
                    recordingActive = rec?.let { if (it.has("active")) it.optBoolean("active") else null },
                    trophiesAvailable = trophies?.optBoolean("available", false) ?: j.optBoolean("trophiesAvailable", false),
                    friendsAvailable = friends?.optBoolean("available", false) ?: j.optBoolean("friendsAvailable", false),
                    savestate = savestateObj?.let { SaveStateCapabilities.fromJson(it) },
                    fullscreen = j.optBoolean("fullscreen", false)
                )
            } catch (_: Exception) {
                EMPTY
            }
        }
    }
}

data class SaveStateCapabilities(
    val supported: Boolean,
    val suspendMode: Boolean,
    val canSave: Boolean,
    val slots: List<SaveSlot>
) {
    companion object {
        fun fromJson(j: JSONObject): SaveStateCapabilities {
            val slotsArr = j.optJSONArray("slots") ?: j.optJSONArray("loadSlots")
            val slots = mutableListOf<SaveSlot>()
            if (slotsArr != null) {
                for (i in 0 until slotsArr.length()) {
                    val o = slotsArr.optJSONObject(i) ?: continue
                    val path = o.optString("path", "").ifBlank { null }
                    val preview = if (o.optBoolean("exists", false)) {
                        SavestateThumbnailStore.metadataForPath(path)
                    } else {
                        null
                    }
                    slots.add(SaveSlot(
                        slot = o.optInt("slot", i),
                        exists = o.optBoolean("exists", false),
                        label = o.optString("label", "Slot $i"),
                        path = path,
                        mtimeMs = o.optLong("mtimeMs", 0L),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        previewPath = preview?.path,
                        previewMtimeMs = preview?.mtimeMs ?: 0L
                    ))
                }
            }
            return SaveStateCapabilities(
                supported = j.optBoolean("supported", true),
                suspendMode = j.optBoolean("suspendMode", false),
                canSave = j.optBoolean("canSave", true),
                slots = slots
            )
        }
    }
}

data class SaveSlot(
    val slot: Int,
    val exists: Boolean,
    val label: String,
    val path: String? = null,
    val mtimeMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val previewPath: String? = null,
    val previewMtimeMs: Long = 0L
)

data class TrophyEntry(
    val id: Int,
    val name: String,
    val description: String,
    val grade: String,
    val unlocked: Boolean,
    val hidden: Boolean,
    val platinumRelevant: Boolean,
    val iconPath: String?
)

data class TrophiesData(
    val available: Boolean,
    val status: String = "ready",
    val gameName: String,
    val trophySet: String,
    val total: Int,
    val unlocked: Int,
    val percent: Int,
    val trophies: List<TrophyEntry>
) {
    companion object {
        fun fromJson(jsonStr: String?): TrophiesData? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val j = JSONObject(jsonStr)
                if (!j.optBoolean("available", false)) return TrophiesData(false, j.optString("status", "unavailable"), "", "", 0, 0, 0, emptyList())
                val arr = j.optJSONArray("trophies")
                val list = mutableListOf<TrophyEntry>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(TrophyEntry(
                            id = o.optInt("id", i),
                            name = o.optString("name", ""),
                            description = o.optString("description", ""),
                            grade = o.optString("grade", "bronze"),
                            unlocked = o.optBoolean("unlocked", false),
                            hidden = o.optBoolean("hidden", false),
                            platinumRelevant = o.optBoolean("platinumRelevant", false),
                            iconPath = if (o.has("iconPath")) o.optString("iconPath") else null
                        ))
                    }
                }
                TrophiesData(
                    available = true,
                    status = j.optString("status", "ready"),
                    gameName = j.optString("gameName", ""),
                    trophySet = j.optString("trophySet", ""),
                    total = j.optInt("total", list.size),
                    unlocked = j.optInt("unlocked", list.count { it.unlocked }),
                    percent = j.optInt("percent", 0),
                    trophies = list
                )
            } catch (_: Exception) { null }
        }
    }
}

object TrophyEvents {
    private val _refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val refreshes: SharedFlow<Unit> = _refreshes
    fun notifyUnlocked() { _refreshes.tryEmit(Unit) }
}

data class FriendsData(
    val available: Boolean,
    val friends: List<FriendEntry>,
    val requestsReceived: List<String>,
    val requestsSent: List<String>,
    val blocked: List<String>
) {
    companion object {
        fun fromJson(jsonStr: String?): FriendsData? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val j = JSONObject(jsonStr)
                if (!j.optBoolean("available", false)) return FriendsData(false, emptyList(), emptyList(), emptyList(), emptyList())
                val friendsArr = j.optJSONArray("friends")
                val friends = mutableListOf<FriendEntry>()
                if (friendsArr != null) {
                    for (i in 0 until friendsArr.length()) {
                        val o = friendsArr.optJSONObject(i) ?: continue
                        friends.add(FriendEntry(
                            username = o.optString("username", ""),
                            online = o.optBoolean("online", false),
                            presenceTitle = o.optString("presenceTitle", ""),
                            presenceStatus = o.optString("presenceStatus", "")
                        ))
                    }
                }
                fun strArr(key: String): List<String> {
                    val a = j.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optString(it, null) }
                }
                FriendsData(
                    available = true,
                    friends = friends,
                    requestsReceived = strArr("requestsReceived"),
                    requestsSent = strArr("requestsSent"),
                    blocked = strArr("blocked")
                )
            } catch (_: Exception) { null }
        }
    }
}

data class FriendEntry(
    val username: String,
    val online: Boolean,
    val presenceTitle: String,
    val presenceStatus: String
)

sealed interface InGamePage {
    data object Main : InGamePage
    data object ConfigureGame : InGamePage
    data object Settings : InGamePage
    data object Monitoring : InGamePage
    data object Controller : InGamePage
    data object Trophies : InGamePage
    data object Friends : InGamePage
    data object SaveStates : InGamePage
}
