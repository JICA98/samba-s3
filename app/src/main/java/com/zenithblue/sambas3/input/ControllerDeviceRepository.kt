package com.zenithblue.sambas3.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Emits only real gamepad/joystick devices and follows hot-plug changes. */
class ControllerDeviceRepository(context: Context) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private val _devices = MutableStateFlow(readDevices())
    val devices: StateFlow<List<InputDevice>> = _devices.asStateFlow()

    fun start() { manager.registerInputDeviceListener(this, null); refresh() }
    fun stop() { manager.unregisterInputDeviceListener(this) }
    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()
    private fun refresh() { _devices.value = readDevices() }
    private fun readDevices(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
        .filter { device -> device.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0 }
        .filter { device -> device.name.isNotBlank() && !device.name.startsWith("gpio-", true) && !device.name.contains("pwrkey", true) && !device.name.contains("pogo", true) }
        .distinctBy { it.descriptor.orEmpty().ifBlank { "${it.vendorId}:${it.productId}:${it.name}" } }
}
