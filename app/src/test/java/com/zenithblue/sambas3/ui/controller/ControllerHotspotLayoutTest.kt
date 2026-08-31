package com.zenithblue.sambas3.ui.controller

import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerHotspotLayoutTest {

    @Test
    fun playstationAndAbxyFaceRectsDoNotOverlapInSameFamilyList() {
        val ps = ControllerHotspotLayout.playstationHotspots().filter { it.id.startsWith("btn_") && it.id !in setOf("btn_l1", "btn_l2", "btn_r1", "btn_r2", "btn_l3", "btn_r3", "btn_select", "btn_start", "btn_guide") && !it.id.contains("dpad") }
        val abxy = ControllerHotspotLayout.abxyHotspots().filter { it.id in setOf("btn_a", "btn_b", "btn_x", "btn_y") }
        // Within one family list, no two face buttons share identical rects.
        fun uniqueRects(spots: List<HotspotRect>) {
            val keys = spots.map { "${it.left},${it.top},${it.right},${it.bottom}" }
            assertEquals(keys.size, keys.toSet().size)
        }
        uniqueRects(ps.filter { it.id in setOf("btn_cross", "btn_circle", "btn_square", "btn_triangle") })
        uniqueRects(abxy)
        // Combined legacy list would collide — prove PS and ABXY share coordinates but are never mixed.
        val psCross = ControllerHotspotLayout.playstationHotspots().first { it.id == "btn_cross" }
        val xboxA = ControllerHotspotLayout.abxyHotspots().first { it.id == "btn_a" }
        assertEquals(psCross.left, xboxA.left, 0.0001f)
        assertEquals(psCross.top, xboxA.top, 0.0001f)
        assertFalse(ControllerHotspotLayout.hotspotsFor(ControllerFamily.PLAYSTATION).any { it.id == "btn_a" })
        assertFalse(ControllerHotspotLayout.hotspotsFor(ControllerFamily.XBOX).any { it.id == "btn_cross" })
    }

    @Test
    fun hitTestUsesFamilySpecificIds() {
        val nx = 0.75f
        val ny = 0.55f // center of south face button
        val psHit = ControllerHotspotLayout.hitTest(nx, ny, ControllerFamily.PLAYSTATION)
        val xboxHit = ControllerHotspotLayout.hitTest(nx, ny, ControllerFamily.XBOX)
        assertEquals("btn_cross", psHit?.id)
        assertEquals("btn_a", xboxHit?.id)
        assertEquals(LogicalControl.CROSS, ControllerHotspotLayout.logicalForHotspot(psHit!!.id, ControllerFamily.PLAYSTATION))
        assertEquals(LogicalControl.CROSS, ControllerHotspotLayout.logicalForHotspot(xboxHit!!.id, ControllerFamily.XBOX))
    }

    @Test
    fun nintendoCrossAndCircleDoNotCollideOnSameHotspotId() {
        val crossId = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.NINTENDO)
        val circleId = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CIRCLE, ControllerFamily.NINTENDO)
        assertNotEquals(crossId, circleId)
        assertEquals("btn_b", crossId)
        assertEquals("btn_a", circleId)
        assertEquals(LogicalControl.CROSS, ControllerHotspotLayout.logicalForHotspot(crossId, ControllerFamily.NINTENDO))
        assertEquals(LogicalControl.CIRCLE, ControllerHotspotLayout.logicalForHotspot(circleId, ControllerFamily.NINTENDO))
    }

    @Test
    fun roundTripLogicalHotspotForAllFamilies() {
        val families = listOf(
            ControllerFamily.PLAYSTATION,
            ControllerFamily.XBOX,
            ControllerFamily.NINTENDO,
            ControllerFamily.GENERIC_GAMEPAD,
        )
        val controls = listOf(
            LogicalControl.CROSS, LogicalControl.CIRCLE, LogicalControl.SQUARE, LogicalControl.TRIANGLE,
            LogicalControl.DPAD_UP, LogicalControl.L1, LogicalControl.R2, LogicalControl.START,
        )
        for (family in families) {
            for (control in controls) {
                assertTrue(
                    "round-trip failed for $family / $control",
                    ControllerHotspotLayout.roundTripHotspot(control, family),
                )
            }
        }
    }

    @Test
    fun xboxTapOnSouthFaceIsReachableAsBtnA() {
        val hit = ControllerHotspotLayout.hitTest(0.75f, 0.55f, ControllerFamily.XBOX)
        assertNotNull(hit)
        assertEquals("btn_a", hit!!.id)
        // Previously firstOrNull on a combined list returned btn_cross and made btn_a unreachable.
        assertNotEquals("btn_cross", hit.id)
    }
}
