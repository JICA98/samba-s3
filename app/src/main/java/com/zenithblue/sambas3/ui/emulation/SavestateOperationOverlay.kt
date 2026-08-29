package com.zenithblue.sambas3.ui.emulation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors

@Composable
fun SavestateOperationOverlay(state: SavestateOperationUiState) {
    if (state is SavestateOperationUiState.Hidden) return
    val transition = rememberInfiniteTransition(label = "savestate-operation")
    val alpha = transition.animateFloat(.65f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = RPCSXColors.primary, modifier = Modifier.size(48.dp).alpha(alpha.value))
            when (state) {
                is SavestateOperationUiState.Saving -> {
                    Text("SAVING SLOT ${state.slot}", color = Color.White)
                    Text(state.stage, color = RPCSXColors.textSecondary)
                }
                is SavestateOperationUiState.Loading -> {
                    Text("LOADING SLOT ${state.slot}", color = Color.White)
                    Text(state.stage, color = RPCSXColors.textSecondary)
                }
                is SavestateOperationUiState.Failed -> {
                    Text("${state.operation} FAILED", color = RPCSXColors.errorColor)
                    Text(state.message, color = RPCSXColors.textSecondary)
                }
                SavestateOperationUiState.Hidden -> Spacer(Modifier.size(1.dp))
            }
        }
    }
}
