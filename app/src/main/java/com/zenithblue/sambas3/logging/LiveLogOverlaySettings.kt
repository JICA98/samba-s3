package com.zenithblue.sambas3.logging

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class LiveLogOverlaySettingsData(
    val enabled: Boolean = false,
    val editMode: Boolean = false,
    val xFraction: Float = 0.04f,
    val yFraction: Float = 0.08f,
    val widthFraction: Float = 0.46f,
    val heightFraction: Float = 0.28f,
    val fontScale: Float = 1.0f,
    val opacity: Float = 0.72f,
    val maxLines: Int = 40,
    val minLevel: String = "INFO",
    val sourceFilter: String = "ALL",
    val autoscroll: Boolean = true,
    val hideWithMenu: Boolean = false,
    val locked: Boolean = true,
) {
    fun clamped(): LiveLogOverlaySettingsData = copy(
        xFraction = xFraction.coerceIn(0f, 0.70f),
        yFraction = yFraction.coerceIn(0f, 0.85f),
        widthFraction = widthFraction.coerceIn(0.30f, 0.95f),
        heightFraction = heightFraction.coerceIn(0.15f, 0.80f),
        fontScale = fontScale.coerceIn(0.70f, 1.50f),
        opacity = opacity.coerceIn(0.25f, 1.00f),
        maxLines = maxLines.coerceIn(20, 100),
    )

    fun reclampForWindow(widthPx: Int, heightPx: Int): LiveLogOverlaySettingsData {
        val c = clamped()
        val maxX = (1f - c.widthFraction).coerceAtLeast(0f)
        val maxY = (1f - c.heightFraction).coerceAtLeast(0f)
        return c.copy(
            xFraction = c.xFraction.coerceIn(0f, maxX),
            yFraction = c.yFraction.coerceIn(0f, maxY),
        )
    }
}

object LiveLogOverlaySettings {
    private const val PREFS = "live_log_overlay"
    private val changes = MutableStateFlow(LiveLogOverlaySettingsData())
    private val initialized = AtomicBoolean(false)

    fun state(context: Context): StateFlow<LiveLogOverlaySettingsData> {
        if (initialized.compareAndSet(false, true)) changes.value = read(context)
        return changes.asStateFlow()
    }

    fun read(context: Context): LiveLogOverlaySettingsData {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LiveLogOverlaySettingsData(
            enabled = p.getBoolean("enabled", false),
            editMode = p.getBoolean("editMode", false),
            xFraction = p.getFloat("xFraction", 0.04f),
            yFraction = p.getFloat("yFraction", 0.08f),
            widthFraction = p.getFloat("widthFraction", 0.46f),
            heightFraction = p.getFloat("heightFraction", 0.28f),
            fontScale = p.getFloat("fontScale", 1.0f),
            opacity = p.getFloat("opacity", 0.72f),
            maxLines = p.getInt("maxLines", 40),
            minLevel = p.getString("minLevel", "INFO") ?: "INFO",
            sourceFilter = p.getString("sourceFilter", "ALL") ?: "ALL",
            autoscroll = p.getBoolean("autoscroll", true),
            hideWithMenu = p.getBoolean("hideWithMenu", false),
            locked = p.getBoolean("locked", true),
        ).clamped()
    }

    fun write(context: Context, settings: LiveLogOverlaySettingsData) {
        val next = settings.clamped()
        changes.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("enabled", next.enabled)
            putBoolean("editMode", next.editMode)
            putFloat("xFraction", next.xFraction)
            putFloat("yFraction", next.yFraction)
            putFloat("widthFraction", next.widthFraction)
            putFloat("heightFraction", next.heightFraction)
            putFloat("fontScale", next.fontScale)
            putFloat("opacity", next.opacity)
            putInt("maxLines", next.maxLines)
            putString("minLevel", next.minLevel)
            putString("sourceFilter", next.sourceFilter)
            putBoolean("autoscroll", next.autoscroll)
            putBoolean("hideWithMenu", next.hideWithMenu)
            putBoolean("locked", next.locked)
        }
    }

    fun reset(context: Context) = write(context, LiveLogOverlaySettingsData())
}
