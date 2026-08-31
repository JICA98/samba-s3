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
import com.zenithblue.sambas3.session.EmulatorStopState

@Composable
fun StopFailureCard(
    state: EmulatorStopState.Failed,
    onRecheck: () -> Unit,
    onViewLogs: () -> Unit,
    onForceClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
    ) {
        Column(
            modifier = Modifier.background(RPCSXColors.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("EMULATOR DID NOT STOP", color = RPCSXColors.errorColor)
            Text(
                "Native state: ${state.nativeState ?: "Unknown"}. ${state.message}",
                color = Color.White,
            )
            Text(
                "Launching is disabled until the emulator reaches Stopped.",
                color = RPCSXColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onRecheck) { Text("RECHECK") }
                OutlinedButton(onClick = onViewLogs) { Text("VIEW LOGS") }
                OutlinedButton(onClick = onForceClose) { Text("FORCE CLOSE APP") }
            }
        }
    }
}
