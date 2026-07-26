package com.zenithblue.sambas3.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun valid_zip_extracts() {
        val zip = tmp.newFile("ok.zip")
        writeZip(
            zip,
            mapOf(
                "meta.json" to """{"schemaVersion":1}""".toByteArray(),
                "libvulkan_freedreno.so" to ByteArray(16) { 1 },
            ),
        )
        val dest = tmp.newFolder("out")
        ZipUtil.unzip(zip, dest)
        assertTrue(File(dest, "meta.json").isFile)
        assertTrue(File(dest, "libvulkan_freedreno.so").isFile)
    }

    @Test
    fun zip_slip_rejected() {
        val zip = tmp.newFile("slip.zip")
        writeZip(zip, mapOf("../evil.so" to byteArrayOf(1, 2, 3)))
        val dest = tmp.newFolder("out-slip")
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.unzip(zip, dest)
        }
    }

    @Test
    fun absolute_path_rejected() {
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.validateEntryName("/tmp/evil.so")
        }
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.validateEntryName("C:\\Windows\\evil.so")
        }
    }

    @Test
    fun excessive_entry_count_rejected() {
        val zip = tmp.newFile("many.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            repeat(ZipUtil.DEFAULT_MAX_ENTRIES + 5) { i ->
                zos.putNextEntry(ZipEntry("f$i.bin"))
                zos.write(byteArrayOf(1))
                zos.closeEntry()
            }
        }
        val dest = tmp.newFolder("out-many")
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.unzip(zip, dest)
        }
    }

    @Test
    fun excessive_entry_size_rejected() {
        val zip = tmp.newFile("big.zip")
        val limits = ZipUtil.Limits(maxEntryBytes = 100, maxTotalBytes = 1000, maxEntries = 10)
        writeZip(zip, mapOf("big.bin" to ByteArray(200) { 7 }))
        val dest = tmp.newFolder("out-big")
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.unzip(zip, dest, limits)
        }
    }

    @Test
    fun nested_dotdot_rejected() {
        assertThrows(ZipUtil.ZipSecurityException::class.java) {
            ZipUtil.validateEntryName("subdir/../../outside.so")
        }
    }
}
