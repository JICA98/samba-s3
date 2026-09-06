package com.zenithblue.sambas3.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

/** Multi-rate telemetry; the repository may sample quickly without re-reading expensive sources. */
class AndroidSystemMetricsCollector(private val context: Context) : MonitoringSystemSource {
    private val batteryManager: BatteryManager? =
        context.getSystemService(BatteryManager::class.java)
            ?: context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)
            ?: context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val cpuFrequencyFiles = File("/sys/devices/system/cpu").listFiles().orEmpty()
        .filter { it.name.matches(Regex("cpu\\d+")) }
        .map { File(it, "cpufreq/scaling_cur_freq") }
        .filter { it.isFile }
    private val gpuFiles = discoverGpuFiles()
    private val zramFile = File("/sys/block/zram0/mm_stat")
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L)
        .coerceAtLeast(1L)
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
    private var procStatReadable = true
    private var procStatRetryMs = 0L
    private var zramReadable = true
    private var zramRetryMs = 0L
    private var gpuLoadReadable = true
    private var gpuFreqReadable = true
    private var gpuRetryMs = 0L
    private var lastGpu: GpuHardwareMetrics? = null
    private var lastEnergyNwh: Long? = null
    private var lastEnergyMs: Long = 0L

    override fun start() {
        if (active) return
        active = true
        // Zero is safe because elapsedRealtime() is positive. Long.MIN_VALUE
        // would make `now - lastRead` overflow and suppress every refresh.
        lastCpuMs = 0L
        lastMemoryMs = 0L
        lastPssMs = 0L
        lastRssMs = 0L
        lastPowerMs = 0L
        lastFreqMs = 0L
        lastSwapMs = 0L
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { battery = BatterySample.from(intent) }
        }.also {
            ContextCompat.registerReceiver(context, it, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // ACTION_BATTERY_CHANGED is sticky; seed the first sample immediately
        // instead of waiting for a level/charge transition broadcast.
        battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(BatterySample::from)
        }.getOrNull()
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
            // Do not call ActivityManager.getMemoryInfo() from the telemetry
            // worker.  That crosses Binder into system_server; when Android is
            // recovering from a GPU/display hang, system_server can be dead.
            // On Android 16 this can trigger a CheckJNI abort while reporting
            // DeadSystemException, which kills SambaS3 instead of returning a
            // recoverable telemetry sample.  /proc/meminfo is local and safe.
            val memInfo = readMemInfo()
            val total = memInfo["MemTotal"]
            val available = memInfo["MemAvailable"] ?: memInfo["MemFree"]
            if (total != null && available != null && total >= available) {
                lastMemory = MemorySample(total - available, total, available)
            }
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
            val currentUa = readBatteryCurrentUa()
            val voltageMv = sample?.voltageMv?.takeIf { it > 0 }
            val instantPower = if (voltageMv != null && currentUa != null) {
                abs(voltageMv * currentUa / 1_000_000_000f).takeIf { it >= 0.05f }
            } else null
            lastPower = PowerSample(
                temperatureC = sample?.temperatureC,
                powerW = instantPower ?: readEnergyPowerW(now),
                percent = sample?.percent, charging = sample?.charging,
                thermalStatus = readThermalStatus(),
                thermalHeadroom = readThermalHeadroom()
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
            val zram = readZramBytes(now)
            lastSwap = SwapSample(memInfo["SwapTotal"]?.minus(memInfo["SwapFree"] ?: 0L), memInfo["SwapTotal"], zram)
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
        val now = SystemClock.elapsedRealtime()
        if (!procStatReadable) {
            if (now < procStatRetryMs) return null
            procStatReadable = true
        }
        val fields = runCatching { File("/proc/stat").useLines { it.firstOrNull()?.trim()?.split(Regex("\\s+")) } }.getOrNull()
        if (fields == null) {
            procStatReadable = false
            procStatRetryMs = now + 8_000L
            return null
        }
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
        return ((ticks - previous.first).toDouble() / clockTicksPerSecond / ((now - previous.second) / 1000.0) * 100.0)
            .toFloat().coerceAtLeast(0f)
    }

    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines -> lines.mapNotNull { line ->
            val parts = line.split(Regex("\\s+")); val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].removeSuffix(":") to value * 1024L
        }.toMap() }
    }.getOrDefault(emptyMap())

    private fun readSelfRss(): Long? = runCatching { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()?.times(1024L) } }.getOrNull()

    private fun discoverGpuFiles(): Pair<File?, File?> {
        val kgslRoots = listOf(
            File("/sys/class/kgsl/kgsl-3d0"),
            File("/sys/devices/virtual/kgsl/kgsl-3d0"),
        )
        val loadCandidates = listOf("gpu_busy_percentage", "gpu_busy", "gpubusy")
        val freqCandidates = listOf("devfreq/cur_freq", "gpuclk", "freq", "cur_freq")
        for (root in kgslRoots) {
            val load = loadCandidates.firstNotNullOfOrNull { name -> File(root, name).takeIf { it.isFile && it.canRead() } }
            val freq = freqCandidates.firstNotNullOfOrNull { name -> File(root, name).takeIf { it.isFile && it.canRead() } }
            if (load != null || freq != null) return load to freq
        }
        val devfreq = File("/sys/class/devfreq").listFiles().orEmpty()
            .firstOrNull { it.name.contains("gpu", true) || it.name.contains("mali", true) || it.name.contains("kgsl", true) }
        val mali = File("/sys/class/misc/mali0/device")
        val genericLoad = listOf(
            devfreq?.let { File(it, "load") },
            File(mali, "utilization"),
            File(mali, "gpuinfo"),
        ).firstOrNull { it != null && it.isFile && it.canRead() }
        val genericFreq = listOf(
            devfreq?.let { File(it, "cur_freq") },
            File(mali, "clock"),
        ).firstOrNull { it != null && it.isFile && it.canRead() }
        return genericLoad to genericFreq
    }

    private fun readGpu(): GpuHardwareMetrics? {
        val now = SystemClock.elapsedRealtime()
        if ((!gpuLoadReadable || !gpuFreqReadable) && now >= gpuRetryMs) {
            gpuLoadReadable = true
            gpuFreqReadable = true
        }
        val load = if (gpuLoadReadable && gpuFiles.first != null) {
            runCatching {
                gpuFiles.first!!.readText().trim().removeSuffix("%").split(Regex("\\s+")).first().toInt()
            }.getOrElse {
                gpuLoadReadable = false
                gpuRetryMs = now + 8_000L
                null
            }
        } else null
        val freq = if (gpuFreqReadable && gpuFiles.second != null) {
            runCatching { normalizeGpuHz(gpuFiles.second!!.readText().trim().toLong()) }
                .getOrElse {
                    gpuFreqReadable = false
                    gpuRetryMs = now + 8_000L
                    null
                }
        } else null
        return if (load != null || freq != null) GpuHardwareMetrics(load, freq) else null
    }

    private fun readBatteryCurrentUa(): Long? {
        val now = runCatching { batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
        if (now != null && now != 0L && now != Long.MIN_VALUE) return now
        val avg = runCatching { batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }.getOrNull()
        return avg?.takeIf { it != 0L && it != Long.MIN_VALUE }
    }

    private fun readEnergyPowerW(nowMs: Long): Float? {
        val energy = runCatching { batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }
            .getOrNull()?.takeIf { it > 0L } ?: return null
        val prev = lastEnergyNwh
        val prevMs = lastEnergyMs
        lastEnergyNwh = energy
        lastEnergyMs = nowMs
        if (prev == null || prevMs <= 0L || nowMs <= prevMs) return null
        val dtSec = (nowMs - prevMs) / 1000.0
        if (dtSec < 0.5) return null
        // ENERGY_COUNTER is nWh. nWh / s → 3.6e-6 W.
        val watts = abs((energy - prev) / dtSec) * 3.6e-6
        return watts.toFloat().takeIf { it.isFinite() && it >= 0.05f && it < 50f }
    }

    private fun readThermalStatus(): Int? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    private fun readThermalHeadroom(): Float? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val pm = powerManager ?: return null
        val immediate = runCatching { pm.getThermalHeadroom(0) }.getOrNull()
        val value = if (immediate != null && immediate.isFinite()) immediate else {
            runCatching { pm.getThermalHeadroom(1) }.getOrNull()
        }
        return value?.takeIf { it.isFinite() }
    }

    private fun readZramBytes(now: Long): Long? {
        if (!zramReadable) {
            if (now < zramRetryMs) return null
            zramReadable = true
        }
        val mm = runCatching {
            val parts = zramFile.readText().trim().split(Regex("\\s+"))
            parts.getOrNull(2)?.toLongOrNull() ?: parts.getOrNull(0)?.toLongOrNull()
        }.getOrNull()
        if (mm != null) return mm
        val used = runCatching { File("/sys/block/zram0/mem_used_total").readText().trim().toLong() }.getOrNull()
        if (used != null) return used
        zramReadable = false
        zramRetryMs = now + 8_000L
        return null
    }

    companion object {
        fun normalizeGpuHz(raw: Long): Long = when {
            raw >= 10_000_000L -> raw
            raw >= 10_000L -> raw * 1_000L
            raw > 0L -> raw * 1_000_000L
            else -> raw
        }
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
