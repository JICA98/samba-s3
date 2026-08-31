package com.zenithblue.sambas3.monitoring

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MonitoringPreset { Minimal, Performance, Developer, Custom }
enum class MonitoringPosition { TopLeft, TopCenter, TopRight, BottomLeft, BottomCenter, BottomRight }

data class MonitoringSettings(
    val enabled: Boolean = false,
    val enabledMetrics: Set<MonitoringMetric> = MonitoringPresets.performance,
    val graphMetrics: Set<MonitoringMetric> = emptySet(),
    val position: MonitoringPosition = MonitoringPosition.TopLeft,
    val layout: MonitoringLayout = MonitoringLayout.Compact,
    val updateMs: Long = 300L,
    val opacity: Float = .72f,
    val textScale: Float = .88f,
    val graphHistorySeconds: Int = 10,
    val fpsScaleMode: FpsGraphScale = FpsGraphScale.TargetWindow,
    val hideWithMenu: Boolean = true
) {
    val preset: MonitoringPreset get() = MonitoringPresets.presetFor(enabledMetrics)
    val showGraphs: Boolean get() = graphMetrics.isNotEmpty()
}

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
            enabledMetrics = if (p.contains("enabledMetrics")) decodeMetrics(p.getString("enabledMetrics", "")) else {
                val oldPreset = runCatching { MonitoringPreset.valueOf(p.getString("preset", MonitoringPreset.Performance.name)!!) }.getOrDefault(MonitoringPreset.Performance)
                MonitoringPresets.forPreset(oldPreset)
            },
            graphMetrics = if (p.contains("graphMetrics")) decodeMetrics(p.getString("graphMetrics", "")) else if (p.getBoolean("graphs", false)) setOf(MonitoringMetric.Fps, MonitoringMetric.FrameTime) else emptySet(),
            position = runCatching { MonitoringPosition.valueOf(p.getString("position", MonitoringPosition.TopLeft.name)!!) }.getOrDefault(MonitoringPosition.TopLeft),
            layout = runCatching { MonitoringLayout.valueOf(p.getString("layout", MonitoringLayout.Compact.name)!!) }.getOrDefault(MonitoringLayout.Compact),
            updateMs = p.getLong("updateMs", 300L).coerceIn(250L, 1000L), opacity = p.getFloat("opacity", .72f).coerceIn(.05f, 1f),
            textScale = p.getFloat("textScale", .88f).coerceIn(.75f, 1.25f),
            graphHistorySeconds = p.getInt("graphHistorySeconds", 10).coerceIn(5, 30),
            fpsScaleMode = runCatching { FpsGraphScale.valueOf(p.getString("fpsScaleMode", FpsGraphScale.TargetWindow.name)!!) }.getOrDefault(FpsGraphScale.TargetWindow),
            hideWithMenu = p.getBoolean("hideWithMenu", true)
        )
    }
    fun write(context: Context, settings: MonitoringSettings) {
        changes.value = settings
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("enabled", settings.enabled)
            .putString("enabledMetrics", encodeMetrics(settings.enabledMetrics))
            .putString("graphMetrics", encodeMetrics(settings.graphMetrics))
            .putString("preset", settings.preset.name).putString("position", settings.position.name)
            .putString("layout", settings.layout.name).putLong("updateMs", settings.updateMs)
            .putFloat("opacity", settings.opacity).putFloat("textScale", settings.textScale)
            .putInt("graphHistorySeconds", settings.graphHistorySeconds)
            .putString("fpsScaleMode", settings.fpsScaleMode.name)
            .putBoolean("graphs", settings.showGraphs).putBoolean("hideWithMenu", settings.hideWithMenu)
        }
    }

    private fun encodeMetrics(metrics: Set<MonitoringMetric>): String = metrics.joinToString(",") { it.name }
    private fun decodeMetrics(value: String?): Set<MonitoringMetric> = value.orEmpty().split(',').mapNotNull { name -> runCatching { MonitoringMetric.valueOf(name) }.getOrNull() }.toSet()
}
