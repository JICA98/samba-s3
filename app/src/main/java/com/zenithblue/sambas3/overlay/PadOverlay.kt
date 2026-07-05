package com.zenithblue.sambas3.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import com.zenithblue.sambas3.Digital1Flags
import com.zenithblue.sambas3.Digital2Flags
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.int
import kotlin.math.min
import kotlin.math.roundToInt

private const val idleAlpha = (0.3 * 255).toInt()

data class State(
    val digital: IntArray = IntArray(2),
    var leftStickX: Int  = 127,
    var leftStickY: Int  = 127,
    var rightStickX: Int = 127,
    var rightStickY: Int = 127
)

interface PadOverlayItem {
    fun draw(canvas: Canvas)
    fun updatePosition(x: Int, y: Int, force: Boolean = false)
    fun startDragging(startX: Int, startY: Int)
    fun stopDragging()
    fun setScale(percent: Int)
    fun setOpacity(percent: Int)
    fun resetConfigs()
    fun onTouch(event: MotionEvent, pointerIndex: Int, padState: State): Boolean
    fun contains(x: Int, y: Int): Boolean
    fun bounds(): Rect
    var dragging: Boolean
    var enabled: Boolean
}

/**
 * Full-screen controller overlay that renders the glassmorphic design from
 * ps3_controller_overlay.html.
 *
 * Layout mirrors the HTML CSS positions, scaled proportionally to the physical
 * screen size so every button is correctly sized and touch-safe (≥ 44dp HIG min).
 *
 * All touch logic, floating sticks, edit mode, fade behaviour, and SharedPrefs
 * persistence are preserved from the original implementation.
 */
class PadOverlay(context: Context?, attrs: AttributeSet?) : SurfaceView(context, attrs) {

    private val buttons: Array<PadOverlayButton>
    private val dpad: PadOverlayDpad
    private val triangleSquareCircleCross: PadOverlayDpad
    private val editables: Array<PadOverlayItem>
    private val state = State()
    private val leftStick:  PadOverlayStick
    private val rightStick: PadOverlayStick
    private val floatingSticks = arrayOf<PadOverlayStick?>(null, null)
    private val sticks = mutableListOf<PadOverlayStick>()

    private val prefs by lazy { context!!.getSharedPreferences("PadOverlayPrefs", Context.MODE_PRIVATE) }
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var selectedInput: PadOverlayItem? = null
        set(value) { field = value; onSelectedInputChange?.invoke(value) }

    var onSelectedInputChange: ((Any?) -> Unit)? = null
    var isEditing = false

    private var fadeHandler:  Handler? = null
    private var fadeRunnable: Runnable? = null
    private var isOverlayVisible = true
    private var lastTouchTime = System.currentTimeMillis()

    private val fadeDuration = 500L
    private val fadeTimeout  = 19_000L

