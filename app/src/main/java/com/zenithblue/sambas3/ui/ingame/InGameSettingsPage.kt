package com.zenithblue.sambas3.ui.ingame

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.ui.settings.AdvancedSettingsScreen
import com.zenithblue.sambas3.ui.settings.getNestedSettings
import com.zenithblue.sambas3.ui.settings.isAdvancedSettingsRoute
import com.zenithblue.sambas3.ui.settings.normalizeAdvancedSettingsPath
import org.json.JSONObject

/**
 * Transactional in-game settings page. Purely presentational: the coordinator
 * owns the single settings session (begin once / edit transient / save once /
 * discard once / end once). No native calls from composition.
 */
@Composable
fun InGameSettingsPage(
    uiState: InGameMenuUiState,
    core: InGameMenuCoreGateway,
    onIntent: (InGameMenuIntent) -> Unit
) {
    val context = LocalContext.current
    val tree = uiState.settingsTreeJson?.let { json ->
        remember(json) { runCatching { JSONObject(json) }.getOrNull() }
    }

    Box(modifier = Modifier.fillMaxSize().background(RPCSXColors.background)) {
        if (uiState.settingsLoading || tree == null) {
            CircularProgressIndicator(
                color = RPCSXColors.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val currentPath = uiState.settingsBackstack.lastOrNull() ?: ""
            val node = remember(tree, currentPath) { getNestedSettings(tree, currentPath) }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RPCSXColors.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SCOPE",
                        color = RPCSXColors.textSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "THIS GAME",
                        color = RPCSXColors.primary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    Text(
                        text = "· applies on next boot",
                        color = RPCSXColors.textSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    if (uiState.settingsWriteError != null) {
                        Text(
                            text = "· ${uiState.settingsWriteError}",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                AdvancedSettingsScreen(
                    modifier = Modifier.weight(1f),
                    navigateBack = {
                        if ((uiState.settingsBackstack.size > 1)) {
                            // Subpage back is handled by coordinator Back; navigateBack maps to Back.
                            onIntent(InGameMenuIntent.Back)
                        } else {
                            onIntent(InGameMenuIntent.Back)
                        }
                    },
                    navigateTo = { route ->
                        if (isAdvancedSettingsRoute(route)) {
                            onIntent(InGameMenuIntent.SettingsNavigate(normalizeAdvancedSettingsPath(route)))
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.setting_available_in_launcher),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    settings = node,
                    path = currentPath,
                    isInSplitPane = false,
                    onValueCommitted = { _, _ -> onIntent(InGameMenuIntent.RequestDirtyCheck) },
                    settingsSetter = { pathArg, value ->
                        // Synchronous adapter: coordinator applies transient set on its scope.
                        // We cannot suspend here; report via intent and optimistically allow.
                        onIntent(InGameMenuIntent.SettingsTransientSet(pathArg, value))
                        true
                    }
                )
                if (uiState.settingsDirty) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onIntent(InGameMenuIntent.SettingsSave) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ingame_save).uppercase()) }
                        TextButton(
                            onClick = { onIntent(InGameMenuIntent.SettingsDiscard) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ingame_discard).uppercase()) }
                    }
                }
            }
        }
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }

    if (uiState.showDirtyDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(InGameMenuIntent.SettingsCancel) },
            title = { Text(stringResource(R.string.ingame_save_changes)) },
            text = { Text(stringResource(R.string.ingame_save_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(InGameMenuIntent.SettingsSaveAndBack)
                }) { Text(stringResource(R.string.ingame_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onIntent(InGameMenuIntent.SettingsDiscardAndBack)
                    }) { Text(stringResource(R.string.ingame_discard)) }
                    TextButton(onClick = { onIntent(InGameMenuIntent.SettingsCancel) }) { Text("Cancel") }
                }
            }
        )
    }
}
