package com.zenithblue.sambas3

import android.content.res.Resources.NotFoundException
import androidx.annotation.Keep
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.InvalidParameterException
import kotlin.concurrent.thread

enum class GameFlag {
    Locked,
    Trial
}

@Serializable
data class GameInfo @Keep constructor(
    val path: String,
    var name: String? = null,
    var iconPath: String? = null,
    var gameFlags: Int = 0
)

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0)
)

enum class GameProgressType {
    Install,
    Compile,
    Remove,
}

data class GameProgress(val id: Long, val type: GameProgressType)

data class Game(
    val info: GameInfoStore,
    val progressList: SnapshotStateList<GameProgress> = mutableStateListOf()
) {
    fun addProgress(progress: GameProgress) {
        if (findProgress(progress.type) != null) {
            throw InvalidParameterException()
        }

        progressList += progress
    }

    fun findProgress(type: GameProgressType) =
        progressList.filter { elem -> elem.type == type }.ifEmpty { null }

    fun findProgress(types: Array<GameProgressType>) =
        progressList.filter { elem -> types.contains(elem.type) }.ifEmpty { null }

    fun removeProgress(type: GameProgressType) =
        progressList.removeIf { progress -> progress.type == type }

    fun hasFlag(flag: GameFlag) = (info.gameFlags.intValue and (1 shl flag.ordinal)) != 0
}

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags)
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(store.path, store.name.value, store.iconPath.value, store.gameFlags.intValue)

internal object GameIdentity {
    private val titleIdPattern = Regex("(?<![A-Za-z0-9])([A-Za-z]{4}\\d{5})(?![A-Za-z0-9])")

    fun key(path: String, name: String?): String {
        val titleId = titleIdPattern.find("$path ${name.orEmpty()}")
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()
        return titleId ?: "path:${path.trimEnd('/').lowercase()}"
    }

