package com.zenithblue.sambas3.ui.settings.components.preference

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceIcon
import com.zenithblue.sambas3.ui.settings.components.safeCombinedClickable
import com.zenithblue.sambas3.ui.settings.components.util.ComposePreview

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePreference(
    icon: @Composable (() -> Unit) = {},
    title: String,
    description: String,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val paddingLeft by animateDpAsState(if (isFocused) 20.dp else 16.dp, label = "paddingLeft")
    val contentColor = if (isFocused) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textPrimary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .safeCombinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind {
                if (isFocused) {
                    drawRect(
                        color = com.zenithblue.sambas3.RPCSXColors.focusRing,
                        topLeft = Offset(0f, 0f),
                        size = Size(4.dp.toPx(), size.height)
                    )
                }
            },
        color = if (isFocused) com.zenithblue.sambas3.RPCSXColors.surfaceOverlay else com.zenithblue.sambas3.RPCSXColors.surface,
        tonalElevation = 0.dp
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = paddingLeft, end = 16.dp)
                    .heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isFocused) com.zenithblue.sambas3.RPCSXColors.primaryMuted else com.zenithblue.sambas3.RPCSXColors.surfaceElevated
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides if (isFocused) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textSecondary
                        ) {
                            icon()
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor
                    )

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFocused) com.zenithblue.sambas3.RPCSXColors.textSecondary else com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
