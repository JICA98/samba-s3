package com.zenithblue.sambas3.debug

import java.io.File
import java.nio.file.Files
import com.zenithblue.sambas3.EmulatorBootMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSavedSlotLaunchPolicyTest {
    @Test fun requestMustBeTypedSlotZeroWithoutAnyCallerSuppliedStatePath() {
        assertTrue(DebugSavedSlotLaunchPolicy.isRequestShapeValid(EmulatorBootMode.UserSelectedSavestate.name, 0, false))
        assertFalse(DebugSavedSlotLaunchPolicy.isRequestShapeValid(EmulatorBootMode.FreshGame.name, 0, false))
        assertFalse(DebugSavedSlotLaunchPolicy.isRequestShapeValid(EmulatorBootMode.UserSelectedSavestate.name, 1, false))
        assertFalse(DebugSavedSlotLaunchPolicy.isRequestShapeValid(EmulatorBootMode.UserSelectedSavestate.name, 0, true))
    }

    @Test fun onlyTheRegisteredTargetAndSlotZeroAreEligible() {
        assertTrue(DebugSavedSlotLaunchPolicy.isAllowed("BCUS98111", 0))
        assertFalse(DebugSavedSlotLaunchPolicy.isAllowed("BLUS31584", 0))
        assertFalse(DebugSavedSlotLaunchPolicy.isAllowed("bcus98111", 0))
        assertFalse(DebugSavedSlotLaunchPolicy.isAllowed("BCUS98111", 1))
        assertFalse(DebugSavedSlotLaunchPolicy.isAllowed(null, 0))
    }

    @Test fun slotMustBeARealNonemptyFileAtTheFixedTitleSlotPath() {
        val root = Files.createTempDirectory("debug-slot-zero").toFile()
        try {
            val directory = File(root, "config/savestates/BCUS98111").apply { mkdirs() }
            val slot = File(directory, "BCUS98111_1_0.SAVESTAT.zst").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, true, slot, root))
            assertFalse(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, false, slot, root))
            assertFalse(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, true, null, root))

            val empty = File(directory, "BCUS98111_1_0.SAVESTAT").apply { createNewFile() }
            assertFalse(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, true, empty, root))

            val wrongSlot = File(directory, "BCUS98111_1_1.SAVESTAT").apply { writeBytes(byteArrayOf(1)) }
            assertFalse(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, true, wrongSlot, root))

            val outside = File(root, "outside.SAVESTAT").apply { writeBytes(byteArrayOf(1)) }
            val escaped = File(directory, "BCUS98111_1_0.SAVESTAT.gz")
            Files.createSymbolicLink(escaped.toPath(), outside.toPath())
            assertFalse(DebugSavedSlotLaunchPolicy.isUsableSlotZero("BCUS98111", 0, true, escaped, root))
        } finally {
            root.deleteRecursively()
        }
    }
}
