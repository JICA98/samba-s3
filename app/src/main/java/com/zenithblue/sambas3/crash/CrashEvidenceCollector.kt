package com.zenithblue.sambas3.crash

import android.content.Context
import com.zenithblue.sambas3.LogMonitor
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.logging.LogArtifactDiscovery
import com.zenithblue.sambas3.logging.LogBroker
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.session.EmulationSessionRecord
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class CrashReport(
    val directory: File,
    val classification: CrashClassification,
    val summary: String,
    val cause: String,
    val sources: Map<String, File>,
    val gameTitle: String? = null,
    val titleId: String? = null,
    val gameIconPath: String? = null,
)

object CrashEvidenceCollector {
    fun collectSummary(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        val sourceFiles = collectSourceFiles(context, session)
        val endMs = session?.let { maxOf(it.lastHeartbeatMs, it.failureAtMs ?: 0L, it.stoppedAtMs ?: 0L) + 2_000L }
        val evidence = buildString {
            if (evidenceHint.isNotBlank()) append(evidenceHint).append('\n')
            sourceFiles.values.forEach { file ->
                if (file.isFile) append(file.readEvidence(session?.startedAtMs, endMs, sessionOwned = isSessionOwned(file))).append('\n')
            }
        }
        val classification = CrashClassifier.classify(evidence, session != null)
        val id = session?.sessionId ?: "report-${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "crash_reports/$id")
        val logSession = session?.sessionId?.let { LogSessionStore.read(context, it) }
        return CrashReport(
            directory = dir,
            classification = classification,
            summary = classification.name.replace('_', ' '),
            cause = CrashClassifier.likelyCause(evidence),
            sources = sourceFiles,
            gameTitle = logSession?.gameTitleSnapshot ?: session?.gameName,
            titleId = logSession?.titleId ?: session?.titleId,
            gameIconPath = logSession?.gameIconSnapshotPath,
        )
    }

    fun collect(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        val sourceFiles = collectSourceFiles(context, session)
        val endMs = session?.let { maxOf(it.lastHeartbeatMs, it.failureAtMs ?: 0L, it.stoppedAtMs ?: 0L) + 2_000L }
        val evidence = buildString {
            append(evidenceHint).append('\n')
            sourceFiles.values.forEach { file ->
                if (file.isFile) append(file.readEvidence(session?.startedAtMs, endMs, sessionOwned = isSessionOwned(file))).append('\n')
            }
        }
        val classification = CrashClassifier.classify(evidence, session != null)
        val id = session?.sessionId ?: "report-${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "crash_reports/$id").apply { mkdirs() }
        val copied = mutableMapOf<String, File>()
        sourceFiles.forEach { (name, source) ->
            val destination = File(dir, name)
            runCatching {
                destination.writeText(source.readEvidence(session?.startedAtMs, endMs, sessionOwned = isSessionOwned(source)))
                copied[name] = destination
            }
        }
        val logSession = session?.sessionId?.let { LogSessionStore.read(context, it) }
        val metadata = File(dir, "metadata.json").apply {
            writeText(JSONObject().apply {
                put("classification", classification.name)
                put("cause", CrashClassifier.likelyCause(evidence))
                put("sessionId", session?.sessionId ?: "")
                put("gamePath", session?.gamePath ?: logSession?.gamePath ?: "")
                put("titleId", logSession?.titleId ?: session?.titleId ?: "")
                put("gameTitle", logSession?.gameTitleSnapshot ?: session?.gameName ?: "")
                put("iconPath", logSession?.gameIconSnapshotPath ?: "")
                put("startedAtMs", session?.startedAtMs ?: logSession?.startedAtMs ?: 0L)
                put("lastHeartbeatMs", session?.lastHeartbeatMs ?: 0L)
                put("failureAtMs", session?.failureAtMs ?: 0L)
                put("stoppedAtMs", session?.stoppedAtMs ?: 0L)
                put("artifactCount", sourceFiles.size)
            }.toString(2))
        }
        File(dir, "summary.txt").writeText(
            buildString {
                appendLine(classification.name)
                appendLine("Game: ${logSession?.gameTitleSnapshot ?: session?.gameName ?: "unknown"}")
                appendLine("Title ID: ${logSession?.titleId ?: session?.titleId ?: "unknown"}")
                appendLine("Likely cause: ${CrashClassifier.likelyCause(evidence)}")
                if (sourceFiles.isEmpty()) appendLine("No log artifacts were attached. Capture starts with the app process, not the Logs screen.")
                appendLine(evidence.take(4000))
            }
        )
        copied["metadata.json"] = metadata
        copied["summary.txt"] = File(dir, "summary.txt")
        return CrashReport(
            dir,
            classification,
            classification.name.replace('_', ' '),
            CrashClassifier.likelyCause(evidence),
            copied,
            gameTitle = logSession?.gameTitleSnapshot ?: session?.gameName,
            titleId = logSession?.titleId ?: session?.titleId,
            gameIconPath = logSession?.gameIconSnapshotPath,
        )
    }

