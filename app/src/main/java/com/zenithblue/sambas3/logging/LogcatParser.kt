package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.LogLevel
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone

data class ParsedLogcatLine(
    val timestampText: String?,
    val timestampMs: Long?,
    val level: LogLevel,
    val tag: String?,
    val message: String,
    val raw: Boolean,
    val continuation: Boolean,
)

/**
 * threadtime parser that never silently drops a line.
 * Unmatched lines become raw OTHER entries, or continuations of the previous message.
 */
class LogcatParser(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var parseErrors: Long = 0L
        private set
    var rawLines: Long = 0L
        private set
    var continuations: Long = 0L
        private set

    private var lastWasStructured = false

    fun reset() {
        parseErrors = 0L
        rawLines = 0L
        continuations = 0L
        lastWasStructured = false
    }

    fun parse(line: String): ParsedLogcatLine {
        val match = THREADTIME.matchEntire(line)
        if (match != null) {
            lastWasStructured = true
            val ts = match.groupValues[1]
            val level = LogLevel.fromChar(match.groupValues[2][0])
            val tag = match.groupValues[3].trim()
            val message = match.groupValues[4]
            return ParsedLogcatLine(
                timestampText = ts,
                timestampMs = parseTimestampMs(ts, nowMs()),
                level = level,
                tag = tag,
                message = message,
                raw = false,
                continuation = false,
            )
        }
        parseErrors++
        return if (lastWasStructured && line.isNotEmpty() && !looksLikeHeader(line)) {
            continuations++
            ParsedLogcatLine(
                timestampText = null,
                timestampMs = null,
                level = LogLevel.DEBUG,
                tag = null,
                message = line,
                raw = true,
                continuation = true,
            )
        } else {
            rawLines++
            lastWasStructured = false
            ParsedLogcatLine(
                timestampText = null,
                timestampMs = null,
                level = LogLevel.DEBUG,
                tag = null,
                message = line,
                raw = true,
                continuation = false,
            )
        }
    }

    companion object {
        // MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG : MSG
        private val THREADTIME = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)$"""
        )

        private fun looksLikeHeader(line: String): Boolean =
            line.startsWith("--------- beginning of") || line.startsWith("-----")

        fun parseTimestampMs(text: String, nowMs: Long): Long? {
            val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getDefault()
                isLenient = false
            }
            val parsed = runCatching { formatter.parse(text) }.getOrNull() ?: return null
            val calendar = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
            }
            var timestamp = calendar.timeInMillis
            if (timestamp > nowMs + 24L * 60L * 60L * 1000L) {
                calendar.add(Calendar.YEAR, -1)
                timestamp = calendar.timeInMillis
            }
            return timestamp
        }
    }
}
