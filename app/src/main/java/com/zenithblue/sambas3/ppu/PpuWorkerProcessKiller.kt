package com.zenithblue.sambas3.ppu

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * Same-UID isolated `:ppu_compile` worker. Cooperative AIDL cancel cannot
 * interrupt LLVM, and BIND_AUTO_CREATE will restart the process if we kill
 * while still bound. Unbind first, then SIGKILL every matching pid.
 */
object PpuWorkerProcessKiller {
    private const val TAG = "PpuWorkerKill"
    const val PROCESS_SUFFIX = ":ppu_compile"

    fun kill(context: Context, knownPid: Int?) {
        val mine = Process.myPid()
        val pids = linkedSetOf<Int>()
        if (knownPid != null && knownPid > 0 && knownPid != mine) {
            pids += knownPid
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.forEach { info ->
                if (info.pid != mine &&
                    info.pid > 0 &&
                    info.processName.endsWith(PROCESS_SUFFIX)
                ) {
                    pids += info.pid
                }
            }
        }
        if (pids.isEmpty()) {
            Log.i(TAG, "no :ppu_compile pid to kill known=$knownPid")
            return
        }
        pids.forEach { pid ->
            Log.i(TAG, "killing PPU worker pid=$pid")
            runCatching { Process.killProcess(pid) }
        }
    }
}
