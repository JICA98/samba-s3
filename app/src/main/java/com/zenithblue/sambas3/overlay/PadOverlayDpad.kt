package com.zenithblue.sambas3.overlay

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.boolean
import com.zenithblue.sambas3.utils.GeneralSettings.int
import kotlin.math.roundToInt

private enum class DpadButton(val bit: Int) {
    Top(1 shl 0), Left(1 shl 1), Right(1 shl 2), Bottom(1 shl 3);
}

private class DpadState(var mask: Int = 0) {
    fun isActive(btn: DpadButton): Boolean = (mask and btn.bit) == btn.bit
    fun setBtn(btn: DpadButton) { mask = mask or btn.bit }
    fun clear() { mask = 0 }
}

/**
 * Draws the D-pad cross or face-button diamond using [GlassButtonRenderer].
 *
 * The HTML uses a 3×3 grid of 48px cells; the center cell is transparent.
 * Each arm has rounded corners only on the outer edge (matching the HTML's
 * per-arm border-radius values).
 *
 * For the face button group ([multitouch] = true) we draw four coloured circles
 * exactly like `drawFaceButton()`.
 */
class PadOverlayDpad(
    @Suppress("UNUSED_PARAMETER") resources: Resources,
    private var buttonWidth: Int,
    private var buttonHeight: Int,
    private val inputId: String,
    private var area: Rect,
    private val digitalIndex: Int,
    @Suppress("UNUSED_PARAMETER") imgTop: android.graphics.Bitmap,
    private val topBit: Int,
    @Suppress("UNUSED_PARAMETER") imgLeft: android.graphics.Bitmap,
    private val leftBit: Int,
    @Suppress("UNUSED_PARAMETER") imgRight: android.graphics.Bitmap,
    private val rightBit: Int,
    @Suppress("UNUSED_PARAMETER") imgBottom: android.graphics.Bitmap,
    private val bottomBit: Int,
    private val multitouch: Boolean
) : PadOverlayItem {

    private val originalButtonWidth  = buttonWidth
    private val originalButtonHeight = buttonHeight
    private val locked     = arrayOf(-1, -1)
    private val btnState   = arrayOf(DpadState(), DpadState())
    private val digitalBits= arrayOf(0, 0)
    private var offsetX    = 0
    private var offsetY    = 0

    private val defaultArea         = Rect(area)
    private val defaultButtonWidth  = buttonWidth
    private val defaultButtonHeight = buttonHeight
    var idleAlpha: Int = 255
    override var dragging: Boolean = false

    override var enabled: Boolean = GeneralSettings["${inputId}_enabled"].boolean(true)
        set(value) {
            field = value
            GeneralSettings.setValue("${inputId}_enabled", value)
        }

    // Per-arm rects (recomputed in updateBounds)
    private val rectTop    = Rect()
    private val rectBottom = Rect()
    private val rectLeft   = Rect()
    private val rectRight  = Rect()

    init { loadSavedPosition() }

    override fun contains(x: Int, y: Int) = area.contains(x, y)

    override fun startDragging(x: Int, y: Int) {
        dragging = true
        offsetX = x - area.left
        offsetY = y - area.top
    }

    override fun updatePosition(x: Int, y: Int, force: Boolean) {
        if (!dragging && !force) return
        val newLeft   = if (!force) x - offsetX else x
        val newTop    = if (!force) y - offsetY else y
        area.set(newLeft, newTop, newLeft + area.width(), newTop + area.height())
        updateBounds()
        GeneralSettings.setValue("${inputId}_x", area.left)
        GeneralSettings.setValue("${inputId}_y", area.top)
    }

    override fun stopDragging() { dragging = false }

    private fun setScale(percent: Int, centerX: Int, centerY: Int) {
        val scaleFactor = percent / 100f
        val newSize = (1024 * scaleFactor).roundToInt()
        area.set(centerX - newSize / 2, centerY - newSize / 2, centerX + newSize / 2, centerY + newSize / 2)

        val defaultScaleWidth  = defaultArea.width().toFloat()  / 1024
        val defaultScaleHeight = defaultArea.height().toFloat() / 1024
        buttonWidth  = (originalButtonWidth  / defaultScaleWidth  * scaleFactor).toInt()
        buttonHeight = (originalButtonHeight / defaultScaleHeight * scaleFactor).toInt()
        updateBounds()

        GeneralSettings.setValue("${inputId}_x",     area.left)
        GeneralSettings.setValue("${inputId}_y",     area.top)
        GeneralSettings.setValue("${inputId}_scale", percent)
    }

    override fun setScale(percent: Int) = setScale(percent, area.centerX(), area.centerY())

    override fun setOpacity(percent: Int) {
        idleAlpha = (255 * percent / 100).coerceIn(0, 255)
        GeneralSettings.setValue("${inputId}_opacity", percent)
    }

    override fun resetConfigs() {
        GeneralSettings.setValue("${inputId}_x",       null)
        GeneralSettings.setValue("${inputId}_y",       null)
        GeneralSettings.setValue("${inputId}_scale",   null)
        GeneralSettings.setValue("${inputId}_opacity", null)
        area = Rect(defaultArea)
        setOpacity(50)
        buttonWidth  = defaultButtonWidth
        buttonHeight = defaultButtonHeight
        updateBounds()
    }

    private fun loadSavedPosition() {
        val scale = GeneralSettings["${inputId}_scale"].int(-1)
        val x     = GeneralSettings["${inputId}_x"].int(area.left)
        val y     = GeneralSettings["${inputId}_y"].int(area.top)
        if (scale != -1) {
            val centerX = x + area.width()  / 2
            val centerY = y + area.height() / 2
            setScale(scale, centerX, centerY)
        } else {
            updatePosition(x, y, true)
        }
    }

    private fun measureDefaultScale(): Int {
        val widthScale  = defaultArea.width().toFloat()  / 1024 * 100
        val heightScale = defaultArea.height().toFloat() / 1024 * 100
        return minOf(widthScale, heightScale).roundToInt()
    }

    fun getInfo(): Triple<String, Int, Int> = Triple(
        if (inputId == "dpad") "Directional Pad" else "Face Buttons",
        GeneralSettings["${inputId}_scale"].int(measureDefaultScale()),
        GeneralSettings["${inputId}_opacity"].int(50)
    )

    private fun updateBounds() {
        // Top arm
        rectTop.set(
            area.centerX() - buttonWidth / 2, area.top,
            area.centerX() + buttonWidth / 2, area.top + buttonHeight
        )
        // Bottom arm
        rectBottom.set(
            area.centerX() - buttonWidth / 2, area.bottom - buttonHeight,
            area.centerX() + buttonWidth / 2, area.bottom
        )
        // Left arm
        rectLeft.set(
            area.left, area.centerY() - buttonWidth / 2,
            area.left + buttonHeight, area.centerY() + buttonWidth / 2
        )
        // Right arm
        rectRight.set(
            area.right - buttonHeight, area.centerY() - buttonWidth / 2,
            area.right, area.centerY() + buttonWidth / 2
        )
    }

    override fun onTouch(event: MotionEvent, pointerIndex: Int, padState: State): Boolean {
        val action = event.actionMasked
        var hit = false

        for (touchIndex in 0..1) {
            if (!multitouch && touchIndex > 0) break

            var activePointerIndex = pointerIndex
            if (locked[touchIndex] != -1 && action == MotionEvent.ACTION_MOVE) {
                activePointerIndex = -1
                for (i in 0 until event.pointerCount) {
                    if (locked[touchIndex] == event.getPointerId(i)) { activePointerIndex = i; break }
                }
                if (activePointerIndex == -1) continue
            }

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ||
                (action == MotionEvent.ACTION_MOVE && locked[touchIndex] != -1)) {

                if (action != MotionEvent.ACTION_MOVE) {
                    if (locked[touchIndex] == -1) locked[touchIndex] = event.getPointerId(pointerIndex)
                    else if (locked[touchIndex] != event.getPointerId(pointerIndex)) continue
                }

                val x = event.getX(activePointerIndex)
                val y = event.getY(activePointerIndex)

                val leftDistance  = x - area.left
                val topDistance   = y - area.top
                val bottomDistance= area.bottom - y
                val rightDistance = area.right  - x
                val distanceWidth = area.width() / 3.5

                val left   = leftDistance   < distanceWidth
                val right  = !left  && rightDistance  < distanceWidth
                val top    = topDistance    < distanceWidth
                val bottom = !top   && bottomDistance < distanceWidth

                hit = true
                digitalBits[touchIndex] = 0
                btnState[touchIndex].clear()

                if (top)    { btnState[touchIndex].setBtn(DpadButton.Top);    digitalBits[touchIndex] = digitalBits[touchIndex] or topBit }
                if (left)   { btnState[touchIndex].setBtn(DpadButton.Left);   digitalBits[touchIndex] = digitalBits[touchIndex] or leftBit }
                if (right)  { btnState[touchIndex].setBtn(DpadButton.Right);  digitalBits[touchIndex] = digitalBits[touchIndex] or rightBit }
                if (bottom) { btnState[touchIndex].setBtn(DpadButton.Bottom); digitalBits[touchIndex] = digitalBits[touchIndex] or bottomBit }

            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (locked[touchIndex] != -1 && (action == MotionEvent.ACTION_CANCEL || event.getPointerId(pointerIndex) == locked[touchIndex])) {
                    hit = true
                    digitalBits[touchIndex] = 0
                    btnState[touchIndex].clear()
                    locked[touchIndex] = -1
                }
            }

            if (hit) break
        }

        padState.digital[digitalIndex] =
            (padState.digital[digitalIndex] and (leftBit or rightBit or topBit or bottomBit).inv()) or digitalBits[0] or digitalBits[1]

        return hit || area.contains(event.getX(pointerIndex).toInt(), event.getY(pointerIndex).toInt())
    }

    override fun bounds(): Rect = area

    override fun draw(canvas: Canvas) {
        if (multitouch) {
            // Face buttons — four coloured circles in a diamond layout
            val topPressed   = btnState[0].isActive(DpadButton.Top)   || btnState[1].isActive(DpadButton.Top)
            val bottomPressed= btnState[0].isActive(DpadButton.Bottom)|| btnState[1].isActive(DpadButton.Bottom)
            val leftPressed  = btnState[0].isActive(DpadButton.Left)  || btnState[1].isActive(DpadButton.Left)
            val rightPressed = btnState[0].isActive(DpadButton.Right) || btnState[1].isActive(DpadButton.Right)

            val alpha = idleAlpha
            val (triBg, triBor) = GlassButtonRenderer.triangleColors()
            val (croBg, croBor) = GlassButtonRenderer.crossColors()
            val (sqBg,  sqBor)  = GlassButtonRenderer.squareColors()
            val (cirBg, cirBor) = GlassButtonRenderer.circleColors()

            GlassButtonRenderer.drawFaceButton(canvas, rectTop,    topPressed,    "△", triBg, triBor, alpha)
            GlassButtonRenderer.drawFaceButton(canvas, rectBottom, bottomPressed, "✕", croBg, croBor, alpha)
            GlassButtonRenderer.drawFaceButton(canvas, rectLeft,   leftPressed,   "□", sqBg,  sqBor,  alpha)
            GlassButtonRenderer.drawFaceButton(canvas, rectRight,  rightPressed,  "○", cirBg, cirBor, alpha)
        } else {
            // D-pad — glass arms with directional arrows
            val topPressed   = btnState[0].isActive(DpadButton.Top)   || btnState[1].isActive(DpadButton.Top)
            val bottomPressed= btnState[0].isActive(DpadButton.Bottom)|| btnState[1].isActive(DpadButton.Bottom)
            val leftPressed  = btnState[0].isActive(DpadButton.Left)  || btnState[1].isActive(DpadButton.Left)
            val rightPressed = btnState[0].isActive(DpadButton.Right) || btnState[1].isActive(DpadButton.Right)

            val alpha = idleAlpha

            // Top arm: rounded top corners only
            GlassButtonRenderer.drawDpadArm(canvas, rectTop, topPressed, "▲",
                topLeft = true, topRight = true, bottomLeft = false, bottomRight = false, overlayAlpha = alpha)
            // Bottom arm: rounded bottom corners only
            GlassButtonRenderer.drawDpadArm(canvas, rectBottom, bottomPressed, "▼",
                topLeft = false, topRight = false, bottomLeft = true, bottomRight = true, overlayAlpha = alpha)
            // Left arm: rounded left corners only
            GlassButtonRenderer.drawDpadArm(canvas, rectLeft, leftPressed, "◀",
                topLeft = true, topRight = false, bottomLeft = true, bottomRight = false, overlayAlpha = alpha)
            // Right arm: rounded right corners only
            GlassButtonRenderer.drawDpadArm(canvas, rectRight, rightPressed, "▶",
                topLeft = false, topRight = true, bottomLeft = false, bottomRight = true, overlayAlpha = alpha)
        }
    }
}
