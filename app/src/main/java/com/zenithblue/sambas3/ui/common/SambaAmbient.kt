package com.zenithblue.sambas3.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors

/**
 * Shared ambient blurred-glow background (hoisted from SettingsScreen).
 * Vertical gradient + two blurred radial orbs; place behind screen content.
 */
@Composable
fun SambaAmbientBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF080B14),
                        Color(0xFF0B101E),
                        Color(0xFF060810)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(x = (-60).dp, y = (-40).dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            RPCSXColors.primary.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(420.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 80.dp, y = 60.dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x281A3660),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}
