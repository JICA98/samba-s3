package com.zenithblue.sambas3.monitoring

import android.content.Context
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import com.zenithblue.sambas3.EmulatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRepositoryTest {
    private val context: Context
        get() = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }

    @Test
    fun disabledDoesNotReadTelemetryOrPerf() = runTest {
        val settings = MutableStateFlow(MonitoringSettings(enabled = false))
        val system = FakeSystemSource()
        val perf = FakePerfSource()
        val repository = MonitoringRepository(context, system, perf, { EmulatorState.Running }, StandardTestDispatcher(testScheduler))

        repository.start(this, settings)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, system.startCount)
        assertEquals(0, system.readCount)
        assertEquals(0, perf.readCount)
        assertEquals(listOf(false), perf.enabledValues)
        repository.stop()
    }

    @Test
    fun enableDisableEnableKeepsExactlyOneActiveSamplingLoop() = runTest {
        val settings = MutableStateFlow(MonitoringSettings(enabled = false, updateMs = 300L))
        val system = FakeSystemSource()
        val perf = FakePerfSource()
        val repository = MonitoringRepository(context, system, perf, { EmulatorState.Running }, StandardTestDispatcher(testScheduler))

        repository.start(this, settings)
        repository.start(this, settings)
        runCurrent()

        settings.value = settings.value.copy(enabled = true)
        runCurrent()
        advanceTimeBy(900L)
        runCurrent()
        assertEquals(1, system.startCount)
        assertEquals(1, perf.enabledValues.count { it })
        assertTrue(system.readCount > 0)
        assertTrue(perf.readCount > 0)
        assertEquals(1, system.maxActiveCount)

        settings.value = settings.value.copy(enabled = false)
        runCurrent()
        assertEquals(1, system.stopCount)
        assertEquals(false, perf.enabledValues.last())
        val readsWhileDisabled = system.readCount
        val perfReadsWhileDisabled = perf.readCount
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(readsWhileDisabled, system.readCount)
        assertEquals(perfReadsWhileDisabled, perf.readCount)

        settings.value = settings.value.copy(enabled = true)
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(2, system.startCount)
        assertEquals(2, perf.enabledValues.count { it })
        assertEquals(1, system.maxActiveCount)
        repository.stop()
        runCurrent()
        assertEquals(2, system.stopCount)
        assertEquals(false, perf.enabledValues.last())
    }

    private class FakeSystemSource : MonitoringSystemSource {
        var startCount = 0
        var stopCount = 0
        var readCount = 0
        var activeCount = 0
        var maxActiveCount = 0

        override fun start() {
            startCount++
            activeCount++
            maxActiveCount = maxOf(maxActiveCount, activeCount)
        }

        override fun stop() {
            if (activeCount == 0) return
            activeCount--
            stopCount++
        }

        override fun read(): AndroidSystemMetrics {
            readCount++
            return AndroidSystemMetrics()
        }
    }

    private class FakePerfSource : MonitoringPerfSource {
        val enabledValues = mutableListOf<Boolean>()
        var readCount = 0

        override fun setEnabled(enabled: Boolean, intervalMs: Long) {
            enabledValues += enabled
        }

        override fun read(): EmulatorMetrics? {
            readCount++
            return EmulatorMetrics(fps = 60f, frameTimeMs = 16.7f)
        }

        override fun logUiSnapshot(metrics: EmulatorMetrics) = Unit
    }
}
