package com.example.fftt01

import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.*

class WaveletActivity : AppCompatActivity() {

    private lateinit var waveletView: WaveletView
    private lateinit var familySpinner: Spinner
    private lateinit var boundarySpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var checkSoft: CheckBox
    private lateinit var checkWPT: CheckBox
    private lateinit var checkLog: CheckBox
    private lateinit var checkLocalNorm: CheckBox
    private lateinit var checkReconstruct: CheckBox
    private lateinit var checkCWT: CheckBox
    private lateinit var sliderOrder: Slider
    private lateinit var sliderLevel: Slider
    private lateinit var sliderSampling: Slider
    private lateinit var sliderThreshold: Slider
    
    private lateinit var txtLevelValue: TextView
    private lateinit var txtOrderValue: TextView
    private lateinit var txtSamplingValue: TextView
    private lateinit var txtThresholdValue: TextView

    private var filePath: String? = null
    private var pcmData: FloatArray? = null

    private var colorSchemeIdx = 0
    private var decompositionLevel = 4
    private var targetFreq = 44100f
    private var originalSampleRate = 44100f
    private var threshold = 0.01f
    private var selectedFamilyIdx = 0
    private var waveletOrder = 2
    private var isSoftThreshold = true
    private var isWPT = false
    private var isLogScale = false
    private var isLocalNorm = false
    private var isReconstruct = false
    private var isCWT = false
    private var selectedBoundaryIdx = 0

    @Volatile
    private var isStopRequested = false
    private var currentRequestId = 0

    private lateinit var prefs: SharedPreferences

    private val filterMap = mapOf(
        0 to mapOf(2 to floatArrayOf(0.70710678f, 0.70710678f)), // Haar
        1 to mapOf(
            2 to floatArrayOf(0.48296291f, 0.8365163f, 0.22414387f, -0.12940952f), // DB2
            4 to floatArrayOf(0.23037781f, 0.71484657f, 0.63088076f, -0.02798376f, -0.18703481f, 0.03084138f, 0.03288301f, -0.0105974f) // DB4
        ),
        2 to mapOf(
            4 to floatArrayOf(-0.07576571f, -0.02963553f, 0.49761867f, 0.80373875f, 0.29785771f, -0.09921954f, -0.01260397f, 0.0322231f) // SYM4
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wavelet)

        waveletView = findViewById(R.id.waveletView)
        familySpinner = findViewById(R.id.waveletFamilySpinner)
        boundarySpinner = findViewById(R.id.waveletBoundarySpinner)
        colorSpinner = findViewById(R.id.waveletColorSpinner)
        checkSoft = findViewById(R.id.checkSoftThresh)
        checkWPT = findViewById(R.id.checkWPT)
        checkLog = findViewById(R.id.checkLogScale)
        checkLocalNorm = findViewById(R.id.checkLocalNorm)
        checkReconstruct = findViewById(R.id.checkReconstruct)
        checkCWT = findViewById(R.id.checkCWT)
        sliderOrder = findViewById(R.id.sliderOrder)
        sliderLevel = findViewById(R.id.sliderLevel)
        sliderSampling = findViewById(R.id.sliderSampling)
        sliderThreshold = findViewById(R.id.sliderThreshold)

        txtLevelValue = findViewById(R.id.txtLevelValue)
        txtOrderValue = findViewById(R.id.txtOrderValue)
        txtSamplingValue = findViewById(R.id.txtSamplingValue)
        txtThresholdValue = findViewById(R.id.txtThresholdValue)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        loadPrefs()
        setupControls()
        setupColorSpinner()

        filePath = intent.getStringExtra("FILE_PATH")
        waveletView.post {
            updateAllLabelPositions()
            filePath?.let { loadAndDecode(File(it)) }
        }

        findViewById<Button>(R.id.btnWaveletBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnWaveletStop).setOnClickListener { isStopRequested = true }
        findViewById<Button>(R.id.btnWaveletGalleryTop).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnWaveletListenTop).setOnClickListener { 
            // In wavelet, listen can play the reconstructed audio or original
            // For now, let's just make it a back button or implement play if needed
            finish() 
        }
        
