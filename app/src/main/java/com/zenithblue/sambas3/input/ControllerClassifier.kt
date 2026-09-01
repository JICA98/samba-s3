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

    /** Framework-independent metadata used by both Android classification and JVM tests. */
    data class InputDeviceMetadata(
        val name: String,
        val vendorId: Int = 0,
        val productId: Int = 0,
        val sources: Int = 0,
        val isVirtual: Boolean = false,
        val keyboardType: Int = 0,
        val motionAxes: Set<Int> = emptySet(),
    )

    fun classify(
        name: String,
        vendorId: Int = 0,
        productId: Int = 0,
        sources: Int = 0,
        isVirtual: Boolean = false,
        keyboardType: Int = 0,
        motionAxes: Set<Int> = emptySet(),
    ): Pair<InputDeviceType, ControllerFamily> {
        val n = name.lowercase()
        val metadata = InputDeviceMetadata(
            name = name,
            vendorId = vendorId,
            productId = productId,
            sources = sources,
            isVirtual = isVirtual,
            keyboardType = keyboardType,
            motionAxes = motionAxes,
        )
        return classify(metadata)
    }

    fun classify(metadata: InputDeviceMetadata): Pair<InputDeviceType, ControllerFamily> {
        val n = metadata.name.lowercase()
        val type = detectType(metadata)
        val family = when (type) {
            InputDeviceType.KEYBOARD -> ControllerFamily.KEYBOARD
            InputDeviceType.TOUCH -> ControllerFamily.TOUCH_CONTROLLER
            InputDeviceType.MOUSE -> ControllerFamily.UNKNOWN
            InputDeviceType.UNKNOWN -> ControllerFamily.UNKNOWN
            InputDeviceType.GAMEPAD -> classifyGamepad(n, metadata.vendorId, metadata.productId)
        }
        val reason = when (type) {
            InputDeviceType.KEYBOARD -> keyboardReason(metadata)
            InputDeviceType.GAMEPAD -> "gamepad-capability-or-identity"
            InputDeviceType.TOUCH -> "touch-identity"
            InputDeviceType.MOUSE -> "mouse-source"
            InputDeviceType.UNKNOWN -> "no-supported-identity"
        }
        Log.i(
            TAG,
            "classify name='${metadata.name}' vid=${metadata.vendorId} pid=${metadata.productId} " +
                "sources=${metadata.sources} keyboardType=${metadata.keyboardType} -> $type/$family reason=$reason",
        )
        return type to family
    }

    fun classifyDevice(device: InputDevice): Pair<InputDeviceType, ControllerFamily> =
        classify(InputDeviceMetadata(
            name = device.name.orEmpty(),
            vendorId = device.vendorId,
            productId = device.productId,
            sources = device.sources,
            isVirtual = device.isVirtual,
            keyboardType = device.keyboardType,
            motionAxes = device.motionRanges.map { it.axis }.toSet(),
        ))

    private fun detectType(metadata: InputDeviceMetadata): InputDeviceType {
        val nameLower = metadata.name.lowercase()
        val sources = metadata.sources
        if (nameLower.contains("touch") && (nameLower.contains("controller") || nameLower.contains("overlay") || nameLower.contains("virtual"))) {
            return InputDeviceType.TOUCH
        }

        // Identity wins over capability bits. Some genuine controllers expose SOURCE_KEYBOARD
        // for media/extra buttons, so keyboard-before-gamepad would misclassify those instead.
        val knownGamepad = isKnownGamepad(nameLower, metadata.vendorId, metadata.productId)
        val hasGamepad = hasSource(sources, InputDevice.SOURCE_GAMEPAD) || hasSource(sources, InputDevice.SOURCE_JOYSTICK)
        val hasKeyboard = hasSource(sources, InputDevice.SOURCE_KEYBOARD)
        val strongKeyboard = looksLikeKeyboard(nameLower) ||
            metadata.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

        if (knownGamepad) return InputDeviceType.GAMEPAD
        if (strongKeyboard && hasKeyboard && !metadata.isVirtual && !isKeyboardNoise(nameLower)) {
            return InputDeviceType.KEYBOARD
        }
        if (hasGamepad) return InputDeviceType.GAMEPAD
        if (hasKeyboard) {
            if (isKeyboardNoise(nameLower)) {
                return InputDeviceType.UNKNOWN
            }
            return InputDeviceType.KEYBOARD
        }
        if (hasSource(sources, InputDevice.SOURCE_MOUSE)) return InputDeviceType.MOUSE
        if (metadata.isVirtual && nameLower.contains("touch")) return InputDeviceType.TOUCH
        // Name-only fallback when sources unavailable (unit tests)
        if (sources == 0) {
            if (looksLikeKeyboard(nameLower)) return InputDeviceType.KEYBOARD
            if (looksLikeGamepad(nameLower)) return InputDeviceType.GAMEPAD
        }
        return InputDeviceType.UNKNOWN
    }

    private fun isKnownGamepad(nameLower: String, vendorId: Int, productId: Int): Boolean =
        vendorId in SONY_VIDS || vendorId in MICROSOFT_VIDS || vendorId in NINTENDO_VIDS ||
            looksPlayStation(nameLower) || looksXbox(nameLower) || looksNintendo(nameLower) ||
            (looksLikeGamepad(nameLower) && !looksLikeKeyboard(nameLower))

    private fun isKeyboardNoise(nameLower: String): Boolean =
        nameLower.startsWith("gpio-") || nameLower.contains("pwrkey") || nameLower.contains("pogo")

    private fun keyboardReason(metadata: InputDeviceMetadata): String = buildString {
        if (looksLikeKeyboard(metadata.name.lowercase())) append("strong-keyboard-name+")
        if (metadata.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) append("alphabetic+")
        if (hasSource(metadata.sources, InputDevice.SOURCE_KEYBOARD)) append("keyboard-source")
    }.trimEnd('+').ifBlank { "keyboard-source" }

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
