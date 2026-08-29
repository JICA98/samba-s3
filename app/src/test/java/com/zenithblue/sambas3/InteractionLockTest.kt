package com.zenithblue.sambas3

import com.zenithblue.sambas3.ui.emulation.EmulatorInteractionLock
import com.zenithblue.sambas3.ui.emulation.InteractionLock
import org.junit.Assert.*
import org.junit.Test

class InteractionLockTest {
    @Test fun rejectsDuplicateOwnersUntilReleased() {
        val lock = InteractionLock()
        assertTrue(lock.lock(EmulatorInteractionLock.SavestateSaving))
        assertFalse(lock.lock(EmulatorInteractionLock.SavestateLoading))
        assertTrue(lock.isLocked())
        lock.unlock()
        assertTrue(lock.lock(EmulatorInteractionLock.SavestateLoading))
    }
}
