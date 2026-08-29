package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavestateTransitionControllerTest {
    @Test
    fun normal_save_requires_durable_commit_surface_and_frame() {
        val controller = SavestateTransitionController()
        assertTrue(controller.begin(11L, 4))
        assertEquals(SavestateTransitionController.Phase.Saving, controller.state.phase)
        assertFalse(controller.committed(12L, 4, "/state"))
        assertTrue(controller.committed(11L, 4, "/state"))
        assertTrue(controller.surfaceResetStarted())
        assertTrue(controller.surfaceReady(11L, 4))
        assertTrue(controller.bootStarted(11L, 4))
        assertTrue(controller.firstFrameConfirmed(11L, 4))
        assertEquals(SavestateTransitionController.Phase.Completed, controller.state.phase)
    }

    @Test
    fun duplicate_or_stale_completion_cannot_advance_transition() {
        val controller = SavestateTransitionController()
        assertTrue(controller.begin(21L, 2))
        assertFalse(controller.committed(21L, 3, "/wrong-slot"))
        assertFalse(controller.committed(20L, 2, "/wrong-request"))
        assertEquals(SavestateTransitionController.Phase.Saving, controller.state.phase)
        assertTrue(controller.committed(21L, 2, "/state"))
        assertFalse(controller.committed(21L, 2, "/duplicate"))
    }

    @Test
    fun recovery_boot_starts_waiting_for_first_frame() {
        val controller = SavestateTransitionController()
        assertTrue(controller.beginRecoveryBoot(31L, 7, "/saved-slot"))
        assertEquals(SavestateTransitionController.Phase.AwaitingFirstFrame, controller.state.phase)
        assertTrue(controller.firstFrameConfirmed(31L, 7))
        assertFalse(controller.firstFrameConfirmed(31L, 7))
    }

    @Test
    fun failure_is_terminal_until_reset() {
        val controller = SavestateTransitionController()
        assertTrue(controller.begin(41L, 1))
        assertTrue(controller.fail("surface"))
        assertEquals(SavestateTransitionController.Phase.Failed, controller.state.phase)
        assertFalse(controller.fail("duplicate"))
        controller.reset()
        assertEquals(SavestateTransitionController.Phase.Idle, controller.state.phase)
    }
}
