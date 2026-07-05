package com.zenithblue.sambas3.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog stick that renders a glassmorphic ring + nub via [GlassButtonRenderer],
 * matching `.stick-ring` / `.stick-nub` from ps3_controller_overlay.html.
 *
 * Touch logic (floating stick, L3/R3 press on lift, radius clamping) is
 * unchanged from the original implementation.
 */
class PadOverlayStick(
    @Suppress("UNUSED_PARAMETER") resources: Resources,
    private val isLeft: Boolean,
    @Suppress("UNUSED_PARAMETER") bg: Bitmap,       // kept for API compatibility; not used for drawing
    @Suppress("UNUSED_PARAMETER") stick: Bitmap,    // kept for API compatibility; not used for drawing
    private val pressDigitalIndex: Int = 0,
    private val pressBit: Int = 0
) {
    /** Outer ring bounds (also doubles as the stick's touch region). */
    private val ringBounds = Rect()

    /** Nub bounds — starts at center of ring, moves with touch. */
    private val nubBounds  = Rect()

    /** Current alpha applied to the whole stick (ring + nub). */
    var alpha: Int = (0.3f * 255).toInt()
        set(value) { field = value }

    private var locked   = -1
    private var pressX   = -1
    private var pressY   = -1
    private var bgOffsetX= 0
    private var bgOffsetY= 0

    // Nub radius is ~48% of ring radius, matching --stick-inner / --stick-size ratio (46/96≈0.48)
    private val nubRadiusRatio = 0.48f

    fun contains(x: Int, y: Int) = ringBounds.contains(x, y)
    fun isActive(): Boolean = locked != -1

    /** Size of the ring (used to restore nub after releasing). */
    private val ringSize get() = ringBounds.width()

    private fun centreNub() {
        val cx = ringBounds.centerX()
        val cy = ringBounds.centerY()
        val nr = (ringSize * nubRadiusRatio / 2).toInt()
        nubBounds.set(cx - nr, cy - nr, cx + nr, cy + nr)
    }

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        ringBounds.set(left, top, right, bottom)
        centreNub()
    }

    fun setBounds(r: Rect) = setBounds(r.left, r.top, r.right, r.bottom)

    fun onAdd(event: MotionEvent, pointerIndex: Int) {
        locked = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex).toInt()
        val y = event.getY(pointerIndex).toInt()
        pressX = x; pressY = y

        val hw = ringBounds.width()  / 2
        val hh = ringBounds.height() / 2
        ringBounds.set(x - hw, y - hh, x + hw, y + hh)
        centreNub()
    }

    fun onTouch(event: MotionEvent, pointerIndex: Int, padState: State): Int {
        val action = event.actionMasked

        if ((pressBit != 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) ||
            (locked != -1 && action == MotionEvent.ACTION_MOVE)) {

            var activePointerIndex = pointerIndex

            if (action != MotionEvent.ACTION_MOVE) {
                if (locked == -1) {
                    locked = event.getPointerId(pointerIndex)
                    pressX = event.getX(pointerIndex).toInt()
                    pressY = event.getY(pointerIndex).toInt()
                    bgOffsetX = ringBounds.centerX() - pressX
                    bgOffsetY = ringBounds.centerY() - pressY
                    // Shift ring to touch point
                    ringBounds.offset(-bgOffsetX, -bgOffsetY)
                    centreNub()
                } else if (locked != event.getPointerId(pointerIndex)) {
                    return 0
                }
            } else {
                for (i in 0 until event.pointerCount) {
                    if (locked == event.getPointerId(i)) { activePointerIndex = i; break }
                }
                if (activePointerIndex == -1) return 0
            }

            padState.digital[pressDigitalIndex] = padState.digital[pressDigitalIndex] or pressBit

            var dx = event.getX(activePointerIndex) - pressX
            var dy = event.getY(activePointerIndex) - pressY

            val bgR  = ringBounds.width() / 2f
            val dist = hypot(dx, dy)
            if (dist > bgR) {
                val L = atan2(dy, dx)
                dx = bgR * cos(L)
                dy = bgR * sin(L)
            }

            val stickX = ((dx / bgR) * 127 + 128).toInt().coerceIn(0, 255)
            val stickY = ((dy / bgR) * 127 + 128).toInt().coerceIn(0, 255)

            if (isLeft) { padState.leftStickX  = stickX; padState.leftStickY  = stickY }
            else        { padState.rightStickX = stickX; padState.rightStickY = stickY }

            // Move nub
            val nx = (pressX + dx).toInt()
            val ny = (pressY + dy).toInt()
            val nr = (ringBounds.width() * nubRadiusRatio / 2).toInt()
            nubBounds.set(nx - nr, ny - nr, nx + nr, ny + nr)

            return 1
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (locked != -1 && (action == MotionEvent.ACTION_CANCEL || event.getPointerId(pointerIndex) == locked)) {
                locked = -1

                // Restore ring to original offset position
                ringBounds.offset(bgOffsetX, bgOffsetY)
                bgOffsetX = 0; bgOffsetY = 0
                centreNub()

                padState.digital[pressDigitalIndex] = padState.digital[pressDigitalIndex] and pressBit.inv()

                if (isLeft) { padState.leftStickX  = 127; padState.leftStickY  = 127 }
                else        { padState.rightStickX = 127; padState.rightStickY = 127 }

                return -1
            }
        }

        return 0
    }

    fun draw(canvas: Canvas) {
        GlassButtonRenderer.drawStickRing(canvas, ringBounds, alpha)
        GlassButtonRenderer.drawStickNub(canvas, nubBounds, if (isLeft) "L3" else "R3", alpha)
    }
}
