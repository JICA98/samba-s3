package com.zenithblue.sambas3.overlay

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min

/**
 * Procedural Canvas renderer that replicates the glassmorphic PS3 controller
 * design from ps3_controller_overlay.html.
 *
 * CSS variable → Kotlin constant mapping:
 *   --glass-bg       → GLASS_BG
 *   --glass-border   → GLASS_BORDER
 *   --glass-active   → GLASS_ACTIVE
 *   --glass-label    → GLASS_LABEL
 *   --glass-label-dim→ GLASS_LABEL_DIM
 *   --shadow-btn     → drawn via BlurMaskFilter with SHADOW_COLOR
 *   --shadow-active  → drawn via BlurMaskFilter with SHADOW_ACTIVE_COLOR
 *   --tri-color/border, --cross-*, --sq-*, --circ-*  → per-button colors
 */
object GlassButtonRenderer {

    // ── CSS variable equivalents ──────────────────────────────────────────
    private const val GLASS_BG            = 0x73_0F_0F_14.toInt()  // rgba(15,15,20,0.45)
    private const val GLASS_BORDER        = 0x24_FF_FF_FF.toInt()  // rgba(255,255,255,0.14)
    private const val GLASS_ACTIVE        = 0x4D_FF_FF_FF.toInt()  // rgba(255,255,255,0.30)
    private const val GLASS_LABEL         = 0xB3_FF_FF_FF.toInt()  // rgba(255,255,255,0.70)
    private const val GLASS_LABEL_DIM     = 0x61_FF_FF_FF.toInt()  // rgba(255,255,255,0.38)
    private const val SHADOW_COLOR        = 0x73_00_00_00.toInt()  // rgba(0,0,0,0.45)
    private const val SHADOW_ACTIVE_COLOR = 0x99_00_00_00.toInt()  // rgba(0,0,0,0.60)

    // Face-button colours (from HTML CSS vars)
    private const val TRI_BG   = 0xD9_32_B4_82.toInt()  // rgba(50,180,130,0.85)
    private const val TRI_BOR  = 0x99_50_D2_A0.toInt()  // rgba(80,210,160,0.60)
    private const val CRO_BG   = 0xD9_50_8C_DC.toInt()  // rgba(80,140,220,0.85)
    private const val CRO_BOR  = 0x99_64_A5_F0.toInt()  // rgba(100,165,240,0.60)
    private const val SQ_BG    = 0xCC_C8_50_82.toInt()  // rgba(200,80,130,0.80)
    private const val SQ_BOR   = 0x99_E1_6E_9B.toInt()  // rgba(225,110,155,0.60)
    private const val CIR_BG   = 0xCC_D2_46_3C.toInt()  // rgba(210,70,60,0.80)
    private const val CIR_BOR  = 0x99_EB_64_5A.toInt()  // rgba(235,100,90,0.60)

    // PS button
    private const val PS_BG    = 0xB8_14_14_23.toInt()  // rgba(20,20,35,0.72)
    private const val PS_BOR   = 0x8C_82_78_DC.toInt()  // rgba(130,120,220,0.55)
    private const val PS_GLOW  = 0x4D_6E_64_C8.toInt()  // rgba(110,100,200,0.30)
    private const val PS_LABEL = 0xE6_B4_AA_F0.toInt()  // rgba(180,170,240,0.90)

    // ── Reusable Paint objects ─────────────────────────────────────────────
    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glowPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineGradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF      = RectF()

