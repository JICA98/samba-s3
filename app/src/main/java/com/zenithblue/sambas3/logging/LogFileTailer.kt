package com.zenithblue.sambas3.logging

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Polling file tailer. Handles late create, append, truncation, rotation,
 * CRLF/LF, partial last lines, UTF-8 split across reads, and a bounded max line.
 */
class LogFileTailer(
    private val file: File,
    startAtEnd: Boolean = false,
    initialOffset: Long? = null,
    private val maxLineBytes: Int = 32 * 1024,
) {
    private var offset: Long = when {
        initialOffset != null -> initialOffset.coerceAtLeast(0L)
        startAtEnd && file.isFile -> file.length()
        else -> 0L
    }
    private val carry = ArrayList<Byte>(256)
    private var seenLength: Long = if (file.isFile) file.length() else -1L
    private var missing = !file.isFile
    private var inode: Long? = inodeOf()

    fun poll(maxLines: Int = 1_000): List<String> {
        if (!file.isFile) {
            missing = true
            return emptyList()
        }
        if (missing) {
            offset = 0L
            carry.clear()
            missing = false
            inode = inodeOf()
        }
        val currentInode = inodeOf()
        if (currentInode != null && inode != null && currentInode != inode) {
            offset = 0L
            carry.clear()
            inode = currentInode
        }
        val length = file.length()
        if (length < offset) {
            offset = 0L
            carry.clear()
        }
        seenLength = length
        if (offset >= length && carry.isEmpty()) return emptyList()
        val lines = ArrayList<String>(16)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.coerceAtMost(length))
            val buf = ByteArray(8 * 1024)
            while (lines.size < maxLines && raf.filePointer < length) {
                val n = raf.read(buf)
                if (n <= 0) break
                var i = 0
                while (i < n && lines.size < maxLines) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) {
                        if (carry.isNotEmpty() && carry.last() == '\r'.code.toByte()) {
                            carry.removeAt(carry.lastIndex)
                        }
                        lines.add(decodeCarry())
                        carry.clear()
                    } else if (carry.size >= maxLineBytes) {
                        lines.add(decodeCarry())
                        carry.clear()
                        if (b != '\n'.code.toByte() && b != '\r'.code.toByte()) carry.add(b)
                    } else {
                        carry.add(b)
                    }
                    i++
                }
                offset = raf.filePointer - (n - i).toLong()
                if (lines.size >= maxLines) break
            }
        }
        return lines
    }

    fun drain(): List<String> {
        val lines = poll(Int.MAX_VALUE).toMutableList()
        if (carry.isNotEmpty()) {
            if (carry.last() == '\r'.code.toByte()) carry.removeAt(carry.lastIndex)
            lines.add(decodeCarry())
            carry.clear()
        }
        return lines
    }

    private fun decodeCarry(): String {
        val bytes = ByteArray(carry.size) { carry[it] }
        return String(bytes, Charsets.UTF_8)
    }

    private fun inodeOf(): Long? = runCatching {
        Files.getAttribute(file.toPath(), "unix:ino") as Long
    }.getOrNull()
}
