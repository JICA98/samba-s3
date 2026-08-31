package com.zenithblue.sambas3.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.Digital2Flags
import com.zenithblue.sambas3.RPCSX

/**
 * Agent ADB bridge for controller injection — no coordinate taps.
 *
 * Register in MainActivity/RPCSXActivity onCreate; handles:
 *  - com.zenithblue.sambas3.DEBUG_PAD  with extras d1,d2,lx,ly,rx,ry (ints)
 *  - com.zenithblue.sambas3.DEBUG_PAD_CROSS (+ CIRCLE/SQUARE/TRIANGLE/START/SELECT/PS/L1/R1/L2/R2/UP/DOWN/LEFT/RIGHT/L3/R3)
 *    → press 120ms then release (deterministic for loop scripts).
 *
 * Example:
 *  adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS
 *  adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d2 64 --ei lx 127
 * Seen in LogMonitor as tag "DebugPad" → routed to BACKEND (via RPCSX-UI? actually uses Log.w).
 */
class DebugPadReceiver(private val onDebugFatal: (() -> Unit)? = null) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_FATAL) {
            if (BuildConfig.DEBUG) onDebugFatal?.invoke()
            else Log.w("DebugPad", "DEBUG_FATAL ignored in non-debug build")
            return
        }
        when {
            action == ACTION_PAD -> {
                val d1 = intent.getIntExtra("d1", 0)
                val d2 = intent.getIntExtra("d2", 0)
                val lx = intent.getIntExtra("lx", 127)
                val ly = intent.getIntExtra("ly", 127)
                val rx = intent.getIntExtra("rx", 127)
                val ry = intent.getIntExtra("ry", 127)
                Log.w("DebugPad", "PAD d1=$d1 d2=$d2 lx=$lx ly=$ly rx=$rx ry=$ry")
                RPCSX.instance.overlayPadData(d1, d2, lx, ly, rx, ry)
            }
            action.startsWith(PREFIX) -> {
                val suffix = action.removePrefix(PREFIX)
                val (d1, d2) = buttonToBits(suffix) ?: run {
                    Log.w("DebugPad", "unknown button $suffix")
                    return
                }
                Log.w("DebugPad", "BUTTON $suffix d1=$d1 d2=$d2 press 120ms")
                RPCSX.instance.overlayPadData(d1, d2, 127, 127, 127, 127)
                Handler(Looper.getMainLooper()).postDelayed({
                    RPCSX.instance.overlayPadData(0, 0, 127, 127, 127, 127)
                    Log.w("DebugPad", "BUTTON $suffix release")
                }, 120)
            }
        }
    }

    private fun buttonToBits(name: String): Pair<Int, Int>? = when (name.uppercase()) {
        "CROSS" -> 0 to Digital2Flags.CELL_PAD_CTRL_CROSS.bit
        "CIRCLE" -> 0 to Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit
        "SQUARE" -> 0 to Digital2Flags.CELL_PAD_CTRL_SQUARE.bit
        "TRIANGLE" -> 0 to Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit
        "L1" -> 0 to Digital2Flags.CELL_PAD_CTRL_L1.bit
        "R1" -> 0 to Digital2Flags.CELL_PAD_CTRL_R1.bit
        "L2" -> 0 to Digital2Flags.CELL_PAD_CTRL_L2.bit
        "R2" -> 0 to Digital2Flags.CELL_PAD_CTRL_R2.bit
        "START" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_START.bit to 0
        "SELECT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_SELECT.bit to 0
        "PS" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_PS.bit to 0
        "UP" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_UP.bit to 0
        "DOWN" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_DOWN.bit to 0
        "LEFT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_LEFT.bit to 0
        "RIGHT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_RIGHT.bit to 0
        "L3" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_L3.bit to 0
        "R3" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_R3.bit to 0
        else -> null
    }

    companion object {
        const val ACTION_PAD = "com.zenithblue.sambas3.DEBUG_PAD"
        const val ACTION_FATAL = "com.zenithblue.sambas3.DEBUG_FATAL"
        const val PREFIX = "com.zenithblue.sambas3.DEBUG_PAD_"

        fun register(context: Context, onDebugFatal: (() -> Unit)? = null): DebugPadReceiver {
            val r = DebugPadReceiver(onDebugFatal)
            val f = IntentFilter().apply {
                addAction(ACTION_PAD)
                addAction(ACTION_FATAL)
                addAction(PREFIX + "CROSS")
                addAction(PREFIX + "CIRCLE")
                addAction(PREFIX + "SQUARE")
                addAction(PREFIX + "TRIANGLE")
                addAction(PREFIX + "L1")
                addAction(PREFIX + "R1")
                addAction(PREFIX + "L2")
                addAction(PREFIX + "R2")
                addAction(PREFIX + "START")
                addAction(PREFIX + "SELECT")
                addAction(PREFIX + "PS")
                addAction(PREFIX + "UP")
                addAction(PREFIX + "DOWN")
                addAction(PREFIX + "LEFT")
                addAction(PREFIX + "RIGHT")
                addAction(PREFIX + "L3")
                addAction(PREFIX + "R3")
            }
            context.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            Log.i("DebugPad", "registered")
            return r
        }
    }
}
