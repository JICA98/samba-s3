package com.zenithblue.sambas3.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOutputProfilesTest {
    @Test
    fun strictRenderingUsesExplicitThenCuratedThenGlobalPrecedence() {
        assertEquals(
            false,
            VideoOutputProfiles.effectiveStrictRendering("false", "true", "true")
        )
        assertEquals(
            true,
            VideoOutputProfiles.effectiveStrictRendering(null, "true", "false")
        )
        assertEquals(
            true,
            VideoOutputProfiles.effectiveStrictRendering(null, null, "true")
        )
        assertEquals(
            false,
            VideoOutputProfiles.effectiveStrictRendering(null, null, "false")
        )
    }

    @Test
    fun widthUsesSelectedAspectForEachRequestedHeight() {
        assertEquals(800, VideoOutputProfiles.widthForHeight(360, "20:9", 16.0 / 9.0))
        assertEquals(1200, VideoOutputProfiles.widthForHeight(540, "20:9", 16.0 / 9.0))
        assertEquals(1680, VideoOutputProfiles.widthForHeight(720, "21:9", 16.0 / 9.0))
        assertEquals(2400, VideoOutputProfiles.widthForHeight(1080, "20:9", 16.0 / 9.0))
    }

    @Test
    fun nativeWidthUsesCurrentWindowAspect() {
        assertEquals(880, VideoOutputProfiles.widthForHeight(360, "Native", 22.0 / 9.0))
    }

    @Test
    fun scalePresetLabelsSurfaceAndNominalRsxDimensionsSeparately() {
        val profile = VideoOutputProfiles.presets(1280, 720, "20:9", 22.0 / 9.0)
            .first { it.height == 540 }

        assertEquals(1200, profile.surfaceWidth)
        assertEquals(75, profile.scalePercent)
        assertEquals(960, profile.estimatedRsxWidth)
        assertEquals(540, profile.estimatedRsxHeight)
        assertEquals("Surface 1200 × 540 · scale 75% · nominal RSX 960 × 540", profile.label)
    }

    @Test
    fun storedSurfaceHeightIsAuthoritativeForSelectedPreset() {
        val profiles = VideoOutputProfiles.presets(1920, 1080, "16:9", 16.0 / 9.0)

        assertEquals(540, VideoOutputProfiles.explicitlySelected(profiles, 540)?.height)
        assertEquals(null, VideoOutputProfiles.explicitlySelected(profiles, null))
    }

    @Test
    fun parsesExistingRpcsxResolutionLabels() {
        assertEquals(1280 to 720, VideoOutputProfiles.dimensions("1280x720"))
        assertEquals(1920 to 1080, VideoOutputProfiles.dimensions("1920x1080i"))
    }
}
