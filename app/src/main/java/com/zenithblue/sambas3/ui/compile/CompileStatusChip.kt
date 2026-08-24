package com.zenithblue.sambas3.ui.compile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.AppTypography
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors

/**
 * Compile status chip used by the launcher home page for runtime and install
 * compilation. When both domains are active both lines are shown — PPU is not
 * allowed to hide shader status.
 */
@Composable
fun CompileStatusChip(
    state: CompileProgressBridge.CompileState,
    modifier: Modifier = Modifier,
) {
    if (!state.isActive) return

    Surface(
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.94f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, RPCSXColors.focusRing.copy(alpha = 0.45f)),
        modifier = modifier.widthIn(max = 520.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.ppuActive) {
                Text(
                    text = stringResource(R.string.compiling_ppu_title),
                    style = AppTypography.labelMedium,
                    color = RPCSXColors.primary
                )
                LinearProgressIndicator(
                    progress = { (state.ppuPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = RPCSXColors.primary,
                    trackColor = RPCSXColors.surfaceOverlay
                )
                Text(
                    text = state.ppuMsg ?: stringResource(R.string.compiling_ppu_title),
                    style = AppTypography.labelSmall,
                    color = RPCSXColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.shaderActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = RPCSXColors.primary
                    )
                    Text(
                        text = stringResource(R.string.compiling_shaders_title),
                        style = AppTypography.labelMedium,
                        color = RPCSXColors.primary
                    )
                    ShaderPulseDot()
                }
                Text(
                    text = state.shaderMsg ?: stringResource(R.string.compiling_shaders_desc),
                    style = AppTypography.labelSmall,
                    color = RPCSXColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ShaderPulseDot() {
    val pulse by rememberInfiniteTransition(label = "shader-dot").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shader-dot-alpha"
    )
    Surface(
        color = RPCSXColors.primary,
        shape = CircleShape,
        modifier = Modifier
            .size(8.dp)
            .alpha(pulse)
    ) {}
}
