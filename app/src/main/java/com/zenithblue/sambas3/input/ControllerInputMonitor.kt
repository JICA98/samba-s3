package com.zenithblue.sambas3.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControllerMonitorMode { Off, Test, Capture }

sealed interface ControllerMonitorSession {
    data object Off : ControllerMonitorSession
    data class Test(val deviceKey: String, val deviceId: Int, val profile: ControllerProfile) : ControllerMonitorSession
    data class Capture(
        val deviceKey: String,
        val deviceId: Int,
        val target: LogicalControl,
        val profile: ControllerProfile,
    ) : ControllerMonitorSession
}

data class ControllerInputEvent(
    val keyCode: Int,
    val down: Boolean,
    val deviceId: Int = -1,
    val deviceKey: String? = null,
    val source: Int = 0,
)

/** Process-wide observation scoped to one selected device. */
object ControllerInputMonitor {
    private var mapper = GamepadMapper(
        ControllerProfile(digitalBindings = FamilyDefaultMappings.gamepadDefaults()),
    )
    private var session: ControllerMonitorSession = ControllerMonitorSession.Off
    private val _state = MutableStateFlow(LogicalPadState())
    private val _testState = MutableStateFlow(ControllerTestState())
    private val _events = MutableSharedFlow<ControllerInputEvent>(extraBufferCapacity = 32)

    val state: StateFlow<LogicalPadState> = _state.asStateFlow()
    val testState: StateFlow<ControllerTestState> = _testState.asStateFlow()
    val events: SharedFlow<ControllerInputEvent> = _events.asSharedFlow()

    fun setMode(next: ControllerMonitorMode) {
        session = when (next) {
            ControllerMonitorMode.Off -> ControllerMonitorSession.Off
            ControllerMonitorMode.Test -> ControllerMonitorSession.Test("*", -1, mapper.profile())
            ControllerMonitorMode.Capture -> ControllerMonitorSession.Capture("*", -1, LogicalControl.CROSS, mapper.profile())
        }
        resetState()
    }

    fun startTest(deviceKey: String, deviceId: Int, profile: ControllerProfile) {
        mapper = GamepadMapper(profile)
        session = ControllerMonitorSession.Test(deviceKey, deviceId, profile)
        resetState(deviceKey)
        android.util.Log.i("S3PADTEST", "session start deviceKey=$deviceKey deviceId=$deviceId profile=${profile.id}")
    }

    fun startCapture(deviceKey: String, deviceId: Int, target: LogicalControl, profile: ControllerProfile) {
        mapper = GamepadMapper(profile)
        session = ControllerMonitorSession.Capture(deviceKey, deviceId, target, profile)
        resetState(deviceKey)
        android.util.Log.i("S3PADTEST", "capture start deviceKey=$deviceKey target=${target.name}")
    }

    fun stopSession() {
        if (session !is ControllerMonitorSession.Off) android.util.Log.i("S3PADTEST", "session stop")
        session = ControllerMonitorSession.Off
        resetState()
    }

    /** Controls cleanup must not tear down a dedicated Test session during navigation. */
    fun stopCaptureIfActive() {
        if (session is ControllerMonitorSession.Capture) stopSession()
    }

    fun currentSession(): ControllerMonitorSession = session
    fun currentMode(): ControllerMonitorMode = when (session) {
        ControllerMonitorSession.Off -> ControllerMonitorMode.Off
        is ControllerMonitorSession.Test -> ControllerMonitorMode.Test
        is ControllerMonitorSession.Capture -> ControllerMonitorMode.Capture
    }

    /** True while Test/Capture owns physical input; the Activity uses this to consume it. */
    fun consumesPhysicalInput(): Boolean = session !is ControllerMonitorSession.Off

    fun acceptsDevice(deviceId: Int): Boolean = when (val active = session) {
        ControllerMonitorSession.Off -> false
        is ControllerMonitorSession.Test -> active.deviceId < 0 || deviceId < 0 || active.deviceId == deviceId
        is ControllerMonitorSession.Capture -> active.deviceId < 0 || deviceId < 0 || active.deviceId == deviceId
    }

    /** Legacy/test entry point; production callers should use [observeKey]. */
    fun key(mapper: GamepadMapper, event: KeyEvent, down: Boolean) {
        if (session is ControllerMonitorSession.Off || !acceptsDevice(event.deviceId)) return
        handleKey(mapper, event, down, event.deviceId, event.device?.let(ControllerDeviceRepository::toConnected)?.deviceKey)
    }

