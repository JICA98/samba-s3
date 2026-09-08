package com.zenithblue.sambas3.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveLogOverlaySettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("live_log_overlay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun clamps_geometry_font_opacity() {
        val raw = LiveLogOverlaySettingsData(
            xFraction = -1f,
            yFraction = 2f,
            widthFraction = 0.01f,
            heightFraction = 1.5f,
            fontScale = 0.1f,
            opacity = 0.01f,
        ).clamped()
        assertEquals(0.30f, raw.widthFraction, 0.001f)
        assertEquals(0.80f, raw.heightFraction, 0.001f)
        assertEquals(0.70f, raw.fontScale, 0.001f)
        assertEquals(0.25f, raw.opacity, 0.001f)
        assertTrue(raw.xFraction >= 0f)
        assertTrue(raw.yFraction <= 0.85f)
    }

    @Test
    fun reclamp_after_window_resize() {
        val settings = LiveLogOverlaySettingsData(xFraction = 0.8f, widthFraction = 0.5f, yFraction = 0.9f, heightFraction = 0.4f)
            .reclampForWindow(1000, 500)
        assertTrue(settings.xFraction + settings.widthFraction <= 1.001f)
        assertTrue(settings.yFraction + settings.heightFraction <= 1.001f)
    }

    @Test
    fun reset_defaults() {
        LiveLogOverlaySettings.write(context, LiveLogOverlaySettingsData(enabled = true, opacity = 0.4f))
        LiveLogOverlaySettings.reset(context)
        val read = LiveLogOverlaySettings.read(context)
        assertFalse(read.enabled)
        assertEquals(0.72f, read.opacity, 0.001f)
    }

    @Test
    fun persistence_round_trip() {
        val original = LiveLogOverlaySettingsData(
            enabled = true,
            editMode = true,
            xFraction = 0.1f,
            yFraction = 0.2f,
            widthFraction = 0.4f,
            heightFraction = 0.3f,
            fontScale = 1.2f,
            opacity = 0.5f,
            maxLines = 50,
            minLevel = "WARN",
            sourceFilter = "RPCSX_BACKEND",
            autoscroll = false,
            hideWithMenu = true,
            locked = false,
        )
        LiveLogOverlaySettings.write(context, original)
        val read = LiveLogOverlaySettings.read(context)
        assertEquals(original.enabled, read.enabled)
        assertEquals(original.minLevel, read.minLevel)
        assertEquals(original.sourceFilter, read.sourceFilter)
        assertEquals(original.maxLines, read.maxLines)
        assertEquals(original.locked, read.locked)
        assertEquals(original.widthFraction, read.widthFraction, 0.001f)
    }
}
