package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

object PpuInstallSessionStore {
    private const val TAG = "PpuSessionStore"
    private const val DIR_NAME = "ppu-install"
    private const val FILE_NAME = "session.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun sessionFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun load(context: Context): PpuInstallSession? {
        return try {
            val f = sessionFile(context)
            if (!f.exists()) return null
            json.decodeFromString<PpuInstallSession>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session: ${e.message}")
            null
        }
    }

    @Synchronized
    fun save(context: Context, session: PpuInstallSession) {
        try {
            val target = sessionFile(context)
            val temp = File(target.parentFile, "session.json.tmp")
            val text = json.encodeToString(session)
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${e.message}", e)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            val f = sessionFile(context)
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
    }
}
