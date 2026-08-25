package com.zenithblue.sambas3

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ImportPhase {
    PREPARING,
    COPYING,
    EXTRACTING,
    INDEXING,
    COMPILING_PPU,
    READY,
    FAILED,
    CANCELED
}

data class ImportSession(
    val progressId: Long,
    val sourceUri: Uri? = null,
    val sourceName: String? = null,
    val provisionalTitleId: String? = null,
    val resolvedTitleId: String? = null,
    val installedPath: String? = null,
    val phase: ImportPhase = ImportPhase.PREPARING
)

object ImportSessionStore {
    private val _sessions = MutableStateFlow<List<ImportSession>>(emptyList())
    val sessions: StateFlow<List<ImportSession>> = _sessions
    // Alias for GamesScreen collect
    val flow: StateFlow<List<ImportSession>> get() = sessions

    @Synchronized
    fun createOrUpdate(session: ImportSession) {
        val list = _sessions.value.toMutableList()
        val idx = list.indexOfFirst { it.progressId == session.progressId }
        if (idx >= 0) list[idx] = session else list.add(0, session)
        _sessions.value = list
    }

    @Synchronized
    fun updatePhase(progressId: Long, phase: ImportPhase, resolvedTitleId: String? = null, installedPath: String? = null) {
        val list = _sessions.value.toMutableList()
        val idx = list.indexOfFirst { it.progressId == progressId }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(phase = phase, resolvedTitleId = resolvedTitleId ?: cur.resolvedTitleId, installedPath = installedPath ?: cur.installedPath)
            _sessions.value = list
        }
    }

    @Synchronized
    fun remove(progressId: Long) {
        _sessions.value = _sessions.value.filterNot { it.progressId == progressId }
    }

    @Synchronized
    fun clear() {
        _sessions.value = emptyList()
    }

    fun find(progressId: Long): ImportSession? = _sessions.value.find { it.progressId == progressId }

    /** Derive provisional titleId from filename like GTA-San-Andreas-BLUS31584.iso -> BLUS31584 */
    fun provisionalTitleIdFromName(name: String?): String? {
        if (name == null) return null
        return Regex("(?i)([A-Z]{4}[0-9]{5})").find(name)?.groupValues?.getOrNull(1)?.uppercase()
    }
}
