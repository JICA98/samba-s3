package com.zenithblue.sambas3.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class AtomicFileCopierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copiesOnlyBytesActuallyReturnedByUnevenReads() {
        val expected = ByteArray(131_101) { index -> (index % 251).toByte() }
        val input = object : ByteArrayInputStream(expected) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, min(length, 37))
            }
        }
        val target = tmp.root.resolve("game/PS3DataMain.obb")

        val copied = AtomicFileCopier.copy(input, target, expected.size.toLong())

        assertEquals(expected.size.toLong(), copied)
        assertArrayEquals(expected, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test
    fun replacesExistingFileOnlyAfterSuccessfulVerification() {
        val target = tmp.newFile("existing.obb")
        target.writeBytes(byteArrayOf(9, 9, 9))
        val replacement = byteArrayOf(1, 2, 3, 4, 5)

        AtomicFileCopier.copy(
            ByteArrayInputStream(replacement),
            target,
            replacement.size.toLong(),
        )

        assertArrayEquals(replacement, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test
    fun sizeMismatchPreservesExistingFileAndRemovesTemporaryFile() {
        val target = tmp.newFile("mismatch.obb")
        val original = byteArrayOf(7, 7, 7, 7)
        target.writeBytes(original)

        assertThrows(IOException::class.java) {
            AtomicFileCopier.copy(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                target,
                10L,
            )
        }

        assertArrayEquals(original, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test
    fun sourceFailurePreservesExistingFileAndRemovesTemporaryFile() {
        val target = tmp.newFile("failure.obb")
        val original = ByteArray(64) { 5 }
        target.writeBytes(original)
        val input = object : InputStream() {
            private var copied = 0

            override fun read(): Int {
                if (copied >= 128) throw IOException("provider disconnected")
                copied++
                return copied and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (copied >= 128) throw IOException("provider disconnected")
                val count = min(length, 128 - copied)
                repeat(count) { index -> buffer[offset + index] = ((copied + index) and 0xff).toByte() }
                copied += count
                return count
            }
        }

        assertThrows(IOException::class.java) {
            AtomicFileCopier.copy(input, target, null)
        }

        assertArrayEquals(original, target.readBytes())
        assertNoTemporaryFiles(target)
    }

    @Test
    fun unknownProviderSizeStillCopiesCompleteStream() {
        val expected = byteArrayOf(11, 22, 33, 44)
        val target = tmp.root.resolve("unknown-size.bin")

        val copied = AtomicFileCopier.copy(ByteArrayInputStream(expected), target, null)

        assertEquals(expected.size.toLong(), copied)
        assertArrayEquals(expected, target.readBytes())
    }

    @Test
    fun handlesAZeroLengthReadWithoutDroppingTheNextByte() {
        val expected = byteArrayOf(3, 1, 4, 1, 5)
        val delegate = ByteArrayInputStream(expected)
        val input = object : InputStream() {
            private var returnedZero = false

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }
        val target = tmp.root.resolve("zero-read.bin")

        AtomicFileCopier.copy(input, target, expected.size.toLong())

        assertArrayEquals(expected, target.readBytes())
    }

    private fun assertNoTemporaryFiles(target: java.io.File) {
        val leftovers = target.absoluteFile.parentFile
            ?.listFiles { file -> file.name.endsWith(".importing") }
            .orEmpty()
        assertFalse("temporary import files remain: ${leftovers.toList()}", leftovers.isNotEmpty())
    }
}
