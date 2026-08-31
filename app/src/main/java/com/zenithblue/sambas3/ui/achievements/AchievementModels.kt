package com.zenithblue.sambas3.ui.achievements

import android.util.Log
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

enum class TrophySnapshotState {
    READY,
    NO_TROPHY_SET,
    INITIALIZING,
    PARSE_ERROR,
    UNSUPPORTED,
    EMPTY,
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

interface TrophyQuery {
    fun current(): String
    fun title(titleId: String): String
}

private class RpcsxTrophyQuery(private val rpcsx: RPCSX) : TrophyQuery {
    override fun current(): String = rpcsx.getCurrentTrophies()
    override fun title(titleId: String): String = rpcsx.getTrophiesForTitle(titleId)
}

private data class TrophyCacheKey(
    val titleId: String,
    val userId: String?,
    val trophySetId: String?,
    val generation: String?,
)

/**
 * The single provider used by both the live in-game page and Home's stopped
 * title page. Kotlin stores only a short-lived parsed snapshot; unlock state
 * remains owned by RPCSX/TROPUSR and is never recreated in a second database.
 */
class TrophySnapshotProvider(
    private val query: TrophyQuery,
    private val parse: (String?) -> TrophySnapshot? = { TrophySnapshot.fromJson(it) },
) {
    private val cache = LinkedHashMap<TrophyCacheKey, TrophySnapshot>()

    suspend fun current(force: Boolean = false): TrophySnapshot? = withContext(Dispatchers.IO) {
        load(source = "live", force = force) { query.current() }
    }

    suspend fun title(titleId: String, force: Boolean = false): TrophySnapshot? = withContext(Dispatchers.IO) {
        load(source = "title", force = force) { query.title(titleId) }
    }

    @Synchronized
    fun invalidate(titleId: String? = null, trophySetId: String? = null) {
        cache.entries.removeIf { (key, _) ->
            (titleId == null || key.titleId.equals(titleId, ignoreCase = true)) &&
                (trophySetId == null || key.trophySetId.equals(trophySetId, ignoreCase = true))
        }
        Log.i("S3TROPHY", "cache invalidated title=${titleId ?: "*"} set=${trophySetId ?: "*"}")
    }

    @Synchronized
    private fun cached(key: TrophyCacheKey): TrophySnapshot? = cache[key]

    @Synchronized
    private fun remember(key: TrophyCacheKey, snapshot: TrophySnapshot) {
        cache[key] = snapshot
        while (cache.size > 8) cache.remove(cache.entries.first().key)
    }

    private fun load(source: String, force: Boolean, query: () -> String): TrophySnapshot? {
        val snapshot = parse(runCatching { query() }.getOrNull()) ?: return null
        val key = TrophyCacheKey(snapshot.titleId, snapshot.rpcS3UserId, snapshot.trophySetId, snapshot.generation)
        val hit = !force && cached(key) != null
        val result = if (hit) cached(key)!! else snapshot
        if (!hit) remember(key, snapshot)
        Log.i(
            "S3TROPHY",
            "source=$source title=${result.titleId} user=${result.rpcS3UserId ?: "unknown"} " +
                "set=${result.trophySetId ?: "unknown"} TROPUSR=${result.tropusrPath ?: "unknown"} " +
                "exists=${result.tropusrExists} size=${result.tropusrSize} mtime=${result.tropusrMtime} " +
                "generation=${result.generation ?: "unknown"} cache=${if (hit) "hit" else "miss"} " +
                "cacheKey=${key.titleId}|${key.userId ?: "unknown"}|${key.trophySetId ?: "unknown"}|${key.generation ?: "unknown"} " +
                "total=${result.total} unlocked=${result.unlocked} ids=${result.unlockedIds} durationMs=${result.queryDurationMs ?: 0L}"
        )
        return result
    }
}

object AchievementRepository {
    private val provider = TrophySnapshotProvider(RpcsxTrophyQuery(RPCSX.instance))

    suspend fun current(force: Boolean = false): TrophySnapshot? = provider.current(force)
    suspend fun title(titleId: String, force: Boolean = false): TrophySnapshot? = provider.title(titleId, force)
    fun invalidate(titleId: String? = null, trophySetId: String? = null) = provider.invalidate(titleId, trophySetId)
}

data class TrophyInvalidation(
    val titleId: String?,
    val trophySetId: String?,
    val trophyId: Int?,
)

object AchievementEvents {
    private val _invalidations = MutableSharedFlow<TrophyInvalidation>(extraBufferCapacity = 16)
    val invalidations: SharedFlow<TrophyInvalidation> = _invalidations

    fun notifyUnlocked(payload: String? = null) {
        val event = runCatching {
            payload?.takeIf { it.isNotBlank() }?.let {
                val json = JSONObject(it)
                TrophyInvalidation(
                    titleId = json.optString("titleId", "").ifBlank { null },
                    trophySetId = json.optString("trophySet", json.optString("trophySetId", "")).ifBlank { null },
                    trophyId = json.optInt("id", -1).takeIf { id -> id >= 0 },
                )
            }
        }.getOrNull() ?: TrophyInvalidation(null, null, null)
        AchievementRepository.invalidate(event.titleId, event.trophySetId)
        _invalidations.tryEmit(event)
        Log.i("S3TROPHY", "unlock event title=${event.titleId ?: "*"} set=${event.trophySetId ?: "*"} id=${event.trophyId ?: "*"}")
    }
}
