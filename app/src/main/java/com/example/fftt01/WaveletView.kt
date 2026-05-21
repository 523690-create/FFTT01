package com.example.fftt01

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.log10

class WaveletView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var waveletBitmap: Bitmap? = null
    private var lastResult: List<FloatArray>? = null
    
    private val paint = Paint()
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = context.resources.getDimension(R.dimen.font_button)
        isAntiAlias = true
        setShadowLayer(2f * context.resources.displayMetrics.density, 1f * context.resources.displayMetrics.density, 1f * context.resources.displayMetrics.density, Color.BLACK)
    }
    
    private val progressPaint = Paint().apply {
        color = Color.WHITE
        alpha = 128
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val progressBgPaint = Paint().apply {
        color = Color.BLACK
        alpha = 64
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val progressRect = RectF()
    private val viewRect = RectF()

    private var isLogScale = false
    private var isLocalNorm = false
    private var isCwtMode = false
    private var isWPT = false
    
    private var zoomFactorX = 1f
    private var zoomFactorY = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    
    private var displaySampleRate = 44100f
    private var numLevels = 0
    
    private var progress = 0f
    private var isCalculating = false

    fun setWPT(enabled: Boolean) {
        isWPT = enabled
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldZoomX = zoomFactorX
            val oldZoomY = zoomFactorY
            
            val spanX = detector.currentSpanX
            val spanY = detector.currentSpanY
            val prevSpanX = detector.previousSpanX
            val prevSpanY = detector.previousSpanY
            val totalSpan = detector.currentSpan
            
            if (prevSpanX > 0 && prevSpanY > 0) {
                val scaleX = spanX / prevSpanX
                val scaleY = spanY / prevSpanY
                
                if (spanX > totalSpan * 0.4f) zoomFactorX *= scaleX
                if (spanY > totalSpan * 0.4f) zoomFactorY *= scaleY
            }
            
            zoomFactorX = zoomFactorX.coerceIn(1f, 10f)
            zoomFactorY = zoomFactorY.coerceIn(1f, 10f)
            
            val focusX = detector.focusX
            val focusY = detector.focusY
            
            offsetX = focusX - (focusX - offsetX) * (zoomFactorX / oldZoomX)
            offsetY = focusY - (focusY - offsetY) * (zoomFactorY / oldZoomY)
            
            checkOffsets()
            invalidate()
            return true
        }
    })

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Color schemes
    private val colorSchemes = arrayOf(
        intArrayOf( // 0: Heat (Original 7-color)
            Color.parseColor("#4B0082"), // Indigo
            Color.BLUE,
            Color.CYAN,
            Color.GREEN,
            Color.YELLOW,
            Color.parseColor("#FFA500"), // Orange
            Color.RED
        ),
        intArrayOf( // 1: Viridis
            Color.parseColor("#440154"),
            Color.parseColor("#3b528b"),
            Color.parseColor("#21918c"),
            Color.parseColor("#5ec962"),
            Color.parseColor("#fde725")
        ),
        intArrayOf( // 2: Magma
            Color.parseColor("#000004"),
            Color.parseColor("#3b0f70"),
            Color.parseColor("#8c2981"),
            Color.parseColor("#fe9f6d"),
            Color.parseColor("#fcfdbf")
        ),
        intArrayOf( // 3: Grayscale
            Color.BLACK,
            Color.WHITE
        )
    )
    private var activeColors = colorSchemes[0]

    fun setLogScale(enabled: Boolean) {
        if (isLogScale != enabled) {
            isLogScale = enabled
            lastResult?.let { updateData(it, false) }
        }
    }

    fun setLocalNorm(enabled: Boolean) {
        if (isLocalNorm != enabled) {
            isLocalNorm = enabled
            lastResult?.let { updateData(it, false) }
        }
    }

    fun setCwtMode(enabled: Boolean) {
        isCwtMode = enabled
    }

    fun setSamplingFreq(fs: Float) {
        displaySampleRate = fs
        invalidate()
    }
    
    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        isCalculating = progress < 1f && progress > 0f
        invalidate()
    }
    
    fun setCalculating(calculating: Boolean) {
        isCalculating = calculating
        if (!calculating) progress = 0f
        invalidate()
    }

    fun setColorScheme(index: Int) {
        val idx = index.coerceIn(0, colorSchemes.size - 1)
        activeColors = colorSchemes[idx]
        lastResult?.let { updateData(it, false) }
        invalidate()
    }

    private fun checkOffsets() {
        val maxOffsetX = 0f
        val minOffsetX = width * (1f - zoomFactorX)
        val maxOffsetY = 0f
        val minOffsetY = height * (1f - zoomFactorY)
        
        offsetX = offsetX.coerceIn(minOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(minOffsetY, maxOffsetY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        if (!scaleDetector.isInProgress) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    offsetX += dx
                    offsetY += dy
                    checkOffsets()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private var isUpdating = false
    private var currentUpdateId = 0

    fun updateData(dwtResult: List<FloatArray>, isInterim: Boolean = false) {
        if (width <= 0 || height <= 0 || dwtResult.isEmpty()) return
        if (isUpdating && isInterim) return // Skip interim updates if still processing previous one
        
        val updateId = ++currentUpdateId
        if (!isInterim) lastResult = dwtResult
        isUpdating = true

        numLevels = dwtResult.size
        // Limit resolution for performance and memory safety
        val bmpWidth = width.coerceAtMost(1024)
        val bmpHeight = height.coerceAtMost(1024)
        
        Thread {
            try {
                // Pre-calculate or reuse a buffer if possible, but here we allocate
                val pixels = try {
                    IntArray(bmpWidth * bmpHeight)
                } catch (e: OutOfMemoryError) {
                    if (updateId == currentUpdateId) isUpdating = false
                    System.gc() // Hint for GC
                    return@Thread
                }
                
                var globalMax = 1e-9f
                if (!isLocalNorm) {
                    for (level in dwtResult) {
                        for (v in level) {
                            if (abs(v) > globalMax) globalMax = abs(v)
                        }
                    }
                }

                if (isCwtMode) {
                    val numScales = dwtResult.size
                    for (y in 0 until bmpHeight) {
                        val scalePos = (y.toFloat() / bmpHeight) * (numScales - 1)
                        val s1 = scalePos.toInt()
                        val s2 = (s1 + 1).coerceAtMost(numScales - 1)
                        val frac = scalePos - s1
                        val data1 = dwtResult[s1]
                        val data2 = dwtResult[s2]
                        
                        var currentMax = globalMax
                        if (isLocalNorm) {
                            var localMax = 1e-9f
                            for (v in data1) if (abs(v) > localMax) localMax = abs(v)
                            for (v in data2) if (abs(v) > localMax) localMax = abs(v)
                            currentMax = localMax
                        }
                        val logMax = log10(currentMax + 1e-9f)

                        val cellW = bmpWidth.toFloat() / data1.size
                        for (x in 0 until bmpWidth) {
                            val dIdx = (x / cellW).toInt().coerceIn(0, data1.size - 1)
                            val rawVal = abs(data1[dIdx]) * (1 - frac) + abs(data2[dIdx]) * frac
                            val normalizedValue = if (isLogScale) {
                                val dB = 20 * log10(rawVal + 1e-9f)
                                val maxDB = 20 * logMax
                                ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
                            } else {
                                (rawVal / currentMax).coerceIn(0f, 1f)
                            }
                            pixels[y * bmpWidth + x] = getColorForValue(normalizedValue)
                        }
                    }
                } else {
                    val levelHeight = bmpHeight.toFloat() / numLevels
                    for (l in 0 until numLevels) {
                        val data = dwtResult[l]
                        val cellW = bmpWidth.toFloat() / data.size
                        var currentMax = globalMax
                        if (isLocalNorm) {
                            var localMax = 1e-9f
                            for (v in data) if (abs(v) > localMax) localMax = abs(v)
                            currentMax = localMax
                        }
                        val logMax = log10(currentMax + 1e-9f)
                        val top = (l * levelHeight).toInt()
                        val bottom = ((l + 1) * levelHeight).toInt().coerceAtMost(bmpHeight)
                        for (y in top until bottom) {
                            for (x in 0 until bmpWidth) {
                                val dIdx = (x / cellW).toInt().coerceIn(0, data.size - 1)
                                val rawVal = abs(data[dIdx])
                                val normalizedValue = if (isLogScale) {
                                    val dB = 20 * log10(rawVal + 1e-9f)
                                    val maxDB = 20 * logMax
                                    ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
                                } else {
                                    (rawVal / currentMax).coerceIn(0f, 1f)
                                }
                                pixels[y * bmpWidth + x] = getColorForValue(normalizedValue)
                            }
                        }
                    }
                }
                
                if (updateId != currentUpdateId) return@Thread

                val bitmap = try {
                    Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    null
                } ?: run { if (updateId == currentUpdateId) isUpdating = false; return@Thread }
                
                bitmap.setPixels(pixels, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)
                
                post {
                    if (updateId == currentUpdateId) {
                        val oldBitmap = waveletBitmap
                        waveletBitmap = bitmap
                        oldBitmap?.recycle()
                        
                        if (!isInterim) {
                            isCalculating = false
                            progress = 0f
                        }
                        isUpdating = false
                        invalidate()
                    } else {
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                if (updateId == currentUpdateId) isUpdating = false
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(zoomFactorX, zoomFactorY)
        
        waveletBitmap?.let {
            viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(it, null, viewRect, null)
        }
        canvas.restore()

        if (numLevels > 0) {
            val levelHeight = height.toFloat() / numLevels
            for (l in 0 until numLevels) {
                val label = if (isCwtMode) {
                    if (l % (numLevels / 4).coerceAtLeast(1) == 0) "Scale ${numLevels - l}" else ""
                } else if (isWPT) {
                    val bw = (displaySampleRate / 2) / (1 shl numLevels.coerceIn(0, 10)).toDouble()
                    "${(l * bw).toInt()}-${((l + 1) * bw).toInt()} Hz"
                } else {
                    val fMax = (displaySampleRate / 2) / Math.pow(2.0, l.toDouble())
                    val fMin = fMax / 2
                    "${fMin.toInt()}-${fMax.toInt()} Hz"
                }
                if (label.isNotEmpty()) {
                    val labelY = (l + 1) * levelHeight * zoomFactorY + offsetY - 10f
                    canvas.drawText(label, 10f, labelY, textPaint)
                }
            }
        }
        
        if (isCalculating) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = Math.min(width, height) / 4f
            progressRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawCircle(cx, cy, radius, progressBgPaint)
            canvas.drawArc(progressRect, -90f, progress * 360f, true, progressPaint)
        }
    }

    private fun getColorForValue(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val index = (v * (activeColors.size - 1)).toInt()
        val nextIndex = (index + 1).coerceAtMost(activeColors.size - 1)
        val fraction = (v * (activeColors.size - 1)) - index
        return interpolateColor(activeColors[index], activeColors[nextIndex], fraction)
    }

    private fun interpolateColor(c1: Int, c2: Int, fraction: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * fraction).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * fraction).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * fraction).toInt()
        return Color.rgb(r, g, b)
    }
}