        // Auto scale all buttons and titles
        UiUtils.autoScaleText(findViewById(R.id.txtTitleWavelet))
        UiUtils.autoScaleText(findViewById(R.id.btnWaveletBack))
        UiUtils.autoScaleText(findViewById(R.id.btnWaveletStop))
        UiUtils.autoScaleText(findViewById(R.id.btnWaveletGalleryTop))
        UiUtils.autoScaleText(findViewById(R.id.btnWaveletListenTop))
        
        // Auto scale all checkboxes
        UiUtils.autoScaleText(checkSoft)
        UiUtils.autoScaleText(checkWPT)
        UiUtils.autoScaleText(checkCWT)
        UiUtils.autoScaleText(checkLog)
        UiUtils.autoScaleText(checkLocalNorm)
        UiUtils.autoScaleText(checkReconstruct)
    }

    private fun updateAllLabelPositions() {
        adjustSliderThickness(sliderLevel, txtLevelValue)
        adjustSliderThickness(sliderOrder, txtOrderValue)
        adjustSliderThickness(sliderSampling, txtSamplingValue)
        adjustSliderThickness(sliderThreshold, txtThresholdValue)
    }

    private fun adjustSliderThickness(slider: Slider, label: TextView?) {
        slider.post {
            val parent = (slider.parent as? View) ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val density = resources.displayMetrics.density
            val gutterPx = 4f * density
            val maxThickness = resources.getDimension(R.dimen.slider_track_height) * 2.5f
            val thickness = (availableWidth - 2 * gutterPx).coerceAtMost(maxThickness)
            
            if (thickness > 0) {
                slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                slider.trackInactiveTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                slider.thumbTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                slider.haloTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                
                slider.trackHeight = thickness.toInt()
                slider.thumbRadius = (thickness / 2f).toInt()
                slider.haloRadius = 0
                
                label?.let {
                    it.setBackgroundColor(Color.WHITE)
                    it.setTextColor(Color.BLACK)
                    val p = (2f * density).toInt()
                    it.setPadding(p, 0, p, 0)
                    it.elevation = 6f * density
                    it.minWidth = (50f * density).toInt()
                    it.gravity = android.view.Gravity.CENTER
                    UiUtils.autoScaleText(it)
                }
                
                updateLabelPosition(slider, label)
            }
        }
    }

    private fun updateLabelPosition(slider: Slider, label: TextView?) {
        if (label == null) return
        if (slider.height == 0 || label.height == 0 || label.layout == null) {
            label.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (label.height > 0 && label.layout != null && slider.height > 0) {
                        label.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        updateLabelPosition(slider, label)
                    }
                }
            })
            label.post { updateLabelPosition(slider, label) }
            return
        }

        val range = slider.valueTo - slider.valueFrom
        if (range <= 0f) return
        val normalizedValue = (slider.value - slider.valueFrom) / range
        
        val totalHeight = slider.height.toFloat()
        val density = resources.displayMetrics.density
        
        val handleHeight = 24f * density
        val availableLength = totalHeight - handleHeight
        val barTopY = availableLength - (normalizedValue * availableLength)
        
        label.translationY = barTopY - label.top
        
        val targetHeight = (totalHeight - barTopY).toInt().coerceAtLeast(handleHeight.toInt())
        if (label.layoutParams.height != targetHeight) {
            label.layoutParams.height = targetHeight
            label.requestLayout()
        }

        label.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        label.setPadding(0, (2f * density).toInt(), 0, 0)
        
        val barColor = when (slider.id) {
            R.id.sliderLevel, R.id.sliderOrder -> Color.GREEN
            R.id.sliderThreshold -> Color.CYAN
            else -> Color.LTGRAY // FS slider
        }
        label.setBackgroundColor(barColor)
        label.setTextColor(Color.BLACK)
        label.elevation = 6f * density
        
        UiUtils.autoScaleText(label)
    }

    private fun Slider.setSafeValue(v: Float) {
        val newVal = v.coerceIn(valueFrom, valueTo)
        val snappedVal = if (stepSize > 0) {
            round((newVal - valueFrom) / stepSize) * stepSize + valueFrom
        } else {
            newVal
        }
        val finalVal = snappedVal.coerceIn(valueFrom, valueTo)
        if (abs(value - finalVal) > 1e-5f) value = finalVal
    }

    private fun loadPrefs() {
        colorSchemeIdx = prefs.getInt("wavelet_color_scheme", 0)
        decompositionLevel = prefs.getInt("decomposition_level", 4)
        targetFreq = prefs.getFloat("target_freq", 44100f)
        threshold = prefs.getFloat("threshold", 0.01f)
        selectedFamilyIdx = prefs.getInt("family", 0)
        waveletOrder = prefs.getInt("order", 2)
        isSoftThreshold = prefs.getBoolean("soft_thresh", true)
        isWPT = prefs.getBoolean("wpt", false)
        isLogScale = prefs.getBoolean("log_scale", false)
        isLocalNorm = prefs.getBoolean("local_norm", false)
        isReconstruct = prefs.getBoolean("reconstruct", false)
        isCWT = prefs.getBoolean("cwt", false)
        selectedBoundaryIdx = prefs.getInt("boundary", 0)
        
        updateUIFromSettings()
    }

    private fun resetToSafeSettings(errorMsg: String) {
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        decompositionLevel = 4
        isCWT = false
        isWPT = false
        updateUIFromSettings()
        runDwt()
    }

    private fun updateUIFromSettings() {
        checkSoft.isChecked = isSoftThreshold
        checkWPT.isChecked = isWPT
        checkLog.isChecked = isLogScale
        checkLocalNorm.isChecked = isLocalNorm
        checkReconstruct.isChecked = isReconstruct
        checkCWT.isChecked = isCWT
        
        sliderLevel.setSafeValue(decompositionLevel.toFloat())
        sliderOrder.setSafeValue(waveletOrder.toFloat())
        sliderSampling.setSafeValue(targetFreq)
        sliderThreshold.setSafeValue(threshold)
        
        txtLevelValue.text = getString(R.string.fft_size_value, decompositionLevel)
        txtOrderValue.text = getString(R.string.fft_size_value, waveletOrder)
        txtSamplingValue.text = if (targetFreq >= 1000) getString(R.string.khz_value, (targetFreq/1000).toInt()) else getString(R.string.hz_value, targetFreq.toInt())
        txtThresholdValue.text = String.format(java.util.Locale.US, "%.3f", threshold)

        updateOrderSliderRange()
    }

    private fun validateConstraints(): Boolean {
        if (isCWT) return true
        if (pcmData == null) return false
        val maxLevel = (log2(pcmData!!.size.toDouble())).toInt() - 2
        if (decompositionLevel > maxLevel) {
            decompositionLevel = maxLevel.coerceAtLeast(1)
            sliderLevel.value = decompositionLevel.toFloat()
            return false
        }
        return true
    }

    private fun setupColorSpinner() {
        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        val adapter = ArrayAdapter(this, R.layout.spinner_item_large, colorNames)
        colorSpinner.adapter = adapter
        colorSpinner.setSelection(colorSchemeIdx)
        colorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, view: View?, pos: Int, p3: Long) {
                if (colorSchemeIdx != pos) {
                    colorSchemeIdx = pos
                    prefs.edit().putInt("wavelet_color_scheme", colorSchemeIdx).apply()
                    waveletView.setColorScheme(colorSchemeIdx)
                    runDwt()
                }
                styleColorSpinner(colorSpinner, pos)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        styleColorSpinner(colorSpinner, colorSchemeIdx)
    }

    private fun styleColorSpinner(spinner: Spinner, schemeIdx: Int) {
        spinner.post {
            val scheme = waveletView.colorSchemes[schemeIdx.coerceIn(0, waveletView.colorSchemes.size - 1)]
            val bgColor = scheme[0]
            val textColor = scheme[scheme.size - 1]
            
            spinner.setBackgroundColor(bgColor)
            val selectedView = spinner.selectedView as? TextView
            selectedView?.setTextColor(textColor)
            selectedView?.setBackgroundColor(bgColor)
            
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_color_simple)
                UiUtils.autoScaleText(selectedView)
            }
        }
    }

    private fun setupControls() {
        val families = arrayOf("DAUB", "SYM", "COIF")
        val familyAdapter = ArrayAdapter(this, R.layout.spinner_item_large, families)
        familyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        familySpinner.adapter = familyAdapter
        familySpinner.setSelection(selectedFamilyIdx)
        familySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, view: View?, pos: Int, p3: Long) {
                if (selectedFamilyIdx != pos) {
                    selectedFamilyIdx = pos
                    prefs.edit().putInt("family", pos).apply()
                    updateOrderSliderRange()
                    runDwt()
                }
                val selectedView = familySpinner.selectedView as? TextView
                if (selectedView != null) {
                    selectedView.text = getString(R.string.label_fam)
                    UiUtils.autoScaleText(selectedView)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        familySpinner.post {
            val selectedView = familySpinner.selectedView as? TextView
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_fam)
                UiUtils.autoScaleText(selectedView)
            }
        }

        val boundaries = arrayOf("ZERO", "PER", "SYM")
        val boundaryAdapter = ArrayAdapter(this, R.layout.spinner_item_large, boundaries)
        boundaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        boundarySpinner.adapter = boundaryAdapter
        boundarySpinner.setSelection(selectedBoundaryIdx)
        boundarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, view: View?, pos: Int, p3: Long) {
                if (selectedBoundaryIdx != pos) {
                    selectedBoundaryIdx = pos
                    prefs.edit().putInt("boundary", pos).apply()
                    runDwt()
                }
                val selectedView = boundarySpinner.selectedView as? TextView
                if (selectedView != null) {
                    selectedView.text = getString(R.string.label_bnd)
                    UiUtils.autoScaleText(selectedView)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        boundarySpinner.post {
            val selectedView = boundarySpinner.selectedView as? TextView
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_bnd)
                UiUtils.autoScaleText(selectedView)
            }
        }

        checkSoft.setOnCheckedChangeListener { _, isChecked -> 
            isSoftThreshold = isChecked
            prefs.edit().putBoolean("soft_thresh", isChecked).apply()
            runDwt()
        }
        checkWPT.setOnCheckedChangeListener { _, isChecked -> 
            isWPT = isChecked
            if (isChecked) {
                isCWT = false
                checkCWT.isChecked = false
            }
            prefs.edit().putBoolean("wpt", isChecked).apply()
            waveletView.setWPT(isChecked)
            runDwt()
        }
        checkCWT.setOnCheckedChangeListener { _, isChecked -> 
            isCWT = isChecked
            if (isChecked) {
                isWPT = false
                checkWPT.isChecked = false
                isReconstruct = false
                checkReconstruct.isChecked = false
            }
            prefs.edit().putBoolean("cwt", isChecked).apply()
            waveletView.setCwtMode(isChecked)
            runDwt()
        }
        checkLog.setOnCheckedChangeListener { _, isChecked -> 
            isLogScale = isChecked
            prefs.edit().putBoolean("log_scale", isChecked).apply()
            waveletView.setLogScale(isChecked)
            runDwt()
        }
        checkLocalNorm.setOnCheckedChangeListener { _, isChecked -> 
            isLocalNorm = isChecked
            prefs.edit().putBoolean("local_norm", isChecked).apply()
            waveletView.setLocalNorm(isChecked)
            runDwt()
        }
        checkReconstruct.setOnCheckedChangeListener { _, isChecked -> 
            isReconstruct = isChecked
            if (isChecked) {
                isCWT = false
                checkCWT.isChecked = false
            }
            prefs.edit().putBoolean("reconstruct", isChecked).apply()
            runDwt()
        }

        sliderLevel.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                decompositionLevel = value.toInt()
                txtLevelValue.text = getString(R.string.fft_size_value, decompositionLevel)
                prefs.edit().putInt("decomposition_level", decompositionLevel).apply()
                updateLabelPosition(slider, txtLevelValue)
                runDwt()
            }
        }
        sliderOrder.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                waveletOrder = value.toInt()
                txtOrderValue.text = getString(R.string.fft_size_value, waveletOrder)
                prefs.edit().putInt("order", waveletOrder).apply()
                updateLabelPosition(slider, txtOrderValue)
                runDwt()
            }
        }
        sliderSampling.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                targetFreq = value
                txtSamplingValue.text = if (targetFreq >= 1000) getString(R.string.khz_value, (targetFreq/1000).toInt()) else getString(R.string.hz_value, targetFreq.toInt())
                prefs.edit().putFloat("target_freq", targetFreq).apply()
                updateLabelPosition(slider, txtSamplingValue)
                runDwt()
            }
        }
        sliderThreshold.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                threshold = value
                txtThresholdValue.text = String.format(java.util.Locale.US, "%.3f", threshold)
                prefs.edit().putFloat("threshold", threshold).apply()
                updateLabelPosition(slider, txtThresholdValue)
                runDwt()
            }
        }
    }

    private fun updateOrderSliderRange() {
        val allowedOrders = when (selectedFamilyIdx) {
            0 -> listOf(2) // Haar
            1 -> listOf(2, 4, 6, 8, 10) // DB
            2 -> listOf(2, 4, 6, 8, 10) // SYM
            else -> listOf(2, 4)
        }
        
        sliderOrder.valueFrom = allowedOrders.first().toFloat()
        sliderOrder.valueTo = allowedOrders.last().toFloat()
        sliderOrder.stepSize = (allowedOrders.getOrNull(1)?.minus(allowedOrders.first()) ?: 1).toFloat().coerceAtLeast(1f)
        
        if (waveletOrder !in allowedOrders) {
            waveletOrder = allowedOrders.first()
            sliderOrder.value = waveletOrder.toFloat()
        }
    }

    private fun loadAndDecode(file: File) {
        Thread {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                
                var audioTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        break
                    }
                }

                if (audioTrackIndex == -1) {
                    extractor.release()
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var isEOS = false
                val pcmList = mutableListOf<Float>()

                while (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outIndex = codec.dequeueOutputBuffer(info, 10000)
                    while (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        val shorts = ShortArray(info.size / 2)
                        buffer.asShortBuffer().get(shorts)
                        for (s in shorts) pcmList.add(s / 32768f)
                        codec.releaseOutputBuffer(outIndex, false)
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
                codec.stop()
                codec.release()
                extractor.release()

                pcmData = pcmList.toFloatArray()
                originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat()
                runDwt()
            } catch (e: Exception) {
                runOnUiThread { resetToSafeSettings("Load error: ${e.message}") }
            }
        }.start()
    }

    private fun runDwt() {
        val originalData = pcmData ?: return
        if (!validateConstraints()) return
        
        val requestId = ++currentRequestId
        isStopRequested = true

        if (isCWT) {
            Thread { 
                try {
                    if (requestId == currentRequestId) {
                        isStopRequested = false
                        runCwt(originalData, requestId)
                    }
                } catch (e: Throwable) {
                    if (requestId == currentRequestId) {
                        runOnUiThread { resetToSafeSettings("CWT Failure: ${e.message}") }
                    }
                }
            }.start()
            return
        }

        if (isReconstruct) {
            runReconstruct()
            return
        }

        Thread {
            try {
                if (requestId != currentRequestId) return@Thread
                isStopRequested = false
                
                val data = resample(originalData, originalSampleRate, targetFreq)
                val h = filterMap[selectedFamilyIdx]?.get(waveletOrder) ?: return@Thread
                val g = getDetailFilter(h)

                val results = mutableListOf<FloatArray>()
                var currentSignal = data
                
                runOnUiThread { waveletView.setCalculating(calculating = true) }

                if (isWPT) {
                    var currentLevelNodes = mutableListOf(data)
                    for (level in 0 until decompositionLevel) {
                        if (isStopRequested || requestId != currentRequestId) break
                        val nextLevelNodes = mutableListOf<FloatArray>()
                        for (node in currentLevelNodes) {
                            val (a, d) = decompose(node, h, g)
                            nextLevelNodes.add(a.map { applyThreshold(it) }.toFloatArray())
                            nextLevelNodes.add(d.map { applyThreshold(it) }.toFloatArray())
                        }
                        currentLevelNodes = nextLevelNodes
                        val progressVal = (level + 1).toFloat() / decompositionLevel
                        val interim = currentLevelNodes.toList()
                        runOnUiThread {
                            waveletView.setProgress(progressVal)
                            waveletView.updateData(interim, isInterim = true)
                        }
                    }
                    results.addAll(currentLevelNodes)
                } else {
                    for (level in 0 until decompositionLevel) {
                        if (isStopRequested || requestId != currentRequestId) break
                        val (a, d) = decompose(currentSignal, h, g)
                        results.add(d.map { applyThreshold(it) }.toFloatArray())
                        currentSignal = a
                        
                        val progressVal = (level + 1).toFloat() / decompositionLevel
                        val interim = results.toList() + listOf(currentSignal)
                        runOnUiThread {
                            waveletView.setProgress(progressVal)
                            waveletView.updateData(interim, isInterim = true)
                        }
                    }
                    results.add(currentSignal)
                }

                if (requestId == currentRequestId) {
                    if (isStopRequested) {
                        runOnUiThread { 
                            waveletView.setCalculating(false)
                            Toast.makeText(this@WaveletActivity, "Calculation Stopped", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val finalResult = if (isWPT) results else results.toList() + listOf(currentSignal)
                        runOnUiThread {
                            waveletView.setSamplingFreq(targetFreq)
                            waveletView.updateData(finalResult, isInterim = false)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { resetToSafeSettings("DWT error: ${e.message}") }
            }
        }.start()
    }

    private fun runCwt(originalData: FloatArray, requestId: Int) {
        if (isStopRequested || requestId != currentRequestId) return
        val data = resample(originalData, originalSampleRate, targetFreq)
        val n = data.size
        
        if (n > 60000) {
            runOnUiThread { resetToSafeSettings("Buffer too large for CWT: $n samples. Max 60k.") }
            return
        }

        val paddedSize = try {
            FFTUtils.nextPowerOfTwo(n)
        } catch (e: Exception) {
            runOnUiThread { resetToSafeSettings("Buffer error: ${e.message}") }
            return
        }
        
        val sigRe = try { FloatArray(paddedSize) } catch (e: OutOfMemoryError) { null }
        val sigIm = try { FloatArray(paddedSize) } catch (e: OutOfMemoryError) { null }
        
        if (sigRe == null || sigIm == null) {
            runOnUiThread { resetToSafeSettings("Memory error: CWT requires too much RAM") }
            return
        }

        for (i in 0 until n) sigRe[i] = data[i]
        FFTUtils.compute(sigRe, sigIm)
        
        val numScales = 100
        val minScale = 1f
        val maxScale = 2f.pow(decompositionLevel.toFloat() + 3)
        val coefficients = Array(numScales) { FloatArray(n) }
        val wavRe = try { FloatArray(paddedSize) } catch (e: OutOfMemoryError) { null }
        val wavIm = try { FloatArray(paddedSize) } catch (e: OutOfMemoryError) { null }
        
        if (wavRe == null || wavIm == null) {
            runOnUiThread { resetToSafeSettings("Memory error: Scaling buffers failed") }
            return
        }

        try {
            for (s in 0 until numScales) {
                if (isStopRequested || requestId != currentRequestId) break
                val scale = minScale * (maxScale / minScale).pow(s.toFloat() / (numScales - 1))
                val w0 = 6.0f
                for (i in 0 until paddedSize) {
                    val omega = if (i <= paddedSize / 2) (2f * PI.toFloat() * i) / paddedSize else (2f * PI.toFloat() * (i - paddedSize)) / paddedSize
                    val valExp = -0.5f * (scale * omega - w0).pow(2)
                    wavRe[i] = if (valExp > -20f) exp(valExp) * sqrt(scale) else 0f
                    wavIm[i] = 0f
                }
                for (i in 0 until paddedSize) {
                    val r = sigRe[i] * wavRe[i] - sigIm[i] * wavIm[i]
                    val im = sigRe[i] * wavIm[i] + sigIm[i] * wavRe[i]
                    wavRe[i] = r
                    wavIm[i] = im
                }
                FFTUtils.inverse(wavRe, wavIm)
                for (i in 0 until n) coefficients[s][i] = applyThreshold(sqrt(wavRe[i] * wavRe[i] + wavIm[i] * wavIm[i]))

                if (s % 10 == 0 || s == numScales - 1) {
                    val progressVal = (s + 1).toFloat() / numScales
                    runOnUiThread {
                        waveletView.setProgress(progressVal)
                        waveletView.updateData(coefficients.slice(0..s).toList(), isInterim = true)
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { resetToSafeSettings("CWT error: ${e.message}") }
            return
        }
        
        if (requestId == currentRequestId && !isStopRequested) {
            runOnUiThread {
                waveletView.setSamplingFreq(targetFreq)
                waveletView.updateData(coefficients.toList(), isInterim = false)
            }
        }
    }

    private fun resample(data: FloatArray, oldFs: Float, newFs: Float): FloatArray {
        if (abs(oldFs - newFs) < 1f) return data
        val ratio = oldFs / newFs
        val newSize = (data.size / ratio).toInt()
        val result = FloatArray(newSize)
        for (i in 0 until newSize) {
            val oldIdx = i * ratio
            val base = oldIdx.toInt()
            val frac = oldIdx - base
            if (base + 1 < data.size) result[i] = data[base] * (1 - frac) + data[base + 1] * frac
            else result[i] = data[base]
        }
        return result
    }

    private fun decompose(signal: FloatArray, h: FloatArray, g: FloatArray): Pair<FloatArray, FloatArray> {
        val n = signal.size
        val half = n / 2
        val a = FloatArray(half)
        val d = FloatArray(half)
        for (i in 0 until half) {
            var sumA = 0f
            var sumD = 0f
            for (k in h.indices) {
                val idx = (2 * i + k) % n
                sumA += signal[idx] * h[k]
                sumD += signal[idx] * g[k]
            }
            a[i] = sumA
            d[i] = sumD
        }
        return Pair(a, d)
    }

    private fun getDetailFilter(h: FloatArray): FloatArray {
        val g = FloatArray(h.size)
        for (i in h.indices) g[i] = if (i % 2 == 0) h[h.size - 1 - i] else -h[h.size - 1 - i]
        return g
    }

    private fun runReconstruct() {
        val originalData = pcmData ?: return
        Thread {
            try {
                val data = resample(originalData, originalSampleRate, targetFreq)
                val h = filterMap[selectedFamilyIdx]?.get(waveletOrder) ?: return@Thread
                val g = getDetailFilter(h)
                runReconstructInternal(data, h, g, currentRequestId)
            } catch (e: Exception) {
                runOnUiThread { resetToSafeSettings("Reconstruct error: ${e.message}") }
            }
        }.start()
    }

    private fun runReconstructInternal(signal: FloatArray, h: FloatArray, g: FloatArray, requestId: Int) {
        var current = signal
        for (l in 0 until decompositionLevel) {
            if (isStopRequested || requestId != currentRequestId) break
            val (a, d) = decompose(current, h, g)
            current = reconstruct(a, d, h, g)
        }
        if (requestId == currentRequestId && !isStopRequested) {
            runOnUiThread {
                waveletView.setSamplingFreq(targetFreq)
                waveletView.updateData(listOf(current), isInterim = false)
            }
        }
    }

    private fun reconstruct(a: FloatArray, d: FloatArray, h: FloatArray, g: FloatArray): FloatArray {
        val n = a.size * 2
        val result = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (k in h.indices) {
                val idx = (i - k)
                if (idx >= 0 && idx % 2 == 0) {
                    val aIdx = idx / 2
                    if (aIdx < a.size) sum += a[aIdx] * h[k] + d[aIdx] * g[k]
                }
            }
            result[i] = sum
        }
        return result
    }

    private fun applyThreshold(value: Float): Float {
        val absVal = abs(value)
        if (absVal < threshold) return 0f
        return if (isSoftThreshold) sign(value) * (absVal - threshold) else value
    }
}
