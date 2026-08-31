package com.zenithblue.sambas3.input

import android.util.Log
import android.view.KeyEvent
import com.zenithblue.sambas3.Digital1Flags
import com.zenithblue.sambas3.Digital2Flags
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.InputBindingPrefs
import org.json.JSONObject

enum class LogicalControl(val label: String, val bank: Int, val bit: Int) {
    DPAD_UP("D-pad Up", 0, Digital1Flags.CELL_PAD_CTRL_UP.bit),
    DPAD_DOWN("D-pad Down", 0, Digital1Flags.CELL_PAD_CTRL_DOWN.bit),
    DPAD_LEFT("D-pad Left", 0, Digital1Flags.CELL_PAD_CTRL_LEFT.bit),
    DPAD_RIGHT("D-pad Right", 0, Digital1Flags.CELL_PAD_CTRL_RIGHT.bit),
    CROSS("Cross", 1, Digital2Flags.CELL_PAD_CTRL_CROSS.bit),
    CIRCLE("Circle", 1, Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit),
    SQUARE("Square", 1, Digital2Flags.CELL_PAD_CTRL_SQUARE.bit),
    TRIANGLE("Triangle", 1, Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit),
    L1("L1", 1, Digital2Flags.CELL_PAD_CTRL_L1.bit),
    R1("R1", 1, Digital2Flags.CELL_PAD_CTRL_R1.bit),
    L2("L2", 1, Digital2Flags.CELL_PAD_CTRL_L2.bit),
    R2("R2", 1, Digital2Flags.CELL_PAD_CTRL_R2.bit),
    L3("L3", 0, Digital1Flags.CELL_PAD_CTRL_L3.bit),
    R3("R3", 0, Digital1Flags.CELL_PAD_CTRL_R3.bit),
    START("Start", 0, Digital1Flags.CELL_PAD_CTRL_START.bit),
    SELECT("Select", 0, Digital1Flags.CELL_PAD_CTRL_SELECT.bit),
    PS_HOME_FRONTEND("PS / Guide", 0, Digital1Flags.CELL_PAD_CTRL_PS.bit)
}

data class AxisBinding(val axis: Int, val invert: Boolean = false)
data class StickTuning(val deadzone: Float = .12f, val sensitivity: Float = 1f, val invertX: Boolean = false, val invertY: Boolean = false)

data class ControllerProfile(
    val id: String = "default",
    val name: String = "Default",
    val deviceKey: String? = null,
    val deviceDescriptor: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val family: ControllerFamily? = null,
    val digitalBindings: Map<LogicalControl, Int>,
    val leftX: AxisBinding = AxisBinding(MotionAxis.X),
    val leftY: AxisBinding = AxisBinding(MotionAxis.Y),
    val rightX: AxisBinding = AxisBinding(MotionAxis.Z),
    val rightY: AxisBinding = AxisBinding(MotionAxis.RZ),
    val leftTrigger: AxisBinding = AxisBinding(MotionAxis.LTRIGGER),
    val rightTrigger: AxisBinding = AxisBinding(MotionAxis.RTRIGGER),
    val leftStick: StickTuning = StickTuning(),
    val rightStick: StickTuning = StickTuning(),
    val triggerThreshold: Float = .1f,
    val isDefault: Boolean = false,
)

object MotionAxis {
    const val X = 0; const val Y = 1; const val Z = 11; const val RZ = 14
    const val LTRIGGER = 17; const val RTRIGGER = 18; const val HAT_X = 15; const val HAT_Y = 16
}

/**
 * Pure profile selection / migration / fallback — no Android prefs I/O.
 * Driven by unit tests with in-memory profile lists.
 */
object ControllerProfileSelection {
    private const val TAG = "S3PADMAP"

    fun selectForDevice(
        profiles: List<ControllerProfile>,
        deviceKey: String,
        family: ControllerFamily,
        descriptor: String? = null,
        vendorId: Int? = null,
        productId: Int? = null,
        legacyLogicalBindings: Map<LogicalControl, Int> = emptyMap(),
    ): ControllerProfile {
        profiles.firstOrNull { it.deviceKey == deviceKey }?.let {
            Log.i(TAG, "profile hit deviceKey=$deviceKey id=${it.id}")
            return it
        }
        if (!descriptor.isNullOrBlank()) {
            profiles.firstOrNull { it.deviceDescriptor == descriptor }?.let {
                Log.i(TAG, "profile hit descriptor=$descriptor id=${it.id}")
                return it.copy(deviceKey = deviceKey)
            }
        }
        if (vendorId != null && productId != null && (vendorId != 0 || productId != 0)) {
            profiles.firstOrNull { it.vendorId == vendorId && it.productId == productId }?.let {
                Log.i(TAG, "profile hit vid/pid=$vendorId/$productId id=${it.id}")
                return it.copy(deviceKey = deviceKey)
            }
        }
        Log.i(TAG, "profile fallback family=$family deviceKey=$deviceKey")
        return buildDefault(deviceKey, family, descriptor, vendorId, productId, legacyLogicalBindings)
    }

