package com.zenithblue.sambas3.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpuDiagnosticFixtureTest {
    private fun header(elfClass: Int = 1, dataEncoding: Int = 2, machine: Int = 23): ByteArray =
        ByteArray(52).also {
            it[0] = 0x7f
            it[1] = 'E'.code.toByte()
            it[2] = 'L'.code.toByte()
            it[3] = 'F'.code.toByte()
            it[4] = elfClass.toByte()
            it[5] = dataEncoding.toByte()
            it[18] = (machine ushr 8).toByte()
            it[19] = machine.toByte()
        }

    @Test fun registersReviewedPromotionAndSignedImmediateFixtures() {
        assertTrue(SpuDiagnosticFixture.expectedSha256("hot_promotion") == "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e")
        assertTrue(SpuDiagnosticFixture.expectedSha256("hot_loop_null_result") == "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e")
        assertTrue(SpuDiagnosticFixture.expectedSha256("hot_loop_stop_before_publish") == "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e")
        assertEquals(1, SpuDiagnosticFixture.diagnosticCase("hot_loop_null_result"))
        assertEquals(2, SpuDiagnosticFixture.diagnosticCase("hot_loop_stop_before_publish"))
        assertTrue(SpuDiagnosticFixture.expectedSha256("same_key_success_wait") == "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e")
        assertEquals(3, SpuDiagnosticFixture.diagnosticCase("same_key_success_wait"))
        assertNull(SpuDiagnosticFixture.diagnosticCase("hot_promotion"))
        assertNull(SpuDiagnosticFixture.diagnosticCase("unknown"))
        assertTrue(SpuDiagnosticFixture.expectedSha256("il_signed_matrix") == "0fb1a49aa42f0ad5c68c9f853548ff6403608f16e8d2e3f650c221518349087b")
        assertEquals(4, SpuDiagnosticFixture.diagnosticCase("same_key_owner_abandon_retry"))
        assertEquals("b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e", SpuDiagnosticFixture.expectedSha256("same_key_owner_abandon_retry"))
        assertEquals(5, SpuDiagnosticFixture.diagnosticCase("oversize_single_entry"))
        assertEquals("9bf564d4109c23c42fa8fcc89752d2d31d9f08ecae064dbc7373e29a224ec868", SpuDiagnosticFixture.expectedSha256("oversize_single_entry"))
        assertEquals(6, SpuDiagnosticFixture.diagnosticCase("self_modify_retained_selector"))
        assertEquals("294ad18669bd212c7e1d419b5591ee71d5e1c1647e8f4770794c19068c3b17ea", SpuDiagnosticFixture.expectedSha256("self_modify_retained_selector"))
        assertNull(SpuDiagnosticFixture.diagnosticCase("hot_promotion"))
        assertNull(SpuDiagnosticFixture.expectedSha256("unknown"))
    }

    @Test fun acceptsOnlyBigEndianElf32ForSpu() {
        assertTrue(SpuDiagnosticFixture.isElf32BigEndianSpu(header()))
        assertFalse(SpuDiagnosticFixture.isElf32BigEndianSpu(header(elfClass = 2)))
        assertFalse(SpuDiagnosticFixture.isElf32BigEndianSpu(header(dataEncoding = 1)))
        assertFalse(SpuDiagnosticFixture.isElf32BigEndianSpu(header(machine = 21)))
        assertFalse(SpuDiagnosticFixture.isElf32BigEndianSpu(header().copyOf(19)))
    }
}
