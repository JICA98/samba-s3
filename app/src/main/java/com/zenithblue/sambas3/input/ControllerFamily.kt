package com.zenithblue.sambas3.input

enum class InputDeviceType {
    GAMEPAD,
    KEYBOARD,
    TOUCH,
    MOUSE,
    UNKNOWN,
}

enum class ControllerFamily {
    PLAYSTATION,
    XBOX,
    NINTENDO,
    KEYBOARD,
    GENERIC_GAMEPAD,
    TOUCH_CONTROLLER,
    UNKNOWN,
}

data class ConnectedInputDevice(
    val deviceId: Int,
    val descriptor: String?,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val isVirtual: Boolean,
    val sources: Int,
    val transport: String?,
    val type: InputDeviceType,
    val family: ControllerFamily,
) {
    val deviceKey: String = ControllerDeviceKey.stableKey(
        descriptor = descriptor,
        vendorId = vendorId,
        productId = productId,
        name = name,
        transport = transport,
        sources = sources,
        deviceId = deviceId,
    )
}
