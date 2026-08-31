package com.zenithblue.sambas3.ppu

import android.content.Context
import com.zenithblue.sambas3.session.EmulatorStopCoordinator
import com.zenithblue.sambas3.session.EmulatorStopReason

object GameStopHelper {
    suspend fun stopGameplay(context: Context): Boolean =
        EmulatorStopCoordinator.stop(context, EmulatorStopReason.HomeStop)
}
