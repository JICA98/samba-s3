package com.zenithblue.sambas3.ppu

import android.util.Log
import java.io.File

/** Crash-safe text write. Preserve the last good file if rename fails. */
object PpuAtomicFiles {
    private const val TAG = "PpuAtomicFiles"

    fun writeUtf8(target: File, text: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, target.name + ".tmp")
        return try {
            temp.writeText(text)
            if (temp.renameTo(target)) {
                true
            } else if (!target.exists() && temp.renameTo(target)) {
                true
            } else {
                Log.e(TAG, "rename failed; keeping ${target.name} path=${target.absolutePath}")
                temp.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed path=${target.absolutePath}: ${e.message}", e)
            runCatching { temp.delete() }
            false
        }
    }
}
