package com.zenithblue.sambas3.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class ControllerMonitorMode { Off, Test, Capture }
data class ControllerInputEvent(val keyCode: Int, val down: Boolean)

object ControllerInputMonitor {
    private var mapper = GamepadMapper(ControllerProfileRepository.load())
    private var mode = ControllerMonitorMode.Off
    private val _state = MutableStateFlow(LogicalPadState())
    private val _events = MutableSharedFlow<ControllerInputEvent>(extraBufferCapacity = 16)
    val state: StateFlow<LogicalPadState> = _state.asStateFlow()
    val events: SharedFlow<ControllerInputEvent> = _events
    fun setMode(next: ControllerMonitorMode) {
        mode = next
        if (next == ControllerMonitorMode.Off) _state.value = LogicalPadState()
    }
    fun currentMode(): ControllerMonitorMode = mode
    fun key(mapper: GamepadMapper, event: KeyEvent, down: Boolean) {
        if (mode == ControllerMonitorMode.Off) return
        if (mode == ControllerMonitorMode.Capture && down && event.repeatCount == 0) _events.tryEmit(ControllerInputEvent(event.keyCode, true))
        _state.value = (if (down) mapper.keyDown(event.keyCode) else mapper.keyUp(event.keyCode)) ?: mapper.current()
    }
    fun motion(mapper: GamepadMapper, event: MotionEvent) { if (mode != ControllerMonitorMode.Off) _state.value = mapper.motion(event) }
    fun observeKey(event: KeyEvent, down: Boolean) { key(mapper, event, down) }
    fun observeMotion(event: MotionEvent) { motion(mapper, event) }
    fun reloadProfile() { mapper = GamepadMapper(ControllerProfileRepository.load()) }
}
