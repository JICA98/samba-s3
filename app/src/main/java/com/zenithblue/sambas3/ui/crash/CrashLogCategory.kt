package com.zenithblue.sambas3.ui.crash

import com.zenithblue.sambas3.crash.CrashLogReader
import com.zenithblue.sambas3.crash.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class CategoryContent(
    val offset: Long,
    val totalBytes: Long,
    val text: String,
    val noMatch: Boolean = false,
)

internal suspend fun resolveCategoryContent(
    report: CrashReport,
    tabIndex: Int,
    query: String,
    selectedArtifact: String? = null,
    requestedOffset: Long? = null,
): CategoryContent = withContext(Dispatchers.IO) {
    when (tabIndex) {
        0 -> summaryContent(report)
        1 -> readMatching(report, query, selectedArtifact, requestedOffset, ::isBackend)
        2 -> gpuContent(report, query, selectedArtifact, requestedOffset)
        3 -> readMatching(report, query, selectedArtifact, requestedOffset, ::isApp)
        4 -> readMatching(report, query, selectedArtifact, requestedOffset, ::isSystem)
        5 -> deviceContent(report)
        else -> CategoryContent(0L, 0L, "Unknown category")
    }
}

private fun summaryContent(report: CrashReport): CategoryContent {
    val synthesized = buildString {
        appendLine("SAMBAS3 SESSION DIAGNOSTICS")
        appendLine()
        appendLine("Game:            ${report.gameTitle ?: "Unknown"}")
        appendLine("Title ID:        ${report.titleId ?: "Unknown"}")
        appendLine("Session ID:      ${report.sessionId ?: "Unknown"}")
        appendLine("Revision:        ${report.revision}")
        appendLine("Outcome:         ${report.diagnostics?.outcome?.name ?: report.classification.name}")
        appendLine("Raw stop reason: ${report.rawStopReason ?: "(none)"}")
        appendLine("Diagnostic cause:${report.diagnostics?.diagnosticCause?.name ?: report.cause}")
        appendLine("Capture state:   ${report.captureState?.name ?: "unknown"}")
        appendLine("Session Folder:  ${report.directory.name}")
        appendLine()
        appendLine("CAPTURED LOG ARTIFACTS (${report.sources.size})")
        if (report.sources.isEmpty()) {
            appendLine("No log artifacts were captured for this session.")
            appendLine("Absence of a summary object is not proof that logs do not exist.")
        } else {
            report.sources.forEach { (name, file) ->
                val sizeStr = if (file.isFile) "%,d bytes".format(file.length()) else "Missing"
                appendLine("  • $name : $sizeStr")
            }
        }
    }
    return CategoryContent(0L, synthesized.toByteArray().size.toLong(), synthesized)
}

private fun gpuContent(
    report: CrashReport,
    query: String,
    selectedArtifact: String?,
    requestedOffset: Long?,
): CategoryContent {
    val dedicated = matchingFiles(report, ::isGpu)
    if (dedicated.isNotEmpty()) return readMatching(report, query, selectedArtifact, requestedOffset, ::isGpu)
    val backend = matchingFiles(report, ::isBackend)
    if (backend.isEmpty()) {
        return CategoryContent(
            0L,
            0L,
            "[VULKAN / GPU]\nNo dedicated GPU log and no backend evidence.\nThis is source absence, not zero matching events.",
        )
    }
    val excerpts = buildString {
        appendLine("[VULKAN / GPU derived from backend]")
        appendLine("No standalone GPU driver file. Extracted backend events with raw offsets:")
        appendLine()
        for ((name, file) in backend) {
            val reader = CrashLogReader.forArtifact(file)
            val gpu = Regex("vulkan|gpu|turnip|mesa|adreno|VK_|swapchain|pipeline", RegexOption.IGNORE_CASE)
            val found = reader.find("VK_")
            appendLine("artifact=$name size=${reader.sourceLength()} firstVK=${if (found < 0) "none" else found}")
            val (off, text) = reader.readTail(64 * 1024)
            text.lineSequence().filter { gpu.containsMatchIn(it) }.take(80).forEach { appendLine("  $it") }
            appendLine("  (tail offset $off)")
        }
    }
    return CategoryContent(0L, excerpts.toByteArray().size.toLong(), excerpts)
}

