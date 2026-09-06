package com.zenithblue.sambas3.logging

import android.os.Process as AndroidProcess
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

object AppLogcatSource {
    private const val TAG = "AppLogcatSource"
    private const val DEFAULT_IDLE_DELAY_MS = 10_000L

    @Volatile
    private var activeProcess: java.lang.Process? = null

    fun stopActiveProcess() {
        activeProcess?.let { proc ->
            runCatching { proc.destroy() }
        }
        activeProcess = null
    }

    suspend fun run(
        engine: LogBrokerEngine,
        sessionId: () -> String?,
        isStreaming: () -> Boolean = { false },
    ) {
        val parser = LogcatParser()
        var lines = 0L
        var bytes = 0L
        engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Waiting)
        while (coroutineContext.isActive) {
            if (!isStreaming()) {
                engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Quiet(lines))
                delay(DEFAULT_IDLE_DELAY_MS)
                continue
            }
            try {
                val proc = startLogcat(AndroidProcess.myPid())
                activeProcess = proc
                proc.inputStream.bufferedReader().use { reader ->
                    while (coroutineContext.isActive && isStreaming()) {
                        val line = reader.readLine() ?: break
                        val parsed = parser.parse(line)
                        engine.emit(
                            message = parsed.message,
                            source = classify(parsed),
                            level = parsed.level,
                            tag = parsed.tag,
                            sessionId = sessionId(),
                            timestampMs = parsed.timestampMs,
                            timestampText = parsed.timestampText,
                            raw = parsed.raw,
                        )
                        lines++
                        bytes += line.length
                        if (lines % 16L == 0L) {
                            engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Active(lines, bytes))
                        }
                    }
                }
                proc.destroy()
                activeProcess = null
            } catch (e: Exception) {
                engine.setStatus(
                    LogSourceKind.APP_ANDROID,
                    LogSourceStatus.Unavailable(e.message ?: "logcat failed"),
                )
                activeProcess = null
                delay(DEFAULT_IDLE_DELAY_MS)
            }
        }
        engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Ended(lines, bytes))
    }

    private fun startLogcat(pid: Int): java.lang.Process {
        val withPid = runCatching {
            ProcessBuilder("logcat", "-v", "threadtime", "-b", "main,crash,system", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
        }.getOrNull()
        if (withPid != null) return withPid
        return ProcessBuilder("logcat", "-v", "threadtime", "-b", "main,crash,system")
            .redirectErrorStream(true)
            .start()
    }

    private fun classify(parsed: ParsedLogcatLine): LogSourceKind {
        if (parsed.raw) return LogSourceKind.OTHER
        val tag = parsed.tag ?: return LogSourceKind.APP_ANDROID
        val lower = tag.lowercase()
        return when {
            lower.contains("vulkan") || lower == "vkdbg" -> LogSourceKind.VULKAN
            lower.contains("mesa") || lower.contains("turnip") || lower.contains("freedreno") ||
                lower.contains("adrenotools") -> LogSourceKind.TURNIP_GPU
            lower.startsWith("cell") || lower.startsWith("sys_") -> LogSourceKind.GAME
            lower.contains("rpcs") || lower.contains("ppu") || lower.contains("spu") ||
                lower == "sys_log" || lower == "android" -> LogSourceKind.RPCSX_BACKEND
            else -> LogSourceKind.APP_ANDROID
        }
    }
}
