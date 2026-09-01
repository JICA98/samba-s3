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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

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

    @Test
    fun stale_create_and_change_are_ignored_after_replacement() {
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

        manager.onSurfaceCreated(old, old.generation, testSurface())
        manager.onSurfaceChanged(old, old.generation, testSurface())

        assertEquals(fresh, manager.currentFrame)
        assertEquals(listOf(0 to old.generation, 2 to old.generation), events)
    }

    @Test
    fun temporary_surface_loss_keeps_generation_for_reattach() {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val events = mutableListOf<Pair<Int, Long>>()
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, event, generation ->
            events += event to generation
            true
        })
        manager.installInitial()
        val frame = manager.currentFrame!!
        manager.onSurfaceCreated(frame, frame.generation, testSurface())
        manager.onSurfaceDestroyed(frame, frame.generation, testSurface())
        manager.onSurfaceDestroyed(frame, frame.generation, testSurface())

        assertEquals(frame, manager.currentFrame)
        assertEquals(frame.generation, manager.currentGeneration)
        assertEquals(listOf(0 to frame.generation, 2 to frame.generation), events)

        manager.onSurfaceCreated(frame, frame.generation, testSurface())
        assertEquals(listOf(0 to frame.generation, 2 to frame.generation, 0 to frame.generation), events)
    }

    @Test
    fun replacement_without_surface_waits_for_fresh_surface_only() {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val events = mutableListOf<Pair<Int, Long>>()
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, event, generation ->
            events += event to generation
            true
        })
        manager.installInitial()
        val old = manager.currentFrame!!
        var ready = 0
        manager.replace { ready++ }

        val fresh = manager.currentFrame!!
        assertNotEquals(old.generation, fresh.generation)
        assertEquals(0, ready)
        assertEquals(emptyList<Pair<Int, Long>>(), events)

        manager.onSurfaceCreated(fresh, fresh.generation, testSurface())
        assertEquals(1, ready)
        assertEquals(listOf(0 to fresh.generation), events)
    }

    @Test
    fun await_ready_requires_native_create_and_surface_generation() = runBlocking {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, _, _ -> true })
        manager.installInitial()
        val frame = manager.currentFrame!!
        val waiter = async { manager.awaitReady(1_000L) }
        kotlinx.coroutines.yield()
        assertTrue(!waiter.isCompleted)

        manager.onSurfaceCreated(frame, frame.generation, testSurface())
        assertEquals(SurfaceReadyResult.Ready, waiter.await())
    }

    @Test
    fun await_ready_reports_native_create_failure() = runBlocking {
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        val manager = SurfaceLeaseManager(host, SurfaceLeaseBridge { _, _, _ -> false })
        manager.installInitial()
        val frame = manager.currentFrame!!
        val waiter = async { manager.awaitReady(1_000L) }
        kotlinx.coroutines.yield()
        manager.onSurfaceCreated(frame, frame.generation, testSurface())
        assertEquals(SurfaceReadyResult.Failed, waiter.await())
    }
}
