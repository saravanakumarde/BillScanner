package com.billscanner.app.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * A plain ImageView with pinch-to-zoom, double-tap-to-zoom, and panning
 * while zoomed in — so a bill photo can be zoomed in on to read faint or
 * small print, the same way a person would lean in closer to look at it.
 *
 * Self-contained (no third-party dependency): all zoom/pan state lives in a
 * single Matrix applied via setImageMatrix, scaleType is forced to MATRIX.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 3f
    }

    private val matrixValues = FloatArray(9)
    private val imageMatrix2 = Matrix()
    private var currentScale = 1f

    private var baseFitScale = 1f
    private var baseFitDx = 0f
    private var baseFitDy = 0f
    private var lastDrawableW = 0
    private var lastDrawableH = 0

    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    /** Resets zoom/pan to the initial fit-to-view state; call after loading a new image. */
    fun resetZoom() {
        currentScale = 1f
        computeBaseFit()
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBaseFit()
        applyMatrix()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        currentScale = 1f
        computeBaseFit()
        applyMatrix()
    }

    private fun computeBaseFit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0 || width <= 0 || height <= 0) return
        lastDrawableW = dw
        lastDrawableH = dh
        // Fit-center math, matching the old ScaleType.FIT_CENTER look at scale=1.
        baseFitScale = min(width.toFloat() / dw, height.toFloat() / dh)
        baseFitDx = (width - dw * baseFitScale) / 2f
        baseFitDy = (height - dh * baseFitScale) / 2f
    }

    private fun applyMatrix() {
        imageMatrix2.reset()
        imageMatrix2.postScale(baseFitScale, baseFitScale)
        imageMatrix2.postTranslate(baseFitDx, baseFitDy)
        imageMatrix2.postScale(currentScale, currentScale, width / 2f, height / 2f)
        constrainTranslation()
        imageMatrix = imageMatrix2
    }

    /** Keeps the image from being dragged entirely off-screen while zoomed. */
    private fun constrainTranslation() {
        if (lastDrawableW <= 0 || lastDrawableH <= 0) return
        imageMatrix2.getValues(matrixValues)
        val scaledW = lastDrawableW * matrixValues[Matrix.MSCALE_X]
        val scaledH = lastDrawableH * matrixValues[Matrix.MSCALE_Y]
        var dx = 0f
        var dy = 0f

        if (scaledW > width) {
            val minX = width - scaledW
            val curX = matrixValues[Matrix.MTRANS_X]
            dx = when {
                curX > 0f -> -curX
                curX < minX -> minX - curX
                else -> 0f
            }
        } else {
            dx = (width - scaledW) / 2f - matrixValues[Matrix.MTRANS_X]
        }

        if (scaledH > height) {
            val minY = height - scaledH
            val curY = matrixValues[Matrix.MTRANS_Y]
            dy = when {
                curY > 0f -> -curY
                curY < minY -> minY - curY
                else -> 0f
            }
        } else {
            dy = (height - scaledH) / 2f - matrixValues[Matrix.MTRANS_Y]
        }

        if (dx != 0f || dy != 0f) imageMatrix2.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // When a second finger lifts during/after a pinch, re-anchor
                // panning to whichever pointer remains so it doesn't jump.
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && currentScale > MIN_SCALE) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        if (isDragging || kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) {
                            isDragging = true
                            imageMatrix2.postTranslate(dx, dy)
                            constrainTranslation()
                            imageMatrix = imageMatrix2
                        }
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            currentScale = newScale
            applyMatrix()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            currentScale = if (currentScale > MIN_SCALE + 0.1f) MIN_SCALE else DOUBLE_TAP_SCALE
            applyMatrix()
            return true
        }

        // Single tap dismisses the viewer, same as before — but only when
        // not zoomed in, so a tap while zoomed doesn't accidentally close it.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (currentScale <= MIN_SCALE + 0.01f) {
                performClick()
            }
            return true
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
