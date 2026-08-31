package com.zenithblue.sambas3.monitoring

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

/** Multi-rate telemetry; the repository may sample quickly without re-reading expensive sources. */
class AndroidSystemMetricsCollector(private val context: Context) : MonitoringSystemSource {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val cpuFrequencyFiles = File("/sys/devices/system/cpu").listFiles().orEmpty()
        .filter { it.name.matches(Regex("cpu\\d+")) }
        .map { File(it, "cpufreq/scaling_cur_freq") }
        .filter { it.isFile }
    private val gpuFiles = discoverGpuFiles()
    private val zramFile = File("/sys/block/zram0/mm_stat")
    private var active = false
    private var battery: BatterySample? = null
    private var receiver: BroadcastReceiver? = null
    private var lastCpuMs = Long.MIN_VALUE
    private var lastMemoryMs = Long.MIN_VALUE
    private var lastPssMs = Long.MIN_VALUE
    private var lastRssMs = Long.MIN_VALUE
    private var lastPowerMs = Long.MIN_VALUE
    private var lastFreqMs = Long.MIN_VALUE
    private var lastSwapMs = Long.MIN_VALUE
    private var systemCpu: Pair<Long, Long>? = null
    private var processCpu: Pair<Long, Long>? = null
    private var lastCpu: Pair<Float?, Float?> = null to null
    private var lastMemory = MemorySample()
    private var lastPss: Long? = null
    private var lastRss: Long? = null
    private var lastPower = PowerSample()
    private var lastFrequencies: List<Long> = emptyList()
    private var lastSwap = SwapSample()
    private var lastGpu: GpuHardwareMetrics? = null

