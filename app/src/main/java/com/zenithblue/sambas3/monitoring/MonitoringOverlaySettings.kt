package com.zenithblue.sambas3.monitoring

import android.content.Context
import androidx.core.content.edit

enum class MonitoringPreset { Minimal, Performance, Developer }
enum class MonitoringPosition { TopLeft, TopCenter, TopRight, BottomLeft, BottomCenter, BottomRight }

data class MonitoringSettings(
    val enabled: Boolean = false,
    val preset: MonitoringPreset = MonitoringPreset.Performance,
    val position: MonitoringPosition = MonitoringPosition.TopLeft,
    val updateMs: Long = 300L,
    val opacity: Float = .82f,
    val showGraphs: Boolean = false,
    val hideWithMenu: Boolean = true
)

object MonitoringOverlaySettings {
    private const val PREFS = "monitoring_overlay"
    fun read(context: Context): MonitoringSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MonitoringSettings(
            enabled = p.getBoolean("enabled", false),
            preset = runCatching { MonitoringPreset.valueOf(p.getString("preset", MonitoringPreset.Performance.name)!!) }.getOrDefault(MonitoringPreset.Performance),
            position = runCatching { MonitoringPosition.valueOf(p.getString("position", MonitoringPosition.TopLeft.name)!!) }.getOrDefault(MonitoringPosition.TopLeft),
            updateMs = p.getLong("updateMs", 300L), opacity = p.getFloat("opacity", .82f),
            showGraphs = p.getBoolean("graphs", false), hideWithMenu = p.getBoolean("hideWithMenu", true)
        )
    }
    fun write(context: Context, settings: MonitoringSettings) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("enabled", settings.enabled).putString("preset", settings.preset.name).putString("position", settings.position.name)
            .putLong("updateMs", settings.updateMs).putFloat("opacity", settings.opacity).putBoolean("graphs", settings.showGraphs).putBoolean("hideWithMenu", settings.hideWithMenu)
    }
}
