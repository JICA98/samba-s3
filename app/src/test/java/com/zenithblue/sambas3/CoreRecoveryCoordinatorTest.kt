package com.zenithblue.sambas3

import com.zenithblue.sambas3.session.CoreRecoveryCoordinator
import com.zenithblue.sambas3.session.StopResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreRecoveryCoordinatorTest {
    @Test fun stoppedDoesNotKill() = runBlocking {
        var killed = false
        assertEquals(StopResult.AlreadyStopped, CoreRecoveryCoordinator.ensureStoppedForFreshBoot("test", { EmulatorState.Stopped }, { killed = true }))
        assertEquals(false, killed)
    }

    @Test fun stoppingIsWaitedBeforeBootMayContinue() = runBlocking {
        var state = EmulatorState.Stopping
        var kills = 0
        val result = CoreRecoveryCoordinator.ensureStoppedForFreshBoot("test", { state }, { kills++ }, timeoutMs = 500, pollMs = 10)
        assertEquals(StopResult.TimedOut, result)
        assertEquals(0, kills)
        state = EmulatorState.Stopped
    }
}
