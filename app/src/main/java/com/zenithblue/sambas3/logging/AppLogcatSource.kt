package com.zenithblue.sambas3.logging

import android.os.Process as AndroidProcess
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

object AppLogcatSource {
    private const val TAG = "AppLogcatSource"
    private const val DEFAULT_IDLE_DELAY_MS = 10_000L

    @Volatile
    private var activeProcess: java.lang.Process? = null
    @Volatile
    private var watermarkMs: Long? = null
    private val wake = AtomicBoolean(false)
    @Volatile
    private var lastStatus: String = "idle"

    fun stopActiveProcess() {
        activeProcess?.let { proc ->
            runCatching { proc.destroy() }
        }
        activeProcess = null
    }

    fun wake() {
        wake.set(true)
    }

    fun drainHint() {
        lastStatus = "drain"
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
            val forced = wake.getAndSet(false)
            if (!isStreaming() && !forced) {
                engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Quiet(lines))
                delay(DEFAULT_IDLE_DELAY_MS)
                continue
            }
            try {
                val boundSession = sessionId()
                val proc = startLogcat(AndroidProcess.myPid())
                if (proc == null) {
                    engine.setStatus(
                        LogSourceKind.APP_ANDROID,
                        LogSourceStatus.Unavailable("logcat launch failed"),
                    )
                    lastStatus = "launch-failed"
                    delay(DEFAULT_IDLE_DELAY_MS)
                    continue
                }
                activeProcess = proc
                lastStatus = "active"
                proc.inputStream.bufferedReader().use { reader ->
                    while (coroutineContext.isActive && (isStreaming() || wake.get())) {
                        val line = reader.readLine() ?: break
                        val parsed = parser.parse(line)
                        val ts = parsed.timestampMs
                        if (watermarkMs != null && ts != null && ts < watermarkMs!!) continue
                        if (ts != null) watermarkMs = ts
                        engine.emit(
                            message = parsed.message,
                            source = classify(parsed),
                            level = parsed.level,
                            tag = parsed.tag,
                            sessionId = boundSession,
                            timestampMs = parsed.timestampMs,
                            timestampText = parsed.timestampText,
                            raw = parsed.raw,
                            boundSessionId = boundSession,
                        )
                        lines++
                        bytes += line.toByteArray(Charsets.UTF_8).size
                        if (lines % 16L == 0L) {
                            engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Active(lines, bytes))
                        }
                    }
                }
                val exit = runCatching { proc.exitValue() }.getOrNull()
                proc.destroy()
                activeProcess = null
                if (exit != null && exit != 0) {
                    engine.setStatus(
                        LogSourceKind.APP_ANDROID,
                        LogSourceStatus.Unavailable("logcat exited $exit"),
                    )
                    lastStatus = "exited-$exit"
                    delay(DEFAULT_IDLE_DELAY_MS)
                }
            } catch (e: Exception) {
                val denied = e.message?.contains("Permission", true) == true
                engine.setStatus(
                    LogSourceKind.APP_ANDROID,
                    if (denied) LogSourceStatus.PermissionDenied(e.message ?: "logcat denied")
                    else LogSourceStatus.Unavailable(e.message ?: "logcat failed"),
                )
                lastStatus = if (denied) "denied" else "error"
                activeProcess = null
                delay(DEFAULT_IDLE_DELAY_MS)
            }
        }
        engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Ended(lines, bytes))
    }

    private fun startLogcat(pid: Int): java.lang.Process? {
        val withPid = runCatching {
            ProcessBuilder("logcat", "-v", "threadtime", "-b", "main,crash,system", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
        }.getOrNull()
        if (withPid != null) return withPid
        Log.w(TAG, "pid-filtered logcat unsupported; not falling back to all-device collection")
        return null
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
