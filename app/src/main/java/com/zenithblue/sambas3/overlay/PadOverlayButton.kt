package com.zenithblue.sambas3.overlay

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import com.zenithblue.sambas3.Digital1Flags
import kotlin.math.roundToInt
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.boolean
import com.zenithblue.sambas3.utils.InputBindingPrefs

/**
 * A controller button that renders itself via [GlassButtonRenderer] using
 * procedural Canvas drawing rather than a bitmap, matching the glassmorphic
 * design of ps3_controller_overlay.html.
 *
 * [buttonType] determines which drawing path is used:
 *   FACE_TRIANGLE, FACE_CROSS, FACE_SQUARE, FACE_CIRCLE → colored circular buttons
 *   SHOULDER                                             → rounded rectangle
 *   CENTER_SELECT, CENTER_START                          → pill shape
 *   PS_HOME                                              → PS button with purple glow
 */
enum class GlassButtonType {
    FACE_TRIANGLE, FACE_CROSS, FACE_SQUARE, FACE_CIRCLE,
    SHOULDER,
    CENTER_SELECT, CENTER_START,
    PS_HOME
}

class PadOverlayButton(
    resources: Resources,
    private val digital1: Int,
    private val digital2: Int,
    val buttonType: GlassButtonType = GlassButtonType.SHOULDER,
    private val label: String = ""
) : Drawable(), PadOverlayItem {

    private var pressed = false
    private var locked  = -1
    private var _alpha  = (0.3f * 255).toInt()   // default idle alpha (matches idleAlpha)
    override var dragging = false
    private var offsetX = 0
    private var offsetY = 0
    private var _bounds = Rect()

    var defaultSize: Pair<Int, Int> = Pair(-1, -1)
    lateinit var defaultPosition: Pair<Int, Int>

    override var enabled: Boolean = GeneralSettings["button_${digital1}_${digital2}_enabled"].boolean(true)
        set(value) {
            field = value
            GeneralSettings.setValue("button_${digital1}_${digital2}_enabled", value)
        }

    override fun bounds(): Rect = _bounds
    override fun contains(x: Int, y: Int) = _bounds.contains(x, y)
    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        _bounds.set(left, top, right, bottom)
    }
    override fun setBounds(bounds: Rect) = setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)

    override fun getAlpha(): Int = _alpha
    override fun setAlpha(alpha: Int) { _alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { /* not used */ }
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        if (_bounds.isEmpty) return
        when (buttonType) {
            GlassButtonType.FACE_TRIANGLE -> {
                val (bg, bor) = GlassButtonRenderer.triangleColors()
                GlassButtonRenderer.drawFaceButton(canvas, _bounds, pressed, "△", bg, bor, _alpha)
            }
            GlassButtonType.FACE_CROSS -> {
                val (bg, bor) = GlassButtonRenderer.crossColors()
                GlassButtonRenderer.drawFaceButton(canvas, _bounds, pressed, "✕", bg, bor, _alpha)
            }
            GlassButtonType.FACE_SQUARE -> {
                val (bg, bor) = GlassButtonRenderer.squareColors()
                GlassButtonRenderer.drawFaceButton(canvas, _bounds, pressed, "□", bg, bor, _alpha)
            }
            GlassButtonType.FACE_CIRCLE -> {
                val (bg, bor) = GlassButtonRenderer.circleColors()
                GlassButtonRenderer.drawFaceButton(canvas, _bounds, pressed, "○", bg, bor, _alpha)
            }
            GlassButtonType.SHOULDER -> {
                GlassButtonRenderer.drawShoulderButton(canvas, _bounds, pressed, label, _alpha)
            }
            GlassButtonType.CENTER_SELECT -> {
                GlassButtonRenderer.drawCenterButton(canvas, _bounds, pressed, "SELECT", _alpha)
            }
            GlassButtonType.CENTER_START -> {
                GlassButtonRenderer.drawCenterButton(canvas, _bounds, pressed, "START", _alpha)
            }
            GlassButtonType.PS_HOME -> {
                GlassButtonRenderer.drawPsButton(canvas, _bounds, pressed, _alpha)
            }
        }
    }

    override fun onTouch(event: MotionEvent, pointerIndex: Int, padState: State): Boolean {
        val action = event.actionMasked
        var hit = false
        val origAlpha = _alpha
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (locked == -1) {
                locked = event.getPointerId(pointerIndex)
                pressed = true
                _alpha = 255
                hit = true
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (locked != -1 && (action == MotionEvent.ACTION_CANCEL || event.getPointerId(pointerIndex) == locked)) {
                pressed = false
                locked = -1
                _alpha = origAlpha.coerceAtMost((0.3f * 255).toInt()).coerceAtLeast(
                    (GeneralSettings["button_${digital1}_${digital2}_opacity"] as Int? ?: 50)
                        .let { (255 * it / 100) }
                )
                hit = true
            }
        }

        if (pressed) {
            padState.digital[0] = padState.digital[0] or digital1
            padState.digital[1] = padState.digital[1] or digital2
        } else {
            padState.digital[0] = padState.digital[0] and digital1.inv()
            padState.digital[1] = padState.digital[1] and digital2.inv()
        }

        return hit
    }

    override fun startDragging(startX: Int, startY: Int) {
        dragging = true
        offsetX = startX - _bounds.left
        offsetY = startY - _bounds.top
    }

    override fun updatePosition(x: Int, y: Int, force: Boolean) {
        if (dragging) {
            val l = x - offsetX; val t = y - offsetY
            _bounds.set(l, t, l + _bounds.width(), t + _bounds.height())
            GeneralSettings.setValue("button_${digital1}_${digital2}_x", l)
            GeneralSettings.setValue("button_${digital1}_${digital2}_y", t)
        } else if (force) {
            _bounds.set(x, y, x + _bounds.width(), y + _bounds.height())
            GeneralSettings.setValue("button_${digital1}_${digital2}_x", x)
            GeneralSettings.setValue("button_${digital1}_${digital2}_y", y)
        }
    }

    override fun stopDragging() { dragging = false }

    override fun setScale(percent: Int) {
        val newSize = (1024 * percent / 100f).roundToInt()
        _bounds.set(_bounds.left, _bounds.top, _bounds.left + newSize, _bounds.top + newSize)
        GeneralSettings.setValue("button_${digital1}_${digital2}_scale", percent)
    }

    override fun setOpacity(percent: Int) {
        _alpha = (255 * percent / 100f).roundToInt()
        GeneralSettings.setValue("button_${digital1}_${digital2}_opacity", percent)
    }

    fun measureDefaultScale(): Int {
        if (defaultSize.second <= 0 || defaultSize.first <= 0) return 100
        val widthScale  = defaultSize.second.toFloat() / 1024 * 100
        val heightScale = defaultSize.first.toFloat()  / 1024 * 100
        return minOf(widthScale, heightScale).roundToInt()
    }

    override fun resetConfigs() {
        setOpacity(50)
        _bounds.set(
            defaultPosition.first, defaultPosition.second,
            defaultPosition.first + defaultSize.second,
            defaultPosition.second + defaultSize.first
        )
        GeneralSettings.setValue("button_${digital1}_${digital2}_scale",   null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_opacity", null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_x",       null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_y",       null)
    }

    fun getInfo(): Triple<String, Int, Int> {
        val dn = if (digital1 == Digital1Flags.None.ordinal) 1 else 0
        return Triple(
            InputBindingPrefs.rpcsxKeyCodeToString(if (dn == 0) digital1 else digital2, dn),
            GeneralSettings["button_${digital1}_${digital2}_scale"] as Int? ?: measureDefaultScale(),
            GeneralSettings["button_${digital1}_${digital2}_opacity"] as Int? ?: 50
        )
    }
}
