package com.zenithblue.sambas3.ui.achievements

import android.util.Log
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

/** Shared live/Home provider. RPCSX/TROPUSR remains the only unlock-state owner. */
class TrophySnapshotProvider(
    private val query: TrophyQuery,
    private val parse: (String?) -> TrophySnapshot? = { TrophySnapshot.fromJson(it) },
) {
    private val cache = LinkedHashMap<TrophyCacheKey, TrophySnapshot>()

    suspend fun current(force: Boolean = false): TrophySnapshot? = withContext(Dispatchers.IO) {
        load("live", force) { query.current() }
    }

    suspend fun title(titleId: String, force: Boolean = false): TrophySnapshot? = withContext(Dispatchers.IO) {
        load("title", force) { query.title(titleId) }
    }

    @Synchronized
    fun invalidate(titleId: String? = null, trophySetId: String? = null) {
        cache.entries.removeIf { (key, _) ->
            (titleId == null || key.titleId.equals(titleId, ignoreCase = true)) &&
                (trophySetId == null || key.trophySetId.equals(trophySetId, ignoreCase = true))
        }
        Log.i("S3TROPHY", "cache invalidated title=${titleId ?: "*"} set=${trophySetId ?: "*"}")
    }

    @Synchronized private fun cached(key: TrophyCacheKey): TrophySnapshot? = cache[key]

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

data class TrophyInvalidation(val titleId: String?, val trophySetId: String?, val trophyId: Int?)

object AchievementEvents {
    private val _invalidations = MutableSharedFlow<TrophyInvalidation>(extraBufferCapacity = 16)
    val invalidations: SharedFlow<TrophyInvalidation> = _invalidations

    fun notifyUnlocked(payload: String? = null) {
        val event = runCatching {
            payload?.takeIf { it.isNotBlank() }?.let {
                val json = JSONObject(it)
                TrophyInvalidation(
                    json.optString("titleId", "").ifBlank { null },
                    json.optString("trophySet", json.optString("trophySetId", "")).ifBlank { null },
                    json.optInt("id", -1).takeIf { id -> id >= 0 },
                )
            }
        }.getOrNull() ?: TrophyInvalidation(null, null, null)
        AchievementRepository.invalidate(event.titleId, event.trophySetId)
        _invalidations.tryEmit(event)
        Log.i("S3TROPHY", "unlock event title=${event.titleId ?: "*"} set=${event.trophySetId ?: "*"} id=${event.trophyId ?: "*"}")
    }
}
