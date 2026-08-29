package com.zenithblue.sambas3

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

class GraphicsFrame : SurfaceView, SurfaceHolder.Callback {
    interface Listener {
        fun onSurfaceCreated(frame: GraphicsFrame, generation: Long, surface: Surface)
        fun onSurfaceChanged(frame: GraphicsFrame, generation: Long, surface: Surface)
        fun onSurfaceDestroyed(frame: GraphicsFrame, generation: Long, surface: Surface)
    }

    var generation: Long = 0L
    var listener: Listener? = null
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
        listener?.onSurfaceChanged(this, generation, p0.surface)
            ?: RPCSX.instance.surfaceEventV2(p0.surface, 1, generation)
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        if (!created) return
        created = false
        listener?.onSurfaceDestroyed(this, generation, p0.surface)
            ?: RPCSX.instance.surfaceEventV2(p0.surface, 2, generation)
    }
}
