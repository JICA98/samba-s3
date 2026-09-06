package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBrokerTest {
    @Test
    fun merges_multiple_sources_with_stable_sequence() {
        val engine = LogBrokerEngine(ringCapacity = 50)
        engine.emit("a", LogSourceKind.APP_ANDROID)
        engine.emit("b", LogSourceKind.RPCSX_BACKEND)
        engine.emit("c", LogSourceKind.VULKAN)
        val snap = engine.snapshot.value
        assertEquals(3, snap.size)
        assertEquals(listOf(1L, 2L, 3L), snap.map { it.sequence })
        assertEquals(LogSourceKind.APP_ANDROID, snap[0].source)
        assertEquals(LogSourceKind.VULKAN, snap[2].source)
    }

    @Test
    fun ring_buffer_caps_and_counts_drops() {
        val engine = LogBrokerEngine(ringCapacity = 3)
        repeat(5) { engine.emit("line$it", LogSourceKind.APP_ANDROID) }
        val snap = engine.snapshot.value
        assertEquals(3, snap.size)
        assertEquals("line2", snap.first().message)
        assertEquals("line4", snap.last().message)
        assertEquals(2L, engine.droppedCount())
    }

    @Test
    fun display_pause_does_not_stop_collection() {
        val engine = LogBrokerEngine()
        engine.emit("one", LogSourceKind.APP_ANDROID)
        engine.displayPaused = true
        val pausedSnap = engine.snapshot.value
        engine.emit("two", LogSourceKind.APP_ANDROID)
        engine.emit("three", LogSourceKind.APP_ANDROID)
        assertEquals(pausedSnap, engine.snapshot.value)
        assertEquals(3, engine.currentRing().size)
        engine.displayPaused = false
        engine.publish()
        assertEquals(3, engine.snapshot.value.size)
    }

    @Test
    fun source_status_transitions() {
        val engine = LogBrokerEngine()
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Waiting)
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Active(12, 400))
        val status = engine.status.value[LogSourceKind.RPCSX_BACKEND]
        assertTrue(status is LogSourceStatus.Active)
        assertEquals(12L, (status as LogSourceStatus.Active).lines)
    }

    @Test
    fun snapshot_contains_emitted_batch() {
        val engine = LogBrokerEngine()
        engine.emit("x", LogSourceKind.GAME, level = LogLevel.ERROR, tag = "cellGame")
        assertEquals("x", engine.snapshot.value.single().message)
        assertEquals("cellGame", engine.snapshot.value.single().tag)
    }
}
