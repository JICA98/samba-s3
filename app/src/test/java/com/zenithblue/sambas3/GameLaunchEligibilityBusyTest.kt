package com.zenithblue.sambas3

import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import com.zenithblue.sambas3.ppu.GameRunEligibilityHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameLaunchEligibilityBusyTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PpuReadinessStore.load(context)
    }

    @Test
    fun every_non_stopped_engine_state_blocks_fresh_launch() {
        val game = Game(
            GameInfoStore(
                "/files/config/games/BLUS31584",
                androidx.compose.runtime.mutableStateOf("GTA"),
                androidx.compose.runtime.mutableStateOf(null),
                androidx.compose.runtime.mutableIntStateOf(0),
            )
        )
        for (state in EmulatorState.entries - EmulatorState.Stopped) {
            val availability = GameRunEligibilityHelper.evaluateAvailability(
                context, game, false, null, null, state, "/files/config/games/BLUS31584"
            )
            if (state == EmulatorState.Running || state == EmulatorState.Paused) {
                assertEquals(GameLaunchAvailability.GameplayRunning, availability)
            } else {
                assertTrue("state=$state", availability is GameLaunchAvailability.EngineBusy)
            }
        }
    }
}
