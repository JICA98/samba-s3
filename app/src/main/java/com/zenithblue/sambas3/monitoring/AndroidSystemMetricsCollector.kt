package com.zenithblue.sambas3.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import kotlin.math.abs

class AndroidSystemMetricsCollector(private val context: Context) {
    private var lastProc: Pair<Long, Long>? = null
    private var lastSystem: Pair<Long, Long>? = null

    fun read(): AndroidSystemMetrics {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } }.getOrNull()
        val memInfo = readMemInfo()
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }?.div(10f)
        val voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        val currentUa = runCatching {
            context.getSystemService(BatteryManager::class.java)
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE || it == 0L }
        val power = if (voltageMv != null && currentUa != null) abs(voltageMv * currentUa / 1_000_000_000f) else null
        val thermal = context.getSystemService(PowerManager::class.java)
        return AndroidSystemMetrics(
            systemCpuPercent = readSystemCpu(),
            processCpuPercent = readProcessCpu(),
            ramUsedBytes = memory.totalMem - memory.availMem,
            ramTotalBytes = memory.totalMem,
            ramAvailableBytes = memory.availMem,
            processPssBytes = pss?.totalPss?.times(1024L),
            processRssBytes = readSelfRss(),
            swapUsedBytes = memInfo["SwapTotal"]?.minus(memInfo["SwapFree"] ?: 0L),
            swapTotalBytes = memInfo["SwapTotal"],
            zramUsedBytes = readZramUsed(),
            batteryTemperatureC = temp,
            thermalStatus = if (android.os.Build.VERSION.SDK_INT >= 29) thermal.currentThermalStatus else null,
            thermalHeadroom = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { thermal.getThermalHeadroom(0) }.getOrNull() else null,
            batteryPowerW = power,
            batteryPercent = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 },
            charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL },
            cpuFrequenciesHz = readCpuFrequencies(),
            gpu = readGpu()
        )
    }

    private fun readSystemCpu(): Float? {
        val fields = runCatching { File("/proc/stat").useLines { it.firstOrNull()?.trim()?.split(Regex("\\s+")) } }.getOrNull() ?: return null
        if (fields.size < 5 || fields[0] != "cpu") return null
        val idle = fields.getOrNull(4)?.toLongOrNull() ?: return null
        val total = fields.drop(1).mapNotNull { it.toLongOrNull() }.sum()
        val previous = lastSystem
        lastSystem = total to idle
        if (previous == null || total <= previous.first) return null
        return ((total - previous.first - (idle - previous.second)).toFloat() / (total - previous.first) * 100f).coerceIn(0f, 100f)
    }

    private fun readProcessCpu(): Float? {
        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return null
        val end = stat.lastIndexOf(')')
        val fields = stat.substring(end + 2).trim().split(Regex("\\s+"))
        val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: return null) + (fields.getOrNull(12)?.toLongOrNull() ?: 0L)
        val now = SystemClock.elapsedRealtime()
        val previous = lastProc
        lastProc = ticks to now
        if (previous == null || now <= previous.second) return null
        val hz = 100L
        return ((ticks - previous.first).toDouble() / hz / ((now - previous.second) / 1000.0) * 100.0).toFloat().coerceAtLeast(0f)
    }

    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").readLines().mapNotNull { line ->
            val parts = line.split(Regex("\\s+")); val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].removeSuffix(":") to value * 1024L
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readSelfRss(): Long? = runCatching { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) } }.getOrNull()
    private fun readZramUsed(): Long? = runCatching { File("/sys/block/zram0/mm_stat").readText().trim().split(Regex("\\s+"))[2].toLong() }.getOrNull()
    private fun readCpuFrequencies(): List<Long> = File("/sys/devices/system/cpu").listFiles().orEmpty().filter { it.name.matches(Regex("cpu\\d+")) }.mapNotNull { runCatching { File(it, "cpufreq/scaling_cur_freq").readText().trim().toLong() * 1000L }.getOrNull() }

    private fun readGpu(): GpuHardwareMetrics? {
        val kgsl = File("/sys/class/kgsl/kgsl-3d0")
        val load = runCatching { File(kgsl, "gpu_busy_percentage").readText().trim().toInt() }.getOrNull()
        val freq = runCatching { File(kgsl, "devfreq/cur_freq").readText().trim().toLong() }.getOrNull()
        if (load != null || freq != null) return GpuHardwareMetrics(load, freq)
        val generic = File("/sys/class/devfreq").listFiles().orEmpty().firstOrNull { it.name.contains("gpu", true) || it.name.contains("mali", true) }
        if (generic != null) return GpuHardwareMetrics(frequencyHz = runCatching { File(generic, "cur_freq").readText().trim().toLong() }.getOrNull())
        return null
    }
}