    fun observeKey(event: KeyEvent, down: Boolean): Boolean {
        if (session is ControllerMonitorSession.Off) return false
        if (!acceptsDevice(event.deviceId)) return true
        handleKey(mapper, event, down, event.deviceId, event.device?.let(ControllerDeviceRepository::toConnected)?.deviceKey)
        return true
    }

    fun motion(mapper: GamepadMapper, event: MotionEvent) {
        if (session is ControllerMonitorSession.Off || !acceptsDevice(event.deviceId)) return
        handleMotion(mapper, event, event.deviceId)
    }

    fun observeMotion(event: MotionEvent): Boolean {
        if (session is ControllerMonitorSession.Off) return false
        if (!acceptsDevice(event.deviceId)) return true
        handleMotion(mapper, event, event.deviceId)
        return true
    }

    fun reloadProfile() { reloadProfile(ControllerProfileRepository.load()) }

    fun reloadProfile(profile: ControllerProfile) {
        mapper = GamepadMapper(profile)
        when (val active = session) {
            ControllerMonitorSession.Off -> Unit
            is ControllerMonitorSession.Test -> session = active.copy(profile = profile)
            is ControllerMonitorSession.Capture -> session = active.copy(profile = profile)
        }
        if (session !is ControllerMonitorSession.Off) resetState(_testState.value.deviceKey)
    }

    fun activeProfile(): ControllerProfile = mapper.profile()

    private fun handleKey(mapper: GamepadMapper, event: KeyEvent, down: Boolean, deviceId: Int, deviceKey: String?) {
        if (down && event.repeatCount != 0) return
        val action = mapper.actionLabelForKey(event.keyCode)
        val logical = mapper.logicalForKey(event.keyCode)
        val nextPad = if (down) mapper.keyDown(event.keyCode) else mapper.keyUp(event.keyCode)
        if (nextPad != null) _state.value = nextPad else _state.value = mapper.current()
        _events.tryEmit(ControllerInputEvent(event.keyCode, down, deviceId, deviceKey, event.source))

        val activeTest = session as? ControllerMonitorSession.Test ?: return
        val previous = _testState.value
        val pressed = previous.pressedPhysicalKeys.toMutableSet().apply {
            if (down) add(event.keyCode) else remove(event.keyCode)
        }
        val logicalPressed = previous.pressedLogicalControls.toMutableSet().apply {
            if (logical != null) {
                if (down) add(logical) else remove(logical)
            }
        }
        val unmapped = previous.unmappedPhysicalInputs.toMutableList()
        val existing = unmapped.firstOrNull { it.keyCode == event.keyCode }
        if (action == null && down && existing == null) {
            unmapped += TestPhysicalInput(event.keyCode, PhysicalInputLabelFormatter.key(event.keyCode), null)
            android.util.Log.i("S3KEYBOARD", "unmapped key deviceId=$deviceId key=${event.keyCode}")
        } else if (!down) {
            unmapped.removeAll { it.keyCode == event.keyCode }
        }
        _testState.value = previous.copy(
            deviceKey = activeTest.deviceKey,
            pressedPhysicalKeys = pressed,
            pressedLogicalControls = logicalPressed,
            unmappedPhysicalInputs = unmapped,
            pad = _state.value,
            lastInput = if (down) TestInputDisplay(
                PhysicalInputLabelFormatter.key(event.keyCode),
                action ?: "Unmapped input",
            ) else previous.lastInput,
        )
        android.util.Log.d("S3PADTEST", "key deviceId=$deviceId key=${event.keyCode} down=$down action=${action ?: "UNMAPPED"}")
    }

    private fun handleMotion(mapper: GamepadMapper, event: MotionEvent, deviceId: Int) {
        if (event.action != MotionEvent.ACTION_MOVE) return
        _state.value = mapper.motion(event)
        val activeTest = session as? ControllerMonitorSession.Test ?: return
        val pad = _state.value
        _testState.value = _testState.value.copy(
            deviceKey = activeTest.deviceKey,
            pad = pad,
            lastInput = TestInputDisplay("Analog", "LX/LY/RX/RY"),
        )
        android.util.Log.d("S3PADTEST", "motion deviceId=$deviceId lx=${pad.leftX},${pad.leftY} rx=${pad.rightX},${pad.rightY}")
    }

    private fun resetState(deviceKey: String? = null) {
        _state.value = LogicalPadState()
        _testState.value = ControllerTestState(deviceKey = deviceKey)
    }
}
