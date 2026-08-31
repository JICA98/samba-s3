package com.zenithblue.sambas3.ui.crash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.HomeRecoveryState

@Composable
fun CrashRecoveryCard(
    state: HomeRecoveryState,
    onContinueSave: () -> Unit,
    onRetry: () -> Unit,
    onPlayFresh: () -> Unit,
    onChooseSave: () -> Unit,
    onDetails: () -> Unit,
    onViewLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> state.session.gameName ?: state.session.gamePath.substringAfterLast('/')
        is HomeRecoveryState.Interrupted -> state.session.gameName ?: state.session.gamePath.substringAfterLast('/')
        is HomeRecoveryState.LoadFailure -> state.gamePath.substringAfterLast('/')
        is HomeRecoveryState.ActionFailed -> state.session?.gameName ?: "Game"
        is HomeRecoveryState.ActionRunning -> "Game recovery"
        HomeRecoveryState.None -> return
    }
    val isLoadFailure = state is HomeRecoveryState.LoadFailure
    val isRunning = state is HomeRecoveryState.ActionRunning
    val confirmed = state is HomeRecoveryState.ConfirmedCrash
    val message = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> "The emulator reported a fatal error. Likely cause: ${state.report.cause}."
        is HomeRecoveryState.Interrupted -> state.message
        is HomeRecoveryState.LoadFailure -> "Saved slot could not be restored: ${state.reason}"
        is HomeRecoveryState.ActionFailed -> state.message
        is HomeRecoveryState.ActionRunning -> "Preparing emulator..."
        HomeRecoveryState.None -> ""
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
    ) {
        Column(
            modifier = Modifier.background(RPCSXColors.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (isLoadFailure) "LOAD FAILED · $title" else if (confirmed) "$title crashed" else "$title stopped unexpectedly",
                color = if (confirmed || isLoadFailure) RPCSXColors.errorColor else RPCSXColors.primary,
            )
            Text(message, color = Color.White)
            if (isRunning) {
                Text("Working...", color = RPCSXColors.textSecondary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isLoadFailure) {
                        Button(onClick = onRetry) { Text("RETRY SAVE") }
                        OutlinedButton(onClick = onPlayFresh) { Text("PLAY FRESH") }
                    } else {
                        Button(onClick = onContinueSave) { Text("CONTINUE SAVE") }
                        OutlinedButton(onClick = onRetry) { Text("RETRY") }
                    }
                    OutlinedButton(onClick = onChooseSave) { Text("CHOOSE SAVE") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onDetails) { Text("DETAILS") }
                    OutlinedButton(onClick = onViewLogs) { Text("VIEW LOGS") }
                    OutlinedButton(onClick = onDismiss) { Text("DISMISS") }
                }
            }
        }
    }
}
