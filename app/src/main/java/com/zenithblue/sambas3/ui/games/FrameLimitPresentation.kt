package com.zenithblue.sambas3.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors

internal const val FRAME_LIMIT_PATH = "Video@@Frame limit"

private val PRIMARY_FRAME_LIMITS = listOf("30", "60", "Infinite")

internal data class FrameLimitUiText(val title: String, val description: String)

internal fun frameLimitUiText(value: String): FrameLimitUiText = when (value) {
    "30" -> FrameLimitUiText("30 FPS", "Cap presentation at 30 frames per second")
    "60" -> FrameLimitUiText("60 FPS", "Allow 60 FPS when the game can produce it")
    "Infinite" -> FrameLimitUiText("Uncapped", "Remove the emulator ceiling; game timing still applies")
    "50" -> FrameLimitUiText("50 FPS · advanced", "Cap presentation at 50 frames per second")
    "120" -> FrameLimitUiText("120 FPS · advanced", "Cap presentation at 120 frames per second")
    "Display" -> FrameLimitUiText("Display refresh · advanced", "Match the device display refresh rate")
    "Auto" -> FrameLimitUiText("Auto · legacy", "Follow the emulated vblank rate")
    "PS3 Native" -> FrameLimitUiText("PS3 native pacing · advanced", "Use the title's original console cadence")
    "Off" -> FrameLimitUiText("Limiter off · legacy", "Legacy mode; use Uncapped for no ceiling")
    else -> FrameLimitUiText(value, "Frame pacing mode")
}

/**
 * Keep the three supported mobile presets prominent. A legacy value remains
 * visible only while it is selected, so an existing install can migrate
 * without violating SingleSelectionDialog's current-value contract.
 */
internal fun frameLimitOptions(available: List<String>, current: String?): List<String> = buildList {
    PRIMARY_FRAME_LIMITS.filterTo(this) { it in available }
    current?.takeIf { it in available && it !in this }?.let(::add)
}

@Composable
internal fun FrameLimitOptionRow(value: String, selected: Boolean, onClick: () -> Unit) {
    val copy = frameLimitUiText(value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .selectable(
                selected = selected,
                enabled = true,
                role = Role.RadioButton,
                onClick = onClick
            )
            .background(if (selected) RPCSXColors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                copy.title,
                color = if (selected) RPCSXColors.primary else RPCSXColors.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                copy.description,
                color = RPCSXColors.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
