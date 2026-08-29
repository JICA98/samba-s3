package com.zenithblue.sambas3

import android.graphics.SurfaceTexture
import android.view.Surface
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SurfaceLeaseManagerTest {
    private fun testSurface() = Surface(SurfaceTexture(0))

    @Test
    fun replacement_releases_old_before_creating_new_generation() {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val events = mutableListOf<Pair<Int, Long>>()
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, event, generation ->
            events += event to generation
            true
        })
        manager.installInitial()
        val old = manager.currentFrame!!
        manager.onSurfaceCreated(old, old.generation, testSurface())

        var ready = 0
        manager.replace { ready++ }
        assertEquals(old, manager.currentFrame)
        manager.onSurfaceDestroyed(old, old.generation, testSurface())

        val fresh = manager.currentFrame!!
        assertNotEquals(old.generation, fresh.generation)
        assertEquals(listOf(0 to old.generation, 2 to old.generation), events)
        manager.onSurfaceCreated(fresh, fresh.generation, testSurface())
        assertEquals(1, ready)
        assertEquals(listOf(0 to old.generation, 2 to old.generation, 0 to fresh.generation), events)
    }

    @Test
    fun stale_destroy_does_not_clear_new_generation() {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val events = mutableListOf<Pair<Int, Long>>()
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, event, generation ->
            events += event to generation
            true
        })
        manager.installInitial()
        val old = manager.currentFrame!!
        manager.onSurfaceCreated(old, old.generation, testSurface())
        manager.replace {}
        manager.onSurfaceDestroyed(old, old.generation, testSurface())
        val fresh = manager.currentFrame!!
        manager.onSurfaceCreated(fresh, fresh.generation, testSurface())

        manager.onSurfaceDestroyed(old, old.generation, testSurface())

        assertEquals(fresh, manager.currentFrame)
        assertEquals(fresh.generation, manager.currentGeneration)
        assertTrue(events.none { it.first == 2 && it.second == fresh.generation })
    }

    @Test
    fun native_destroy_failure_reports_and_does_not_advance() {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        var failure: String? = null
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, event, _ -> event != 2 })
        manager.onFailure = { failure = it }
        manager.installInitial()
        val old = manager.currentFrame!!
        manager.onSurfaceCreated(old, old.generation, testSurface())
        manager.replace {}
        manager.onSurfaceDestroyed(old, old.generation, testSurface())

        assertEquals(old, manager.currentFrame)
        assertTrue(failure?.startsWith("native-destroy-failed") == true)
    }
}
