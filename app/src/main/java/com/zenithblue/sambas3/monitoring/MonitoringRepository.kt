package com.zenithblue.sambas3.monitoring

import android.content.Context
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.EmulatorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MonitoringRepository(context: Context) {
    private val appContext = context.applicationContext
    private val system = AndroidSystemMetricsCollector(appContext)
    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val emulator = runCatching { RPCSX.getState() }
                    .takeIf { it.isSuccess }
                    ?.getOrNull()
                    ?.takeIf { it == EmulatorState.Running || it == EmulatorState.Paused }
                    ?.let { PerformanceMetricsBridge.read() }
                val android = system.read()
                _snapshot.value = MonitoringSnapshot(emulator ?: EmulatorMetrics(), android)
                delay(MonitoringOverlaySettings.read(appContext).updateMs.coerceIn(100L, 1000L))
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
