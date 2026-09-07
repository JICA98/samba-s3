package com.zenithblue.sambas3.ui.common

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.settings.ControllerHintStrip

/**
 * Unified top bar for all Settings sub-pages.
 * Mirrors the Settings-root / Advanced top bar: 52dp (48dp compact),
 * translucent #EE090C16 + bottom gold hairline, circled back + glyph badge.
 *
 * Insets: sits below the status bar / notch (statusBars + horizontal safeDrawing).
 */
@Composable
fun SambaTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 48.dp else 52.dp)
            .background(Color(0xEE090C16))
            .drawBehind {
                drawLine(
                    color = Color(0x20C9A84C),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = if (compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0x20C9A84C),
            border = BorderStroke(1.dp, Color(0x35C9A84C)),
            modifier = Modifier
                .size(if (compact) 30.dp else 34.dp)
                .focusProperties { canFocus = false }
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = false }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                    contentDescription = "Back",
                    tint = RPCSXColors.primary,
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                )
            }
        }

        // Controller glyph badge for Back: [ O ]
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = RPCSXColors.textSecondary.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, RPCSXColors.textSecondary.copy(alpha = 0.6f)),
            modifier = Modifier
                .padding(start = 6.dp)
                .focusProperties { canFocus = false }
        ) {
            Text(
                text = "○",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = RPCSXColors.textSecondary
            )
        }

        Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))

        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = RPCSXColors.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Text(
            text = title.uppercase(),
            color = RPCSXColors.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 14.sp else 17.sp,
            letterSpacing = 2.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )

        actions()
    }
}

/**
 * Full-screen scaffold for Settings sub-pages:
 * ambient blur behind, [SambaTopBar] on top (below notch),
 * content in the middle, [ControllerHintStrip] above the gesture nav bar.
 *
 * Handles Circle/BACK → [onBack] via both View.OnKeyListener (gamepad) and
 * Compose preview-key (keyboard), plus optional extra key handling.
 * Requests initial focus so D-pad works immediately.
 */
@Composable
fun SambaScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    actions: @Composable RowScope.() -> Unit = {},
    hints: List<Pair<Int, String>> = listOf(
        R.drawable.cross to "Select",
        R.drawable.circle to "Back"
    ),
    compact: Boolean = false,
    ambient: Boolean = true,
    autoFocus: Boolean = true,
    showHints: Boolean = true,
    /** When false, Circle/BACK is ignored (e.g. remap capture in progress). */
    isBackAllowed: () -> Boolean = { true },
    onGamepadKey: ((keyCode: Int) -> Boolean)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val rootFocus = remember { FocusRequester() }
    val view = LocalView.current

    DisposableEffect(view, onBack, onGamepadKey) {
        val listener = android.view.View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (onGamepadKey?.invoke(keyCode) == true) return@OnKeyListener true
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (!isBackAllowed()) return@OnKeyListener false
                    onBack()
                    true
                }
                else -> false
            }
        }
        view.setOnKeyListener(listener)
        onDispose { view.setOnKeyListener(null) }
    }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            try { rootFocus.requestFocus() } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = keyEvent.nativeKeyEvent.keyCode
                if (onGamepadKey?.invoke(code) == true) return@onPreviewKeyEvent true
                when (code) {
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                        if (!isBackAllowed()) return@onPreviewKeyEvent false
                        onBack()
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (ambient) SambaAmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            SambaTopBar(
                title = title,
                onBack = onBack,
                iconRes = iconRes,
                compact = compact,
                actions = actions
            )
            Box(modifier = Modifier.weight(1f)) {
                content(PaddingValues(0.dp))
            }
            if (showHints) {
                ControllerHintStrip(hints = hints)
            }
        }
    }
}

/**
 * Split-pane body wrapper: horizontal safe insets only (vertical handled by parent).
 */
@Composable
fun SambaSplitBody(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    ) {
        content()
    }
}
