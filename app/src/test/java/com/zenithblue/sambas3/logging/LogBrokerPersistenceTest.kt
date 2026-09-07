package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBrokerPersistenceTest {
    @Test
    fun displayEvictionIsNotPersistDrop() {
        val engine = LogBrokerEngine(ringCapacity = 2)
        repeat(5) { engine.emit("line$it", LogSourceKind.APP_ANDROID) }
        assertEquals(3L, engine.droppedCount())
        assertEquals(0L, engine.persistDroppedCount())
    }

    @Test
    fun boundSessionIdIsPreservedOnEmit() {
        val engine = LogBrokerEngine()
        engine.emit("old", LogSourceKind.APP_ANDROID, boundSessionId = "A")
        engine.emit("new", LogSourceKind.APP_ANDROID, boundSessionId = "B")
        val snap = engine.snapshot.value
        assertEquals("A", snap[0].sessionId)
        assertEquals("B", snap[1].sessionId)
    }

    @Test
    fun hydrateDoesNotRequireActiveSession() {
        val engine = LogBrokerEngine()
        engine.hydrate(
            listOf(
                UnifiedLogEntry(
                    sequence = 0L,
                    sessionId = "hist",
                    timestampMs = null,
                    timestampText = null,
                    monotonicNs = null,
                    level = LogLevel.INFO,
                    tag = "t",
                    message = "old",
                    source = LogSourceKind.APP_ANDROID,
                )
            )
        )
        assertEquals("hist", engine.snapshot.value.single().sessionId)
        assertTrue(engine.snapshot.value.single().sequence > 0L)
    }
}
