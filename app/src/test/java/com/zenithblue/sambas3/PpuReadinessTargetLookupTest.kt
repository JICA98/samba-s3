package com.zenithblue.sambas3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PpuReadinessTargetLookupTest {

    private lateinit var ctx: Context
    private lateinit var oldRoot: String

    @Before
    fun setUp() {
        oldRoot = RPCSX.rootDirectory
        ctx = ApplicationProvider.getApplicationContext()
        val tmpRoot = ctx.filesDir.absolutePath + "/ppuTargetTest_${System.nanoTime()}/"
        File(tmpRoot, "config/prefs").mkdirs()
        RPCSX.rootDirectory = tmpRoot
    }

    @After
    fun tearDown() {
        RPCSX.rootDirectory = oldRoot
    }

    @Test
    fun resolveLlvmCpu_rejectsEmptyOrBracesAndFallsBackToBaseline() {
        // When native returns empty or uninitialized {}, fallback safely to baseline target (never "auto" or empty)
        val cpu = PpuReadinessStore.resolveLlvmCpu("BLUS30443")
        assertEquals("cortex-a34", cpu)
    }

    @Test
    fun normalizeFingerprint_cleansUpLegacyBracesAndAuto() {
        val legacyBraces = "v7-kusa|{}|BLUS30443"
        val normalizedBraces = PpuReadinessStore.normalizeFingerprint(legacyBraces)
        assertEquals("v7-kusa|cortex-a34|BLUS30443", normalizedBraces)

        val legacyAuto = "v7-kusa|auto|BLUS30443"
        val normalizedAuto = PpuReadinessStore.normalizeFingerprint(legacyAuto)
        assertEquals("v7-kusa|cortex-a34|BLUS30443", normalizedAuto)

        val explicit = "v7-kusa|cortex-a78|BLUS30443"
        assertEquals(explicit, PpuReadinessStore.normalizeFingerprint(explicit))
    }

    @Test
    fun load_migratesLegacyBracesAndAutoInFingerprint() {
        val prefs = File(RPCSX.rootDirectory, "config/prefs")
        prefs.mkdirs()
        File(prefs, "ppu_state.json").writeText(
            """
            {"version":1,"entries":{"BLUS30443":{"key":"BLUS30443","preRuntime":"READY","runtime":"NOT_STARTED","fingerprint":"v7-kusa|{}|BLUS30443","updatedMs":1},"BLES00001":{"key":"BLES00001","preRuntime":"READY","runtime":"NOT_STARTED","fingerprint":"v7-kusa|auto|BLES00001","updatedMs":1}}}
            """.trimIndent()
        )
        PpuReadinessStore.load(ctx)
        val entries = PpuReadinessStore.allEntries(ctx)
        assertEquals("v7-kusa|cortex-a34|BLUS30443", entries["BLUS30443"]?.fingerprint)
        assertEquals("v7-kusa|cortex-a34|BLES00001", entries["BLES00001"]?.fingerprint)
    }

    @Test
    fun invalidateIfFingerprintChanged_avoidsFalseInvalidationOnLegacyBraces() {
        val prefs = File(RPCSX.rootDirectory, "config/prefs")
        prefs.mkdirs()
        File(prefs, "ppu_state.json").writeText(
            """
            {"version":1,"entries":{"BLUS30443":{"key":"BLUS30443","preRuntime":"READY","runtime":"NOT_STARTED","fingerprint":"v7-kusa|{}|BLUS30443","updatedMs":1}}}
            """.trimIndent()
        )
        PpuReadinessStore.load(ctx)

        // Compare against normalized baseline fingerprint
        val invalidated = PpuReadinessStore.invalidateIfFingerprintChanged(ctx, "BLUS30443", "v7-kusa|cortex-a34|BLUS30443")
        assertFalse("Should not invalidate when legacy {} maps to baseline", invalidated)
        assertEquals(PreRuntimePpuState.READY, PpuReadinessStore.getPreRuntimeState(ctx, "BLUS30443"))
    }

    @Test
    fun invalidateIfFingerprintChanged_invalidatesOnGenuineTargetChange() {
        val prefs = File(RPCSX.rootDirectory, "config/prefs")
        prefs.mkdirs()
        File(prefs, "ppu_state.json").writeText(
            """
            {"version":1,"entries":{"BLUS30443":{"key":"BLUS30443","preRuntime":"READY","runtime":"NOT_STARTED","fingerprint":"v7-kusa|cortex-a34|BLUS30443","updatedMs":1}}}
            """.trimIndent()
        )
        PpuReadinessStore.load(ctx)

        val invalidated = PpuReadinessStore.invalidateIfFingerprintChanged(ctx, "BLUS30443", "v7-kusa|cortex-a78|BLUS30443")
        assertTrue("Should invalidate when target changes from cortex-a34 to cortex-a78", invalidated)
        assertEquals(PreRuntimePpuState.INVALIDATED, PpuReadinessStore.getPreRuntimeState(ctx, "BLUS30443"))
    }

    @Test
    fun fingerprint_preservesTitleIdInFallbackPath() {
        val fp = PpuReadinessStore.fingerprint(ctx, "BLUS30443")
        assertTrue("Fingerprint must contain titleId BLUS30443", fp?.contains("BLUS30443") == true)
        assertFalse("Fingerprint must not retain auto", fp?.contains("|auto|") == true)
        assertTrue("Fingerprint must contain effective baseline cortex-a34", fp?.contains("|cortex-a34|") == true)
    }

    @Test
    fun fingerprint_preservesTitleIdentityWhenTitleIdNull() {
        val fp = PpuReadinessStore.fingerprint(ctx, null)
        assertTrue("Fingerprint must contain 'unknown' title when titleId is null", fp?.contains("unknown") == true)
        assertTrue("Fingerprint must contain effective baseline cortex-a34", fp?.contains("|cortex-a34|") == true)
    }

    @Test
    fun resolveLlvmCpu_neverReturnsAutoOrEmpty() {
        val cpu = PpuReadinessStore.resolveLlvmCpu("BLES99999")
        assertTrue("Effective CPU must not be empty", cpu.isNotEmpty())
        assertFalse("Effective CPU must not be 'auto'", cpu.equals("auto", ignoreCase = true))
        assertEquals("cortex-a34", cpu)
    }
}
