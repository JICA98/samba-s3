package com.zenithblue.sambas3.monitoring

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.EmulatorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

interface MonitoringSystemSource {
    fun start()
    fun stop()
    fun read(): AndroidSystemMetrics
}

interface MonitoringPerfSource {
    fun setEnabled(enabled: Boolean, intervalMs: Long)
    fun read(): EmulatorMetrics?
    fun logUiSnapshot(metrics: EmulatorMetrics)
}

class MonitoringRepository(
    context: Context,
    private val system: MonitoringSystemSource = AndroidSystemMetricsCollector(context.applicationContext),
    private val perf: MonitoringPerfSource = PerformanceMetricsBridge,
    private val stateProvider: () -> EmulatorState? = {
        runCatching { RPCSX.getState() }.getOrNull()
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val appContext = context.applicationContext
    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()
    private val history = MonitoringHistory()
    private var job: Job? = null
    private var generation = 0L
    private var wasRunning = false
    private var androidAvailabilityLogged = false

    fun start(scope: CoroutineScope, settingsFlow: StateFlow<MonitoringSettings> = MonitoringOverlaySettings.state(appContext)) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            settingsFlow.collectLatest { settings ->
                if (!settings.enabled) {
                    system.stop()
                    perf.setEnabled(false, settings.updateMs)
                    history.clear()
                    wasRunning = false
                    androidAvailabilityLogged = false
                    return@collectLatest
                }
                val intervalMs = settings.updateMs
                perf.setEnabled(true, intervalMs)
                runCatching { system.start() }.onFailure { Log.w("S3PERF", "android telemetry start failed", it) }
                try {
                    while (isActive) {
                        // Paused is deliberately not a valid fresh telemetry
                        // state. Keeping the old emulator snapshot visible
                        // while paused makes FPS look fabricated.
                        val running = stateProvider() == EmulatorState.Running
                        if (running && !wasRunning) generation++
                        if (!running && wasRunning) history.clear()
                        wasRunning = running
                        val emulator = running.takeIf { it }?.let { perf.read() }
                        if (emulator != null && settings.graphMetrics.isNotEmpty()) history.append(emulator, settings.graphHistorySeconds, settings.graphMetrics, generation)
                        val android = runCatching { system.read() }.getOrElse {
                            Log.w("S3PERF", "android telemetry read failed", it)
                            AndroidSystemMetrics()
                        }
                        val metricDebug = buildMap {
                            val nowMs = SystemClock.elapsedRealtime()
                            if (emulator != null) MonitoringMetricDescriptors.all
                                .filter { it.source == MonitoringMetricSource.Emulator && hasEmulatorValue(emulator, it.metric) }
                                .forEach { put(it.metric, MetricDebugInfo(nowMs, "RPCSX emu_flip/perf collector")) }
                            MonitoringMetricDescriptors.all
                                .filter { it.source != MonitoringMetricSource.Emulator && hasAndroidValue(android, it.metric) }
                                .forEach { put(it.metric, MetricDebugInfo(nowMs, "Android system collector")) }
                        }
                        val snapshot = MonitoringSnapshot(emulator ?: EmulatorMetrics(), android, metricDebug = metricDebug)
                        if (!androidAvailabilityLogged && (android.ramTotalBytes != null || android.batteryTemperatureC != null || android.gpu != null)) {
                            androidAvailabilityLogged = true
                            Log.d("S3PERF", "android telemetry available ram=${android.ramUsedBytes != null} gpu=${android.gpu != null} temp=${android.batteryTemperatureC != null} power=${android.batteryPowerW != null}")
                        }
                        _snapshot.value = snapshot.copy(fpsHistory = history.fps(), frameTimeHistory = history.frameTime())
                        perf.logUiSnapshot(snapshot.emulator)
                        delay(intervalMs.coerceIn(250L, 1000L))
                    }
                } finally {
                    system.stop()
                    perf.setEnabled(false, intervalMs)
                    wasRunning = false
                    androidAvailabilityLogged = false
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        system.stop()
        perf.setEnabled(false, 300L)
        history.clear()
        wasRunning = false
        generation = 0L
    }

    private fun hasEmulatorValue(metrics: EmulatorMetrics, metric: MonitoringMetric): Boolean = when (metric) {
        MonitoringMetric.Fps -> metrics.fps != null
        MonitoringMetric.FrameTime -> metrics.frameTimeMs != null
        MonitoringMetric.RpcsxHostCpu -> metrics.hostCpuPercent != null
        MonitoringMetric.PpuCpu -> metrics.ppuCpuPercent != null
        MonitoringMetric.SpuCpu -> metrics.spuCpuPercent != null
        MonitoringMetric.RsxCpu -> metrics.rsxCpuPercent != null
        MonitoringMetric.RsxLoad -> metrics.rsxLoadPercent != null
        MonitoringMetric.PpuThreads -> metrics.ppuThreads != null
        MonitoringMetric.SpuThreads -> metrics.spuThreads != null
        MonitoringMetric.HostThreads -> metrics.hostThreads != null
        else -> false
    }

    private fun hasAndroidValue(metrics: AndroidSystemMetrics, metric: MonitoringMetric): Boolean = when (metric) {
        MonitoringMetric.AndroidSystemCpu -> metrics.systemCpuPercent != null
        MonitoringMetric.AndroidProcessCpu -> metrics.processCpuPercent != null
        MonitoringMetric.GpuHardwareLoad -> metrics.gpu?.loadPercent != null
        MonitoringMetric.GpuFrequency -> metrics.gpu?.frequencyHz != null
        MonitoringMetric.CpuFrequency -> metrics.cpuFrequenciesHz.isNotEmpty()
        MonitoringMetric.RamUsed -> metrics.ramUsedBytes != null
        MonitoringMetric.RamAvailable -> metrics.ramAvailableBytes != null
        MonitoringMetric.RamTotal -> metrics.ramTotalBytes != null
        MonitoringMetric.AppRss -> metrics.processRssBytes != null
        MonitoringMetric.AppPss -> metrics.processPssBytes != null
        MonitoringMetric.SwapUsed -> metrics.swapUsedBytes != null
        MonitoringMetric.SwapTotal -> metrics.swapTotalBytes != null
        MonitoringMetric.ZramUsed -> metrics.zramUsedBytes != null
        MonitoringMetric.BatteryPercent -> metrics.batteryPercent != null
        MonitoringMetric.BatteryTemperature -> metrics.batteryTemperatureC != null
        MonitoringMetric.BatteryPower -> metrics.batteryPowerW != null
        MonitoringMetric.ThermalStatus -> metrics.thermalStatus != null
        MonitoringMetric.ThermalHeadroom -> metrics.thermalHeadroom != null
        else -> false
    }
}
