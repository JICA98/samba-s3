package com.zenithblue.sambas3

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PpuActivityLaunchGateTest {
    @Test
    fun staleReadyCacheReturnsHomeInsteadOfRecompilingInLoadingScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "BLUS30443"
        val wasInitialized = RPCSX.initialized
        PpuReadinessStore.setPreRuntimeState(context, title, PreRuntimePpuState.READY, "old-compiler-key")
        PpuReadinessStore.setRuntimeState(context, title, RuntimePpuState.IDLE_AFTER_COMPILE)
        RPCSX.state.value = EmulatorState.Stopped
        RPCSX.activeGame.value = null
        RPCSX.initialized = true
        try {
            val intent = Intent(context, RPCSXActivity::class.java).apply {
                putExtra("path", "direct_iso/$title")
            }
            val controller = Robolectric.buildActivity(RPCSXActivity::class.java, intent).create()
            assertTrue(controller.get().isFinishing)
            assertEquals(PreRuntimePpuState.INVALIDATED, PpuReadinessStore.getPreRuntimeState(context, title))
            assertEquals(MainActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity.component?.className)
            controller.destroy()
        } finally {
            RPCSX.initialized = wasInitialized
            PpuReadinessStore.removeEntry(context, title)
        }
    }

    @Test
    fun unpreparedFreshAndSaveLaunchesReturnHomeBeforeNativeBoot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "BLUS30443"
        PpuReadinessStore.removeEntry(context, title)
        RPCSX.state.value = EmulatorState.Stopped
        RPCSX.activeGame.value = null

        for (mode in EmulatorBootMode.entries) {
            val intent = Intent(context, RPCSXActivity::class.java).apply {
                putExtra("path", "direct_iso/$title")
                putExtra(RPCSXActivity.EXTRA_ORIGINAL_GAME_PATH, "direct_iso/$title")
                putExtra(RPCSXActivity.EXTRA_BOOT_MODE, mode.name)
                putExtra(RPCSXActivity.EXTRA_SAVESTATE_PATH, "/test/state.SAVESTAT")
            }
            val controller = Robolectric.buildActivity(RPCSXActivity::class.java, intent).create()
            val activity = controller.get()
            assertTrue("$mode must not enter the emulator", activity.isFinishing)
            assertEquals(
                MainActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className,
            )
            // Deferred activities never initialize the rendering/stop machinery.
            // Destruction must not touch it or terminate an unrelated PPU worker.
            controller.destroy()
        }
    }
}
