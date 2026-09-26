package com.zenithblue.sambas3

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.zenithblue.sambas3.gameconfig.OutputSurfaceSize
import kotlin.math.min
import kotlin.math.roundToInt

class GraphicsFrame : SurfaceView, SurfaceHolder.Callback {
    interface Listener {
        fun onSurfaceCreated(frame: GraphicsFrame, generation: Long, surface: Surface)
        fun onSurfaceChanged(frame: GraphicsFrame, generation: Long, surface: Surface)
        fun onSurfaceDestroyed(frame: GraphicsFrame, generation: Long, surface: Surface)
    }

    var generation: Long = 0L
    var listener: Listener? = null
    var fixedOutputSize: OutputSurfaceSize? = null
        private set
    private var created = false
    val hasCreatedSurface: Boolean get() = created
    constructor(context: Context) : super(context) {
        holder.addCallback(this)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        holder.addCallback(this)
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        holder.addCallback(this)
    }

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        holder.addCallback(this)
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        if (created) return
        created = true
        listener?.onSurfaceCreated(this, generation, p0.surface)
            ?: RPCSX.instance.surfaceEventV2(p0.surface, 0, generation)
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        if (!created) return
        Log.i(
            OUTPUT_TAG,
            "surface-changed generation=$generation callback=${p2}x$p3 " +
                "view=${width}x$height fixed=${fixedOutputSize?.let { "${it.width}x${it.height}" } ?: "layout"}"
        )
        listener?.onSurfaceChanged(this, generation, p0.surface)
            ?: RPCSX.instance.surfaceEventV2(p0.surface, 1, generation)
    }

    /** Must be configured before the view is attached so the first window uses this buffer size. */
    internal fun setFixedOutputSize(size: OutputSurfaceSize?) {
        fixedOutputSize = size
        if (size == null) return
        holder.setFixedSize(size.width, size.height)
        setBackgroundColor(Color.BLACK)
        requestLayout()
    }

    /** Fit the buffer ratio inside the available host area without stretching. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val output = fixedOutputSize
        if (output == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthLimit = MeasureSpec.getSize(widthMeasureSpec)
        val heightLimit = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            widthLimit <= 0 || heightLimit <= 0
        ) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val fitScale = min(widthLimit.toDouble() / output.width, heightLimit.toDouble() / output.height)
        val measuredWidth = (output.width * fitScale).roundToInt().coerceIn(1, widthLimit)
        val measuredHeight = (output.height * fitScale).roundToInt().coerceIn(1, heightLimit)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        if (!created) return
        created = false
        listener?.onSurfaceDestroyed(this, generation, p0.surface)
            ?: RPCSX.instance.surfaceEventV2(p0.surface, 2, generation)
    }

    private companion object {
        const val OUTPUT_TAG = "S3OUTPUT"
    }
}
