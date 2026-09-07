package com.zenithblue.sambas3.logging

import android.content.Context
import android.net.Uri
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

object LogSessionStore {
    private const val SCHEMA = 2
    private const val MAX_SESSIONS = 16
    private const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L
    private const val MAX_ICON_BYTES = 256 * 1024
    const val ICON_NAME = "game_icon.bin"
    private val lock = Any()
    private val pinned = HashSet<String>()
    private val finalizing = HashSet<String>()

    fun sessionsRoot(context: Context): File =
        File(context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs"), "sessions").apply { mkdirs() }

    fun sessionDir(context: Context, sessionId: String): File =
        File(sessionsRoot(context), sessionId).apply { mkdirs() }

    fun begin(
        context: Context,
        sessionId: String = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
        gamePath: String,
        titleId: String?,
        gameTitle: String,
        iconPath: String?,
        bootMode: String?,
        driverLabel: String?,
        appVersion: String?,
        coreBuildId: String?,
        pid: Int,
        nowMs: Long = System.currentTimeMillis(),
        processInstanceId: String? = null,
        producerEpoch: String? = null,
        appliedDriverLabel: String? = driverLabel,
    ): LogSessionManifest {
        val dir = sessionDir(context, sessionId)
        val iconSnapshot = snapshotIcon(context, iconPath, File(dir, ICON_NAME))
        val manifest = LogSessionManifest(
            schemaVersion = SCHEMA,
            sessionId = sessionId,
            gamePath = gamePath,
            titleId = titleId,
            gameTitleSnapshot = gameTitle,
            gameIconSnapshotPath = iconSnapshot,
            startedAtMs = nowMs,
            terminalState = LogSessionTerminal.RUNNING,
            bootMode = bootMode,
            appVersion = appVersion,
            coreBuildId = coreBuildId,
            pid = pid,
            driverLabel = driverLabel,
            appliedDriverLabel = appliedDriverLabel ?: driverLabel,
            revision = 1L,
            captureState = CaptureState.RECORDING,
            processInstanceId = processInstanceId,
            producerEpoch = producerEpoch,
        )
        synchronized(lock) { writeManifest(dir, manifest) }
        cleanupOld(context, activeSessionId = sessionId)
        return manifest
    }

    fun read(context: Context, sessionId: String): LogSessionManifest? =
        readManifest(File(sessionsRoot(context), sessionId))

    fun list(context: Context): List<LogSessionManifest> =
        sessionsRoot(context).listFiles()
            ?.mapNotNull { readManifest(it) }
            ?.sortedByDescending { it.startedAtMs }
            ?: emptyList()

    fun active(context: Context): LogSessionManifest? =
        list(context).firstOrNull { it.terminalState == LogSessionTerminal.RUNNING }

    fun lastCompleted(context: Context): LogSessionManifest? =
        list(context).firstOrNull { it.terminalState != LogSessionTerminal.RUNNING } ?: list(context).firstOrNull()

    fun latest(context: Context): LogSessionManifest? = list(context).firstOrNull()

    fun pin(sessionId: String) {
        synchronized(lock) { pinned += sessionId }
    }

    fun unpin(sessionId: String) {
        synchronized(lock) { pinned -= sessionId }
    }

    fun isProtected(sessionId: String, terminal: LogSessionTerminal? = null): Boolean {
        synchronized(lock) {
            if (sessionId in pinned || sessionId in finalizing) return true
        }
        return terminal == LogSessionTerminal.RUNNING
    }

    fun attachArtifact(context: Context, sessionId: String, artifact: LogArtifact): LogSessionManifest? {
        val dir = File(sessionsRoot(context), sessionId)
        synchronized(lock) {
            val current = readManifest(dir) ?: return null
            val artifacts = current.artifacts.filterNot { it.path == artifact.path || it.id == artifact.id } + artifact
            val next = current.copy(artifacts = artifacts, revision = current.revision + 1)
            writeManifest(dir, next)
            return next
        }
    }

    fun finalize(
        context: Context,
        sessionId: String,
        terminal: LogSessionTerminal,
        reason: String?,
        droppedLines: Long = 0L,
        extraArtifacts: List<LogArtifact> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        captureState: CaptureState = CaptureState.SEALED,
        captureError: String? = null,
        displayDropped: Long = droppedLines,
        persistenceDropped: Long = 0L,
    ): LogSessionManifest? {
        val dir = File(sessionsRoot(context), sessionId)
        synchronized(lock) {
            finalizing += sessionId
            val current = readManifest(dir) ?: return null
            val artifacts = mergeArtifacts(current.artifacts, extraArtifacts)
            val next = current.copy(
                endedAtMs = nowMs,
                terminalState = SessionDiagnostics.preferTerminal(current.terminalState, terminal),
                stopReason = reason ?: current.stopReason,
                artifacts = artifacts,
                droppedLines = displayDropped,
                displayDroppedLines = displayDropped,
                persistenceDroppedLines = persistenceDropped,
                revision = current.revision + 1,
                captureState = captureState,
                captureError = captureError ?: current.captureError,
            )
            writeManifest(dir, next)
            finalizing -= sessionId
            return next
        }
    }

    fun reconcileInterrupted(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        liveSessionId: String? = null,
        liveProcessInstanceId: String? = null,
    ): List<LogSessionManifest> {
        val updated = mutableListOf<LogSessionManifest>()
        synchronized(lock) {
            for (manifest in list(context)) {
                if (manifest.terminalState != LogSessionTerminal.RUNNING) continue
                if (liveSessionId != null && manifest.sessionId == liveSessionId) continue
                if (liveProcessInstanceId != null &&
                    !manifest.processInstanceId.isNullOrBlank() &&
                    manifest.processInstanceId == liveProcessInstanceId
                ) continue
                val next = manifest.copy(
                    endedAtMs = nowMs,
                    terminalState = LogSessionTerminal.INTERRUPTED,
                    stopReason = manifest.stopReason ?: "process-restart",
                    captureState = CaptureState.RECOVERED_PARTIAL,
                    revision = manifest.revision + 1,
                )
                writeManifest(File(sessionsRoot(context), manifest.sessionId), next)
                updated += next
            }
        }
        return updated
    }

    fun cleanupOld(context: Context, activeSessionId: String? = null) {
        val sessions = list(context)
        var total = sessions.sumOf { sessionBytes(File(sessionsRoot(context), it.sessionId)) }
        val deletable = sessions.filter {
            it.sessionId != activeSessionId &&
                it.terminalState != LogSessionTerminal.RUNNING &&
                !isProtected(it.sessionId, it.terminalState) &&
                it.captureState != CaptureState.FINALIZING &&
                it.captureState != CaptureState.RECORDING
        }.sortedBy { it.startedAtMs }
        var remaining = sessions.size
        for (manifest in deletable) {
            if (remaining <= MAX_SESSIONS && total <= MAX_TOTAL_BYTES) break
            val dir = File(sessionsRoot(context), manifest.sessionId)
            val size = sessionBytes(dir)
            dir.deleteRecursively()
            total -= size
            remaining--
        }
    }

    fun deleteEligible(context: Context, sessionIds: Collection<String>, activeSessionId: String? = null): Int {
        var deleted = 0
        for (id in sessionIds) {
            val current = read(context, id) ?: continue
            if (isProtected(id, current.terminalState) || id == activeSessionId) continue
            if (current.captureState == CaptureState.RECORDING || current.captureState == CaptureState.FINALIZING) continue
            File(sessionsRoot(context), id).deleteRecursively()
            deleted++
        }
        return deleted
    }

    fun snapshotSource(context: Context, sessionId: String, source: File, kind: LogSourceKind, producer: String, generation: String): LogArtifact? {
        if (!source.isFile) return null
        val dir = sessionDir(context, sessionId)
        val relative = "raw/$producer/$generation/${source.name}"
        val dest = File(dir, relative)
        if (!SessionExport.copyBytes(source, dest)) return null
        return LogArtifact(
            id = "$producer-$generation-${source.name}",
            source = kind,
            path = dest.absolutePath,
            detectedAtMs = System.currentTimeMillis(),
            bytes = dest.length(),
            compressed = source.name.endsWith(".gz", true),
            liveTailSupported = !source.name.endsWith(".gz", true),
            finalStatus = "sealed",
            relativePath = relative,
            originalFilename = source.name,
            role = "raw",
            producer = producer,
            generation = generation,
            sealed = true,
            sha256 = SessionExport.sha256(dest),
            firstCursor = 0L,
            lastCursor = dest.length(),
        )
    }

    fun registerOutput(
        context: Context,
        sessionId: String,
        file: File,
        kind: LogSourceKind,
        producer: String,
        generation: String,
        sealed: Boolean,
    ): LogArtifact? {
        if (!file.isFile) return null
        val dir = sessionDir(context, sessionId)
        val relative = if (file.absolutePath.startsWith(dir.absolutePath)) {
            file.absolutePath.removePrefix(dir.absolutePath).trimStart('/')
        } else {
            "raw/$producer/$generation/${file.name}"
        }
        val dest = if (file.absolutePath.startsWith(dir.absolutePath)) file else File(dir, relative).also {
            SessionExport.copyBytes(file, it)
        }
        return LogArtifact(
            id = "$producer-$generation-${file.name}",
            source = kind,
            path = dest.absolutePath,
            detectedAtMs = System.currentTimeMillis(),
            bytes = dest.length(),
            compressed = file.name.endsWith(".gz", true),
            liveTailSupported = !sealed && !file.name.endsWith(".gz", true),
            finalStatus = if (sealed) "sealed" else "checkpoint",
            relativePath = relative,
            originalFilename = file.name,
            role = "raw",
            producer = producer,
            generation = generation,
            sealed = sealed,
            sha256 = if (sealed) SessionExport.sha256(dest) else null,
            firstCursor = 0L,
            lastCursor = dest.length(),
        )
    }

    fun snapshotIcon(context: Context, iconPath: String?, dest: File): String? {
        if (iconPath.isNullOrBlank()) return null
        return runCatching {
            val preview = GamePreviewRepository.resolveInstalledPreview(iconPath)
            val copied = when (preview) {
                is GamePreviewModel.LocalFile -> preview.file.takeIf { it.isFile }?.let { copyBounded(FileInputStream(it), dest) }
                is GamePreviewModel.ContentUri -> context.contentResolver.openInputStream(preview.uri)?.use { copyBounded(it, dest) }
                GamePreviewModel.None -> {
                    if (iconPath.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(iconPath))?.use { copyBounded(it, dest) }
                    } else {
                        val file = File(iconPath)
                        if (file.isFile) copyBounded(FileInputStream(file), dest) else null
                    }
                }
            }
            if (copied != null && dest.isFile && dest.length() > 0L) dest.absolutePath else null
        }.getOrNull()
    }

