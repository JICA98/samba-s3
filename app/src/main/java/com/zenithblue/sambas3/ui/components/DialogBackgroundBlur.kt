package com.zenithblue.sambas3.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Applies real platform blur behind a Compose dialog on Android 12+. */
@Composable
fun DialogBackgroundBlur(radius: Int = 32) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view, radius) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = radius
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}
