package com.zenithblue.sambas3.ui.settings.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** Activates the component with the gamepad ✕ (A) button when it has focus. */
fun Modifier.gamepadActivate(onClick: () -> Unit): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
    ) {
        onClick()
        true
    } else {
        false
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.safeCombinedClickable(
    debounceTime: Long = 500L,
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = composed {
    var lastClickTime by remember { mutableStateOf(0L) }

    this.combinedClickable(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClickTime > debounceTime) {
                lastClickTime = now
                onClick()
            }
        },
        onLongClick = {
            onLongClick()
        }
    )
}