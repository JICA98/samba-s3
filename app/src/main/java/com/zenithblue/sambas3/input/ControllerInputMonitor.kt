package com.zenithblue.sambas3.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ControllerInputMonitor {
    private var mapper = GamepadMapper(ControllerProfileRepository.load())
    private val _state = MutableStateFlow(LogicalPadState())
    val state: StateFlow<LogicalPadState> = _state.asStateFlow()
    fun key(mapper: GamepadMapper, event: KeyEvent, down: Boolean) {
        _state.value = (if (down) mapper.keyDown(event.keyCode) else mapper.keyUp(event.keyCode)) ?: mapper.current()
    }
    fun motion(mapper: GamepadMapper, event: MotionEvent) { _state.value = mapper.motion(event) }
    fun observeKey(event: KeyEvent, down: Boolean) { key(mapper, event, down) }
    fun observeMotion(event: MotionEvent) { motion(mapper, event) }
    fun reloadProfile() { mapper = GamepadMapper(ControllerProfileRepository.load()) }
}
