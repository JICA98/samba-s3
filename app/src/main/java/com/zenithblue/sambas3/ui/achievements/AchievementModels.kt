package com.zenithblue.sambas3.ui.achievements

import android.util.Log
import org.json.JSONObject

enum class TrophySnapshotState {
    READY,
    NO_TROPHY_SET,
    INITIALIZING,
    PARSE_ERROR,
    UNSUPPORTED,
    EMPTY,
}

enum class TrophyEmptyReason { NoTrophySet, Initializing, ParseError, Unsupported }

sealed interface AchievementUiState {
    data object Loading : AchievementUiState
    data class Ready(val snapshot: TrophySnapshot) : AchievementUiState
    data class Empty(val reason: TrophyEmptyReason) : AchievementUiState
    data class Failed(val message: String) : AchievementUiState
}

data class TrophyEntry(
    val id: Int,
    val name: String,
    val description: String,
    val grade: String,
    val unlocked: Boolean,
    val hidden: Boolean,
    val platinumRelevant: Boolean,
    val iconPath: String?,
    val unlockTimestamp: Long? = null,
)

data class TrophySnapshot(
    val state: TrophySnapshotState,
    val titleId: String,
    val trophySetId: String?,
    val gameName: String,
    val trophies: List<TrophyEntry>,
    val rpcS3UserId: String?,
    val tropusrPath: String?,
    val tropusrExists: Boolean,
    val tropusrSize: Long,
    val tropusrMtime: Long,
    val generation: String?,
    val querySource: String,
    val queryDurationMs: Long?,
    val status: String,
) {
    val available: Boolean get() = state == TrophySnapshotState.READY && trophies.isNotEmpty()
    val total: Int get() = trophies.size
    val unlocked: Int get() = trophies.count { it.unlocked }
    val percent: Int get() = if (total == 0) 0 else (unlocked * 100 / total)
    val trophySet: String get() = trophySetId.orEmpty()
    val unlockedIds: List<Int> get() = trophies.filter { it.unlocked }.map { it.id }

    fun gradeCount(grade: String, unlockedOnly: Boolean = false): Int = trophies.count {
        it.grade.equals(grade, ignoreCase = true) && (!unlockedOnly || it.unlocked)
    }

    companion object {
        fun fromJson(jsonString: String?): TrophySnapshot? {
            if (jsonString.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(jsonString)
                val status = json.stringValue("status", "ready")
                val available = json.optBoolean("available", false)
                val state = when {
                    available -> TrophySnapshotState.READY
                    status == "initializing" -> TrophySnapshotState.INITIALIZING
                    status == "parse_failed" -> TrophySnapshotState.PARSE_ERROR
                    status == "unsupported" -> TrophySnapshotState.UNSUPPORTED
                    status == "empty" -> TrophySnapshotState.EMPTY
                    else -> TrophySnapshotState.NO_TROPHY_SET
                }
                val entries = buildList {
                    val array = runCatching { json.getJSONArray("trophies") }.getOrNull() ?: return@buildList
                    for (index in 0 until array.length()) {
                        val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
                        add(
                            TrophyEntry(
                                id = item.optInt("id", index),
                                name = item.stringValue("name", "Hidden trophy"),
                                description = item.stringValue("description", "This trophy is hidden"),
                                grade = item.stringValue("grade", "bronze").lowercase(),
                                unlocked = item.optBoolean("unlocked", false),
                                hidden = item.optBoolean("hidden", false),
                                platinumRelevant = item.optBoolean("platinumRelevant", false),
                                iconPath = item.stringValue("iconPath").ifBlank { null },
                                unlockTimestamp = item.optLong("unlockTimestamp", 0L).takeIf { it > 0L },
                            )
                        )
                    }
                }
                TrophySnapshot(
                    state = if (state == TrophySnapshotState.READY && entries.isEmpty()) TrophySnapshotState.EMPTY else state,
                    titleId = json.stringValue("titleId"),
                    trophySetId = json.stringValue("trophySetId", json.stringValue("trophySet")).ifBlank { null },
                    gameName = json.stringValue("gameName"),
                    trophies = entries,
                    rpcS3UserId = json.stringValue("rpcS3UserId").ifBlank { null },
                    tropusrPath = json.stringValue("tropusrPath").ifBlank { null },
                    tropusrExists = json.optBoolean("tropusrExists", false),
                    tropusrSize = json.optLong("tropusrSize", 0L),
                    tropusrMtime = json.optLong("tropusrMtime", 0L),
                    generation = json.stringValue("tropusrGeneration").ifBlank {
                        json.stringValue("generation").ifBlank { null }
                    },
                    querySource = json.stringValue("querySource", "unknown"),
                    queryDurationMs = json.optLong("queryDurationMs", 0L).takeIf { it > 0L },
                    status = status,
                )
            }.onFailure { error -> Log.e("S3TROPHY", "parse failed", error) }.getOrNull()
        }
    }
}

private fun JSONObject.stringValue(key: String, fallback: String = ""): String =
    runCatching { opt(key)?.toString()?.takeUnless { it == "null" } ?: fallback }.getOrDefault(fallback)
