package com.zenithblue.sambas3.logging

import android.content.Context
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.coroutines.coroutineContext

object BackendFileSource {
    private const val DEFAULT_IDLE_DELAY_MS = 10_000L
    private const val ACTIVE_STREAMING_DELAY_MS = 2_000L

    suspend fun run(
        context: Context,
        engine: LogBrokerEngine,
        sessionId: () -> String?,
        isStreaming: () -> Boolean = { false },
    ) {
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Waiting)
        val tailers = LinkedHashMap<String, Pair<LogFileTailer, LogSourceKind>>()
        var lines = 0L
        var bytes = 0L
        var scans = 0
        while (coroutineContext.isActive) {
            if (!isStreaming()) {
                delay(DEFAULT_IDLE_DELAY_MS)
                continue
            }
            if (scans % 5 == 0) {
                val roots = roots(context)
                val discovered = LogArtifactDiscovery.scan(roots, sinceMs = null, maxFiles = 16)
                for (item in discovered) {
                    if (item.compressed) continue
                    val path = item.file.absolutePath
                    if (path in tailers) continue
                    if (isIgnoredArtifact(item.file)) continue
                    tailers[path] = LogFileTailer(item.file, startAtEnd = true) to item.kind
                    engine.setStatus(
                        item.kind,
                        if (item.file.length() > 0L) LogSourceStatus.Quiet(0) else LogSourceStatus.Waiting,
                    )
                }
                if (tailers.isEmpty()) {
                    engine.setStatus(
                        LogSourceKind.RPCSX_BACKEND,
                        LogSourceStatus.Unavailable("no RPCSX/RPCS3 log file discovered yet"),
                    )
                }
            }
            scans++
            for ((path, pair) in tailers) {
                val (tailer, kind) = pair
                val newLines = tailer.poll(maxLines = 200)
                for (line in newLines) {
                    engine.emit(
                        message = line,
                        source = kind,
                        sessionId = sessionId(),
                        artifactId = File(path).name,
                    )
                    lines++
                    bytes += line.length
                }
                if (newLines.isNotEmpty()) {
                    engine.setStatus(kind, LogSourceStatus.Active(lines, bytes))
                }
            }
            delay(ACTIVE_STREAMING_DELAY_MS)
        }
        for ((_, pair) in tailers) {
            pair.first.drain().forEach { line ->
                engine.emit(message = line, source = pair.second, sessionId = sessionId())
            }
        }
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Ended(lines, bytes))
    }

    private fun isIgnoredArtifact(file: File): Boolean {
        val name = file.name
        val path = file.absolutePath
        if (name.startsWith("rpcsx_app") || name.startsWith("rpcsx_backend") || name.startsWith("rpcsx_vulkan")) return true
        if (path.contains("/files/logs/") || path.endsWith("/files/logs")) return true
        return false
    }

    fun roots(context: Context): List<File> = buildList {
        if (RPCSX.rootDirectory.isNotBlank()) add(File(RPCSX.rootDirectory))
        add(context.filesDir)
        add(context.cacheDir)
        context.getExternalFilesDir(null)?.let(::add)
        add(File(context.filesDir, "cache"))
        add(File(context.cacheDir, "shaderlog"))
    }
}