    fun buildDefault(
        deviceKey: String?,
        family: ControllerFamily,
        descriptor: String? = null,
        vendorId: Int? = null,
        productId: Int? = null,
        legacyLogicalBindings: Map<LogicalControl, Int> = emptyMap(),
    ): ControllerProfile {
        val bindings = FamilyDefaultMappings.mergeWithLegacy(family, legacyLogicalBindings)
        return ControllerProfile(
            id = if (deviceKey != null) "device:$deviceKey" else "default",
            name = when (family) {
                ControllerFamily.KEYBOARD -> "Keyboard Default"
                ControllerFamily.PLAYSTATION -> "PlayStation Default"
                ControllerFamily.XBOX -> "Xbox Default"
                ControllerFamily.NINTENDO -> "Nintendo Default"
                else -> "Default"
            },
            deviceKey = deviceKey,
            deviceDescriptor = descriptor,
            vendorId = vendorId,
            productId = productId,
            family = family,
            digitalBindings = bindings,
            isDefault = true,
        )
    }

    /**
     * Migrate a legacy global profile / InputBindingPrefs snapshot into a per-device profile
     * without dropping any existing logical bindings.
     */
    fun migrateLegacy(
        legacy: ControllerProfile,
        deviceKey: String,
        family: ControllerFamily,
        descriptor: String? = null,
        vendorId: Int? = null,
        productId: Int? = null,
    ): ControllerProfile {
        val merged = FamilyDefaultMappings.mergeWithLegacy(family, legacy.digitalBindings)
        return legacy.copy(
            id = "device:$deviceKey",
            name = if (legacy.name.isBlank() || legacy.name == "Default") {
                "${family.name.lowercase().replaceFirstChar { it.uppercase() }} Profile"
            } else legacy.name,
            deviceKey = deviceKey,
            deviceDescriptor = descriptor ?: legacy.deviceDescriptor,
            vendorId = vendorId ?: legacy.vendorId,
            productId = productId ?: legacy.productId,
            family = family,
            digitalBindings = merged,
            isDefault = false,
        )
    }
}

object ControllerProfileRepository {
    private const val KEY = "controller_profiles_v1"
    private const val TAG = "S3PADMAP"

    fun default(): ControllerProfile {
        val fromPrefs = FamilyDefaultMappings.fromInputBindingPrefs(InputBindingPrefs.loadBindings())
        val bindings = FamilyDefaultMappings.mergeWithLegacy(ControllerFamily.GENERIC_GAMEPAD, fromPrefs)
        return ControllerProfile(digitalBindings = bindings, isDefault = true, family = ControllerFamily.GENERIC_GAMEPAD)
    }