    fun collectSourceFiles(context: Context, session: EmulationSessionRecord?): Map<String, File> {
        runCatching { LogMonitor.flushWriters() }
        val files = LinkedHashMap<String, File>()
        fun add(file: File) {
            if (!file.isFile) return
            val key = uniqueName(files, file)
            files[key] = file
        }
        session?.sessionId?.let { id ->
            LogSessionStore.read(context, id)?.let { manifest ->
                manifest.gameIconSnapshotPath?.let { add(File(it)) }
                File(LogSessionStore.sessionDir(context, id), "manifest.json").let(::add)
                manifest.artifacts.forEach { add(File(it.path)) }
            }
        }
        LogMonitor.getAllLogFiles().forEach(::add)
        val roots = buildList<File> {
            if (RPCSX.rootDirectory.isNotBlank()) add(File(RPCSX.rootDirectory))
            add(context.filesDir)
            add(context.cacheDir)
            context.getExternalFilesDir("logs")?.let(::add)
            context.getExternalFilesDir(null)?.let(::add)
        }
        val since = session?.startedAtMs
        LogArtifactDiscovery.scan(roots, sinceMs = since).forEach { add(it.file) }
        LogBroker.engine.currentRing().takeIf { it.isNotEmpty() }?.let { ring ->
            val fallback = File(context.cacheDir, "broker-ring-tail.log")
            runCatching {
                fallback.writeText(ring.takeLast(400).joinToString("\n") { it.message })
                add(fallback)
            }
        }
        return files
    }

    private fun uniqueName(existing: Map<String, File>, file: File): String {
        var name = file.name
        var i = 1
        while (existing.containsKey(name)) {
            name = "${file.nameWithoutExtension}-$i.${file.extension.ifBlank { "log" }}"
            i++
        }
        return name
    }

    private fun isSessionOwned(file: File): Boolean =
        file.path.contains("${File.separator}logs${File.separator}sessions${File.separator}") ||
            file.name == "broker-ring-tail.log" ||
            file.name == "manifest.json"

    private fun File.readEvidence(startedAtMs: Long?, endAtMs: Long? = null, maxBytes: Int = 512 * 1024, sessionOwned: Boolean = false): String {
        if (startedAtMs == null || sessionOwned) return readPrefix(maxBytes)
        val start = startedAtMs
        val now = System.currentTimeMillis()
        val lines = StringBuilder()
        var parsedLine = false
        val fileLength = length()
        val tailStart = maxOf(0L, fileLength - maxBytes.toLong())
        java.io.RandomAccessFile(this, "r").use { file ->
            file.seek(tailStart)
            if (tailStart > 0L) file.readLine()
            while (file.filePointer < fileLength) {
                val line = file.readLine() ?: break
                val lineTime = line.logTimestampMs(now)
                if (lineTime != null) {
                    parsedLine = true
                    if (lineTime >= start && (endAtMs == null || lineTime <= endAtMs) && lines.length < maxBytes) lines.append(line).append('\n')
                } else if (!parsedLine && lastModified() >= start && (endAtMs == null || lastModified() <= endAtMs) && lines.length < maxBytes) {
                    lines.append(line).append('\n')
                }
                if (lines.length >= maxBytes) break
            }
        }
        return lines.toString().take(maxBytes)
    }

    private fun File.readPrefix(maxBytes: Int): String {
        if (!isFile) return ""
        return inputStream().use { stream ->
            val bytes = ByteArray(maxBytes)
            val count = stream.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count)
        }
    }

    private fun String.logTimestampMs(nowMs: Long): Long? {
        val match = Regex("^\\[?(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})").find(this) ?: return null
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }
        val parsed = runCatching { formatter.parse(match.groupValues[1]) ?: return null }.getOrNull() ?: return null
        val calendar = Calendar.getInstance().apply {
            time = parsed
            set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
        }
        var timestamp = calendar.timeInMillis
        if (timestamp > nowMs + 24 * 60 * 60 * 1000L) {
            calendar.add(Calendar.YEAR, -1)
            timestamp = calendar.timeInMillis
        }
        return timestamp
    }
}
