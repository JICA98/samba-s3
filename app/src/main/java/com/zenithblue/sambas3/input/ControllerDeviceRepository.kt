package com.zenithblue.sambas3.input

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Emits gamepads/joysticks and external keyboards; follows hot-plug changes. */
class ControllerDeviceRepository(context: Context) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private val _devices = MutableStateFlow(readConnected())
    val devices: StateFlow<List<ConnectedInputDevice>> = _devices.asStateFlow()

    /** Raw Android devices retained for callers that still need InputDevice. */
    val rawDevices: StateFlow<List<InputDevice>>
        get() = _raw
    private val _raw = MutableStateFlow(emptyList<InputDevice>())

    fun start() {
        manager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun stop() {
        manager.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        val connected = readConnected()
        _devices.value = connected
        _raw.value = connected.mapNotNull { InputDevice.getDevice(it.deviceId) }
        Log.i(TAG, "devices=${connected.size} ${connected.joinToString { "${it.name}(${it.family})" }}")
    }

    private fun readConnected(): List<ConnectedInputDevice> =
        InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> InputDevice.getDevice(id)?.let(::toConnected) }
            .distinctBy { it.deviceKey }

    companion object {
        private const val TAG = "S3INPUT"

        fun toConnected(device: InputDevice): ConnectedInputDevice? {
            val name = device.name.orEmpty()
            if (name.isBlank()) return null
            if (isSystemNoiseDevice(name)) return null
            val (type, family) = ControllerClassifier.classifyDevice(device)
            if (type != InputDeviceType.GAMEPAD && type != InputDeviceType.KEYBOARD && type != InputDeviceType.TOUCH) {
                return null
            }
            // Built-in soft keyboards / virtual non-gamepads are noisy — keep virtual touch only.
            if (device.isVirtual && type == InputDeviceType.KEYBOARD) return null
            return ConnectedInputDevice(
                deviceId = device.id,
                descriptor = device.descriptor,
                name = name,
                vendorId = device.vendorId,
                productId = device.productId,
                isVirtual = device.isVirtual,
                sources = device.sources,
                transport = null,
                type = type,
                family = family,
            )
        }

        /** OEM power/haptic/stylus nodes that expose SOURCE_KEYBOARD but are not user keyboards. */
        fun isSystemNoiseDevice(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("gpio") ||
                n.contains("pwrkey") ||
                n.contains("pogo") ||
                n.contains("pmic") ||
                n.contains("resin") ||
                n.contains("haptic") ||
                n.contains("headset jack") ||
                n.contains("button jack") ||
                n.contains("touchpanel") ||
                n.contains("stylus") ||
                n == "qwerty" ||
                n.contains("uinput-fpc") ||
                n.contains("uinput-goodix")
        }

        /** Pure helper for tests / offline classification without InputDevice. */
        fun fromMetadata(
            deviceId: Int,
            name: String,
            vendorId: Int = 0,
            productId: Int = 0,
            descriptor: String? = null,
            sources: Int = 0,
            isVirtual: Boolean = false,
            transport: String? = null,
            keyboardType: Int = 0,
            motionAxes: Set<Int> = emptySet(),
        ): ConnectedInputDevice {
            val (type, family) = ControllerClassifier.classify(
                name = name,
                vendorId = vendorId,
                productId = productId,
                sources = sources,
                isVirtual = isVirtual,
                keyboardType = keyboardType,
                motionAxes = motionAxes,
            )
            return ConnectedInputDevice(
                deviceId = deviceId,
                descriptor = descriptor,
                name = name,
                vendorId = vendorId,
                productId = productId,
                isVirtual = isVirtual,
                sources = sources,
                transport = transport,
                type = type,
                family = family,
            )
        }
    }
}