    // ── Helper to apply alpha overlay ──────────────────────────────────────
    private fun colorWithAlpha(baseColor: Int, extraAlpha: Int): Int {
        val a = ((Color.alpha(baseColor) * extraAlpha) / 255).coerceIn(0, 255)
        return Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    /**
     * Draw a circular face button (Triangle / Cross / Square / Circle).
     *
     * @param pressed     Whether the button is currently held
     * @param label       Unicode glyph: "△" "✕" "□" "○"
     * @param bgColor     Button fill (one of TRI_BG / CRO_BG / SQ_BG / CIR_BG)
     * @param borderColor Button stroke (one of *_BOR constants)
     * @param overlayAlpha Overall overlay alpha 0-255
     */
    fun drawFaceButton(
        canvas: Canvas,
        rect: Rect,
        pressed: Boolean,
        label: String,
        bgColor: Int,
        borderColor: Int,
        overlayAlpha: Int = 255
    ) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val r  = (min(rect.width(), rect.height()) / 2f) - 2f

        // ── Drop shadow ──
        if (!pressed) {
            glowPaint.maskFilter = BlurMaskFilter(r * 0.4f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
            canvas.drawCircle(cx, cy + r * 0.12f, r, glowPaint)
            glowPaint.maskFilter = null
        }

        // ── Color glow on pressed ──
        if (pressed) {
            glowPaint.maskFilter = BlurMaskFilter(r * 0.7f, BlurMaskFilter.Blur.NORMAL)
            val glowColor = Color.argb(
                (Color.alpha(borderColor) * overlayAlpha / 255),
                Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor)
            )
            glowPaint.color = glowColor
            canvas.drawCircle(cx, cy, r + r * 0.15f, glowPaint)
            glowPaint.maskFilter = null
        }

        // ── Background fill ──
        val activeBg = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(bgColor, overlayAlpha)
        fillPaint.color = activeBg
        canvas.drawCircle(cx, cy, r, fillPaint)

        // ── Inset top shine ──
        if (!pressed) {
            shineGradPaint.shader = LinearGradient(
                cx, cy - r, cx, cy,
                intArrayOf(colorWithAlpha(0x1AFFFFFF.toInt(), overlayAlpha), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, shineGradPaint)
            shineGradPaint.shader = null
        }

        // ── Border ──
        val activeBorder = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(borderColor, overlayAlpha)
        borderPaint.color = activeBorder
        borderPaint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, r - 1f, borderPaint)

        // ── Label ──
        val labelColor = colorWithAlpha(GLASS_LABEL, overlayAlpha)
        textPaint.color = labelColor
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = r * 0.75f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    /**
     * Draw a shoulder button (L1/L2/R1/R2) — rectangle with rounded corners.
     * Maps to `.shoulder` in the HTML.
     */
    fun drawShoulderButton(
        canvas: Canvas,
        rect: Rect,
        pressed: Boolean,
        label: String,
        overlayAlpha: Int = 255
    ) {
        rectF.set(rect)
        val cornerR = rect.height() * 0.30f  // ~= CSS border-radius: 12px relative to height

        // ── Drop shadow ──
        if (!pressed) {
            glowPaint.maskFilter = BlurMaskFilter(rect.height() * 0.5f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
            rectF.set(rect.left.toFloat(), rect.top + rect.height() * 0.1f,
                rect.right.toFloat(), rect.bottom.toFloat() + rect.height() * 0.15f)
            canvas.drawRoundRect(rectF, cornerR, cornerR, glowPaint)
            glowPaint.maskFilter = null
            rectF.set(rect)
        }

        // ── Active white glow ──
        if (pressed) {
            glowPaint.maskFilter = BlurMaskFilter(rect.height() * 0.6f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(0x38FFFFFF.toInt(), overlayAlpha)
            canvas.drawRoundRect(rectF, cornerR, cornerR, glowPaint)
            glowPaint.maskFilter = null
        }

        // ── Fill ──
        val bg = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BG, overlayAlpha)
        fillPaint.color = bg
        canvas.drawRoundRect(rectF, cornerR, cornerR, fillPaint)

        // ── Top shine ──
        if (!pressed) {
            shineGradPaint.shader = LinearGradient(
                0f, rect.top.toFloat(), 0f, rect.exactCenterY(),
                intArrayOf(colorWithAlpha(0x1AFFFFFF.toInt(), overlayAlpha), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rectF, cornerR, cornerR, shineGradPaint)
            shineGradPaint.shader = null
        }

        // ── Border ──
        borderPaint.color = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BORDER, overlayAlpha)
        borderPaint.strokeWidth = 2f
        val inset = 1f
        rectF.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(rectF, cornerR - inset, cornerR - inset, borderPaint)
        rectF.set(rect)

        // ── Label ──
        textPaint.color = colorWithAlpha(GLASS_LABEL, overlayAlpha)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = rect.height() * 0.40f
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    /**
     * Draw a D-pad arm (a single directional cell) with selective corner rounding.
     * Uses android.graphics.Path so each corner can be independently rounded,
     * matching the HTML per-arm border-radius (e.g. `8px 8px 0 0` for the top arm).
     */
    fun drawDpadArm(
        canvas: Canvas,
        rect: Rect,
        pressed: Boolean,
        label: String,
        topLeft: Boolean,
        topRight: Boolean,
        bottomLeft: Boolean,
        bottomRight: Boolean,
        overlayAlpha: Int = 255
    ) {
        val r  = rect.width() * 0.17f   // ~8px corner radius on a 48px cell
        val path = buildSelectivePath(rect, r, topLeft, topRight, bottomLeft, bottomRight)

        // ── Drop shadow ──
        if (!pressed) {
            glowPaint.maskFilter = BlurMaskFilter(rect.height() * 0.35f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
            canvas.drawPath(path, glowPaint)
            glowPaint.maskFilter = null
        }

        // ── Fill ──
        val bg = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BG, overlayAlpha)
        fillPaint.color = bg
        canvas.drawPath(path, fillPaint)

        // ── Top shine ──
        if (!pressed) {
            shineGradPaint.shader = LinearGradient(
                0f, rect.top.toFloat(), 0f, rect.exactCenterY(),
                intArrayOf(colorWithAlpha(0x1AFFFFFF.toInt(), overlayAlpha), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, shineGradPaint)
            shineGradPaint.shader = null
        }

        // ── Border ──
        borderPaint.color = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BORDER, overlayAlpha)
        borderPaint.strokeWidth = 2f
        canvas.drawPath(path, borderPaint)

        // ── Arrow glyph ──
        val arrowSize = min(rect.width(), rect.height()) * 0.38f
        textPaint.color = colorWithAlpha(GLASS_LABEL, overlayAlpha)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = arrowSize
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    /**
     * Build a Path that rounds only the specified corners of [rect].
     * Unrounded corners are drawn as right angles.
     */
    private fun buildSelectivePath(
        rect: Rect,
        r: Float,
        roundTopLeft: Boolean,
        roundTopRight: Boolean,
        roundBottomLeft: Boolean,
        roundBottomRight: Boolean
    ): android.graphics.Path {
        val path = android.graphics.Path()
        val l = rect.left.toFloat()
        val t = rect.top.toFloat()
        val right = rect.right.toFloat()
        val b = rect.bottom.toFloat()

        path.moveTo(l + if (roundTopLeft) r else 0f, t)
        path.lineTo(right - if (roundTopRight) r else 0f, t)
        if (roundTopRight)  path.quadTo(right, t, right, t + r)
        path.lineTo(right, b - if (roundBottomRight) r else 0f)
        if (roundBottomRight) path.quadTo(right, b, right - r, b)
        path.lineTo(l + if (roundBottomLeft) r else 0f, b)
        if (roundBottomLeft) path.quadTo(l, b, l, b - r)
        path.lineTo(l, t + if (roundTopLeft) r else 0f)
        if (roundTopLeft)   path.quadTo(l, t, l + r, t)
        path.close()
        return path
    }

    /**
     * Draw the center (SELECT/START) pill button. Matches `.center-btn`.
     */
    fun drawCenterButton(
        canvas: Canvas,
        rect: Rect,
        pressed: Boolean,
        label: String,
        overlayAlpha: Int = 255
    ) {
        rectF.set(rect)
        val cornerR = rect.height() / 2f  // pill shape (border-radius: 18px)

        // shadow
        if (!pressed) {
            glowPaint.maskFilter = BlurMaskFilter(rect.height() * 0.5f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
            val shadowRect = RectF(rectF)
            shadowRect.offset(0f, rect.height() * 0.1f)
            canvas.drawRoundRect(shadowRect, cornerR, cornerR, glowPaint)
            glowPaint.maskFilter = null
        }

        if (pressed) {
            glowPaint.maskFilter = BlurMaskFilter(rect.height() * 0.8f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(0x38FFFFFF.toInt(), overlayAlpha)
            canvas.drawRoundRect(rectF, cornerR, cornerR, glowPaint)
            glowPaint.maskFilter = null
        }

        // fill
        val bg = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BG, overlayAlpha)
        fillPaint.color = bg
        canvas.drawRoundRect(rectF, cornerR, cornerR, fillPaint)

        // border
        borderPaint.color = if (pressed) colorWithAlpha(GLASS_ACTIVE, overlayAlpha) else colorWithAlpha(GLASS_BORDER, overlayAlpha)
        borderPaint.strokeWidth = 2f
        val inset = 1f
        rectF.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(rectF, cornerR - inset, cornerR - inset, borderPaint)

        // label
        textPaint.color = colorWithAlpha(GLASS_LABEL_DIM, overlayAlpha)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = rect.height() * 0.38f
        textPaint.letterSpacing = 0.06f
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
        textPaint.letterSpacing = 0f
    }

    /**
     * Draw the PS / Home button. Matches `#ps-btn` with purple glow.
     */
    fun drawPsButton(
        canvas: Canvas,
        rect: Rect,
        pressed: Boolean,
        overlayAlpha: Int = 255
    ) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val r  = min(rect.width(), rect.height()) / 2f - 2f

        // Ambient purple glow (always visible, stronger on press)
        val glowStrength = if (pressed) 0.8f else 0.4f
        glowPaint.maskFilter = BlurMaskFilter(r * 1.1f, BlurMaskFilter.Blur.NORMAL)
        val glowColor = colorWithAlpha(PS_GLOW, (overlayAlpha * glowStrength).toInt().coerceIn(0, 255))
        glowPaint.color = glowColor
        canvas.drawCircle(cx, cy, r * 1.1f, glowPaint)
        glowPaint.maskFilter = null

        // Drop shadow
        if (!pressed) {
            glowPaint.maskFilter = BlurMaskFilter(r * 0.5f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
            canvas.drawCircle(cx, cy + r * 0.1f, r, glowPaint)
            glowPaint.maskFilter = null
        }

        // Active white ring on press
        if (pressed) {
            glowPaint.maskFilter = BlurMaskFilter(r * 0.6f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.color = colorWithAlpha(0x38FFFFFF.toInt(), overlayAlpha)
            canvas.drawCircle(cx, cy, r + r * 0.15f, glowPaint)
            glowPaint.maskFilter = null
        }

        // Fill
        val bg = if (pressed)
            colorWithAlpha(0x66_6E_64_C8.toInt(), overlayAlpha)
        else
            colorWithAlpha(PS_BG, overlayAlpha)
        fillPaint.color = bg
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Top shine
        if (!pressed) {
            shineGradPaint.shader = LinearGradient(
                cx, cy - r, cx, cy,
                intArrayOf(colorWithAlpha(0x1AFFFFFF.toInt(), overlayAlpha), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, shineGradPaint)
            shineGradPaint.shader = null
        }

        // Border
        borderPaint.color = colorWithAlpha(PS_BOR, overlayAlpha)
        borderPaint.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy, r - 1f, borderPaint)

        // "PS" label
        textPaint.color = colorWithAlpha(PS_LABEL, overlayAlpha)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = r * 0.65f
        textPaint.letterSpacing = 0.02f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("PS", cx, textY, textPaint)
        textPaint.letterSpacing = 0f
    }

    /**
     * Draw the analog stick ring (`.stick-ring` in HTML).
     */
    fun drawStickRing(canvas: Canvas, rect: Rect, overlayAlpha: Int = 255) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val r  = min(rect.width(), rect.height()) / 2f - 2f

        // Subtle outer glow
        glowPaint.maskFilter = BlurMaskFilter(r * 0.25f, BlurMaskFilter.Blur.NORMAL)
        glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
        canvas.drawCircle(cx, cy, r, glowPaint)
        glowPaint.maskFilter = null

        // Fill — rgba(128,128,128,0.20) (visible grey glass shade)
        fillPaint.color = colorWithAlpha(0x33_80_80_80.toInt(), overlayAlpha)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Border — rgba(128,128,128,0.50) (visible grey shade)
        borderPaint.color = colorWithAlpha(0x80_80_80_80.toInt(), overlayAlpha)
        borderPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, r - 1f, borderPaint)
    }

    /**
     * Draw the stick nub (`.stick-nub` in HTML).
     */
    fun drawStickNub(canvas: Canvas, rect: Rect, label: String, overlayAlpha: Int = 255) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val r  = min(rect.width(), rect.height()) / 2f - 2f

        // Drop shadow
        glowPaint.maskFilter = BlurMaskFilter(r * 0.5f, BlurMaskFilter.Blur.NORMAL)
        glowPaint.color = colorWithAlpha(SHADOW_COLOR, overlayAlpha)
        canvas.drawCircle(cx, cy + r * 0.1f, r, glowPaint)
        glowPaint.maskFilter = null

        // Fill — glass-bg
        fillPaint.color = colorWithAlpha(GLASS_BG, overlayAlpha)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Top shine
        shineGradPaint.shader = LinearGradient(
            cx, cy - r, cx, cy,
            intArrayOf(colorWithAlpha(0x26FFFFFF.toInt(), overlayAlpha), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, shineGradPaint)
        shineGradPaint.shader = null

        // Border — rgba(255,255,255,0.22)
        borderPaint.color = colorWithAlpha(0x38_FF_FF_FF.toInt(), overlayAlpha)
        borderPaint.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy, r - 1f, borderPaint)

        // Label (L3/R3)
        textPaint.color = colorWithAlpha(GLASS_LABEL_DIM, overlayAlpha)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textSize = r * 0.50f
        textPaint.letterSpacing = 0.04f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
        textPaint.letterSpacing = 0f
    }

    // ── Exposed color constants for external use ──────────────────────────
    fun triangleColors() = Pair(TRI_BG, TRI_BOR)
    fun crossColors()    = Pair(CRO_BG, CRO_BOR)
    fun squareColors()   = Pair(SQ_BG,  SQ_BOR)
    fun circleColors()   = Pair(CIR_BG, CIR_BOR)
}
