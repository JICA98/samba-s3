package com.zenithblue.sambas3.input

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
    val deviceDescriptor: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val digitalBindings: Map<LogicalControl, Int>,
    val leftX: AxisBinding = AxisBinding(MotionAxis.X),
    val leftY: AxisBinding = AxisBinding(MotionAxis.Y),
    val rightX: AxisBinding = AxisBinding(MotionAxis.Z),
    val rightY: AxisBinding = AxisBinding(MotionAxis.RZ),
    val leftTrigger: AxisBinding = AxisBinding(MotionAxis.LTRIGGER),
    val rightTrigger: AxisBinding = AxisBinding(MotionAxis.RTRIGGER),
    val leftStick: StickTuning = StickTuning(),
    val rightStick: StickTuning = StickTuning(),
    val triggerThreshold: Float = .1f
)

object MotionAxis {
    const val X = 0; const val Y = 1; const val Z = 11; const val RZ = 14
    const val LTRIGGER = 17; const val RTRIGGER = 18; const val HAT_X = 15; const val HAT_Y = 16
}

object ControllerProfileRepository {
    private const val KEY = "controller_profiles_v1"

    fun default(): ControllerProfile {
        val bindings = InputBindingPrefs.loadBindings().entries.mapNotNull { (key, pair) ->
            LogicalControl.entries.firstOrNull { it.bank == pair.second && it.bit == pair.first }?.let { it to key }
        }.toMap().toMutableMap()
        LogicalControl.entries.forEach { logical ->
            if (logical !in bindings && logical != LogicalControl.PS_HOME_FRONTEND) {
                InputBindingPrefs.defaultBindings.entries.firstOrNull { it.value.first == logical.bit && it.value.second == logical.bank }?.let { bindings[logical] = it.key }
            }
        }
        return ControllerProfile(digitalBindings = bindings)
    }

    fun load(): ControllerProfile {
        val raw = GeneralSettings[KEY] as? String ?: return default()
        return runCatching {
            val j = JSONObject(raw)
            val profileObject = j.optJSONArray("profiles")?.let { profiles ->
                (0 until profiles.length()).mapNotNull { profiles.optJSONObject(it) }.firstOrNull { it.optString("id") == j.optString("current", "default") }
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
                invertY = value.optBoolean("invertY", defaultValue.invertY)
            )
        }
        return fallback.copy(
            id = j.optString("id", "default"),
            name = j.optString("name", "Default"),
            deviceDescriptor = j.optString("deviceDescriptor", "").ifBlank { null },
            vendorId = if (j.has("vendorId")) j.optInt("vendorId") else null,
            productId = if (j.has("productId")) j.optInt("productId") else null,
            digitalBindings = map,
            leftX = axis("leftX", fallback.leftX), leftY = axis("leftY", fallback.leftY),
            rightX = axis("rightX", fallback.rightX), rightY = axis("rightY", fallback.rightY),
            leftTrigger = axis("leftTrigger", fallback.leftTrigger), rightTrigger = axis("rightTrigger", fallback.rightTrigger),
            leftStick = tuning("leftStick", fallback.leftStick), rightStick = tuning("rightStick", fallback.rightStick),
            triggerThreshold = j.optDouble("triggerThreshold", fallback.triggerThreshold.toDouble()).toFloat()
        )
    }

    fun save(profile: ControllerProfile): Boolean = runCatching {
        val all = loadAll().filterNot { it.id == profile.id } + profile
        val profiles = org.json.JSONArray().apply { all.forEach { p -> put(serialize(p)) } }
        GeneralSettings.setValue(KEY, JSONObject().put("version", 2).put("current", profile.id).put("profiles", profiles).toString())
    }.isSuccess

    private fun serialize(profile: ControllerProfile): JSONObject = JSONObject().apply {
        put("id", profile.id); put("name", profile.name)
        profile.deviceDescriptor?.let { put("deviceDescriptor", it) }
        profile.vendorId?.let { put("vendorId", it) }
        profile.productId?.let { put("productId", it) }
        put("digital", JSONObject().apply { profile.digitalBindings.forEach { (logical, key) -> put(logical.name, key) } })
        fun axis(name: String, value: AxisBinding) { put(name, JSONObject().put("axis", value.axis).put("invert", value.invert)) }
        axis("leftX", profile.leftX); axis("leftY", profile.leftY); axis("rightX", profile.rightX); axis("rightY", profile.rightY)
        axis("leftTrigger", profile.leftTrigger); axis("rightTrigger", profile.rightTrigger)
        fun tuning(name: String, value: StickTuning) {
            put(name, JSONObject().put("deadzone", value.deadzone).put("sensitivity", value.sensitivity).put("invertX", value.invertX).put("invertY", value.invertY))
        }
        tuning("leftStick", profile.leftStick); tuning("rightStick", profile.rightStick)
        put("triggerThreshold", profile.triggerThreshold)
    }
}
