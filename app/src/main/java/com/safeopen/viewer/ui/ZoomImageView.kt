package com.safeopen.viewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** ImageView with pinch-zoom, pan and double-tap zoom. */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {

    private val m = Matrix()
    private var baseScale = 1f
    private var zoom = 1f
    private val maxZoom = 8f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, maxZoom)
            val f = newZoom / zoom
            zoom = newZoom
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) fit() else {
                zoom = 2.5f
                m.postScale(2.5f, 2.5f, e.x, e.y)
                clamp()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
    })

    init { scaleType = ScaleType.MATRIX }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        baseScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        zoom = 1f
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - d.intrinsicWidth * baseScale) / 2f, (height - d.intrinsicHeight * baseScale) / 2f)
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f || event.pointerCount > 1)
        return true
    }
}
