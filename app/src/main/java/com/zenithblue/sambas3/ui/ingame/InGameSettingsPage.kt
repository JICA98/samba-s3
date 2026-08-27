package com.zenithblue.sambas3.ui.ingame

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.ui.settings.AdvancedSettingsScreen
import com.zenithblue.sambas3.ui.settings.getNestedSettings
import com.zenithblue.sambas3.ui.settings.isAdvancedSettingsRoute
import com.zenithblue.sambas3.ui.settings.normalizeAdvancedSettingsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Transactional in-game settings page.
 * - Starts backend session on entry (beginInGameSettingsSession)
 * - All edits go through settingsSetTransient (no immediate persist)
 * - Square SAVE commits, Triangle DISCARD restores, Back prompts if dirty
 */
@Composable
fun InGameSettingsPage(
    controller: InGameMenuController,
    gamePath: String? = null
) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDirtyDialog by remember { mutableStateOf(false) }
    var pendingBack by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Begin session (if not already)
        withContext(Dispatchers.IO) {
            try { RPCSX.instance.beginInGameSettingsSession() } catch (_: Exception) {}
        }
        tree = withContext(Dispatchers.IO) {
            try {
                JSONObject(RPCSX.instance.settingsGet(""))
            } catch (e: Exception) {
                null
            }
        }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            // If still dirty and page popped without explicit save/discard, session remains; controller handles.
        }
    }

    BackHandler(enabled = true) {
        // If dirty, show dialog; else pop
        val dirty = try { RPCSX.instance.hasDirtyInGameSettings() } catch (_: Exception) { controller.hasDirtySettings }
        if (dirty) {
            showDirtyDialog = true
            pendingBack = true
        } else {
            if (!controller.back()) {
                // If back not consumed due to dirty (should not happen), show dialog
                showDirtyDialog = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(RPCSXColors.background)) {
        if (loading || tree == null) {
            CircularProgressIndicator(
                color = RPCSXColors.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val loaded = tree!!
            val currentPath = controller.settingsBackstack.lastOrNull() ?: ""
            val node = remember(loaded, currentPath) { getNestedSettings(loaded, currentPath) }
            Column(modifier = Modifier.fillMaxSize()) {
                AdvancedSettingsScreen(
                    modifier = Modifier.weight(1f),
                    navigateBack = {
                        val dirty = try { RPCSX.instance.hasDirtyInGameSettings() } catch (_: Exception) { controller.hasDirtySettings }
                        if (dirty) {
                            showDirtyDialog = true
                            pendingBack = true
                        } else {
                            if (controller.settingsBackstack.size > 1) {
                                controller.settingsBackstack.removeAt(controller.settingsBackstack.lastIndex)
                            } else {
                                // Back to menu: end session if not dirty
                                try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
                                controller.back()
                            }
                        }
                    },
                    navigateTo = { route ->
                        if (isAdvancedSettingsRoute(route)) {
                            controller.settingsBackstack.add(normalizeAdvancedSettingsPath(route))
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
                    onValueCommitted = { _, _ ->
                        // Update dirty from backend
                        val dirty = try { RPCSX.instance.hasDirtyInGameSettings() } catch (_: Exception) { false }
                        controller.setDirty(dirty)
                    },
                    settingsSetter = { pathArg, value ->
                        val ok = try { RPCSX.instance.settingsSetTransient(pathArg, value) } catch (e: Exception) { false }
                        if (ok) {
                            val dirty = try { RPCSX.instance.hasDirtyInGameSettings() } catch (_: Exception) { true }
                            controller.setDirty(dirty)
                        }
                        ok
                    }
                )
                // Footer Save/Discard when dirty
                val isDirty = try { RPCSX.instance.hasDirtyInGameSettings() } catch (_: Exception) { controller.hasDirtySettings }
                if (isDirty) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val ok = try { RPCSX.instance.commitInGameSettingsSession() } catch (_: Exception) { false }
                                if (ok) {
                                    try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
                                    controller.onSettingsSaved()
                                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                                } else {
                                    AlertDialogQueue.showDialog("Error", "Failed to save settings")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("□ SAVE") }
                        TextButton(
                            onClick = {
                                val ok = try { RPCSX.instance.discardInGameSettingsSession() } catch (_: Exception) { false }
                                if (ok) {
                                    try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
                                    // Reload tree from backend after discard
                                    tree = try { JSONObject(RPCSX.instance.settingsGet("")) } catch (_: Exception) { tree }
                                    controller.onSettingsDiscarded()
                                    Toast.makeText(context, "Changes discarded", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("△ DISCARD") }
                    }
                }
            }
        }
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }

    if (showDirtyDialog) {
        AlertDialog(
            onDismissRequest = { showDirtyDialog = false; pendingBack = false },
            title = { Text(stringResource(R.string.ingame_save_changes)) },
            text = { Text(stringResource(R.string.ingame_save_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDirtyDialog = false
                    val ok = try { RPCSX.instance.commitInGameSettingsSession() } catch (_: Exception) { false }
                    try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
                    if (pendingBack) {
                        pendingBack = false
                        controller.onSettingsSaved()
                    }
                }) { Text(stringResource(R.string.ingame_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDirtyDialog = false
                        val ok = try { RPCSX.instance.discardInGameSettingsSession() } catch (_: Exception) { false }
                        try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
                        tree = try { JSONObject(RPCSX.instance.settingsGet("")) } catch (_: Exception) { tree }
                        if (pendingBack) {
                            pendingBack = false
                            controller.onSettingsDiscarded()
                        }
                    }) { Text(stringResource(R.string.ingame_discard)) }
                    TextButton(onClick = { showDirtyDialog = false; pendingBack = false }) { Text("Cancel") }
                }
            }
        )
    }
}

// Legacy overload kept for old callers (not used by new controller host)
@Composable
fun InGameSettingsPage(
    uiState: InGameUiState,
    onBackToMenu: () -> Unit
) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        tree = withContext(Dispatchers.IO) {
            try { JSONObject(RPCSX.instance.settingsGet("")) } catch (_: Exception) { null }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(RPCSXColors.background)) {
        val loaded = tree
        if (loaded == null) {
            CircularProgressIndicator(color = RPCSXColors.primary, modifier = Modifier.align(Alignment.Center))
        } else {
            val currentPath = uiState.settingsBackstack.lastOrNull() ?: ""
            val node = remember(loaded, currentPath) { getNestedSettings(loaded, currentPath) }
            AdvancedSettingsScreen(
                navigateBack = { if (!uiState.popSubPage()) onBackToMenu() },
                navigateTo = { route ->
                    if (isAdvancedSettingsRoute(route)) {
                        uiState.settingsBackstack.add(normalizeAdvancedSettingsPath(route))
                    } else {
                        Toast.makeText(context, context.getString(R.string.setting_available_in_launcher), Toast.LENGTH_SHORT).show()
                    }
                },
                settings = node,
                path = currentPath,
                isInSplitPane = false,
                onValueCommitted = { _, _ -> }
            )
        }
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }
}