    override fun start() {
        if (active) return
        active = true
        lastCpuMs = Long.MIN_VALUE
        lastMemoryMs = Long.MIN_VALUE
        lastPssMs = Long.MIN_VALUE
        lastRssMs = Long.MIN_VALUE
        lastPowerMs = Long.MIN_VALUE
        lastFreqMs = Long.MIN_VALUE
        lastSwapMs = Long.MIN_VALUE
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { battery = BatterySample.from(intent) }
        }.also {
            ContextCompat.registerReceiver(context, it, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun stop() {
        if (!active) return
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        battery = null
        active = false
    }

    override fun read(): AndroidSystemMetrics {
        if (!active) return AndroidSystemMetrics()
        val now = SystemClock.elapsedRealtime()
        if (now - lastCpuMs >= 500L) {
            lastCpu = readSystemCpu() to readProcessCpu()
            lastCpuMs = now
        }
        if (now - lastMemoryMs >= 1_000L) {
            val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            lastMemory = MemorySample(memory.totalMem - memory.availMem, memory.totalMem, memory.availMem)
            lastMemoryMs = now
        }
        if (now - lastPssMs >= 3_000L) {
            lastPss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L }.getOrNull()
            lastPssMs = now
        }
        if (now - lastRssMs >= 1_000L) {
            lastRss = readSelfRss()
            lastRssMs = now
        }
        if (now - lastPowerMs >= 1_000L) {
            val sample = battery
            val currentUa = runCatching { batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()?.takeIf { it != 0L }
            val voltageMv = sample?.voltageMv?.takeIf { it > 0 }
            lastPower = PowerSample(
                temperatureC = sample?.temperatureC,
                powerW = if (voltageMv != null && currentUa != null) abs(voltageMv * currentUa / 1_000_000_000f) else null,
                percent = sample?.percent, charging = sample?.charging,
                thermalStatus = if (android.os.Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else null,
                thermalHeadroom = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { powerManager.getThermalHeadroom(0) }.getOrNull() else null
            )
            lastPowerMs = now
        }
        if (now - lastFreqMs >= 1_000L) {
            lastFrequencies = cpuFrequencyFiles.mapNotNull { runCatching { it.readText().trim().toLong() * 1000L }.getOrNull() }
            lastGpu = readGpu()
            lastFreqMs = now
        }
        if (now - lastSwapMs >= 2_000L) {
            val memInfo = readMemInfo()
            lastSwap = SwapSample(memInfo["SwapTotal"]?.minus(memInfo["SwapFree"] ?: 0L), memInfo["SwapTotal"], runCatching { zramFile.readText().trim().split(Regex("\\s+"))[2].toLong() }.getOrNull())
            lastSwapMs = now
        }
        return AndroidSystemMetrics(
            systemCpuPercent = lastCpu.first, processCpuPercent = lastCpu.second,
            ramUsedBytes = lastMemory.used, ramTotalBytes = lastMemory.total, ramAvailableBytes = lastMemory.available,
            processPssBytes = lastPss, processRssBytes = lastRss, swapUsedBytes = lastSwap.used, swapTotalBytes = lastSwap.total,
            zramUsedBytes = lastSwap.zram, batteryTemperatureC = lastPower.temperatureC, thermalStatus = lastPower.thermalStatus,
            thermalHeadroom = lastPower.thermalHeadroom, batteryPowerW = lastPower.powerW, batteryPercent = lastPower.percent,
            charging = lastPower.charging, cpuFrequenciesHz = lastFrequencies, gpu = lastGpu
        )
    }

    private fun readSystemCpu(): Float? {
        val fields = runCatching { File("/proc/stat").useLines { it.firstOrNull()?.trim()?.split(Regex("\\s+")) } }.getOrNull() ?: return null
        if (fields.size < 5 || fields[0] != "cpu") return null
        val idle = fields[4].toLongOrNull() ?: return null
        val total = fields.drop(1).mapNotNull { it.toLongOrNull() }.sum()
        val previous = systemCpu
        systemCpu = total to idle
        if (previous == null || total <= previous.first) return null
        return ((total - previous.first - (idle - previous.second)).toFloat() / (total - previous.first) * 100f).coerceIn(0f, 100f)
    }

    private fun readProcessCpu(): Float? {
        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return null
        val end = stat.lastIndexOf(')')
        if (end < 0) return null
        val fields = stat.substring(end + 2).trim().split(Regex("\\s+"))
        val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: return null) + (fields.getOrNull(12)?.toLongOrNull() ?: 0L)
        val now = SystemClock.elapsedRealtime()
        val previous = processCpu
        processCpu = ticks to now
        if (previous == null || now <= previous.second) return null
        return ((ticks - previous.first).toDouble() / 100.0 / ((now - previous.second) / 1000.0) * 100.0).toFloat().coerceAtLeast(0f)
    }

    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines -> lines.mapNotNull { line ->
            val parts = line.split(Regex("\\s+")); val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].removeSuffix(":") to value * 1024L
        }.toMap() }
    }.getOrDefault(emptyMap())

    private fun readSelfRss(): Long? = runCatching { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) } }.getOrNull()

    private fun discoverGpuFiles(): Pair<File?, File?> {
        val kgsl = File("/sys/class/kgsl/kgsl-3d0")
        val load = File(kgsl, "gpu_busy_percentage").takeIf { it.isFile }
        val freq = File(kgsl, "devfreq/cur_freq").takeIf { it.isFile }
        if (load != null || freq != null) return load to freq
        val generic = File("/sys/class/devfreq").listFiles().orEmpty().firstOrNull { it.name.contains("gpu", true) || it.name.contains("mali", true) }
        return generic?.let { File(it, "load").takeIf(File::isFile) } to generic?.let { File(it, "cur_freq").takeIf(File::isFile) }
    }

    private fun readGpu(): GpuHardwareMetrics? {
        val load = gpuFiles.first?.let { runCatching { it.readText().trim().removeSuffix("%").toInt() }.getOrNull() }
        val freq = gpuFiles.second?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
        return if (load != null || freq != null) GpuHardwareMetrics(load, freq) else null
    }

    private data class MemorySample(val used: Long? = null, val total: Long? = null, val available: Long? = null)
    private data class SwapSample(val used: Long? = null, val total: Long? = null, val zram: Long? = null)
    private data class PowerSample(val temperatureC: Float? = null, val powerW: Float? = null, val percent: Int? = null, val charging: Boolean? = null, val thermalStatus: Int? = null, val thermalHeadroom: Float? = null)
    private data class BatterySample(val temperatureC: Float?, val voltageMv: Int?, val percent: Int?, val charging: Boolean?) {
        companion object {
            fun from(intent: Intent) = BatterySample(
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }?.div(10f),
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0), intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 },
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
            )
        }
    }
}
