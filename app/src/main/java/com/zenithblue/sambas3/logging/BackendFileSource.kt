package com.zenithblue.sambas3.logging

import android.content.Context
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class BoundTailer(
    val tailer: LogFileTailer,
    val kind: LogSourceKind,
    val generation: String,
    val boundSessionId: String?,
    val path: String,
)

object BackendFileSource {
    private const val DEFAULT_IDLE_DELAY_MS = 10_000L
    private const val ACTIVE_STREAMING_DELAY_MS = 250L
    private const val BURST_MAX_LINES = 2_000
    private const val ITERATION_BYTE_BUDGET = 256 * 1024

    private val wake = AtomicBoolean(false)
    @Volatile private var latestTailers: Map<String, BoundTailer> = emptyMap()

    fun wake() {
        wake.set(true)
    }

    suspend fun drainOnce() {
        val current = latestTailers
        for ((_, bound) in current) {
            bound.tailer.drain().forEach { line ->
                LogBroker.engine.emit(
                    message = line,
                    source = bound.kind,
                    sessionId = bound.boundSessionId,
                    artifactId = File(bound.path).name,
                    boundSessionId = bound.boundSessionId,
                )
            }
        }
    }

    suspend fun run(
        context: Context,
        engine: LogBrokerEngine,
        sessionId: () -> String?,
        isStreaming: () -> Boolean = { false },
    ) {
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Waiting)
        val tailers = LinkedHashMap<String, BoundTailer>()
        latestTailers = tailers
        var lines = 0L
        var bytes = 0L
        var scans = 0
        while (coroutineContext.isActive) {
            val forced = wake.getAndSet(false)
            if (!isStreaming() && !forced) {
                delay(DEFAULT_IDLE_DELAY_MS)
                continue
            }
            if (forced || scans % 5 == 0) {
                val roots = roots(context)
                val discovered = LogArtifactDiscovery.scan(roots, sinceMs = null, maxFiles = 16)
                val liveSession = sessionId()
                for (item in discovered) {
                    if (item.compressed) continue
                    val path = item.file.absolutePath
                    if (path in tailers) continue
                    if (isIgnoredArtifact(item.file)) continue
                    val generation = "${item.file.name}-${item.file.length()}-${item.file.lastModified()}"
                    tailers[path] = BoundTailer(
                        tailer = LogFileTailer(item.file, startAtEnd = false),
                        kind = item.kind,
                        generation = generation,
                        boundSessionId = liveSession,
                        path = path,
                    )
                    engine.setStatus(
                        item.kind,
                        if (item.file.length() > 0L) LogSourceStatus.Quiet(0) else LogSourceStatus.Waiting,
                    )
                }
                latestTailers = tailers.toMap()
                if (tailers.isEmpty()) {
                    engine.setStatus(
                        LogSourceKind.RPCSX_BACKEND,
                        LogSourceStatus.Unavailable("no RPCSX/RPCS3 log file discovered yet"),
                    )
                }
            }
            scans++
            var iterationBytes = 0
            for ((path, bound) in tailers) {
                val newLines = try {
                    bound.tailer.poll(maxLines = BURST_MAX_LINES)
                } catch (e: Exception) {
                    engine.setStatus(bound.kind, LogSourceStatus.ParseError(e.message ?: "io"))
                    emptyList()
                }
                for (line in newLines) {
                    engine.emit(
                        message = line,
                        source = bound.kind,
                        sessionId = bound.boundSessionId ?: sessionId(),
                        artifactId = File(path).name,
                        boundSessionId = bound.boundSessionId,
                    )
                    lines++
                    val size = line.toByteArray(Charsets.UTF_8).size
                    bytes += size
                    iterationBytes += size
                }
                if (newLines.isNotEmpty()) {
                    engine.setStatus(bound.kind, LogSourceStatus.Active(lines, bytes))
                }
                if (iterationBytes >= ITERATION_BYTE_BUDGET) break
            }
            delay(ACTIVE_STREAMING_DELAY_MS)
        }
        drainOnce()
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
