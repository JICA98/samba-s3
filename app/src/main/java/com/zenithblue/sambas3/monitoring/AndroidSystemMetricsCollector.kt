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
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

/**
 * Multi-rate system telemetry collector for Android host metrics.
 *
 * Polling cadence and cost separation:
 * - CPU utilization (/proc/stat, /proc/self/stat): sampled every 500 ms.
 * - Memory (/proc/meminfo) & Process RSS (/proc/self/status): sampled every 1,000 ms.
 *   Uses local /proc nodes to avoid Binder crossings into system_server during unstable states.
 * - Diagnostic Process PSS (Debug.getMemoryInfo): expensive /proc/self/smaps traversal isolated
 *   to a 20s diagnostic cadence (or on-demand via [requestPssSample]).
 * - Battery & PowerManager thermal status: sampled every 1,000 ms.
 * - Thermal Headroom (PowerManager.getThermalHeadroom): isolated to a 15s schedule (Android
 *   documentation advises >= 10s intervals) with cached values between polls and no immediate
 *   second forecast retries.
 * - GPU utilization & frequency: sampled every 1,000 ms with typed GpuSource discovery and
 *   validation, rate-limited logging, and precise percentage calculation.
 */
class AndroidSystemMetricsCollector(
    private val context: Context,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val monotonicNanoProvider: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val thermalHeadroomProvider: (() -> Float?)? = null,
    private val pssProvider: (() -> Long?)? = null
) : MonitoringSystemSource {

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

    private val zramFile = File("/sys/block/zram0/mm_stat")
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L)
        .coerceAtLeast(1L)

    private val gpuParser = GpuSourceParser()
    private var gpuLoadSource: GpuSource? = null
    private var gpuFreqSource: GpuSource? = null

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
    private var lastHeadroomMs = Long.MIN_VALUE
    private var lastGpuSampleLogMs = 0L

    private var systemCpu: CpuStatParser.SystemCpuSnapshot? = null
    private var processCpu: Pair<Long, Long>? = null // ticks to monotonic nano
    private var lastCpu: Pair<Float?, Float?> = null to null
    private var lastMemory = MemorySample()
    private var lastPss: Long? = null
    private var lastRss: Long? = null
    private var lastPower = PowerSample()
    private var cachedThermalHeadroom: Float? = null
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

    private val sampleTimestampsMs = mutableMapOf<MonitoringMetric, Long>()

    override fun start() {
        if (active) return
        active = true

        // Reset timestamps. Zero or offset negative safe for positive monotonic elapsed time.
        lastCpuMs = 0L
        lastMemoryMs = 0L
        lastPssMs = -DIAGNOSTIC_PSS_INTERVAL_MS
        lastRssMs = 0L
        lastPowerMs = 0L
        lastFreqMs = 0L
        lastSwapMs = 0L
        lastHeadroomMs = -THERMAL_HEADROOM_INTERVAL_MS
        lastGpuSampleLogMs = 0L

        systemCpu = null
        processCpu = null
        cachedThermalHeadroom = null
        gpuParser.resetCumulativeCounters()
        sampleTimestampsMs.clear()

        gpuLoadSource = discoverGpuLoadSource()
        gpuFreqSource = discoverGpuFreqSource()

        if (gpuLoadSource != null) {
            Log.i("S3PERF", "Selected GPU load source: path=${gpuLoadSource!!.path}, format=${gpuLoadSource!!.format}, semantics=${gpuLoadSource!!.samplingSemantics}")
        } else {
            Log.i("S3PERF", "No supported GPU load source discovered")
        }
        if (gpuFreqSource != null) {
            Log.i("S3PERF", "Selected GPU freq source: path=${gpuFreqSource!!.path}, units=${gpuFreqSource!!.units}")
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = BatterySample.from(intent)
            }
        }.also { r ->
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    r,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        }

        // ACTION_BATTERY_CHANGED is sticky; seed first sample immediately
        battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(BatterySample::from)
        }.getOrNull()
    }

    override fun stop() {
        if (!active) return
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        battery = null
        cachedThermalHeadroom = null
        systemCpu = null
        processCpu = null
        gpuParser.resetCumulativeCounters()
        sampleTimestampsMs.clear()
        active = false
    }

    /** Request immediate on-demand PSS refresh on the next [read] invocation. */
    fun requestPssSample() {
        lastPssMs = 0L
    }

    fun setGpuLoadSourceForTesting(source: GpuSource?) {
        gpuLoadSource = source
        gpuLoadReadable = true
    }

    fun setGpuFreqSourceForTesting(source: GpuSource?) {
        gpuFreqSource = source
        gpuFreqReadable = true
    }

    override fun read(): AndroidSystemMetrics {
        if (!active) return AndroidSystemMetrics()
        val now = timeProvider()

        if (now - lastCpuMs >= CPU_INTERVAL_MS) {
            lastCpu = readSystemCpu(now) to readProcessCpu(now)
            lastCpuMs = now
        }

        if (now - lastMemoryMs >= MEMORY_INTERVAL_MS) {
            // Do not call ActivityManager.getMemoryInfo() from the telemetry
            // worker. That crosses Binder into system_server; when Android is
            // recovering from a GPU/display hang, system_server can be dead.
            // On Android 16 this can trigger a CheckJNI abort while reporting
            // DeadSystemException, which kills SambaS3 instead of returning a
            // recoverable telemetry sample. /proc/meminfo is local and safe.
            val memInfo = readMemInfo()
            val total = memInfo["MemTotal"]
            val available = memInfo["MemAvailable"] ?: memInfo["MemFree"]
            if (total != null && available != null && total >= available) {
                lastMemory = MemorySample(total - available, total, available)
                sampleTimestampsMs[MonitoringMetric.RamTotal] = now
                sampleTimestampsMs[MonitoringMetric.RamUsed] = now
                sampleTimestampsMs[MonitoringMetric.RamAvailable] = now
            }
            lastMemoryMs = now
        }

        // PSS is expensive (Debug.getMemoryInfo parses /proc/self/smaps in kernel).
        // Isolate to a 20s diagnostic cadence to eliminate telemetry CPU spikes.
        if (now - lastPssMs >= DIAGNOSTIC_PSS_INTERVAL_MS) {
            lastPss = pssProvider?.invoke() ?: runCatching {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L
            }.getOrNull()
            lastPssMs = now
            if (lastPss != null) {
                sampleTimestampsMs[MonitoringMetric.AppPss] = now
            }
        }

        // Cheap RSS from /proc/self/status on the standard 1s cadence
        if (now - lastRssMs >= RSS_INTERVAL_MS) {
            lastRss = readSelfRss()
            lastRssMs = now
            if (lastRss != null) {
                sampleTimestampsMs[MonitoringMetric.AppRss] = now
            }
        }

        // Thermal Headroom polling on dedicated >= 10s schedule
        if (now - lastHeadroomMs >= THERMAL_HEADROOM_INTERVAL_MS) {
            cachedThermalHeadroom = pollThermalHeadroom()
            lastHeadroomMs = now
            if (cachedThermalHeadroom != null) {
                sampleTimestampsMs[MonitoringMetric.ThermalHeadroom] = now
            }
        }

        if (now - lastPowerMs >= POWER_INTERVAL_MS) {
            val sample = battery
            val currentUa = readBatteryCurrentUa()
            val voltageMv = sample?.voltageMv?.takeIf { it > 0 }
            val instantPower = if (voltageMv != null && currentUa != null) {
                abs(voltageMv * currentUa / 1_000_000_000f).takeIf { it >= 0.05f }
            } else null

            val thermalStatus = readThermalStatus()
            lastPower = PowerSample(
                temperatureC = sample?.temperatureC,
                powerW = instantPower ?: readEnergyPowerW(now),
                percent = sample?.percent,
                charging = sample?.charging,
                thermalStatus = thermalStatus,
                thermalHeadroom = cachedThermalHeadroom
            )
            sample?.temperatureC?.let { sampleTimestampsMs[MonitoringMetric.BatteryTemperature] = now }
            sample?.percent?.let { sampleTimestampsMs[MonitoringMetric.BatteryPercent] = now }
            lastPower.powerW?.let { sampleTimestampsMs[MonitoringMetric.BatteryPower] = now }
            thermalStatus?.let { sampleTimestampsMs[MonitoringMetric.ThermalStatus] = now }
            lastPowerMs = now
        }

        if (now - lastFreqMs >= FREQ_GPU_INTERVAL_MS) {
            lastFrequencies = cpuFrequencyFiles.mapNotNull {
                runCatching { it.readText().trim().toLong() * 1000L }.getOrNull()
            }
            if (lastFrequencies.isNotEmpty()) {
                sampleTimestampsMs[MonitoringMetric.CpuFrequency] = now
            }
            lastGpu = readGpu(now)
            lastFreqMs = now
        }

        if (now - lastSwapMs >= SWAP_INTERVAL_MS) {
            val memInfo = readMemInfo()
            val zram = readZramBytes(now)
            val swapTotal = memInfo["SwapTotal"]
            val swapFree = memInfo["SwapFree"] ?: 0L
            val swapUsed = swapTotal?.minus(swapFree)
            lastSwap = SwapSample(swapUsed, swapTotal, zram)
            swapUsed?.let { sampleTimestampsMs[MonitoringMetric.SwapUsed] = now }
            swapTotal?.let { sampleTimestampsMs[MonitoringMetric.SwapTotal] = now }
            zram?.let { sampleTimestampsMs[MonitoringMetric.ZramUsed] = now }
            lastSwapMs = now
        }

        return AndroidSystemMetrics(
            systemCpuPercent = lastCpu.first,
            processCpuPercent = lastCpu.second,
            ramUsedBytes = lastMemory.used,
            ramTotalBytes = lastMemory.total,
            ramAvailableBytes = lastMemory.available,
            processPssBytes = lastPss,
            processRssBytes = lastRss,
            swapUsedBytes = lastSwap.used,
            swapTotalBytes = lastSwap.total,
            zramUsedBytes = lastSwap.zram,
            batteryTemperatureC = lastPower.temperatureC,
            thermalStatus = lastPower.thermalStatus,
            thermalHeadroom = lastPower.thermalHeadroom,
            batteryPowerW = lastPower.powerW,
            batteryPercent = lastPower.percent,
            charging = lastPower.charging,
            cpuFrequenciesHz = lastFrequencies,
            gpu = lastGpu,
            timestampsMs = sampleTimestampsMs.toMap()
        )
    }

    private fun readSystemCpu(now: Long): Float? {
        if (!procStatReadable) {
            if (now < procStatRetryMs) return null
            procStatReadable = true
        }
        val line = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
        if (line == null) {
            procStatReadable = false
            procStatRetryMs = now + 8_000L
            return null
        }
        val snapshot = CpuStatParser.parseSystemCpuLine(line) ?: return null
        val previous = systemCpu
        systemCpu = snapshot
        if (previous == null) return null
        val result = CpuStatParser.computeSystemUtilization(snapshot, previous)
        if (result != null) {
            sampleTimestampsMs[MonitoringMetric.AndroidSystemCpu] = now
        }
        return result
    }

    private fun readProcessCpu(now: Long): Float? {
        val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return null
        val ticks = CpuStatParser.parseProcessTicks(stat) ?: return null
        val nowNs = monotonicNanoProvider()
        val previous = processCpu
        processCpu = ticks to nowNs
        if (previous == null) return null
        val elapsedSec = (nowNs - previous.second) / 1_000_000_000.0
        val result = CpuStatParser.computeProcessUtilization(ticks, previous.first, elapsedSec, clockTicksPerSecond)
        if (result != null) {
            sampleTimestampsMs[MonitoringMetric.AndroidProcessCpu] = now
        }
        return result
    }

    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                parts[0].removeSuffix(":") to value * 1024L
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun readSelfRss(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.getOrNull()

    private fun discoverGpuLoadSource(): GpuSource? {
        val kgslRoots = listOf(
            File("/sys/class/kgsl/kgsl-3d0"),
            File("/sys/devices/virtual/kgsl/kgsl-3d0"),
        )
        for (root in kgslRoots) {
            val pctFile = File(root, "gpu_busy_percentage")
            if (pctFile.isFile && pctFile.canRead()) {
                return GpuSource(
                    path = pctFile.absolutePath,
                    format = GpuFormat.PERCENTAGE_SCALAR,
                    units = "%",
                    scope = "device",
                    samplingSemantics = GpuSamplingSemantics.INSTANTANEOUS
                )
            }
            for (name in listOf("gpu_busy", "gpubusy")) {
                val pairFile = File(root, name)
                if (pairFile.isFile && pairFile.canRead()) {
                    return GpuSource(
                        path = pairFile.absolutePath,
                        format = GpuFormat.KGSL_WINDOWED_PAIR,
                        units = "%",
                        scope = "device",
                        samplingSemantics = GpuSamplingSemantics.WINDOWED
                    )
                }
            }
        }
        val devfreqRoots = File("/sys/class/devfreq").listFiles().orEmpty()
            .filter { it.name.contains("gpu", true) || it.name.contains("mali", true) || it.name.contains("kgsl", true) }
        for (devfreq in devfreqRoots) {
            val loadFile = File(devfreq, "load")
            if (loadFile.isFile && loadFile.canRead()) {
                val probe = runCatching { loadFile.readText().trim() }.getOrNull().orEmpty()
                val detected = GpuSourceParser.detectFormat(probe)
                if (detected != GpuFormat.UNSUPPORTED) {
                    return GpuSource(
                        path = loadFile.absolutePath,
                        format = detected,
                        units = "%",
                        scope = "device",
                        samplingSemantics = if (detected == GpuFormat.KGSL_WINDOWED_PAIR) GpuSamplingSemantics.WINDOWED else GpuSamplingSemantics.INSTANTANEOUS
                    )
                }
            }
        }
        val mali = File("/sys/class/misc/mali0/device")
        val maliUtil = File(mali, "utilization")
        if (maliUtil.isFile && maliUtil.canRead()) {
            return GpuSource(
                path = maliUtil.absolutePath,
                format = GpuFormat.PERCENTAGE_SCALAR,
                units = "%",
                scope = "device",
                samplingSemantics = GpuSamplingSemantics.INSTANTANEOUS
            )
        }
        return null
    }

    private fun discoverGpuFreqSource(): GpuSource? {
        val kgslRoots = listOf(
            File("/sys/class/kgsl/kgsl-3d0"),
            File("/sys/devices/virtual/kgsl/kgsl-3d0"),
        )
        val freqCandidates = listOf(
            "devfreq/cur_freq" to "Hz",
            "gpuclk" to "Hz",
            "freq" to "Hz",
            "cur_freq" to "Hz"
        )
        for (root in kgslRoots) {
            for ((name, units) in freqCandidates) {
                val f = File(root, name)
                if (f.isFile && f.canRead()) {
                    return GpuSource(
                        path = f.absolutePath,
                        format = GpuFormat.PERCENTAGE_SCALAR,
                        units = units,
                        scope = "device",
                        samplingSemantics = GpuSamplingSemantics.INSTANTANEOUS
                    )
                }
            }
        }
        val devfreqRoots = File("/sys/class/devfreq").listFiles().orEmpty()
            .filter { it.name.contains("gpu", true) || it.name.contains("mali", true) || it.name.contains("kgsl", true) }
        for (devfreq in devfreqRoots) {
            val curFreq = File(devfreq, "cur_freq")
            if (curFreq.isFile && curFreq.canRead()) {
                return GpuSource(
                    path = curFreq.absolutePath,
                    format = GpuFormat.PERCENTAGE_SCALAR,
                    units = "Hz",
                    scope = "device",
                    samplingSemantics = GpuSamplingSemantics.INSTANTANEOUS
                )
            }
        }
        val maliClock = File("/sys/class/misc/mali0/device/clock")
        if (maliClock.isFile && maliClock.canRead()) {
            return GpuSource(
                path = maliClock.absolutePath,
                format = GpuFormat.PERCENTAGE_SCALAR,
                units = "Hz",
                scope = "device",
                samplingSemantics = GpuSamplingSemantics.INSTANTANEOUS
            )
        }
        return null
    }

    private fun readGpu(now: Long): GpuHardwareMetrics? {
        if ((!gpuLoadReadable || !gpuFreqReadable) && now >= gpuRetryMs) {
            gpuLoadReadable = true
            gpuFreqReadable = true
        }

        val loadPrecise = if (gpuLoadReadable && gpuLoadSource != null) {
            val source = gpuLoadSource!!
            runCatching {
                val rawText = File(source.path).readText()
                val parsed = gpuParser.parseUtilization(rawText, source, monotonicNanoProvider())
                if (now - lastGpuSampleLogMs >= GPU_SAMPLE_LOG_INTERVAL_MS) {
                    lastGpuSampleLogMs = now
                    Log.d("S3PERF", "GPU load [${source.path}]: raw='${rawText.trim()}' -> load=$parsed%")
                }
                parsed
            }.getOrElse {
                gpuLoadReadable = false
                gpuRetryMs = now + 8_000L
                null
            }
        } else null

        val freq = if (gpuFreqReadable && gpuFreqSource != null) {
            val source = gpuFreqSource!!
            runCatching {
                val raw = File(source.path).readText().trim().toLong()
                when (source.units.lowercase()) {
                    "mhz" -> raw * 1_000_000L
                    "khz" -> raw * 1_000L
                    "hz" -> raw
                    else -> normalizeGpuHz(raw)
                }
            }.getOrElse {
                gpuFreqReadable = false
                gpuRetryMs = now + 8_000L
                null
            }
        } else null

        val loadInt = loadPrecise?.let { Math.round(it).toInt() }
        if (loadInt != null) {
            sampleTimestampsMs[MonitoringMetric.GpuHardwareLoad] = now
        }
        if (freq != null) {
            sampleTimestampsMs[MonitoringMetric.GpuFrequency] = now
        }
        return if (loadInt != null || freq != null) {
            GpuHardwareMetrics(loadPercent = loadInt, frequencyHz = freq, loadPercentPrecise = loadPrecise)
        } else null
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
        // ENERGY_COUNTER is nWh. nWh / s -> 3.6e-6 W.
        val watts = abs((energy - prev) / dtSec) * 3.6e-6
        return watts.toFloat().takeIf { it.isFinite() && it >= 0.05f && it < 50f }
    }

    private fun readThermalStatus(): Int? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    private fun pollThermalHeadroom(): Float? {
        thermalHeadroomProvider?.let { return it.invoke()?.takeIf { h -> h.isFinite() } }
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val pm = powerManager ?: return null
        // Android recommends querying at most once every 10 seconds.
        // No immediate second forecast retry on non-finite responses.
        return runCatching { pm.getThermalHeadroom(0) }
            .getOrNull()
            ?.takeIf { it.isFinite() }
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
        const val CPU_INTERVAL_MS = 500L
        const val MEMORY_INTERVAL_MS = 1_000L
        const val RSS_INTERVAL_MS = 1_000L
        const val POWER_INTERVAL_MS = 1_000L
        const val FREQ_GPU_INTERVAL_MS = 1_000L
        const val SWAP_INTERVAL_MS = 2_000L
        const val THERMAL_HEADROOM_INTERVAL_MS = 15_000L // >= 10s per Android rate guidance
        const val DIAGNOSTIC_PSS_INTERVAL_MS = 20_000L // 15–30s diagnostic cadence
        const val GPU_SAMPLE_LOG_INTERVAL_MS = 10_000L // Rate limit raw sample logging

        fun normalizeGpuHz(raw: Long): Long = when {
            raw >= 10_000_000L -> raw
            raw >= 10_000L -> raw * 1_000L
            raw > 0L -> raw * 1_000_000L
            else -> raw
        }
    }

    private data class MemorySample(val used: Long? = null, val total: Long? = null, val available: Long? = null)
    private data class SwapSample(val used: Long? = null, val total: Long? = null, val zram: Long? = null)
    private data class PowerSample(
        val temperatureC: Float? = null,
        val powerW: Float? = null,
        val percent: Int? = null,
        val charging: Boolean? = null,
        val thermalStatus: Int? = null,
        val thermalHeadroom: Float? = null
    )
    private data class BatterySample(val temperatureC: Float?, val voltageMv: Int?, val percent: Int?, val charging: Boolean?) {
        companion object {
            fun from(intent: Intent) = BatterySample(
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }?.div(10f),
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 },
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                    it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
                }
            )
        }
    }
}
