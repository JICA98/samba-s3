package com.zenithblue.sambas3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView

/** Visual-only Compose host. It must never become the touch target. */
class TouchThroughComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {
    private var compositionContent: @Composable () -> Unit by mutableStateOf({})

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            android.util.Log.i("S3TOUCH", "monitor action=down consumed=false")
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
