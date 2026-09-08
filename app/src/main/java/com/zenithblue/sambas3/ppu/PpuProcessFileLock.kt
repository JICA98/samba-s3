package com.zenithblue.sambas3.ppu

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

class PpuProcessFileLock private constructor(
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
    val file: File
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    companion object {
        fun tryAcquire(context: Context, titleId: String): PpuProcessFileLock? {
            return try {
                val locksDir = File(context.filesDir, "locks").apply { if (!exists()) mkdirs() }
                // RPCSX owns process-global VM/JIT state. Serialize all PPU phases and titles.
                val lockFile = File(locksDir, "ppu-compile-global.lock")
                val raf = RandomAccessFile(lockFile, "rw")
                val channel = raf.channel
                val lock = channel.tryLock() ?: run {
                    channel.close()
                    raf.close()
                    return null
                }
                PpuProcessFileLock(raf, channel, lock, lockFile)
            } catch (_: Exception) {
                null
            }
        }
    }
}
