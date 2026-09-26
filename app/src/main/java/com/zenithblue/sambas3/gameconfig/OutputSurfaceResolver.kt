package com.zenithblue.sambas3.gameconfig

/** Pixel dimensions for the Android surface buffer, independent of RSX render scale. */
data class OutputSurfaceSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

/** Resolves a stored output-height choice to an aspect-aware SurfaceHolder buffer size. */
object OutputSurfaceResolver {
    fun resolve(height: Int?, aspect: String, nativeAspect: Double): OutputSurfaceSize? {
        if (height == null) return null
        require(height in OutputResolutionStore.supportedHeights) {
            "Unsupported output height: $height"
        }
        return OutputSurfaceSize(
            width = VideoOutputProfiles.widthForHeight(height, aspect, nativeAspect),
            height = height
        )
    }
}