    private val outlinePaint = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val yellowOutlinePaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f
    }

    // ─── Placeholder bitmap used only to satisfy the Dpad constructor ─────
    private fun emptyBitmap(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    init {
        val metrics    = context!!.resources.displayMetrics
        val totalW     = metrics.widthPixels
        val totalH     = metrics.heightPixels
        val sizeHint   = min(totalH, totalW)

        // ── Base unit: 1/8 of short side, capped so 3×btnSize fits in 40% of screen height.
        //    The D-pad occupies 3 cells vertically, so we ensure 3*btnSize ≤ 0.40*totalH.
        val btnSizeRaw = sizeHint / 8
        val btnSizeMax = (totalH * 0.40f / 3f).toInt()   // largest size that keeps dpad on screen
        val btnSize    = minOf(btnSizeRaw, btnSizeMax)

        val shoulderW  = (btnSize * 1.55f).toInt()  // ~72px / 46px ≈ 1.56 ratio from HTML
        val shoulderH  = (btnSize * 0.78f).toInt()  // --shoulder-h 40 / --btn-size 52 ≈ 0.77
        val centerH    = (btnSize * 0.69f).toInt()  // --center-h 36 / 52
        val centerW    = (btnSize * 1.00f).toInt()  // --center-w 52 / 52
        val psSize     = (btnSize * 0.92f).toInt()  // --ps-size 46 / 52
        val stickSize  = (btnSize * 1.85f).toInt()  // --stick-size 96 / 52
        val l3r3Size   = (btnSize * 1.85f).toInt()  // fixed sticks size matched to floating stick size (enlarged to 1.85)

        // Horizontal safe margins (like safe-area-inset-left/right in HTML)
        val sideMargin = (totalW * 0.038f).toInt()  // ~14px on 360dp
        val topMargin  = (sizeHint * 0.018f).toInt() // ~10px

        // ── Shoulder buttons ─────────────────────────────────────────────────
        // HTML: top=10+safe, stacked vertically with 58px gap (10+40+8)
        val shoulderGap = (shoulderH * 0.50f).toInt()
        val btnL2X = sideMargin
        val btnL2Y = topMargin
        val btnL1X = sideMargin
        val btnL1Y = topMargin + shoulderH + shoulderGap

        val btnR2X = totalW - sideMargin - shoulderW
        val btnR2Y = topMargin
        val btnR1X = totalW - sideMargin - shoulderW
        val btnR1Y = btnR2Y + shoulderH + shoulderGap

        // ── D-pad — bottom-left ──────────────────────────────────────────────
        // HTML: bottom ~ 110px safe, left ~ 18px safe
        // Each dpad arm is dpadCell × dpadCell; total cross = 3×dpadCell
        val dpadCell  = btnSize                 // one arm cell = btn size
        val dpadW     = dpadCell * 3
        val dpadH     = dpadCell * 3
        // bottom margin from HTML: ~110px on 832px tall ≈ 13.2%; use 13%
        val dpadBotMargin = (totalH * 0.13f).toInt()
        // Clamp: never let the dpad go above y=topMargin or below the screen
        val dpadX     = sideMargin.coerceIn(0, (totalW - dpadW).coerceAtLeast(0))
        val dpadY     = (totalH - dpadBotMargin - dpadH).coerceIn(topMargin, (totalH - dpadH).coerceAtLeast(topMargin))

        // ── Face buttons — bottom-right ──────────────────────────────────────
        // HTML: bottom ~ 108px safe, right ~ 18px safe
        // faceAreaW = btnSize*2 + gap; gap set to 70% of btnSize to avoid cluttering/overlapping diagonally
        val faceGap    = (btnSize * 0.70f).toInt()
        val faceAreaW  = btnSize * 2 + faceGap
        val faceAreaH  = faceAreaW
        val faceBotMargin = (totalH * 0.13f).toInt()
        val faceX      = (totalW - sideMargin - faceAreaW - (btnSize * 0.4f).toInt()).coerceIn(0, (totalW - faceAreaW).coerceAtLeast(0))
        val faceY      = (totalH - faceBotMargin - faceAreaH).coerceIn(topMargin, (totalH - faceAreaH).coerceAtLeast(topMargin))

        // ── Center cluster (SELECT / PS / START) — centered ──────────────────
        // HTML: bottom ~ 62px safe
        val centerGap       = (btnSize * 0.23f).toInt()   // 12px on 52px
        val clusterTotalW   = centerW + centerGap + psSize + centerGap + centerW
        val clusterX        = (totalW / 2 - clusterTotalW / 2).coerceIn(0, (totalW - clusterTotalW).coerceAtLeast(0))
        val clusterY        = topMargin.coerceIn(0, totalH - maxOf(centerH, psSize))

        val btnSelectX = clusterX
        val btnSelectY = (clusterY + (maxOf(centerH, psSize) - centerH) / 2).coerceIn(0, totalH - centerH)
        val btnPsX     = (clusterX + centerW + centerGap).coerceIn(0, totalW - psSize)
        val btnPsY     = clusterY.coerceIn(0, totalH - psSize)
        val btnStartX  = (btnPsX + psSize + centerGap).coerceIn(0, totalW - centerW)
        val btnStartY  = btnSelectY

        // ── Analog sticks ────────────────────────────────────────────────────
        // HTML: left  → bottom 14px, left 58px  (58/360 ≈ 16% from left)
        //       right → bottom 14px, right 120px (120/360 ≈ 33% from right)
        val stickBotMargin = (totalH * 0.017f).toInt()   // 14px / 832px ≈ 1.7%
        val lStickX = (totalW * 0.16f).toInt().coerceIn(0, totalW - stickSize)
        val lStickY = (totalH - stickBotMargin - stickSize).coerceIn(0, totalH - stickSize)

        // right stick: right edge 33% from right
        val rStickX = (totalW - (totalW * 0.33f).toInt() - stickSize).coerceIn(0, totalW - stickSize)
        val rStickY = (totalH - stickBotMargin - stickSize).coerceIn(0, totalH - stickSize)

        // ── Fixed L3/R3 (named sticks with click-press) ───────────────────────
        // Left L3: to the right of the dpad — between dpad right edge and center
        val l3x = (dpadX + dpadW + (btnSize * 0.3f).toInt()).coerceIn(0, totalW - l3r3Size)
        val l3y = (totalH - stickBotMargin - l3r3Size).coerceIn(0, totalH - l3r3Size)
        // Right R3: to the left of the face buttons
        val r3x = (faceX - l3r3Size - (btnSize * 0.3f).toInt()).coerceIn(0, totalW - l3r3Size)
        val r3y = l3y

        val empty = emptyBitmap(4)

        // ── D-pad ─────────────────────────────────────────────────────────────
        // buttonWidth/Height = the arm dimensions (each arm is dpadCell wide, dpadCell tall)
        dpad = createDpad(
            "dpad", dpadX, dpadY, dpadW, dpadH,
            dpadCell, dpadCell,   // arm width = cell size, arm height = cell size
            digitalIndex = 0,
            empty, Digital1Flags.CELL_PAD_CTRL_UP.bit,
            empty, Digital1Flags.CELL_PAD_CTRL_LEFT.bit,
            empty, Digital1Flags.CELL_PAD_CTRL_RIGHT.bit,
            empty, Digital1Flags.CELL_PAD_CTRL_DOWN.bit,
            multitouch = false
        )

        // ── Face buttons (triangle / square / circle / cross diamond) ─────────
        triangleSquareCircleCross = createDpad(
            "triangleSquareCircleCross",
            faceX, faceY, faceAreaW, faceAreaH,
            btnSize, btnSize,
            digitalIndex = 1,
            empty, Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit,
            empty, Digital2Flags.CELL_PAD_CTRL_SQUARE.bit,
            empty, Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit,
            empty, Digital2Flags.CELL_PAD_CTRL_CROSS.bit,
            multitouch = true
        )

        // ── Floating analog sticks ────────────────────────────────────────────
        leftStick  = PadOverlayStick(resources, true,  empty, empty)
        rightStick = PadOverlayStick(resources, false, empty, empty)
        leftStick.setBounds(lStickX, lStickY, lStickX + stickSize, lStickY + stickSize)
        leftStick.alpha = idleAlpha
        rightStick.setBounds(rStickX, rStickY, rStickX + stickSize, rStickY + stickSize)
        rightStick.alpha = idleAlpha

        // ── Fixed L3/R3 sticks ────────────────────────────────────────────────
        val l3 = PadOverlayStick(resources, true,  empty, empty,
            pressDigitalIndex = 0, pressBit = Digital1Flags.CELL_PAD_CTRL_L3.bit)
        l3.alpha = idleAlpha
        l3.setBounds(l3x, l3y, l3x + l3r3Size, l3y + l3r3Size)

        val r3 = PadOverlayStick(resources, false, empty, empty,
            pressDigitalIndex = 0, pressBit = Digital1Flags.CELL_PAD_CTRL_R3.bit)
        r3.alpha = idleAlpha
        r3.setBounds(r3x, r3y, r3x + l3r3Size, r3y + l3r3Size)

        sticks += l3
        sticks += r3

        // ── Buttons array ─────────────────────────────────────────────────────
        buttons = arrayOf(
            createButton(
                GlassButtonType.CENTER_START, "START",
                btnStartX, btnStartY, centerW, centerH,
                Digital1Flags.CELL_PAD_CTRL_START, Digital2Flags.None
            ),
            createButton(
                GlassButtonType.CENTER_SELECT, "SELECT",
                btnSelectX, btnSelectY, centerW, centerH,
                Digital1Flags.CELL_PAD_CTRL_SELECT, Digital2Flags.None
            ),
            createButton(
                GlassButtonType.PS_HOME, "PS",
                btnPsX, btnPsY, psSize, psSize,
                Digital1Flags.CELL_PAD_CTRL_PS, Digital2Flags.None
            ),
            createButton(
                GlassButtonType.SHOULDER, "L1",
                btnL1X, btnL1Y, shoulderW, shoulderH,
                Digital1Flags.None, Digital2Flags.CELL_PAD_CTRL_L1
            ),
            createButton(
                GlassButtonType.SHOULDER, "L2",
                btnL2X, btnL2Y, shoulderW, shoulderH,
                Digital1Flags.None, Digital2Flags.CELL_PAD_CTRL_L2
            ),
            createButton(
                GlassButtonType.SHOULDER, "R1",
                btnR1X, btnR1Y, shoulderW, shoulderH,
                Digital1Flags.None, Digital2Flags.CELL_PAD_CTRL_R1
            ),
            createButton(
                GlassButtonType.SHOULDER, "R2",
                btnR2X, btnR2Y, shoulderW, shoulderH,
                Digital1Flags.None, Digital2Flags.CELL_PAD_CTRL_R2
            ),
        )

        editables = arrayOf(*buttons, dpad, triangleSquareCircleCross)
        setWillNotDraw(false)
        requestFocus()
        setupTouchListener(totalW, totalH, sideMargin, btnSize)
    }

    // ── Touch listener (unchanged logic from original) ─────────────────────
    private fun setupTouchListener(totalW: Int, totalH: Int, sideMargin: Int, buttonSize: Int) {
        setOnTouchListener { _, motionEvent ->
            var hit = false

            if (!isEditing) {
                lastTouchTime = System.currentTimeMillis()
                resetFadeTimer()
                if (!isOverlayVisible) fadeInOverlay()
            }

            val action = motionEvent.actionMasked
            val pointerIndex =
                if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
                    motionEvent.actionIndex else 0
            val x = motionEvent.getX(pointerIndex).toInt()
            val y = motionEvent.getY(pointerIndex).toInt()

            if (isEditing) {
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        editables.forEach { editable ->
                            if (editable.contains(x, y)) {
                                selectedInput = editable
                                editable.startDragging(x, y)
                                hit = true
                            }
                        }
                        if (!hit) { selectedInput = null; hit = true }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        editables.forEach { editable ->
                            if (editable.dragging) { editable.updatePosition(x, y); hit = true }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        editables.forEach { it.stopDragging() }
                    }
                }
                if (hit) invalidate()
                return@setOnTouchListener true
            }

            val force = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ||
                    action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_MOVE

            editables.forEach { editable ->
                if (force || (!hit && editable.contains(x, y) && editable.enabled)) {
                    hit = editable.onTouch(motionEvent, pointerIndex, state)
                }
            }

            if (hit && GeneralSettings["haptic_feedback"] as Boolean? ?: true) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }

            if (force || !hit) {
                for (i in sticks.indices) {
                    if (!force && (!sticks[i].contains(x, y) || floatingSticks[i] != null)) continue
                    val touchResult = sticks[i].onTouch(motionEvent, pointerIndex, state)
                    hit = if (touchResult < 0) true else touchResult == 1
                }
            }

            if (force || !hit) {
                for (i in floatingSticks.indices) {
                    val stick = floatingSticks[i] ?: continue
                    val touchResult = stick.onTouch(motionEvent, pointerIndex, state)
                    if (touchResult < 0) { floatingSticks[i] = null; hit = true }
                    else hit = touchResult == 1
                }
            }

            RPCSX.instance.overlayPadData(
                state.digital[0], state.digital[1],
                state.leftStickX, state.leftStickY,
                state.rightStickX, state.rightStickY
            )

            if (!hit && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
                val xInFloatingArea = x > buttonSize * 2 && x < totalW - buttonSize * 2
                val yInFloatingArea = y > buttonSize    && y < totalH - buttonSize
                var inFloatingArea  = xInFloatingArea && yInFloatingArea
                if (!inFloatingArea && yInFloatingArea) {
                    if (x > buttonSize && x <= buttonSize * 2) inFloatingArea = true
                    if (x <= totalW - buttonSize && x >= totalW - buttonSize * 2) inFloatingArea = true
                }

                if (inFloatingArea) {
                    val stickIndex = if (x <= totalW / 2) 0 else 1
                    val stick = if (stickIndex == 0) leftStick else rightStick
                    if (floatingSticks[stickIndex] == null && !sticks[stickIndex].isActive()) {
                        floatingSticks[stickIndex] = stick
                        stick.onAdd(motionEvent, pointerIndex)
                        hit = true
                    }
                }
            }

            if (hit || force) invalidate()
            hit || performClick()
        }
    }

    // ── Drawing ────────────────────────────────────────────────────────────
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        editables.forEach { editable ->
            if (editable.enabled) editable.draw(canvas)
            else createOutline(isEditing, editable.bounds(), canvas, yellowOutlinePaint)
        }
        sticks.forEach         { it.draw(canvas) }
        floatingSticks.forEach { it?.draw(canvas) }

        if (isEditing) {
            if (selectedInput != null) {
                createOutline(true, selectedInput!!.bounds(), canvas, outlinePaint)
            } else {
                editables.forEach { editable -> createOutline(true, editable.bounds(), canvas, outlinePaint) }
            }
        }
    }

    private fun createOutline(shouldApply: Boolean, bounds: Rect, canvas: Canvas, paint: Paint) {
        if (shouldApply) canvas.drawRect(bounds, paint)
    }

    // ── Factory helpers ────────────────────────────────────────────────────

    private fun createButton(
        buttonType: GlassButtonType,
        label: String,
        x: Int, y: Int, width: Int, height: Int,
        digital1: Digital1Flags,
        digital2: Digital2Flags
    ): PadOverlayButton {
        val result = PadOverlayButton(resources, digital1.bit, digital2.bit, buttonType, label)
        val scale   = GeneralSettings["button_${digital1.bit}_${digital2.bit}_scale"].int(0)
        val alpha   = GeneralSettings["button_${digital1.bit}_${digital2.bit}_opacity"].int(50)
        val savedX  = GeneralSettings["button_${digital1.bit}_${digital2.bit}_x"].int(x)
        val savedY  = GeneralSettings["button_${digital1.bit}_${digital2.bit}_y"].int(y)
        result.setBounds(savedX, savedY, savedX + width, savedY + height)
        result.defaultPosition = Pair(x, y)
        result.defaultSize     = Pair(height, width)
        if (scale != 0) result.setScale(scale)
        result.setOpacity(alpha)
        return result
    }

    private fun createDpad(
        inputId: String,
        x: Int, y: Int, width: Int, height: Int,
        buttonWidth: Int, buttonHeight: Int,
        digitalIndex: Int,
        imgTop: Bitmap, topBit: Int,
        imgLeft: Bitmap, leftBit: Int,
        imgRight: Bitmap, rightBit: Int,
        imgBottom: Bitmap, bottomBit: Int,
        multitouch: Boolean
    ): PadOverlayDpad {
        val result = PadOverlayDpad(
            resources, buttonWidth, buttonHeight, inputId,
            Rect(x, y, x + width, y + height), digitalIndex,
            imgTop, topBit,
            imgLeft, leftBit,
            imgRight, rightBit,
            imgBottom, bottomBit,
            multitouch
        )
        val alpha = GeneralSettings["${inputId}_opacity"].int(-1)
        result.setOpacity(if (alpha != -1) alpha else 50)
        return result
    }

    // ── Fade management ────────────────────────────────────────────────────
    private fun resetFadeTimer() {
        fadeHandler?.removeCallbacks(fadeRunnable!!)
        fadeHandler = Handler(Looper.getMainLooper())
        fadeRunnable = Runnable { fadeOutOverlay() }
        fadeHandler?.postDelayed(fadeRunnable!!, fadeTimeout)
    }

    private fun fadeOutOverlay() {
        if (!isOverlayVisible) return
        isOverlayVisible = false
        ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply { duration = fadeDuration; start() }
    }

    private fun fadeInOverlay() {
        if (isOverlayVisible) return
        isOverlayVisible = true
        ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply { duration = fadeDuration; start() }
    }

    // ── Edit-mode APIs (unchanged) ─────────────────────────────────────────
    fun setButtonScale(value: Int)    { selectedInput!!.setScale(value);  invalidate() }
    fun setButtonOpacity(value: Int)  { selectedInput!!.setOpacity(value); invalidate() }

    fun resetButtonConfigs() {
        if (selectedInput != null) selectedInput!!.resetConfigs()
        else editables.forEach { it.resetConfigs() }
        invalidate()
    }

    fun moveButtonLeft() {
        if (selectedInput != null) {
            val b = selectedInput!!.bounds()
            selectedInput!!.updatePosition(b.left - 1, b.top, true)
        } else { editables.forEach { selectedInput = it; moveButtonLeft() }; selectedInput = null }
        invalidate()
    }

    fun moveButtonRight() {
        if (selectedInput != null) {
            val b = selectedInput!!.bounds()
            selectedInput!!.updatePosition(b.left + 1, b.top, true)
        } else { editables.forEach { selectedInput = it; moveButtonRight() }; selectedInput = null }
        invalidate()
    }

    fun moveButtonUp() {
        if (selectedInput != null) {
            val b = selectedInput!!.bounds()
            selectedInput!!.updatePosition(b.left, b.top - 1, true)
        } else { editables.forEach { selectedInput = it; moveButtonUp() }; selectedInput = null }
        invalidate()
    }

    fun moveButtonDown() {
        if (selectedInput !== null) {
            val b = selectedInput!!.bounds()
            selectedInput!!.updatePosition(b.left, b.top + 1, true)
        } else { editables.forEach { selectedInput = it; moveButtonDown() }; selectedInput = null }
        invalidate()
    }

    fun enableButton(value: Boolean) {
        selectedInput!!.enabled = value
        invalidate()
    }
}