    fun writeManifest(dir: File, manifest: LogSessionManifest) {
        dir.mkdirs()
        val target = File(dir, "manifest.json")
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(toJson(manifest).toString(2))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun readManifest(dir: File): LogSessionManifest? {
        if (!dir.isDirectory) return null
        val primary = File(dir, "manifest.json")
        val tmp = File(dir, "manifest.json.tmp")
        val file = when {
            primary.isFile -> primary
            tmp.isFile -> tmp
            else -> return null
        }
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun toJson(manifest: LogSessionManifest): JSONObject = JSONObject().apply {
        put("schemaVersion", manifest.schemaVersion)
        put("sessionId", manifest.sessionId)
        put("gamePath", manifest.gamePath)
        put("titleId", manifest.titleId ?: "")
        put("gameTitleSnapshot", manifest.gameTitleSnapshot)
        put("gameIconSnapshotPath", manifest.gameIconSnapshotPath ?: "")
        put("startedAtMs", manifest.startedAtMs)
        put("endedAtMs", manifest.endedAtMs ?: 0L)
        put("terminalState", manifest.terminalState.name)
        put("stopReason", manifest.stopReason ?: "")
        put("bootMode", manifest.bootMode ?: "")
        put("appVersion", manifest.appVersion ?: "")
        put("coreBuildId", manifest.coreBuildId ?: "")
        put("pid", manifest.pid ?: 0)
        put("driverLabel", manifest.driverLabel ?: "")
            put("droppedLines", manifest.droppedLines)
            put("revision", manifest.revision)
            put("captureState", manifest.captureState.name)
            put("captureError", manifest.captureError ?: "")
            put("processInstanceId", manifest.processInstanceId ?: "")
            put("producerEpoch", manifest.producerEpoch ?: "")
            put("displayDroppedLines", manifest.displayDroppedLines)
            put("persistenceDroppedLines", manifest.persistenceDroppedLines)
            put("appliedDriverLabel", manifest.appliedDriverLabel ?: "")
            put("unknownTerminalRaw", manifest.unknownTerminalRaw ?: "")
            put("artifacts", JSONArray().also { arr ->
                manifest.artifacts.forEach { art ->
                    arr.put(JSONObject().apply {
                        put("id", art.id)
                        put("source", art.source.name)
                        put("path", art.path)
                        put("detectedAtMs", art.detectedAtMs)
                        put("bytes", art.bytes)
                        put("compressed", art.compressed)
                        put("liveTailSupported", art.liveTailSupported)
                        put("finalStatus", art.finalStatus)
                        put("relativePath", art.relativePath ?: "")
                        put("originalFilename", art.originalFilename)
                        put("role", art.role)
                        put("producer", art.producer ?: "")
                        put("generation", art.generation ?: "")
                        put("sealed", art.sealed)
                        put("sha256", art.sha256 ?: "")
                        put("encoding", art.encoding)
                        put("firstCursor", art.firstCursor ?: -1L)
                        put("lastCursor", art.lastCursor ?: -1L)
                    })
                }
            })
        }

    fun fromJson(json: JSONObject): LogSessionManifest {
        val artifacts = mutableListOf<LogArtifact>()
        val arr = json.optJSONArray("artifacts")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                  artifacts += LogArtifact(
                    id = o.optString("id"),
                    source = runCatching { LogSourceKind.valueOf(o.optString("source")) }.getOrDefault(LogSourceKind.OTHER),
                    path = o.optString("path"),
                    detectedAtMs = o.optLong("detectedAtMs"),
                    bytes = o.optLong("bytes"),
                    compressed = o.optBoolean("compressed"),
                    liveTailSupported = o.optBoolean("liveTailSupported"),
                    finalStatus = o.optString("finalStatus", "unknown"),
                    relativePath = o.optString("relativePath").ifBlank { null },
                    originalFilename = o.optString("originalFilename"),
                    role = o.optString("role", "raw"),
                    producer = o.optString("producer").ifBlank { null },
                    generation = o.optString("generation").ifBlank { null },
                    sealed = o.optBoolean("sealed"),
                    sha256 = o.optString("sha256").ifBlank { null },
                    encoding = o.optString("encoding", "utf-8"),
                    firstCursor = o.optLong("firstCursor", -1L).takeIf { it >= 0L },
                    lastCursor = o.optLong("lastCursor", -1L).takeIf { it >= 0L },
                )
            }
        }
        return LogSessionManifest(
            schemaVersion = json.optInt("schemaVersion", SCHEMA),
            sessionId = json.optString("sessionId"),
            gamePath = json.optString("gamePath"),
            titleId = json.optString("titleId").ifBlank { null },
            gameTitleSnapshot = json.optString("gameTitleSnapshot").ifBlank { json.optString("gamePath").substringAfterLast('/') },
            gameIconSnapshotPath = json.optString("gameIconSnapshotPath").ifBlank { null },
            startedAtMs = json.optLong("startedAtMs"),
            endedAtMs = json.optLong("endedAtMs", 0L).takeIf { it != 0L },
            terminalState = parseTerminal(json.optString("terminalState")),
            stopReason = json.optString("stopReason").ifBlank { null },
            bootMode = json.optString("bootMode").ifBlank { null },
            appVersion = json.optString("appVersion").ifBlank { null },
            coreBuildId = json.optString("coreBuildId").ifBlank { null },
            pid = json.optInt("pid", 0).takeIf { it != 0 },
            driverLabel = json.optString("driverLabel").ifBlank { null },
            artifacts = artifacts,
            droppedLines = json.optLong("droppedLines"),
            revision = json.optLong("revision", 1L),
            captureState = runCatching { CaptureState.valueOf(json.optString("captureState")) }
                .getOrDefault(if (json.optString("terminalState") == "RUNNING" || json.optString("terminalState").isBlank()) CaptureState.RECORDING else CaptureState.SEALED),
            captureError = json.optString("captureError").ifBlank { null },
            processInstanceId = json.optString("processInstanceId").ifBlank { null },
            producerEpoch = json.optString("producerEpoch").ifBlank { null },
            displayDroppedLines = json.optLong("displayDroppedLines", json.optLong("droppedLines")),
            persistenceDroppedLines = json.optLong("persistenceDroppedLines"),
            appliedDriverLabel = json.optString("appliedDriverLabel").ifBlank { json.optString("driverLabel").ifBlank { null } },
            unknownTerminalRaw = json.optString("unknownTerminalRaw").ifBlank { unknownTerminal(json.optString("terminalState")) },
        )
    }

