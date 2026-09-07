package com.zenithblue.sambas3.ppu

import android.os.Process
import android.util.Log

/**
 * Same-UID isolated `:ppu_compile` worker. Cooperative AIDL cancel cannot
 * interrupt LLVM, and BIND_AUTO_CREATE will restart the process if we kill
 * while still bound. Unbind first, then SIGKILL the verified pid only.
 */
object PpuWorkerProcessKiller {
    private const val TAG = "PpuWorkerKill"
    const val PROCESS_SUFFIX = ":ppu_compile"

    fun killKnownPid(knownPid: Int?) {
        val mine = Process.myPid()
        if (knownPid == null || knownPid <= 0 || knownPid == mine) {
            Log.i(TAG, "no verified :ppu_compile pid to kill known=$knownPid")
            PpuDiagnosticLog.emit("kill_skipped", pid = knownPid, reason = "no_verified_pid")
            return
        }
        Log.i(TAG, "killing PPU worker pid=$knownPid")
        PpuDiagnosticLog.emit("kill_signal", pid = knownPid)
        runCatching { Process.killProcess(knownPid) }
    }

    fun kill(context: android.content.Context, knownPid: Int?) {
        killKnownPid(knownPid)
    }
}