    fun load(): ControllerProfile {
        val raw = GeneralSettings[KEY] as? String ?: return default()
        return runCatching {
            val j = JSONObject(raw)
            val profileObject = j.optJSONArray("profiles")?.let { profiles ->
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == j.optString("current", "default") }
            } ?: j
            parseProfile(profileObject)
        }.getOrElse { default() }
    }

    fun loadAll(): List<ControllerProfile> {
        val raw = GeneralSettings[KEY] as? String ?: return listOf(default())
        return runCatching {
            val j = JSONObject(raw)
            val arr = j.optJSONArray("profiles") ?: return@runCatching listOf(parseProfile(j))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseProfile) }.ifEmpty { listOf(default()) }
        }.getOrElse { listOf(default()) }
    }

    fun loadForDevice(device: ConnectedInputDevice): ControllerProfile {
        val legacy = FamilyDefaultMappings.fromInputBindingPrefs(InputBindingPrefs.loadBindings())
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = loadAll(),
            deviceKey = device.deviceKey,
            family = device.family,
            descriptor = device.descriptor,
            vendorId = device.vendorId,
            productId = device.productId,
            legacyLogicalBindings = legacy,
        )
        Log.i(TAG, "loadForDevice ${device.name} -> ${selected.id}")
        return selected
    }

    fun save(profile: ControllerProfile): Boolean = runCatching {
        val all = loadAll().filterNot { it.id == profile.id } + profile
        val profiles = org.json.JSONArray().apply { all.forEach { p -> put(serialize(p)) } }
        GeneralSettings.setValue(
            KEY,
            JSONObject().put("version", 3).put("current", profile.id).put("profiles", profiles).toString(),
        )
        Log.i(TAG, "profile saved id=${profile.id} deviceKey=${profile.deviceKey}")
    }.isSuccess

    fun delete(profileId: String): Boolean = runCatching {
        val remaining = loadAll().filterNot { it.id == profileId }
        val current = remaining.firstOrNull()?.id ?: "default"
        val profiles = org.json.JSONArray().apply {
            (remaining.ifEmpty { listOf(default()) }).forEach { put(serialize(it)) }
        }
        GeneralSettings.setValue(
            KEY,
            JSONObject().put("version", 3).put("current", current).put("profiles", profiles).toString(),
        )
    }.isSuccess

    private fun parseProfile(j: JSONObject): ControllerProfile {
        val fallback = default()
        val map = mutableMapOf<LogicalControl, Int>()
        val bindings = j.optJSONObject("digital") ?: JSONObject()
        LogicalControl.entries.forEach { logical -> if (bindings.has(logical.name)) map[logical] = bindings.optInt(logical.name) }
        fun axis(name: String, defaultValue: AxisBinding): AxisBinding {
            val value = j.optJSONObject(name) ?: return defaultValue
            return AxisBinding(value.optInt("axis", defaultValue.axis), value.optBoolean("invert", defaultValue.invert))
        }
        fun tuning(name: String, defaultValue: StickTuning): StickTuning {
            val value = j.optJSONObject(name) ?: return defaultValue
            return StickTuning(
                deadzone = value.optDouble("deadzone", defaultValue.deadzone.toDouble()).toFloat(),
                sensitivity = value.optDouble("sensitivity", defaultValue.sensitivity.toDouble()).toFloat(),
                invertX = value.optBoolean("invertX", defaultValue.invertX),
                invertY = value.optBoolean("invertY", defaultValue.invertY),
            )
        }
        val familyName = j.optString("family", "").ifBlank { null }
        val family = familyName?.let { runCatching { ControllerFamily.valueOf(it) }.getOrNull() }
        return fallback.copy(
            id = j.optString("id", "default"),
            name = j.optString("name", "Default"),
            deviceKey = j.optString("deviceKey", "").ifBlank { null },
            deviceDescriptor = j.optString("deviceDescriptor", "").ifBlank { null },
            vendorId = if (j.has("vendorId")) j.optInt("vendorId") else null,
            productId = if (j.has("productId")) j.optInt("productId") else null,
            family = family,
            digitalBindings = map.ifEmpty { fallback.digitalBindings },
            leftX = axis("leftX", fallback.leftX), leftY = axis("leftY", fallback.leftY),
            rightX = axis("rightX", fallback.rightX), rightY = axis("rightY", fallback.rightY),
            leftTrigger = axis("leftTrigger", fallback.leftTrigger), rightTrigger = axis("rightTrigger", fallback.rightTrigger),
            leftStick = tuning("leftStick", fallback.leftStick), rightStick = tuning("rightStick", fallback.rightStick),
            triggerThreshold = j.optDouble("triggerThreshold", fallback.triggerThreshold.toDouble()).toFloat(),
            isDefault = j.optBoolean("isDefault", false),
        )
    }

    private fun serialize(profile: ControllerProfile): JSONObject = JSONObject().apply {
        put("id", profile.id); put("name", profile.name)
        profile.deviceKey?.let { put("deviceKey", it) }
        profile.deviceDescriptor?.let { put("deviceDescriptor", it) }
        profile.vendorId?.let { put("vendorId", it) }
        profile.productId?.let { put("productId", it) }
        profile.family?.let { put("family", it.name) }
        put("isDefault", profile.isDefault)
        put("digital", JSONObject().apply { profile.digitalBindings.forEach { (logical, key) -> put(logical.name, key) } })
        fun axis(name: String, value: AxisBinding) {
            put(name, JSONObject().put("axis", value.axis).put("invert", value.invert))
        }
        axis("leftX", profile.leftX); axis("leftY", profile.leftY)
        axis("rightX", profile.rightX); axis("rightY", profile.rightY)
        axis("leftTrigger", profile.leftTrigger); axis("rightTrigger", profile.rightTrigger)
        fun tuning(name: String, value: StickTuning) {
            put(
                name,
                JSONObject()
                    .put("deadzone", value.deadzone)
                    .put("sensitivity", value.sensitivity)
                    .put("invertX", value.invertX)
                    .put("invertY", value.invertY),
            )
        }
        tuning("leftStick", profile.leftStick); tuning("rightStick", profile.rightStick)
        put("triggerThreshold", profile.triggerThreshold)
    }
}
