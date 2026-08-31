package com.zenithblue.sambas3.crash

import android.content.Context
import com.zenithblue.sambas3.LogMonitor
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
    val sources: Map<String, File>
)

object CrashEvidenceCollector {
    /** Fast startup path: bounded tails only, no report directory or large copies. */
    fun collectSummary(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        LogMonitor.flushWriters()
        val sourceFiles = LogMonitor.getAllLogFiles().associateBy { it.name }
        val endMs = session?.let { maxOf(it.lastHeartbeatMs, it.failureAtMs ?: 0L, it.stoppedAtMs ?: 0L) + 2_000L }
        val evidence = buildString {
            if (evidenceHint.isNotBlank()) append(evidenceHint).append('\n')
            sourceFiles.values.forEach { file -> if (file.isFile) append(file.readEvidence(session?.startedAtMs, endMs)).append('\n') }
        }
        val classification = CrashClassifier.classify(evidence, session != null)
        val id = session?.sessionId ?: "report-${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "crash_reports/$id")
        return CrashReport(
            directory = dir,
            classification = classification,
            summary = classification.name.replace('_', ' '),
            cause = CrashClassifier.likelyCause(evidence),
            sources = sourceFiles
        )
    }

    /** Explicit user export path. It is intentionally never called during startup rendering. */
    fun collect(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        LogMonitor.flushWriters()
        val sourceFiles = LogMonitor.getAllLogFiles().associateBy { it.name }
        val endMs = session?.let { maxOf(it.lastHeartbeatMs, it.failureAtMs ?: 0L, it.stoppedAtMs ?: 0L) + 2_000L }
        val evidence = buildString {
            append(evidenceHint).append('\n')
            sourceFiles.values.forEach { file -> if (file.isFile) append(file.readEvidence(session?.startedAtMs, endMs)).append('\n') }
        }
        val classification = CrashClassifier.classify(evidence, session != null)
        val id = session?.sessionId ?: "report-${System.currentTimeMillis()}"
        val dir = File(context.filesDir, "crash_reports/$id").apply { mkdirs() }
        val copied = mutableMapOf<String, File>()
        sourceFiles.forEach { (name, source) ->
            val destination = File(dir, name)
            runCatching { destination.writeText(source.readEvidence(session?.startedAtMs, endMs)); copied[name] = destination }
        }
        val metadata = File(dir, "metadata.json").apply { writeText(JSONObject().apply {
            put("classification", classification.name)
            put("cause", CrashClassifier.likelyCause(evidence))
            put("sessionId", session?.sessionId ?: "")
            put("gamePath", session?.gamePath ?: "")
            put("titleId", session?.titleId ?: "")
            put("startedAtMs", session?.startedAtMs ?: 0L)
            put("lastHeartbeatMs", session?.lastHeartbeatMs ?: 0L)
            put("failureAtMs", session?.failureAtMs ?: 0L)
            put("stoppedAtMs", session?.stoppedAtMs ?: 0L)
        }.toString(2)) }
        File(dir, "summary.txt").writeText("${classification.name}\nLikely cause: ${CrashClassifier.likelyCause(evidence)}\n${evidence.take(4000)}")
        copied["metadata.json"] = metadata
        copied["summary.txt"] = File(dir, "summary.txt")
        return CrashReport(dir, classification, classification.name.replace('_', ' '), CrashClassifier.likelyCause(evidence), copied)
    }

    private fun File.readEvidence(startedAtMs: Long?, endAtMs: Long? = null, maxBytes: Int = 512 * 1024): String {
        if (startedAtMs == null) return readPrefix(maxBytes)
        val start = startedAtMs
        val now = System.currentTimeMillis()
        val lines = StringBuilder()
        var parsedLine = false
        // Recovery runs during Home startup. Read only the newest bounded tail;
        // scanning a rotated 25 MB log before drawing the card makes recovery
        // appear hung and can delay the first frame for many seconds.
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

    private fun File.readPrefix(maxBytes: Int): String = inputStream().use { stream ->
        val bytes = ByteArray(maxBytes)
        val count = stream.read(bytes)
        if (count <= 0) "" else String(bytes, 0, count)
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
