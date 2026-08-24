package com.zenithblue.sambas3.ui.ingame

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.ui.settings.AdvancedSettingsScreen
import com.zenithblue.sambas3.ui.settings.getNestedSettings
import com.zenithblue.sambas3.ui.settings.isAdvancedSettingsRoute
import com.zenithblue.sambas3.ui.settings.normalizeAdvancedSettingsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Full-screen in-game settings sheet: loads the engine cfg tree once per open on
 * IO, then reuses [AdvancedSettingsScreen] bound live to settingsGet/settingsSet.
 *
 * Dialogs are hosted INSIDE this overlay composition via
 * `AlertDialogQueue.AlertDialog(respectHostSuppression = false)` so a failed
 * settingsSet always produces a visible dialog during gameplay regardless of the
 * host-suppression flag (review-pass2 MAJOR-1).
 */
@Composable
fun InGameSettingsPage(
    uiState: InGameUiState,
    onBackToMenu: () -> Unit
) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        tree = withContext(Dispatchers.IO) {
            try {
                JSONObject(RPCSX.instance.settingsGet(""))
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(RPCSXColors.background)) {
        val loaded = tree
        if (loaded == null) {
            CircularProgressIndicator(
                color = RPCSXColors.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val currentPath = uiState.settingsBackstack.lastOrNull() ?: ""
            val node = remember(loaded, currentPath) { getNestedSettings(loaded, currentPath) }
            AdvancedSettingsScreen(
                navigateBack = {
                    if (!uiState.popSubPage()) onBackToMenu()
                },
                navigateTo = { route ->
                    if (isAdvancedSettingsRoute(route)) {
                        uiState.settingsBackstack.add(normalizeAdvancedSettingsPath(route))
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
                onValueCommitted = { committedPath, encodedValue ->
                    GameSettingsOverrides.recordGlobal(context, committedPath, encodedValue)
                }
            )
        }

        // In-game dialog host: renders engine-rejection errors during gameplay.
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }
}
