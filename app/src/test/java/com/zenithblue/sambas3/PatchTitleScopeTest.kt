package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PatchTitleScopeTest {
    @Test fun sharedPatchKeepsEachTitlesEnabledState() {
        val shared = Patch(name = "Shared", serials = listOf("BCUS98111", "BCUS98125"), enabled = true, enabledSerials = listOf("BCUS98125"))
        val unrelated = Patch(name = "Other", serials = listOf("BLUS30443"), enabled = true)
        val selected = PatchRepository.forTitle(listOf(shared, unrelated), "BCUS98111")
        assertEquals(listOf("Shared"), selected.map { it.name })
        assertFalse(selected.single().enabled)
        assertEquals(true, PatchRepository.forTitle(listOf(shared), "BCUS98125").single().enabled)
    }
}
