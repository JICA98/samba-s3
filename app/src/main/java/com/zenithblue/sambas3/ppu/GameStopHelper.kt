package com.zenithblue.sambas3.ppu

import android.util.Log
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GameStopHelper {
    private const val TAG = "S3LIFE"

    suspend fun stopGameplay(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "stopGameplay: requesting kill activeGame=${RPCSX.activeGame.value} state=${RPCSX.getState()}")
            RPCSX.instance.kill()
            var attempts = 0
            while (attempts < 50) {
                val s = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                if (s == EmulatorState.Stopped) break
                Log.d(TAG, "stopGameplay waiting for Stopped state=$s attempt=$attempts")
                kotlinx.coroutines.delay(100)
                attempts++
            }
            val finalState = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
            withContext(Dispatchers.Main) {
                RPCSX.state.value = finalState
                if (finalState == EmulatorState.Stopped) {
                    RPCSX.activeGame.value = null
                    Log.i(TAG, "stopGameplay reached Stopped, cleared activeGame")
                } else {
                    Log.w(TAG, "stopGameplay timeout finalState=$finalState")
                }
            }
            finalState == EmulatorState.Stopped
        } catch (e: Exception) {
            Log.e(TAG, "stopGameplay failed: ${e.message}", e)
            false
        }
    }
}
