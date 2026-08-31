package com.zenithblue.sambas3

import com.zenithblue.sambas3.session.EmulatorStopCoordinator
import com.zenithblue.sambas3.session.EmulatorStopReason
import com.zenithblue.sambas3.session.EmulatorStopState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EmulatorStopCoordinatorTest {
    @Before
    fun setUp() = EmulatorStopCoordinator.clearForTest()

    @After
    fun tearDown() = EmulatorStopCoordinator.clearForTest()

    @Test
    fun tenConcurrentStopsIssueOneKillAndShareOutcome() = runBlocking {
        val state = AtomicReference(EmulatorState.Running)
        val kills = AtomicInteger(0)
        val calls = List(10) {
            async {
                EmulatorStopCoordinator.stopForTest(
                    readState = { state.get() },
                    kill = { kills.incrementAndGet(); state.set(EmulatorState.Stopping) },
                    activeTimeoutMs = 1_000,
                    passiveTimeoutMs = 1_000,
                )
            }
        }
        delay(40)
        state.set(EmulatorState.Stopped)
        assertTrue(calls.awaitAll().all { it })
        assertEquals(1, kills.get())
        assertTrue(EmulatorStopCoordinator.state.value is EmulatorStopState.Completed)
    }

    @Test
    fun alreadyStoppingDoesNotSendSecondKill() = runBlocking {
        val state = AtomicReference(EmulatorState.Stopping)
        var kills = 0
        val result = async {
            EmulatorStopCoordinator.stopForTest(
                readState = { state.get() },
                kill = { kills++ },
                activeTimeoutMs = 1_000,
                passiveTimeoutMs = 1_000,
            )
        }
        delay(30)
        state.set(EmulatorState.Stopped)
        assertTrue(result.await())
        assertEquals(0, kills)
    }

    @Test
    fun stateReadFailureIsFailedAndNeverTreatedAsStopped() = runBlocking {
        var kills = 0
        val result = EmulatorStopCoordinator.stopForTest(
            reason = EmulatorStopReason.HomeStop,
            readState = { error("native unavailable") },
            kill = { kills++ },
            activeTimeoutMs = 50,
            passiveTimeoutMs = 50,
        )
        assertFalse(result)
        assertEquals(0, kills)
        val failed = EmulatorStopCoordinator.state.value as EmulatorStopState.Failed
        assertTrue(failed.message.contains("state-read-failed"))
    }

    @Test
    fun delayedStoppedIsRecoveredWithoutSecondKill() = runBlocking {
        val state = AtomicReference(EmulatorState.Running)
        var kills = 0
        val result = async {
            EmulatorStopCoordinator.stopForTest(
                readState = { state.get() },
                kill = { kills++; state.set(EmulatorState.Stopping) },
                activeTimeoutMs = 40,
                passiveTimeoutMs = 1_000,
                pollMs = 10,
            )
        }
        delay(100)
        state.set(EmulatorState.Stopped)
        assertTrue(result.await())
        assertEquals(1, kills)
    }

    @Test
    fun permanentStoppingEventuallyFailsAfterPassiveReconciliation() = runBlocking {
        val state = AtomicReference(EmulatorState.Running)
        var kills = 0
        val result = withTimeout(2_000) {
            EmulatorStopCoordinator.stopForTest(
                readState = { state.get() },
                kill = { kills++; state.set(EmulatorState.Stopping) },
                activeTimeoutMs = 30,
                passiveTimeoutMs = 100,
                pollMs = 10,
            )
        }
        assertFalse(result)
        assertEquals(1, kills)
        assertTrue(EmulatorStopCoordinator.state.value is EmulatorStopState.Failed)
    }
}
