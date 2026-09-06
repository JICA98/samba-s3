package com.zenithblue.sambas3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView

/**
 * Compose host with two touch policies:
 * - locked: every touch falls through to gameplay/pad
 * - edit: overlay panel consumes touches; empty space still falls through
 */
class InteractivePassThroughComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    private var compositionContent: @Composable () -> Unit by mutableStateOf({})
    var interceptTouches: Boolean = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setContent(value: @Composable () -> Unit) {
        compositionContent = value
    }

    @Composable
    override fun Content() {
        compositionContent()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!interceptTouches) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interceptTouches) return false
        return super.onTouchEvent(event)
    }
}
