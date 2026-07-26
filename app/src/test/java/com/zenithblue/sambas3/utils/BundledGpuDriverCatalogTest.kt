package com.zenithblue.sambas3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledGpuDriverCatalogTest {

    private val sampleCatalog = """
    {
      "schemaVersion": 1,
      "drivers": [
        {
          "id": "turnip-26.1.4",
          "displayName": "Turnip 26.1.4 — Recommended",
          "role": "recommended",
          "packageFile": "turnip-26.1.4-sambas3.zip",
          "libraryName": "libvulkan_freedreno.so",
          "supportedGpuFamilies": ["adreno6xx", "adreno7xx"],
          "experimental": false,
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "sourceVersion": "Mesa 26.1.4"
        },
        {
          "id": "turnip-25.3.4",
          "displayName": "Turnip 25.3.4 — Compatibility",
          "role": "compatibility",
          "packageFile": "turnip-25.3.4-sambas3.zip",
          "libraryName": "libvulkan_freedreno.so",
          "supportedGpuFamilies": ["adreno6xx", "adreno7xx"],
          "experimental": false,
          "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "sourceVersion": "Mesa 25.3.4"
        },
        {
          "id": "turnip-a8xx-v29",
          "displayName": "Turnip A8XX v29 — Experimental",
          "role": "experimental",
          "packageFile": "turnip-a8xx-v29-sambas3.zip",
          "libraryName": "libvulkan_freedreno.so",
          "supportedGpuIds": ["810", "825", "829", "830", "840"],
          "forceSysmemGpuIds": ["830"],
          "experimental": true,
          "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
          "sourceVersion": "A8XX v29"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parse_catalog_contains_exactly_three_drivers() {
        val catalog = BundledGpuDriverCatalog.parse(sampleCatalog)
        assertEquals(1, catalog.schemaVersion)
        assertEquals(3, catalog.drivers.size)
    }

    @Test
    fun driver_ids_are_unique() {
        val catalog = BundledGpuDriverCatalog.parse(sampleCatalog)
        val ids = catalog.drivers.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(
            setOf("turnip-26.1.4", "turnip-25.3.4", "turnip-a8xx-v29"),
            ids.toSet(),
        )
    }

    @Test
    fun duplicate_ids_rejected() {
        val bad = sampleCatalog.replace("turnip-25.3.4", "turnip-26.1.4")
        assertThrows(IllegalArgumentException::class.java) {
            BundledGpuDriverCatalog.parse(bad)
        }
    }

    @Test
    fun a8xx_never_recommended_role() {
        val catalog = BundledGpuDriverCatalog.parse(sampleCatalog)
        val a8 = catalog.drivers.first { it.id == "turnip-a8xx-v29" }
        assertTrue(a8.experimental)
        assertFalse(a8.isRecommended)
        assertTrue(a8.forceSysmemGpuIds.contains("830"))
    }

    @Test
    fun roles_match_intent() {
        val catalog = BundledGpuDriverCatalog.parse(sampleCatalog)
        assertTrue(catalog.drivers.first { it.id == "turnip-26.1.4" }.isRecommended)
        assertTrue(catalog.drivers.first { it.id == "turnip-25.3.4" }.isCompatibility)
        assertTrue(catalog.drivers.first { it.id == "turnip-a8xx-v29" }.isExperimental)
    }
}
