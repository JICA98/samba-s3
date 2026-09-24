package com.zenithblue.sambas3.monitoring

/**
 * CPU stat parser and utilization calculator for Android / Linux /proc filesystem.
 *
 * System CPU utilization:
 * - Normalized 0–100% scale across all online CPU cores.
 * - Sourced from the first line of /proc/stat ("cpu ...").
 * - Fields:
 *   1. user (normal processes in user mode)
 *   2. nice (niced processes in user mode)
 *   3. system (processes in kernel mode)
 *   4. idle (twiddling thumbs)
 *   5. iowait (waiting for I/O to complete; counted as idle / non-busy)
 *   6. irq (servicing interrupts; counted as busy)
 *   7. softirq (servicing softirqs; counted as busy)
 *   8. steal (stolen time in virtualized environments; counted as system overhead / busy)
 *   9. guest (guest vCPU; ALREADY included in user by Linux kernel)
 *   10. guest_nice (niced guest vCPU; ALREADY included in nice by Linux kernel)
 * - Guest Time Handling:
 *   In Linux kernel implementation (Documentation/filesystems/proc.rst, fs/proc/stat.c),
 *   guest time is already accounted in user, and guest_nice is already accounted in nice.
 *   To avoid double-counting guest time, guest and guest_nice fields are intentionally NOT added
 *   when computing total CPU time.
 *
 * Process CPU utilization:
 * - Logical core equivalents: 0–100% per core, up to 0–800% for an 8-core host (such as Poco X6 Pro / OnePlus 13R).
 *   For example, 2 fully saturated threads = 200.0%.
 * - Sourced from /proc/self/stat.
 * - utime (field 14, 0-indexed 11 after the `)` comm boundary) + stime (field 15, 0-indexed 12 after `)`).
 * - Monotonic elapsed time dt in seconds and kernel clock ticks per second (_SC_CLK_TCK).
 */
object CpuStatParser {
    data class SystemCpuSnapshot(
        val totalTicks: Long,
        val idleTicks: Long
    )

    fun parseSystemCpuLine(line: String): SystemCpuSnapshot? {
        val trimmed = line.trim()
        val fields = trimmed.split(Regex("\\s+"))
        if (fields.size < 5 || fields[0] != "cpu") return null

        val user = fields.getOrNull(1)?.toLongOrNull() ?: return null
        val nice = fields.getOrNull(2)?.toLongOrNull() ?: 0L
        val system = fields.getOrNull(3)?.toLongOrNull() ?: return null
        val idle = fields.getOrNull(4)?.toLongOrNull() ?: return null
        val iowait = fields.getOrNull(5)?.toLongOrNull() ?: 0L
        val irq = fields.getOrNull(6)?.toLongOrNull() ?: 0L
        val softirq = fields.getOrNull(7)?.toLongOrNull() ?: 0L
        val steal = fields.getOrNull(8)?.toLongOrNull() ?: 0L

        // IMPORTANT: In Linux kernel fs/proc/stat.c:
        // 'user' already includes 'guest', and 'nice' already includes 'guest_nice'.
        // DO NOT add fields[9] (guest) or fields[10] (guest_nice) to total, or they will be double-counted!
        val total = user + nice + system + idle + iowait + irq + softirq + steal
        val idleAll = idle + iowait

        return SystemCpuSnapshot(totalTicks = total, idleTicks = idleAll)
    }

    fun computeSystemUtilization(current: SystemCpuSnapshot, previous: SystemCpuSnapshot): Float? {
        val deltaTotal = current.totalTicks - previous.totalTicks
        val deltaIdle = current.idleTicks - previous.idleTicks
        if (deltaTotal <= 0L) return null
        val deltaBusy = deltaTotal - deltaIdle
        if (deltaBusy < 0L) return 0f
        return ((deltaBusy.toDouble() / deltaTotal.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    fun parseProcessTicks(statContent: String): Long? {
        val end = statContent.lastIndexOf(')')
        if (end < 0) return null
        val fields = statContent.substring(end + 2).trim().split(Regex("\\s+"))
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
        return utime + stime
    }

    fun computeProcessUtilization(
        currentTicks: Long,
        previousTicks: Long,
        elapsedSec: Double,
        clockTicksPerSecond: Long
    ): Float? {
        val deltaTicks = currentTicks - previousTicks
        if (deltaTicks < 0L || elapsedSec <= 0.0 || clockTicksPerSecond <= 0L) return null
        return ((deltaTicks.toDouble() / clockTicksPerSecond) / elapsedSec * 100.0)
            .toFloat().coerceAtLeast(0f)
    }
}
