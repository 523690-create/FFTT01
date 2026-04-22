package com.example.fftt01

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val borderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    var cropRect = RectF()
    private var activeHandle = HANDLE_NONE
    private val handleSize = 60f // Increased for better touch target
    private val edgeTolerance = 40f
    
    private var lastX = 0f
    private var lastY = 0f

    companion object {
        private const val HANDLE_NONE = -1
        private const val HANDLE_TOP_LEFT = 0
        private const val HANDLE_TOP_RIGHT = 1
        private const val HANDLE_BOTTOM_LEFT = 2
        private const val HANDLE_BOTTOM_RIGHT = 3
        private const val HANDLE_TOP = 4
        private const val HANDLE_BOTTOM = 5
        private const val HANDLE_LEFT = 6
        private const val HANDLE_RIGHT = 7
        private const val HANDLE_CENTER = 8
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h) * 0.9f
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        cropRect.set(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw Scrim
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

        // Draw Border
        canvas.drawRect(cropRect, borderPaint)

        // Draw Corner Handles
        val r = handleSize / 3f
        canvas.drawCircle(cropRect.left, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, handlePaint)

        // Draw Edge Handles
        canvas.drawCircle(cropRect.centerX(), cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.centerX(), cropRect.bottom, r, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.centerY(), r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.centerY(), r, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = detectHandle(x, y)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                
                updateRect(x, y, dx, dy)
                
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (activeHandle != HANDLE_NONE) performClick()
                activeHandle = HANDLE_NONE
            }
        }
        return true
    }

    private fun detectHandle(x: Float, y: Float): Int {
        // Corners
        if (dist(x, y, cropRect.left, cropRect.top) < handleSize) return HANDLE_TOP_LEFT
        if (dist(x, y, cropRect.right, cropRect.top) < handleSize) return HANDLE_TOP_RIGHT
        if (dist(x, y, cropRect.left, cropRect.bottom) < handleSize) return HANDLE_BOTTOM_LEFT
        if (dist(x, y, cropRect.right, cropRect.bottom) < handleSize) return HANDLE_BOTTOM_RIGHT

        // Edges
        if (x > cropRect.left && x < cropRect.right) {
            if (kotlin.math.abs(y - cropRect.top) < edgeTolerance) return HANDLE_TOP
            if (kotlin.math.abs(y - cropRect.bottom) < edgeTolerance) return HANDLE_BOTTOM
        }
        if (y > cropRect.top && y < cropRect.bottom) {
            if (kotlin.math.abs(x - cropRect.left) < edgeTolerance) return HANDLE_LEFT
            if (kotlin.math.abs(x - cropRect.right) < edgeTolerance) return HANDLE_RIGHT
        }

        // Center
        if (cropRect.contains(x, y)) return HANDLE_CENTER

        return HANDLE_NONE
    }

    private fun updateRect(x: Float, y: Float, dx: Float, dy: Float) {
        val minSize = handleSize * 2
        when (activeHandle) {
            HANDLE_TOP_LEFT -> {
                cropRect.left = x.coerceIn(0f, cropRect.right - minSize)
                cropRect.top = y.coerceIn(0f, cropRect.bottom - minSize)
            }
            HANDLE_TOP_RIGHT -> {
                cropRect.right = x.coerceIn(cropRect.left + minSize, width.toFloat())
                cropRect.top = y.coerceIn(0f, cropRect.bottom - minSize)
            }
            HANDLE_BOTTOM_LEFT -> {
                cropRect.left = x.coerceIn(0f, cropRect.right - minSize)
                cropRect.bottom = y.coerceIn(cropRect.top + minSize, height.toFloat())
            }
            HANDLE_BOTTOM_RIGHT -> {
                cropRect.right = x.coerceIn(cropRect.left + minSize, width.toFloat())
                cropRect.bottom = y.coerceIn(cropRect.top + minSize, height.toFloat())
            }
            HANDLE_TOP -> {
                cropRect.top = y.coerceIn(0f, cropRect.bottom - minSize)
            }
            HANDLE_BOTTOM -> {
                cropRect.bottom = y.coerceIn(cropRect.top + minSize, height.toFloat())
            }
            HANDLE_LEFT -> {
                cropRect.left = x.coerceIn(0f, cropRect.right - minSize)
            }
            HANDLE_RIGHT -> {
                cropRect.right = x.coerceIn(cropRect.left + minSize, width.toFloat())
            }
            HANDLE_CENTER -> {
                val newLeft = cropRect.left + dx
                val newTop = cropRect.top + dy
                val newRight = cropRect.right + dx
                val newBottom = cropRect.bottom + dy
                
                if (newLeft >= 0 && newRight <= width) {
                    cropRect.left = newLeft
                    cropRect.right = newRight
                }
                if (newTop >= 0 && newBottom <= height) {
                    cropRect.top = newTop
                    cropRect.bottom = newBottom
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }
}