    fun preferPath(candidate: String, existing: String): Boolean {
        val candidateIsIso = candidate.endsWith(".iso", ignoreCase = true)
        val existingIsIso = existing.endsWith(".iso", ignoreCase = true)
        return existingIsIso && !candidateIsIso
    }
}

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        private var needsRefresh = false
        val isRefreshing = mutableStateOf(false)
        private var isRefreshInCooldown = false

        val activeInstallProgress: MutableState<Long?> = mutableStateOf(null)

        fun save() {
            try {
                synchronized(instance) {
                    deduplicateGamesLocked()
                    File(RPCSX.rootDirectory + "games.json").writeText(
                        Json.encodeToString(instance.games.map { game ->
                            toInfo(game.info)
                        }.filter { info -> info.path != "$" })
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun load() {
            withContext(Dispatchers.IO) {
                try {
                    instance.games.clear()
                    instance.games += Json.decodeFromString<Array<GameInfo>>(
                        File(RPCSX.rootDirectory + "games.json").readText()
                    ).map { info -> Game(toStore(info)) }
                    synchronized(instance) {
                        deduplicateGamesLocked()
                    }
                    // Persist the normalized list so a source ISO cannot reappear as
                    // a second card after the next process restart.
                    save()
                } catch (_: NotFoundException) {
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun queueRefresh() {
            needsRefresh = true
            if (!isRefreshing.value || isRefreshInCooldown) {
                thread {
                    isRefreshing.value = true
                    do {
                        needsRefresh = false
                        refresh()
                    } while (needsRefresh)
                    isRefreshInCooldown = true
                    Thread.sleep(300)
                    if (!needsRefresh) {
                        isRefreshInCooldown = false
                        isRefreshing.value = false
                    }
                }
            }
        }

        private fun refresh() {
            clear()
            RPCSX.instance.collectGameInfo(
                RPCSX.rootDirectory + "/config/dev_hdd0/game", -1
            )
            RPCSX.instance.collectGameInfo(RPCSX.rootDirectory + "/config/games", -1)
        }
        
        @Keep
        @JvmStatic
        fun add(gameInfos: Array<GameInfo>, progressId: Long) {
            synchronized(instance) {
                if (progressId >= 0) {
                    val progressEntry =
                        instance.games.filter { game -> game.info.path == "$" }.find { game ->
                            val progress = game.findProgress(GameProgressType.Install)
                                ?.find { progress -> progress.id == progressId }
                            progress != null
                        }

                    if (progressEntry != null) {
                        instance.games.remove(progressEntry)
                    }
                }

                gameInfos.forEach { info -> addOrUpdateLocked(info, progressId) }
                deduplicateGamesLocked()
                save()
            }
        }

        fun addPreview(gameInfos: Array<GameInfo>) {
            synchronized(instance) {
                gameInfos.forEach { info -> addOrUpdateLocked(info, progressId = -1) }
                deduplicateGamesLocked()
            }
        }

        private fun addOrUpdateLocked(info: GameInfo, progressId: Long) {
            val identity = GameIdentity.key(info.path, info.name)
            val existsGame = instance.games.find { game ->
                game.info.path == info.path ||
                    (game.info.path != "$" &&
                        GameIdentity.key(game.info.path, game.info.name.value) == identity)
            }

            if (existsGame == null) {
                val newGame = Game(toStore(info))
                addInstallProgressIfNeeded(newGame, progressId)
                instance.games.add(0, newGame)
                return
            }

            if (existsGame.info.path != info.path &&
                GameIdentity.preferPath(info.path, existsGame.info.path)
            ) {
                val replacement = Game(toStore(info))
                copyProgress(existsGame, replacement)
                addInstallProgressIfNeeded(replacement, progressId)
                instance.games.remove(existsGame)
                instance.games.add(0, replacement)
                return
            }

            existsGame.info.name.value = info.name ?: existsGame.info.name.value
            existsGame.info.iconPath.value = info.iconPath ?: existsGame.info.iconPath.value
            existsGame.info.gameFlags.intValue = info.gameFlags
            addInstallProgressIfNeeded(existsGame, progressId)
        }

        private fun addInstallProgressIfNeeded(game: Game, progressId: Long) {
            if (progressId >= 0 && game.findProgress(GameProgressType.Install) == null) {
                game.addProgress(GameProgress(progressId, GameProgressType.Install))
            }
        }

        private fun copyProgress(source: Game, target: Game) {
            source.progressList.forEach { progress ->
                if (target.findProgress(progress.type) == null) {
                    target.addProgress(progress)
                }
            }
        }

        private fun deduplicateGamesLocked() {
            val unique = LinkedHashMap<String, Game>()
            instance.games.toList().forEach { game ->
                val key = GameIdentity.key(game.info.path, game.info.name.value)
                val existing = unique[key]
                if (existing == null) {
                    unique[key] = game
                } else if (GameIdentity.preferPath(game.info.path, existing.info.path)) {
                    copyProgress(existing, game)
                    unique[key] = game
                } else {
                    copyProgress(game, existing)
                }
            }

            if (unique.size != instance.games.size) {
                instance.games.clear()
                instance.games += unique.values
            }
        }

        fun onBoot(game: Game) {
            synchronized(instance) {
                if (instance.games.first() != game) {
                    instance.games.remove(game)
                    instance.games.add(0, game)
                    save()
                }
            }
        }

        fun createGameInstallEntry(progressId: Long) {
            synchronized(instance) {
                val existing = instance.games.find { game ->
                    game.info.path == "$" &&
                        game.findProgress(GameProgressType.Install)
                            ?.any { progress -> progress.id == progressId } == true
                }
                if (existing != null) {
                    return
                }
                val game = Game(GameInfoStore("$"))
                game.addProgress(GameProgress(progressId, GameProgressType.Install))
                instance.games.add(0, game)
            }
        }

        fun clearProgress(progressId: Long) {
            synchronized(instance) {
                instance.games.forEach { game -> game.progressList.removeIf { progress -> progress.id == progressId } }
                instance.games.removeIf { game -> game.info.path == "$" && game.progressList.isEmpty() }
            }
        }

        fun remove(game: Game) {
            synchronized(instance) {
                instance.games -= game
                save()
            }
        }

        fun find(path: String): Game? {
            synchronized(instance) {
                return instance.games.find { game -> game.info.path == path }
            }
        }

        fun list() = instance.games

        fun clear() {
            instance.games.clear()
        }
    }
}
