package com.zenithblue.sambas3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Package validation tests that exercise ZipUtil + metadata rules without Android Context.
 */
class GpuDriverPackageValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val validMeta = """
        {
          "schemaVersion": 1,
          "name": "Turnip Test",
          "author": "test",
          "packageVersion": "1",
          "vendor": "Mesa",
          "driverVersion": "1.0.0",
          "minApi": 28,
          "description": "test package",
          "libraryName": "libvulkan_freedreno.so"
        }
    """.trimIndent()

    private fun zipOf(entries: Map<String, ByteArray>): File {
        val zip = tmp.newFile("pkg-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun sha256_stable() {
        val data = "hello-driver".toByteArray()
        val a = GpuDriverHelper.sha256Hex(data)
        val b = GpuDriverHelper.sha256Hex(data)
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    @Test
    fun missing_meta_json_detected_after_extract() {
        val zip = zipOf(mapOf("libvulkan_freedreno.so" to ByteArray(8)))
        val dest = tmp.newFolder("no-meta")
        ZipUtil.unzip(zip, dest)
        assertFalse(File(dest, "meta.json").isFile)
        assertTrue(File(dest, "libvulkan_freedreno.so").isFile)
    }

    @Test
    fun invalid_metadata_json_fails_deserialize() {
        val meta = tmp.newFile("meta.json")
        meta.writeText("""{"schemaVersion": 99, "name": "x"}""")
        try {
            GpuDriverMetadata.deserialize(meta)
            org.junit.Assert.fail("expected SerializationException")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun library_path_escape_rejected_by_validator() {
        val dir = tmp.newFolder("drv")
        File(dir, "meta.json").writeText(validMeta)
        // library name with path components must fail
        assertFalse(GpuDriverHelper.validateInstalledLibrary(dir, "../libvulkan_freedreno.so"))
        assertFalse(GpuDriverHelper.validateInstalledLibrary(dir, "sub/libvulkan_freedreno.so"))
    }

    @Test
    fun missing_primary_vulkan_library_fails_validator() {
        val dir = tmp.newFolder("drv2")
        File(dir, "meta.json").writeText(validMeta)
        assertFalse(GpuDriverHelper.validateInstalledLibrary(dir, "libvulkan_freedreno.so"))
    }

    @Test
    fun present_library_passes_validator() {
        val dir = tmp.newFolder("drv3")
        File(dir, "meta.json").writeText(validMeta)
        File(dir, "libvulkan_freedreno.so").writeBytes(ByteArray(32) { 2 })
        assertTrue(GpuDriverHelper.validateInstalledLibrary(dir, "libvulkan_freedreno.so"))
    }

    @Test
    fun metadata_label_uses_bundled_id_when_marked() {
        val dir = tmp.newFolder("bundled")
        File(dir, "meta.json").writeText(validMeta)
        File(dir, "libvulkan_freedreno.so").writeBytes(ByteArray(8))
        BundledDriverMarker.write(
            File(dir, BundledDriverMarker.FILE_NAME),
            BundledDriverMarker(
                id = "turnip-26.1.4",
                sha256 = "aa",
                displayName = "Turnip 26.1.4 — Recommended",
                role = "recommended",
            ),
        )
        val meta = GpuDriverMetadata.deserialize(File(dir, "meta.json"))
        assertTrue(meta.isBundled)
        assertEquals("turnip-26.1.4", meta.label)
        assertEquals("Turnip 26.1.4 — Recommended", meta.uiTitle)
    }

    @Test
    fun checksum_mismatch_detected() {
        val bytes = "driver-bytes".toByteArray()
        val actual = GpuDriverHelper.sha256Hex(bytes)
        val expected = "0".repeat(64)
        assertFalse(actual.equals(expected, ignoreCase = true))
    }
}
