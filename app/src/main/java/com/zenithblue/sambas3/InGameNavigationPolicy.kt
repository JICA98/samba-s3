package com.zenithblue.sambas3

import android.view.KeyEvent

internal enum class AndroidBackAction {
    OpenMenu,
    DispatchMenuBack,
    FinishActivity,
    Consume
}

internal fun resolveAndroidBackAction(
    recoveryTransitionActive: Boolean,
    menuOpen: Boolean,
    emulatorState: EmulatorState
): AndroidBackAction {
    if (recoveryTransitionActive) return AndroidBackAction.Consume
    if (menuOpen) return AndroidBackAction.DispatchMenuBack
    return when (emulatorState) {
        EmulatorState.Running, EmulatorState.Paused -> AndroidBackAction.OpenMenu
        EmulatorState.Stopped -> AndroidBackAction.FinishActivity
        else -> AndroidBackAction.Consume
    }
}

/** Accepts one physical Guide/Home press and consumes its matching release. */
internal class FrontendHomeKeyGate {
    private var held = false

    fun acceptDown(repeatCount: Int): Boolean {
        if (repeatCount != 0 || held) return false
        held = true
        return true
    }

    fun acceptUp(): Boolean {
        held = false
        return true
    }
}

internal enum class KeyboardRenderAction {
    PsButton,
    HomeButton,
    KeyboardButton,
}

/** Reserved physical-keyboard shortcuts handled by the renderer chrome. */
internal fun resolveKeyboardRenderAction(keyCode: Int): KeyboardRenderAction? = when (keyCode) {
    KeyEvent.KEYCODE_HOME -> KeyboardRenderAction.PsButton
    KeyEvent.KEYCODE_F1 -> KeyboardRenderAction.HomeButton
    KeyEvent.KEYCODE_F2 -> KeyboardRenderAction.KeyboardButton
    else -> null
}
