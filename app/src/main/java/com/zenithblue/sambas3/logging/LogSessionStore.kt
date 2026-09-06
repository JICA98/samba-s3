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
    private const val SCHEMA = 1
    private const val MAX_SESSIONS = 16
    private const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L
    private const val MAX_ICON_BYTES = 256 * 1024
    const val ICON_NAME = "game_icon.bin"

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
        )
        writeManifest(dir, manifest)
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

    fun attachArtifact(context: Context, sessionId: String, artifact: LogArtifact): LogSessionManifest? {
        val dir = File(sessionsRoot(context), sessionId)
        val current = readManifest(dir) ?: return null
        val artifacts = current.artifacts.filterNot { it.path == artifact.path || it.id == artifact.id } + artifact
        val next = current.copy(artifacts = artifacts)
        writeManifest(dir, next)
        return next
    }

    fun finalize(
        context: Context,
        sessionId: String,
        terminal: LogSessionTerminal,
        reason: String?,
        droppedLines: Long = 0L,
        extraArtifacts: List<LogArtifact> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): LogSessionManifest? {
        val dir = File(sessionsRoot(context), sessionId)
        val current = readManifest(dir) ?: return null
        val artifacts = (current.artifacts + extraArtifacts).distinctBy { it.path }
        val next = current.copy(
            endedAtMs = nowMs,
            terminalState = terminal,
            stopReason = reason ?: current.stopReason,
            artifacts = artifacts,
            droppedLines = droppedLines,
        )
        writeManifest(dir, next)
        return next
    }

    fun reconcileInterrupted(context: Context, nowMs: Long = System.currentTimeMillis()): List<LogSessionManifest> {
        val updated = mutableListOf<LogSessionManifest>()
        for (manifest in list(context)) {
            if (manifest.terminalState != LogSessionTerminal.RUNNING) continue
            val next = manifest.copy(
                endedAtMs = nowMs,
                terminalState = LogSessionTerminal.INTERRUPTED,
                stopReason = manifest.stopReason ?: "process-restart",
            )
            writeManifest(File(sessionsRoot(context), manifest.sessionId), next)
            updated += next
        }
        return updated
    }

    fun cleanupOld(context: Context, activeSessionId: String? = null) {
        val sessions = list(context)
        var total = sessions.sumOf { sessionBytes(File(sessionsRoot(context), it.sessionId)) }
        val deletable = sessions.filter { it.sessionId != activeSessionId && it.terminalState != LogSessionTerminal.RUNNING }
            .sortedBy { it.startedAtMs }
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
            terminalState = runCatching { LogSessionTerminal.valueOf(json.optString("terminalState")) }
                .getOrDefault(LogSessionTerminal.RUNNING),
            stopReason = json.optString("stopReason").ifBlank { null },
            bootMode = json.optString("bootMode").ifBlank { null },
            appVersion = json.optString("appVersion").ifBlank { null },
            coreBuildId = json.optString("coreBuildId").ifBlank { null },
            pid = json.optInt("pid", 0).takeIf { it != 0 },
            driverLabel = json.optString("driverLabel").ifBlank { null },
            artifacts = artifacts,
            droppedLines = json.optLong("droppedLines"),
        )
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