    private fun parseTerminal(raw: String): LogSessionTerminal {
        if (raw.isBlank()) return LogSessionTerminal.RUNNING
        return runCatching { LogSessionTerminal.valueOf(raw) }.getOrDefault(LogSessionTerminal.INTERRUPTED)
    }

    private fun unknownTerminal(raw: String): String? {
        if (raw.isBlank()) return null
        return if (runCatching { LogSessionTerminal.valueOf(raw) }.isSuccess) null else raw
    }

    private fun mergeArtifacts(current: List<LogArtifact>, extra: List<LogArtifact>): List<LogArtifact> {
        val byId = LinkedHashMap<String, LogArtifact>()
        for (art in current + extra) {
            val key = art.id.ifBlank { art.path }
            val existing = byId[key]
            byId[key] = if (existing == null) art else if (art.sealed) art else existing
        }
        return byId.values.toList()
    }

    private fun copyBounded(input: java.io.InputStream, dest: File): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var copied = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(16 * 1024)
            while (copied < MAX_ICON_BYTES) {
                val n = input.read(buf, 0, minOf(buf.size, (MAX_ICON_BYTES - copied).toInt()))
                if (n <= 0) break
                out.write(buf, 0, n)
                copied += n
            }
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        return copied
    }

    private fun sessionBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
