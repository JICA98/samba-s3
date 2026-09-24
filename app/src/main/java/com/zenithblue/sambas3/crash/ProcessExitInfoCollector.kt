package com.zenithblue.sambas3.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Robust collector for ApplicationExitInfo records on Android 11+ (API 30+),
 * with safe trace stream extraction and offline dumpsys text parsing.
 */
object ProcessExitInfoCollector {

    private const val TAG = "S3EXIT_INFO"
    private const val MAX_TRACE_BYTES = 256 * 1024 // 256 KB max trace memory buffer

    /**
     * Safely queries exit reasons for the given package on Android R+.
     * Handles null streams, IOExceptions, and memory limits.
     */
    fun getExitRecords(
        context: Context,
        pid: Int = 0,
        maxNum: Int = 30,
    ): List<ProcessExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        return runCatching {
            queryHistoricalExitReasons(context, pid, maxNum)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to query historical process exit reasons: ${error.message}")
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun queryHistoricalExitReasons(
        context: Context,
        pid: Int = 0,
        maxNum: Int = 30,
    ): List<ProcessExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val rawList: List<ApplicationExitInfo>? = am.getHistoricalProcessExitReasons(context.packageName, pid, maxNum)
        if (rawList.isNullOrEmpty()) return emptyList()

        return rawList.map { info ->
            val traceText = readTraceSafely(info)
            val subReason = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    info.javaClass.getMethod("getSubReason").invoke(info) as? Int ?: 0
                } else 0
            }.getOrDefault(0)
            val subReasonName = if (subReason != 0) subReasonToString(subReason) else "UNKNOWN"
            ProcessExitRecord(
                pid = info.pid,
                processName = info.processName,
                reason = info.reason,
                reasonName = ProcessExitRecord.reasonToString(info.reason),
                subReason = subReason,
                subReasonName = subReasonName,
                status = info.status,
                importance = info.importance,
                timestamp = info.timestamp,
                pssBytes = info.pss,
                rssBytes = info.rss,
                description = info.description,
                trace = traceText,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun readTraceSafely(info: ApplicationExitInfo): String? {
        val stream: InputStream = runCatching { info.traceInputStream }.getOrNull() ?: return null
        return runCatching {
            stream.use { s ->
                val reader = s.bufferedReader()
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (total < MAX_TRACE_BYTES) {
                    val read = reader.read(buf, 0, minOf(buf.size, MAX_TRACE_BYTES - total))
                    if (read <= 0) break
                    sb.append(buf, 0, read)
                    total += read
                }
                sb.toString().ifBlank { null }
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed reading trace for pid ${info.pid}: ${e.message}")
            null
        }
    }

    /**
     * Parses dumpsys activity exit-info output into structured ProcessExitRecord objects.
     */
    fun parseDumpsysText(dumpsysText: String): List<ProcessExitRecord> {
        if (dumpsysText.isBlank()) return emptyList()

        val results = ArrayList<ProcessExitRecord>()
        val blocks = dumpsysText.split(Regex("(?m)^\\s*ApplicationExitInfo\\s+#\\d+:"))
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue

            // Parse timestamp & pid
            val tsMatch = Regex("timestamp=([0-9-]+\\s+[0-9:.]+)\\s+pid=(\\d+)").find(trimmed) ?: continue
            val tsString = tsMatch.groupValues[1]
            val pid = tsMatch.groupValues[2].toIntOrNull() ?: continue
            val timestamp = runCatching { dateFormat.parse(tsString)?.time }.getOrNull() ?: 0L

            // Parse process, reason, subreason, status
            val procMatch = Regex("process=([^\\s]+)\\s+reason=(\\d+)(?:\\s*\\(([^)]+)\\))?").find(trimmed)
            val process = procMatch?.groupValues?.get(1) ?: "unknown"
            val reasonCode = procMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val reasonName = procMatch?.groupValues?.getOrNull(3)?.ifBlank { null }
                ?: ProcessExitRecord.reasonToString(reasonCode)

            val subReasonMatch = Regex("subreason=(\\d+)(?:\\s*\\(([^)]+)\\))?").find(trimmed)
            val subReasonCode = subReasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val subReasonName = subReasonMatch?.groupValues?.getOrNull(2) ?: "UNKNOWN"

            val statusMatch = Regex("status=(\\d+)").find(trimmed)
            val status = statusMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // Parse importance, pss, rss, description, trace
            val impMatch = Regex("importance=(\\d+)").find(trimmed)
            val importance = impMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val pssMatch = Regex("pss=([^\\s]+)").find(trimmed)
            val pssBytes = parseBytes(pssMatch?.groupValues?.get(1))

            val rssMatch = Regex("rss=([^\\s]+)").find(trimmed)
            val rssBytes = parseBytes(rssMatch?.groupValues?.get(1))

            val descMatch = Regex("description=([^\\n]+?)(?:\\s+state=|$)").find(trimmed)
            val rawDesc = descMatch?.groupValues?.get(1)?.trim()
            val description = if (rawDesc.isNullOrBlank() || rawDesc == "null") null else rawDesc

            val traceMatch = Regex("trace=([\\s\\S]+)$").find(trimmed)
            var traceText = traceMatch?.groupValues?.get(1)?.trim()
            if (traceText == "null" || traceText.isNullOrBlank()) {
                traceText = null
            }

            results.add(
                ProcessExitRecord(
                    pid = pid,
                    processName = process,
                    reason = reasonCode,
                    reasonName = reasonName,
                    subReason = subReasonCode,
                    subReasonName = subReasonName,
                    status = status,
                    importance = importance,
                    timestamp = timestamp,
                    pssBytes = pssBytes,
                    rssBytes = rssBytes,
                    description = description,
                    trace = traceText,
                )
            )
        }

        return results
    }

    fun parseBytes(str: String?): Long {
        if (str.isNullOrBlank() || str == "0.00" || str == "0") return 0L
        val clean = str.trim().uppercase()
        return try {
            when {
                clean.endsWith("GB") -> (clean.removeSuffix("GB").toDouble() * 1024 * 1024 * 1024).toLong()
                clean.endsWith("MB") -> (clean.removeSuffix("MB").toDouble() * 1024 * 1024).toLong()
                clean.endsWith("KB") -> (clean.removeSuffix("KB").toDouble() * 1024).toLong()
                clean.endsWith("B") -> clean.removeSuffix("B").toLong()
                else -> clean.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun subReasonToString(subReason: Int): String = when (subReason) {
        0 -> "UNKNOWN"
        1 -> "WAIT_FOR_DEBUGGER"
        2 -> "TOO_MANY_CACHED_PROCS"
        3 -> "TOO_MANY_EMPTY_PROCS"
        4 -> "TRIM_EMPTY"
        5 -> "LARGE_CACHED"
        6 -> "MEMORY_PRESSURE"
        7 -> "EXCESSIVE_CPU"
        8 -> "SYSTEM_UPDATE_DONE"
        9 -> "KILL_BACKGROUND"
        10 -> "PACKAGE_UPDATE"
        11 -> "UNDELIVERED_BROADCAST"
        21 -> "FORCE_STOP"
        22 -> "REMOVE_TASK"
        23 -> "STOP_APP"
        24 -> "KILL_ALL_BG_EXCEPT"
        25 -> "KILL_ALL_FG"
        else -> "SUBREASON_$subReason"
    }
}
