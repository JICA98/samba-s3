package com.zenithblue.sambas3.ui.crash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.session.EmulatorStopState

@Composable
fun StopFailureCard(
    state: EmulatorStopState.Failed,
    onRecheck: () -> Unit,
    onViewLogs: () -> Unit,
    onForceClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, RPCSXColors.errorColor.copy(alpha = 0.6f)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth(0.85f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "EMULATOR DID NOT STOP",
                    color = RPCSXColors.errorColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Native: ${state.nativeState ?: "Unknown"}",
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                )
            }
            Text(
                state.message.ifBlank { "Launching is disabled until the emulator reaches Stopped." },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onRecheck,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RPCSXColors.primary,
                        contentColor = Color.Black
                    ),
                ) { Text("RECHECK", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = onViewLogs,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp)
                ) { Text("VIEW LOGS", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = onForceClose,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp)
                ) { Text("FORCE CLOSE APP", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
