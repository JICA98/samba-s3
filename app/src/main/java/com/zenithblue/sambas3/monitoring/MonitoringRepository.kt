package com.zenithblue.sambas3.monitoring

import android.content.Context
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
    private var job: Job? = null

    fun start(scope: CoroutineScope, settingsFlow: StateFlow<MonitoringSettings> = MonitoringOverlaySettings.state(appContext)) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            settingsFlow.collectLatest { settings ->
                if (!settings.enabled) {
                    system.stop()
                    perf.setEnabled(false, settings.updateMs)
                    return@collectLatest
                }
                val intervalMs = settings.updateMs
                perf.setEnabled(true, intervalMs)
                system.start()
                try {
                    while (isActive) {
                        val emulator = stateProvider()
                            ?.takeIf { it == EmulatorState.Running || it == EmulatorState.Paused }
                            ?.let { perf.read() }
                        val snapshot = MonitoringSnapshot(emulator ?: EmulatorMetrics(), system.read())
                        _snapshot.value = snapshot
                        perf.logUiSnapshot(snapshot.emulator)
                        delay(intervalMs.coerceIn(250L, 1000L))
                    }
                } finally {
                    system.stop()
                    perf.setEnabled(false, intervalMs)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        system.stop()
        perf.setEnabled(false, 300L)
    }
}