private fun deviceContent(report: CrashReport): CategoryContent {
    val file = report.sources.entries.firstOrNull {
        it.key.equals("metadata.json", true) || it.key.equals("manifest.json", true)
    }?.value
    val historical = if (file != null && file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
    val current = buildString {
        appendLine("[CURRENT DEVICE CONTEXT — not historical session metadata]")
        appendLine("Device Model   : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Android OS     : Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("App Version    : ${com.zenithblue.sambas3.BuildConfig.VERSION_NAME}")
        appendLine()
        if (historical.isNotBlank()) {
            appendLine("[HISTORICAL MANIFEST]")
            appendLine(historical)
        }
    }
    return CategoryContent(0L, current.toByteArray().size.toLong(), current)
}

private fun readMatching(
    report: CrashReport,
    query: String,
    selectedArtifact: String?,
    requestedOffset: Long?,
    matcher: (String, File) -> Boolean,
): CategoryContent {
    val matches = matchingFiles(report, matcher)
    if (matches.isEmpty()) {
        return CategoryContent(0L, 0L, "No matching artifacts.\nCaptured: ${report.sources.keys.joinToString(", ").ifBlank { "(none)" }}")
    }
    val chosen = selectedArtifact?.let { id -> matches.firstOrNull { it.first == id } } ?: matches.first()
    val file = chosen.second
    val reader = CrashLogReader.forArtifact(file)
    val length = reader.sourceLength()
    val page = 256 * 1024
    var noMatch = false
    val off = when {
        query.isNotBlank() -> {
            val found = reader.find(query)
            if (found < 0L) {
                noMatch = true
                (length - page).coerceAtLeast(0L)
            } else found
        }
        requestedOffset != null -> requestedOffset.coerceIn(0L, length)
        else -> (length - page).coerceAtLeast(0L)
    }
    val body = reader.read(off, page)
    val header = buildString {
        appendLine("artifact=${chosen.first}  files=${matches.joinToString { it.first }}")
        if (reader.compressed) appendLine("gzip decoded=${length}B compressed=${reader.compressedLength()}B partial=${reader.partial}")
        if (noMatch) appendLine("No matches")
        appendLine("Showing offset $off / $length (tail-first unless searching)")
        appendLine()
    }
    val text = if (body.isEmpty()) header + "[empty at this offset]" else header + body
    return CategoryContent(off, length, text, noMatch)
}

private fun matchingFiles(report: CrashReport, matcher: (String, File) -> Boolean): List<Pair<String, File>> =
    report.sources.entries
        .filter { it.value.isFile && matcher(it.key, it.value) }
        .map { it.key to it.value }

private fun isBackend(name: String, file: File): Boolean {
    val n = name.lowercase()
    val f = file.name.lowercase()
    return n.contains("backend") || n.contains("rpcsx") || n.contains("rpcs3") || n.contains("tty") ||
        f.contains("backend") || f.contains("rpcsx") || f.contains("rpcs3") || f.endsWith(".log.gz")
}

private fun isGpu(name: String, file: File): Boolean {
    val n = (name + file.name).lowercase()
    return n.contains("vulkan") || n.contains("gpu") || n.contains("turnip") || n.contains("mesa") || n.contains("adreno")
}

private fun isApp(name: String, file: File): Boolean {
    val n = (name + file.name).lowercase()
    return n.contains("app") || n.contains("sambas3") || n.contains("android")
}

private fun isSystem(name: String, file: File): Boolean {
    val n = (name + file.name).lowercase()
    return n.contains("system") || n.contains("logcat") || n.contains("tombstone") || n.contains("os")
}
