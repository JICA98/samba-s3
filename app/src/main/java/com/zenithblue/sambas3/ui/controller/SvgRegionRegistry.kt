package com.zenithblue.sambas3.ui.controller

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SvgViewport(
    val width: Float,
    val height: Float,
)

@Serializable
data class SvgBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

@Serializable
data class KeyboardSvgRegion(
    val code: String,
    val bounds: SvgBounds,
)

@Serializable
data class KeyboardSvgRegionMap(
    val viewBox: SvgViewport,
    val regions: List<KeyboardSvgRegion>,
)

@Serializable
data class ControllerSvgRegion(
    val id: String,
    val kind: String,
    val bounds: SvgBounds,
)

@Serializable
data class ControllerSvgRegionMap(
    val viewBox: SvgViewport,
    val regions: List<ControllerSvgRegion>,
)

object SvgRegionRegistry {
    const val KEYBOARD_REGIONS_ASSET = "controllers/controller_keyboard_regions.json"
    const val DS3_REGIONS_ASSET = "controllers/controller_ds3_regions.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun loadKeyboard(context: Context): KeyboardSvgRegionMap = load(context, KEYBOARD_REGIONS_ASSET)

    fun loadController(context: Context): ControllerSvgRegionMap = load(context, DS3_REGIONS_ASSET)

    internal fun decodeKeyboard(text: String): KeyboardSvgRegionMap = json.decodeFromString(text)

    internal fun decodeController(text: String): ControllerSvgRegionMap = json.decodeFromString(text)

    private inline fun <reified T> load(context: Context, assetPath: String): T =
        context.assets.open(assetPath).bufferedReader().use { json.decodeFromString(it.readText()) }
}
