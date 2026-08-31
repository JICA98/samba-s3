package com.zenithblue.sambas3.monitoring

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val changes = MutableStateFlow(MonitoringSettings())
    fun state(context: Context): StateFlow<MonitoringSettings> {
        // SharedPreferences is the durable source; the flow is the single
        // in-process source used by both the activity and settings screens.
        val stored = read(context)
        if (changes.value != stored) changes.value = stored
        return changes.asStateFlow()
    }
    fun read(context: Context): MonitoringSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MonitoringSettings(
            enabled = p.getBoolean("enabled", false),
            preset = runCatching { MonitoringPreset.valueOf(p.getString("preset", MonitoringPreset.Performance.name)!!) }.getOrDefault(MonitoringPreset.Performance),
            position = runCatching { MonitoringPosition.valueOf(p.getString("position", MonitoringPosition.TopLeft.name)!!) }.getOrDefault(MonitoringPosition.TopLeft),
            updateMs = p.getLong("updateMs", 300L).coerceIn(250L, 1000L), opacity = p.getFloat("opacity", .82f).coerceIn(.2f, 1f),
            showGraphs = p.getBoolean("graphs", false), hideWithMenu = p.getBoolean("hideWithMenu", true)
        )
    }
    fun write(context: Context, settings: MonitoringSettings) {
        changes.value = settings
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("enabled", settings.enabled).putString("preset", settings.preset.name).putString("position", settings.position.name)
            .putLong("updateMs", settings.updateMs).putFloat("opacity", settings.opacity).putBoolean("graphs", settings.showGraphs).putBoolean("hideWithMenu", settings.hideWithMenu)
        }
    }
}
