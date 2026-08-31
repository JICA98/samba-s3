package com.zenithblue.sambas3.ui.games.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.achievements.AchievementsContent
import com.zenithblue.sambas3.ui.ingame.TrophiesData

@Composable
fun StoppedTrophiesDialog(data: TrophiesData?, loading: Boolean, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(.92f).fillMaxHeight(.86f)
        ) {
            AchievementsContent(data, loading, onDismiss)
        }
    }
}
