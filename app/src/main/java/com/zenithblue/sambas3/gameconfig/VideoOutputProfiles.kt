package com.zenithblue.sambas3.gameconfig

import kotlin.math.roundToInt

/** A resolution-scale choice paired with the requested presentation canvas size. */
data class VideoOutputProfile(
    val height: Int,
    val surfaceWidth: Int,
    val scalePercent: Int,
    val estimatedRsxWidth: Int,
    val estimatedRsxHeight: Int
) {
    val key: String get() = "${height}p"
    val label: String
        get() = "Surface $surfaceWidth × $height · scale $scalePercent% · nominal RSX $estimatedRsxWidth × $estimatedRsxHeight"
}

/**
 * Maps exact surface-height presets and presentation widths to RPCSX's existing
 * RSX scale setting. `Video@@Resolution` remains the valid PS3 AV mode. The
 * nominal RSX dimensions are only an estimate from that mode and the integer
 * scale; a title can use render targets with different dimensions.
 */
object VideoOutputProfiles {
    val heights: List<Int> = listOf(360, 540, 720, 1080)

    /** Explicit title value > curated compatibility default > canonical global value. */
    fun effectiveStrictRendering(
        explicitOverride: String?,
        curatedDefault: String?,
        engineGlobalValue: String?
    ): Boolean = sequenceOf(explicitOverride, curatedDefault, engineGlobalValue)
        .mapNotNull { value ->
            value?.let(SettingsValueCodec::decodeToDisplay)?.toBooleanStrictOrNull()
        }
        .firstOrNull() ?: false

    fun dimensions(value: String): Pair<Int, Int>? {
        val match = Regex("(\\d+)\\s*x\\s*(\\d+)").find(value) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
    }

    fun widthForHeight(height: Int, aspect: String, nativeAspect: Double): Int {
        val ratio = when (aspect.trim().lowercase()) {
            "4:3" -> 4.0 / 3.0
            "16:9" -> 16.0 / 9.0
            "20:9" -> 20.0 / 9.0
            "21:9" -> 21.0 / 9.0
            "native" -> nativeAspect.takeIf { it.isFinite() && it > 0.0 } ?: (16.0 / 9.0)
            else -> 16.0 / 9.0
        }
        // RSX color-buffer widths are aligned to an even pixel count.
        return ((height * ratio).roundToInt() + 1) and -2
    }

    fun presets(
        sourceWidth: Int,
        sourceHeight: Int,
        aspect: String,
        nativeAspect: Double
    ): List<VideoOutputProfile> {
        val baseWidth = sourceWidth.takeIf { it > 0 } ?: 1280
        val baseHeight = sourceHeight.takeIf { it > 0 } ?: 720
        return heights.map { height ->
            val percent = ((height * 100.0 / baseHeight).roundToInt()).coerceIn(25, 800)
            VideoOutputProfile(
                height = height,
                surfaceWidth = widthForHeight(height, aspect, nativeAspect),
                scalePercent = percent,
                estimatedRsxWidth = baseWidth * percent / 100,
                estimatedRsxHeight = baseHeight * percent / 100
            )
        }
    }

    /** Stored surface size is authoritative; scale rounding must not choose the UI selection. */
    fun explicitlySelected(profiles: List<VideoOutputProfile>, storedHeight: Int?): VideoOutputProfile? =
        storedHeight?.let { height -> profiles.firstOrNull { it.height == height } }

}
