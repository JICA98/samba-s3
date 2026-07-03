package com.zenithblue.sambas3.ui.settings.components.base

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.ui.settings.components.util.ComposePreview
import com.zenithblue.sambas3.ui.settings.components.LocalPreferenceState
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceIcon
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceSubtitle
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceTitle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BasePreference(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subContent: @Composable (() -> Unit)? = null,
    value: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(0),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val paddingLeft by androidx.compose.animation.core.animateDpAsState(if (isFocused) 20.dp else 16.dp, label = "paddingLeft")
    val contentColor = if (isFocused) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textPrimary

    CompositionLocalProvider(
        LocalPreferenceState provides enabled
    ) {
        val preferenceOnClick: () -> Unit = {
            if (enabled) onClick()
        }
        Surface(
            modifier = modifier
                .onFocusChanged { isFocused = it.isFocused }
                .combinedClickable(
                    onClick = preferenceOnClick,
                    onLongClick = onLongClick
                )
                .drawBehind {
                    if (isFocused) {
                        drawRect(
                            color = com.zenithblue.sambas3.RPCSXColors.focusRing,
                            topLeft = Offset(0f, 0f),
                            size = Size(4.dp.toPx(), size.height)
                        )
                    }
                },
            shape = shape,
            color = if (isFocused) com.zenithblue.sambas3.RPCSXColors.surfaceOverlay else com.zenithblue.sambas3.RPCSXColors.surface,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = paddingLeft, end = 16.dp)
                        .heightIn(min = 72.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        title()
                        subContent?.invoke()
                        value?.invoke()
                    }
                    trailingContent?.invoke()
                }
            }
        }
    }
}

@Preview
@Composable
private fun BasePreferencePreview() {
    ComposePreview {
        BasePreference(
            title = { PreferenceTitle("Preference Title") },
            subContent = { PreferenceSubtitle("Lorem ipsum dolor sit amet, consectetur adipiscing elit.", maxLines = 2) },
            leadingContent = { PreferenceIcon(painterResource(id = R.drawable.ic_search)) },
            trailingContent = { PreferenceIcon(painterResource(id = R.drawable.ic_keyboard_arrow_right)) },
            onClick = {}
        )
    }
}
