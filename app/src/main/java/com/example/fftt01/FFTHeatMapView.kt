package com.example.fftt01

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.pow

class FFTHeatMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 12f * context.resources.displayMetrics.density
        isAntiAlias = true
        setShadowLayer(2f * context.resources.displayMetrics.density, 1f * context.resources.displayMetrics.density, 1f * context.resources.displayMetrics.density, Color.BLACK)
    }

    private var maxHistory = 129 
    private var currentColumn = 0
    private var spectrogramBitmap: Bitmap? = null
    private var yToBinMapping: IntArray? = null
    
    private val minFreq = 80f
    private val maxFreq = 10000f
    private var sampleRate = 44100f
    private var fftSize = 2048
    private var stepSize = 1024
    private var blurRadius = 0
    var isFrozen = false

    private var zoomFactorX = 1f
    private var zoomFactorY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

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

    fun setColorScheme(index: Int) {
        val idx = index.coerceIn(0, colorSchemes.size - 1)
        val oldColors = activeColors
        activeColors = colorSchemes[idx]
        
        spectrogramBitmap?.let { bitmap ->
            synchronized(bitmap) {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                for (i in pixels.indices) {
                    if (pixels[i] != Color.TRANSPARENT && pixels[i] != Color.BLACK) {
                        pixels[i] = reMapColor(pixels[i], oldColors, activeColors)
                    }
                }
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
        invalidate()
    }

    private fun reMapColor(color: Int, oldPalette: IntArray, newPalette: IntArray): Int {
        // Find closest value in old palette to estimate original magnitude
        var bestV = 0f
        var minDiff = Float.MAX_VALUE
        for (i in 0..10) {
            val v = i / 10f
            val c = getColorFromPalette(v, oldPalette)
            val diff = colorDiff(color, c)
            if (diff < minDiff) {
                minDiff = diff
                bestV = v
            }
        }
        return getColorFromPalette(bestV, newPalette)
    }

    private fun colorDiff(c1: Int, c2: Int): Float {
        val dr = Color.red(c1) - Color.red(c2)
        val dg = Color.green(c1) - Color.green(c2)
        val db = Color.blue(c1) - Color.blue(c2)
        return (dr * dr + dg * dg + db * db).toFloat()
    }

    private fun getColorFromPalette(v: Float, palette: IntArray): Int {
        val vv = v.coerceIn(0f, 1f)
        val index = (vv * (palette.size - 1)).toInt()
        val nextIndex = (index + 1).coerceAtMost(palette.size - 1)
        val fraction = (vv * (palette.size - 1)) - index
        return interpolateColor(palette[index], palette[nextIndex], fraction)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    fun setParams(newFftSize: Int, newSampleRate: Float, newStepSize: Int) {
        this.fftSize = newFftSize
        this.sampleRate = newSampleRate
        this.stepSize = newStepSize
        precalculateMapping()
    }

    fun setBlur(radius: Int) {
        blurRadius = radius.coerceIn(0, 10)
        invalidate()
    }

    fun setMaxHistory(count: Int) {
        maxHistory = count
        resetBitmap()
    }

    fun clearHistory() {
        currentColumn = 0
        spectrogramBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    private fun resetBitmap() {
        if (width > 0 && height > 0) {
            val h = height
            spectrogramBitmap = Bitmap.createBitmap(maxHistory, h, Bitmap.Config.ARGB_8888)
            currentColumn = 0
            precalculateMapping()
        }
    }

    private fun precalculateMapping() {
        val h = height
        if (h <= 0) return
        val mapping = IntArray(h)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val numBins = fftSize / 2
        
        for (y in 0 until h) {
            val logF = logMax - (y.toFloat() / h) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            mapping[y] = (freq * fftSize / sampleRate).toInt().coerceIn(0, numBins - 1)
        }
        yToBinMapping = mapping
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetBitmap()
    }

    fun updateFFT(data: FloatArray, force: Boolean = false) {
        if (isFrozen && !force) return
        val bitmap = spectrogramBitmap ?: return
        val h = bitmap.height
        
        currentColumn = (currentColumn + 1) % maxHistory
        
        val columnPixels = IntArray(h)
        
        if (data.size == h) {
            // Data is already mapped to the heat map height (e.g. from ViewerActivity background thread)
            for (y in 0 until h) {
                columnPixels[y] = getColorForValue(data[y])
            }
        } else {
            // Data is raw frequency bins (e.g. from MainActivity real-time recording)
            val logMin = log10(minFreq)
            val logMax = log10(maxFreq)
            val numBins = data.size
            
            // Vertical interpolation for smoother color mapping
            for (y in 0 until h) {
                val logF = logMax - (y.toFloat() / h) * (logMax - logMin)
                val freq = 10.0.pow(logF.toDouble()).toFloat()
                val binIdxExact = (freq * fftSize / sampleRate)
                
                val idx1 = binIdxExact.toInt().coerceIn(0, numBins - 1)
                val idx2 = (idx1 + 1).coerceAtMost(numBins - 1)
                val frac = binIdxExact - idx1
                
                val val1 = data[idx1]
                val val2 = data[idx2]
                val interpolatedVal = val1 + (val2 - val1) * frac.toFloat()
                
                columnPixels[y] = getColorForValue(interpolatedVal)
            }
        }
        
        synchronized(bitmap) {
            bitmap.setPixels(columnPixels, 0, 1, currentColumn, 0, 1, h)
        }
        postInvalidate()
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
        gestureDetector.onTouchEvent(event)
        
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = spectrogramBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(zoomFactorX, zoomFactorY)

        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }

        synchronized(bitmap) {
            if (blurRadius > 0) {
                // To achieve blur without RenderEffect, we draw at lower resolution into a temporary bitmap
                // then upscale with bilinear filtering (isFilterBitmap = true).
                val scale = 1.0f / (blurRadius * 0.5f + 1.0f)
                val bw = (maxHistory * scale).toInt().coerceAtLeast(10)
                val bh = (bitmap.height * scale).toInt().coerceAtLeast(10)
                
                val blurredBitmap = Bitmap.createScaledBitmap(bitmap, bw, bh, true)
                
                if (isFrozen) {
                    canvas.drawBitmap(blurredBitmap, null, RectF(0f, 0f, w, h), paint)
                } else {
                    val bW = blurredBitmap.width
                    val drawCol = (currentColumn.toFloat() / maxHistory * bW).toInt()
                    
                    val splitX = (drawCol + 1).toFloat() / bW * w
                    val src1 = Rect(drawCol + 1, 0, bW, blurredBitmap.height)
                    val dst1 = RectF(0f, 0f, w - splitX, h)
                    canvas.drawBitmap(blurredBitmap, src1, dst1, paint)
                    
                    val src2 = Rect(0, 0, drawCol + 1, blurredBitmap.height)
                    val dst2 = RectF(w - splitX, 0f, w, h)
                    canvas.drawBitmap(blurredBitmap, src2, dst2, paint)
                }
            } else {
                if (isFrozen) {
                    canvas.drawBitmap(bitmap, null, RectF(0f, 0f, w, h), paint)
                } else {
                    val splitX = (currentColumn + 1).toFloat() / maxHistory * w
                    val src1 = Rect(currentColumn + 1, 0, maxHistory, bitmap.height)
                    val dst1 = RectF(0f, 0f, w - splitX, h)
                    canvas.drawBitmap(bitmap, src1, dst1, paint)
                    
                    val src2 = Rect(0, 0, currentColumn + 1, bitmap.height)
                    val dst2 = RectF(w - splitX, 0f, w, h)
                    canvas.drawBitmap(bitmap, src2, dst2, paint)
                }
            }
        }
        canvas.restore()

        // Labels stay fixed but move vertically with pan/zoom
        val targetFreqs = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
        for (f in targetFreqs) {
            val y = freqToY(f, h) * zoomFactorY + offsetY
            canvas.drawLine(0f, y, w, y, linePaint)
            canvas.drawText("${f.toInt()}Hz", 10f, y - 5f, textPaint)
        }

        // Draw Sz and St in the top right corner
        val szText = "Sz $fftSize"
        val stText = "St $stepSize"
        val infoX = w - 10f * resources.displayMetrics.density
        canvas.drawText(szText, infoX - textPaint.measureText(szText), 20f * resources.displayMetrics.density, textPaint)
        canvas.drawText(stText, infoX - textPaint.measureText(stText), 40f * resources.displayMetrics.density, textPaint)
    }

    private fun freqToY(freq: Float, h: Float): Float {
        val f = freq.coerceIn(minFreq, maxFreq)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = log10(f)
        return h * (1f - (logF - logMin) / (logMax - logMin))
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

    fun saveIcon(cropRect: RectF, fileName: String) {
        if (width <= 0 || height <= 0) return
        val fullRender = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullRender)
        draw(canvas)
        val left = cropRect.left.toInt().coerceIn(0, width - 1)
        val top = cropRect.top.toInt().coerceIn(0, height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, height)
        val cropped = Bitmap.createBitmap(fullRender, left, top, right - left, bottom - top)
        val icon = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { out ->
            icon.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
