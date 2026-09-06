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

    private fun sessionFile(context: Context, kind: PpuBatchKind): File {
        val suffix = if (kind == PpuBatchKind.INSTALL) DIR_NAME else "ppu-runtime"
        val dir = File(context.filesDir, suffix).apply { if (!exists()) mkdirs() }
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun load(context: Context, kind: PpuBatchKind = PpuBatchKind.INSTALL): PpuInstallSession? {
        return try {
            val f = sessionFile(context, kind)
            if (!f.exists()) return null
            json.decodeFromString<PpuInstallSession>(f.readText()).takeIf { it.kind == kind }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session: ${e.message}")
            null
        }
    }

    @Synchronized
    fun save(context: Context, session: PpuInstallSession) {
        try {
            val target = sessionFile(context, session.kind)
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
    fun clear(context: Context, kind: PpuBatchKind = PpuBatchKind.INSTALL) {
        try {
            val f = sessionFile(context, kind)
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
    }
}
