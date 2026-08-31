package com.zenithblue.sambas3.input

import android.util.Log
import android.view.InputDevice

/**
 * Classifies connected input devices into controller families using vendor/product
 * IDs and name heuristics. Pure over metadata so JVM unit tests can drive it.
 */
object ControllerClassifier {
    private const val TAG = "S3PAD"

    // Sony
    private val SONY_VIDS = setOf(0x054C)
    // Microsoft
    private val MICROSOFT_VIDS = setOf(0x045E)
    // Nintendo
    private val NINTENDO_VIDS = setOf(0x057E)

    fun classify(
        name: String,
        vendorId: Int = 0,
        productId: Int = 0,
        sources: Int = 0,
        isVirtual: Boolean = false,
    ): Pair<InputDeviceType, ControllerFamily> {
        val n = name.lowercase()
        val type = detectType(n, sources, isVirtual)
        val family = when (type) {
            InputDeviceType.KEYBOARD -> ControllerFamily.KEYBOARD
            InputDeviceType.TOUCH -> ControllerFamily.TOUCH_CONTROLLER
            InputDeviceType.MOUSE -> ControllerFamily.UNKNOWN
            InputDeviceType.UNKNOWN -> ControllerFamily.UNKNOWN
            InputDeviceType.GAMEPAD -> classifyGamepad(n, vendorId, productId)
        }
        Log.i(TAG, "classify name='$name' vid=$vendorId pid=$productId -> $type/$family")
        return type to family
    }

    fun classifyDevice(device: InputDevice): Pair<InputDeviceType, ControllerFamily> =
        classify(
            name = device.name.orEmpty(),
            vendorId = device.vendorId,
            productId = device.productId,
            sources = device.sources,
            isVirtual = device.isVirtual,
        )

    private fun detectType(nameLower: String, sources: Int, isVirtual: Boolean): InputDeviceType {
        if (nameLower.contains("touch") && (nameLower.contains("controller") || nameLower.contains("overlay") || nameLower.contains("virtual"))) {
            return InputDeviceType.TOUCH
        }
        // Use exact source masks — GAMEPAD/KEYBOARD share SOURCE_CLASS_BUTTON, so bitwise OR checks falsely flag keyboards as gamepads.
        val hasGamepad = hasSource(sources, InputDevice.SOURCE_GAMEPAD) || hasSource(sources, InputDevice.SOURCE_JOYSTICK)
        val hasKeyboard = hasSource(sources, InputDevice.SOURCE_KEYBOARD)
        if (hasGamepad) return InputDeviceType.GAMEPAD
        if (hasKeyboard) {
            if (nameLower.startsWith("gpio-") || nameLower.contains("pwrkey") || nameLower.contains("pogo")) {
                return InputDeviceType.UNKNOWN
            }
            return InputDeviceType.KEYBOARD
        }
        if (hasSource(sources, InputDevice.SOURCE_MOUSE)) return InputDeviceType.MOUSE
        if (isVirtual && nameLower.contains("touch")) return InputDeviceType.TOUCH
        // Name-only fallback when sources unavailable (unit tests)
        if (sources == 0) {
            if (looksLikeKeyboard(nameLower)) return InputDeviceType.KEYBOARD
            if (looksLikeGamepad(nameLower)) return InputDeviceType.GAMEPAD
        }
        return InputDeviceType.UNKNOWN
    }

    /** True when [sources] includes the full [mask] (not just the shared class bits). */
    private fun hasSource(sources: Int, mask: Int): Boolean = sources and mask == mask

    private fun classifyGamepad(nameLower: String, vendorId: Int, productId: Int): ControllerFamily {
        if (vendorId in SONY_VIDS || looksPlayStation(nameLower)) return ControllerFamily.PLAYSTATION
        if (vendorId in MICROSOFT_VIDS || looksXbox(nameLower)) return ControllerFamily.XBOX
        if (vendorId in NINTENDO_VIDS || looksNintendo(nameLower)) return ControllerFamily.NINTENDO
        if (looksLikeGamepad(nameLower) || vendorId != 0 || productId != 0) return ControllerFamily.GENERIC_GAMEPAD
        return ControllerFamily.GENERIC_GAMEPAD
    }

    fun looksPlayStation(nameLower: String): Boolean {
        val n = nameLower.lowercase()
        return n.contains("dualshock") || n.contains("dualsense") || n.contains("playstation") ||
            n.contains("sony interactive") || n.contains("wireless controller") && n.contains("sony") ||
            Regex("\\bps[345]\\b").containsMatchIn(n) || n.contains("ds4") || n.contains("ds5")
    }

    fun looksXbox(nameLower: String): Boolean {
        val n = nameLower.lowercase()
        return n.contains("xbox") || n.contains("xinput") || n.contains("microsoft") && n.contains("controller")
    }

    fun looksNintendo(nameLower: String): Boolean {
        val n = nameLower.lowercase()
        return n.contains("nintendo") || n.contains("switch") || n.contains("joy-con") ||
            n.contains("joycon") || n.contains("pro controller")
    }

    private fun looksLikeKeyboard(nameLower: String): Boolean =
        nameLower.contains("keyboard") || nameLower.contains("kbd")

    private fun looksLikeGamepad(nameLower: String): Boolean =
        nameLower.contains("gamepad") || nameLower.contains("controller") ||
            nameLower.contains("joystick") || nameLower.contains("game pad") ||
            looksPlayStation(nameLower) || looksXbox(nameLower) || looksNintendo(nameLower)
}
