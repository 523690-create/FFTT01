package com.example.fftt01

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.content.edit
import androidx.core.view.isGone
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import java.io.File
import java.util.*
import kotlin.math.*

class WaveletActivity : AppCompatActivity() {

    private lateinit var waveletView: WaveletView
    private lateinit var familySpinner: Spinner
    private lateinit var boundarySpinner: Spinner
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
    private lateinit var sliderColor: Slider
    
    private lateinit var txtLevelValue: TextView
    private lateinit var txtOrderValue: TextView
    private lateinit var txtSamplingValue: TextView
    private lateinit var txtThresholdValue: TextView
    private lateinit var txtColorValue: TextView

    private var filePath: String? = null
    private var pcmData: FloatArray? = null

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
    private var colorSchemeIdx = 0

    @Volatile
    private var isStopRequested = false
    private var currentRequestId = 0

    private lateinit var prefs: SharedPreferences

    private val filterMap = mapOf(
        0 to mapOf( // Daubechies
            2 to floatArrayOf(0.4829629f, 0.8365163f, 0.22414387f, -0.12940952f),
            4 to floatArrayOf(0.23037782f, 0.71484655f, 0.6308807f, -0.02798377f, -0.18703482f, 0.03084138f, 0.03288301f, -0.010597401f),
            6 to floatArrayOf(0.11154074f, 0.4946239f, 0.7511339f, 0.31525035f, -0.2262647f, -0.12976687f, 0.097501605f, 0.027522866f, -0.03158204f, 0.0005538422f, 0.0047772575f, -0.10773011f)
        ),
        1 to mapOf( // Symlets
            2 to floatArrayOf(0.4829629131445341f, 0.8365163037378077f, 0.2241438680420134f, -0.1294095225512603f),
            4 to floatArrayOf(0.0322231006040713f, -0.0126039672622612f, -0.0992195435769354f, 0.2978577956055422f, 0.8037387518052163f, 0.4976186676324459f, -0.0296355276459541f, -0.0757657147893407f),
            6 to floatArrayOf(0.0154041093270273f, 0.0034907120842174f, -0.0679442279548073f, -0.001546137461876f, 0.2239077890625405f, 0.7661305174151252f, 0.5661984509385461f, -0.0456156055694133f, -0.1656939890451729f, 0.0381335880155719f, 0.022552550234519f, -0.0084333777038722f)
        ),
        2 to mapOf( // Coiflets
            1 to floatArrayOf(-0.0156557281354445f, -0.0727326195128538f, 0.3848648468642029f, 0.8525720202122554f, 0.3378976624578092f, -0.0713941471666038f),
            2 to floatArrayOf(0.0007205494453681f, 0.0098188151214044f, -0.0145672283288424f, -0.1459110111442116f, 0.446100069123405f, 0.862633294433604f, 0.2026431902046894f, -0.0783824357753173f, 0.0355152331018546f, 0.0117451274026362f, -0.0049137179673602f, -0.0029685472443134f)
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wavelet)

        waveletView = findViewById(R.id.waveletView)
        familySpinner = findViewById(R.id.waveletFamilySpinner)
        boundarySpinner = findViewById(R.id.waveletBoundarySpinner)
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
        sliderColor = findViewById(R.id.sliderColor)

        txtLevelValue = findViewById(R.id.txtLevelValue)
        txtOrderValue = findViewById(R.id.txtOrderValue)
        txtSamplingValue = findViewById(R.id.txtSamplingValue)
        txtThresholdValue = findViewById(R.id.txtThresholdValue)
        txtColorValue = findViewById(R.id.txtColorValue)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        loadPrefs()
        setupControls()

        filePath = intent.getStringExtra("FILE_PATH")
        waveletView.post {
            updateAllLabelPositions()
            filePath?.let { loadAndDecode(File(it)) }
        }
    }

    private fun updateAllLabelPositions() {
        adjustSliderThickness(sliderLevel, txtLevelValue)
        adjustSliderThickness(sliderOrder, txtOrderValue)
        adjustSliderThickness(sliderSampling, txtSamplingValue)
        adjustSliderThickness(sliderThreshold, txtThresholdValue)
        
        // Color slider should be yellow
        sliderColor.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.YELLOW))
        sliderColor.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#7F7F00")))
        adjustSliderThickness(sliderColor, txtColorValue)
    }

    private fun adjustSliderThickness(slider: Slider, label: TextView?) {
        slider.post {
            val parent = (slider.parent as? View) ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val density = resources.displayMetrics.density
            
            label?.let {
                it.setBackgroundColor(Color.WHITE)
                it.setTextColor(Color.BLACK)
                val p = (2f * density).toInt()
                it.setPadding(p, 0, p, 0)
                it.elevation = 6f * density
                it.minWidth = (50f * density).toInt()
                it.textSize = 8f
            }

            slider.post {
                val labelWidth = label?.width ?: 0
                if (labelWidth > 0) {
                    slider.trackHeight = labelWidth
                    slider.thumbRadius = (2f * density).toInt()
                    slider.setCustomThumbDrawable(R.drawable.slider_thumb_line)
                    updateLabelPosition(slider, label)
                }
            }
        }
    }

    private fun updateLabelPosition(slider: Slider, label: TextView?) {
        if (label == null) return
        
        // If the view is effectively hidden (GONE), skip to avoid infinite layout loops.
        if (slider.isGone || label.isGone) return

        // Ensure dimensions and layout are ready
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
        
        // Precise thumb center calculation
        val thumbRadius = slider.thumbRadius.toFloat()
        val trackTop = slider.paddingTop + thumbRadius
        val trackBottom = totalHeight - slider.paddingBottom - thumbRadius
        val trackLength = trackBottom - trackTop
        
        val thumbY = trackBottom - (normalizedValue * trackLength)
        
        // Bar extends from thumbY down to container bottom
        val barTopY = thumbY
        
        // Align label top with barTopY
        label.translationY = barTopY - label.top
        
        // Set label height to fill the remaining space
        val targetHeight = (totalHeight - barTopY).toInt().coerceAtLeast((24f * density).toInt())
        if (label.layoutParams.height != targetHeight) {
            label.layoutParams.height = targetHeight
            label.requestLayout()
        }

        // Ensure text stays at top
        label.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        label.setPadding(0, (2f * density).toInt(), 0, 0)
        
        // Apply theme color to the bar
        val barColor = when (slider.id) {
            R.id.sliderLevel, R.id.sliderOrder -> Color.GREEN
            R.id.sliderThreshold -> Color.CYAN
            R.id.sliderColor -> Color.YELLOW
            else -> Color.LTGRAY // FS slider
        }
        label.setBackgroundColor(barColor)
        label.setTextColor(Color.BLACK)
        label.elevation = 6f * density
    }

    private fun Slider.setSafeValue(v: Float) {
        val step = this.stepSize
        if (step > 0f) {
            val numSteps = round((v - valueFrom) / step)
            this.value = (valueFrom + numSteps * step).coerceIn(valueFrom, valueTo)
        } else {
            this.value = v.coerceIn(valueFrom, valueTo)
        }
    }

    private fun loadPrefs() {
        selectedFamilyIdx = prefs.getInt("family", 0)
        selectedBoundaryIdx = prefs.getInt("boundary", 0)
        isSoftThreshold = prefs.getBoolean("soft_thresh", true)
        isWPT = prefs.getBoolean("wpt", false)
        isLogScale = prefs.getBoolean("log_scale", false)
        isLocalNorm = prefs.getBoolean("local_norm", false)
        isReconstruct = prefs.getBoolean("reconstruct", false)
        isCWT = prefs.getBoolean("cwt", false)
        decompositionLevel = prefs.getInt("level", 4)
        waveletOrder = prefs.getInt("order", 2)
        targetFreq = prefs.getFloat("freq", 44100f)
        
        // Ensure threshold is read and rounded to step size
        val savedThreshold = prefs.getFloat("threshold", 0.1f)
        threshold = (round(savedThreshold / 0.001f) * 0.001f)
        
        colorSchemeIdx = prefs.getInt("color_scheme", 0)

        updateUIFromSettings()
    }

    private fun resetToSafeSettings(reason: String) {
        isStopRequested = true
        isCWT = false
        targetFreq = 11000f
        decompositionLevel = 3
        isWPT = false
        isReconstruct = false
        threshold = 0.01f
        
        runOnUiThread {
            checkCWT.isChecked = false
            checkWPT.isChecked = false
            checkReconstruct.isChecked = false
            sliderSampling.setSafeValue(11000f)
            sliderLevel.setSafeValue(3f)
            sliderThreshold.setSafeValue(0.01f)
            
            updateUIFromSettings()
            Toast.makeText(this, "Safe mode active: $reason", Toast.LENGTH_LONG).show()
            
            prefs.edit {
                putBoolean("cwt", false)
                putBoolean("wpt", false)
                putBoolean("reconstruct", false)
                putFloat("freq", 11000f)
                putInt("level", 3)
                putFloat("threshold", 0.01f)
            }
            
            runDwt()
        }
    }

    private fun updateUIFromSettings() {
        familySpinner.setSelection(selectedFamilyIdx)
        boundarySpinner.setSelection(selectedBoundaryIdx)
        checkSoft.isChecked = isSoftThreshold
        checkWPT.isChecked = isWPT
        checkLog.isChecked = isLogScale
        checkLocalNorm.isChecked = isLocalNorm
        checkReconstruct.isChecked = isReconstruct
        checkCWT.isChecked = isCWT
        
        // Use safe setters to prevent IllegalStateException
        sliderLevel.setSafeValue(decompositionLevel.toFloat())
        sliderOrder.setSafeValue(waveletOrder.toFloat())
        sliderSampling.setSafeValue(targetFreq)
        sliderThreshold.setSafeValue(threshold)
        sliderColor.setSafeValue(colorSchemeIdx.toFloat())
        
        txtLevelValue.text = decompositionLevel.toString()
        txtOrderValue.text = waveletOrder.toString()
        txtSamplingValue.text = if (targetFreq >= 1000) "${(targetFreq/1000).toInt()}\nkHz" else "${targetFreq.toInt()}\nHz"
        txtThresholdValue.text = String.format(java.util.Locale.US, "%.3f", threshold)
        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        txtColorValue.text = colorNames[colorSchemeIdx.coerceIn(0, 3)]

        updateOrderSliderRange()
        
        // Sync WaveletView state
        waveletView.setWPT(isWPT)
        waveletView.setLogScale(isLogScale)
        waveletView.setLocalNorm(isLocalNorm)
        waveletView.setCwtMode(isCWT)
        waveletView.setColorScheme(colorSchemeIdx)
        waveletView.setSamplingFreq(targetFreq)
    }

    private fun validateConstraints(): Boolean {
        val pcm = pcmData ?: return false
        
        val originalCount = pcm.size
        val resampledSize = (originalCount * (targetFreq / originalSampleRate)).toInt()
        
        if (isCWT) {
            if (resampledSize > 60000) {
                resetToSafeSettings("Buffer too large for CWT ($resampledSize samples). Downsampling recommended. Max 60k for CWT.")
                return false
            }
        } else {
            if (resampledSize > 300000) {
                resetToSafeSettings("Buffer too large for DWT ($resampledSize samples). Max 300k for DWT.")
                return false
            }
        }
        
        // Memory safety: approx check
        val estMemory = if (isCWT) resampledSize.toLong() * 100 * 4 else resampledSize.toLong() * (decompositionLevel + 1) * 4
        if (estMemory > 40 * 1024 * 1024) { // 40MB limit for heap safety
             resetToSafeSettings("Estimated memory usage too high (${estMemory/1024/1024}MB).")
             return false
        }
        
        return true
    }

    private fun setupControls() {
        val families = arrayOf("DAUB", "SYM", "COIF")
        val familyAdapter = ArrayAdapter(this, R.layout.spinner_item, families)
        familyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        familySpinner.adapter = familyAdapter
        familySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (selectedFamilyIdx != pos) {
                    selectedFamilyIdx = pos
                    prefs.edit().putInt("family", pos).apply()
                    updateOrderSliderRange()
                    runDwt()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val boundaries = arrayOf("ZERO", "PER", "SYM")
        val boundaryAdapter = ArrayAdapter(this, R.layout.spinner_item, boundaries)
        boundaryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        boundarySpinner.adapter = boundaryAdapter
        boundarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                if (selectedBoundaryIdx != pos) {
                    selectedBoundaryIdx = pos
                    prefs.edit().putInt("boundary", pos).apply()
                    runDwt()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        checkSoft.setOnCheckedChangeListener { _, isChecked ->
            isSoftThreshold = isChecked
            prefs.edit().putBoolean("soft_thresh", isChecked).apply()
            runDwt()
        }
        checkWPT.setOnCheckedChangeListener { _, isChecked ->
            isWPT = isChecked
            waveletView.setWPT(isWPT)
            prefs.edit().putBoolean("wpt", isWPT).apply()
            runDwt()
        }
        checkLog.setOnCheckedChangeListener { _, isChecked ->
            isLogScale = isChecked
            waveletView.setLogScale(isLogScale)
            prefs.edit().putBoolean("log_scale", isChecked).apply()
        }
        checkLocalNorm.setOnCheckedChangeListener { _, isChecked ->
            isLocalNorm = isChecked
            waveletView.setLocalNorm(isLocalNorm)
            prefs.edit().putBoolean("local_norm", isChecked).apply()
        }
        checkReconstruct.setOnCheckedChangeListener { _, isChecked ->
            isReconstruct = isChecked
            prefs.edit().putBoolean("reconstruct", isReconstruct).apply()
            runDwt()
        }
        checkCWT.setOnCheckedChangeListener { _, isChecked ->
            isCWT = isChecked
            waveletView.setCwtMode(isCWT)
            prefs.edit().putBoolean("cwt", isCWT).apply()
            runDwt()
        }

        findViewById<Button>(R.id.btnWaveletGalleryTop).setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.btnWaveletListenTop).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        findViewById<Button>(R.id.btnWaveletStop).setOnClickListener {
            isStopRequested = true
        }

        sliderLevel.addOnChangeListener { s, value, fromUser ->
            if (fromUser) {
                decompositionLevel = value.toInt()
                txtLevelValue.text = decompositionLevel.toString()
                prefs.edit().putInt("level", decompositionLevel).apply()
                updateLabelPosition(s, txtLevelValue)
                runDwt()
            }
        }
        sliderOrder.addOnChangeListener { s, value, fromUser ->
            if (fromUser) {
                waveletOrder = value.toInt()
                txtOrderValue.text = waveletOrder.toString()
                prefs.edit().putInt("order", waveletOrder).apply()
                updateLabelPosition(s, txtOrderValue)
                runDwt()
            }
        }
        sliderSampling.addOnChangeListener { s, value, fromUser ->
            if (fromUser) {
                targetFreq = value
                txtSamplingValue.text = if (targetFreq >= 1000) "${(targetFreq/1000).toInt()}\nkHz" else "${targetFreq.toInt()}\nHz"
                prefs.edit().putFloat("freq", targetFreq).apply()
                updateLabelPosition(s, txtSamplingValue)
                runDwt()
            }
        }
        sliderThreshold.addOnChangeListener { s, value, fromUser ->
            if (fromUser) {
                // Ensure value is a precise multiple of stepSize (0.001) to prevent slider validation crashes
                threshold = (value * 1000f).roundToInt() / 1000f
                txtThresholdValue.text = String.format(java.util.Locale.US, "%.3f", threshold)
                prefs.edit().putFloat("threshold", threshold).apply()
                updateLabelPosition(s, txtThresholdValue)
                runDwt()
            }
        }
        sliderColor.addOnChangeListener { s, value, fromUser ->
            if (fromUser) {
                colorSchemeIdx = value.toInt()
                val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
                txtColorValue.text = colorNames[colorSchemeIdx.coerceIn(0, 3)]
                waveletView.setColorScheme(colorSchemeIdx)
                prefs.edit().putInt("color_scheme", colorSchemeIdx).apply()
                updateLabelPosition(s, txtColorValue)
            }
        }
    }

    private fun updateOrderSliderRange() {
        val availableOrders = filterMap[selectedFamilyIdx]?.keys?.sorted() ?: listOf(2)
        val min = availableOrders.first().toFloat()
        val max = availableOrders.last().toFloat()
        
        // Temporarily broaden bounds to avoid value-out-of-bounds crashes during update
        sliderOrder.valueFrom = minOf(sliderOrder.valueFrom, min, sliderOrder.value)
        sliderOrder.valueTo = maxOf(sliderOrder.valueTo, max, sliderOrder.value)
        
        if (waveletOrder !in availableOrders) {
            waveletOrder = availableOrders.first()
        }
        
        sliderOrder.setSafeValue(waveletOrder.toFloat())
        
        if (min == max) {
            sliderOrder.valueFrom = min - 1
            sliderOrder.valueTo = max
            sliderOrder.isEnabled = false
        } else {
            sliderOrder.valueFrom = min
            sliderOrder.valueTo = max
            sliderOrder.isEnabled = true
        }
    }

    private fun loadAndDecode(file: File) {
        val requestId = ++currentRequestId
        isStopRequested = false
        Thread {
            var codec: MediaCodec? = null
            var extractor: MediaExtractor? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                if (extractor.trackCount == 0) {
                    extractor.release()
                    runOnUiThread { Toast.makeText(this, "No audio track found", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toFloat()
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime == null) {
                    runOnUiThread { Toast.makeText(this, "Unknown audio format", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                var pcmDataArr = FloatArray(256 * 1024)
                var pcmCount = 0
                val info = MediaCodec.BufferInfo()
                var isEOS = false

                val maxSamples = 200000 

                while (!isEOS && pcmCount < maxSamples && !isStopRequested && requestId == currentRequestId) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10000)
                    if (outIdx >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIdx)!!
                        val samplesAvailable = outBuffer.remaining() / 2
                        val framesAvailable = samplesAvailable / channels
                        val framesToRead = min(framesAvailable, maxSamples - pcmCount)
                        
                        if (pcmCount + framesToRead > pcmDataArr.size) {
                            pcmDataArr = pcmDataArr.copyOf(max(pcmDataArr.size * 2, pcmCount + framesToRead))
                        }
                        
                        for (unused1 in 0 until framesToRead) {
                            var sum = 0f
                            for (unused2 in 0 until channels) {
                                if (outBuffer.remaining() >= 2) {
                                    sum += outBuffer.short / 32768f
                                }
                            }
                            pcmDataArr[pcmCount++] = sum / channels
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
                
                if (requestId == currentRequestId && !isStopRequested) {
                    pcmData = pcmDataArr.copyOf(pcmCount)
                    runOnUiThread { 
                        if (validateConstraints()) {
                            runDwt()
                        }
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace()
                runOnUiThread { 
                    resetToSafeSettings("Decode error: ${e.message}")
                    Toast.makeText(this, "Decode error: ${e.message}", Toast.LENGTH_SHORT).show() 
                }
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                } catch (unused: Exception) {}
                try {
                    extractor?.release()
                } catch (unused: Exception) {}
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
        
        // Final safety check for CWT memory usage
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
                    val omega = if (i <= paddedSize / 2) {
                        (2f * PI.toFloat() * i) / paddedSize
                    } else {
                        (2f * PI.toFloat() * (i - paddedSize)) / paddedSize
                    }
                    
                    val valExp = -0.5f * (scale * omega - w0).pow(2)
                    if (valExp > -20f) {
                        wavRe[i] = exp(valExp) * sqrt(scale)
                    } else {
                        wavRe[i] = 0f
                    }
                    wavIm[i] = 0f
                }
                
                for (i in 0 until paddedSize) {
                    val r = sigRe[i] * wavRe[i] - sigIm[i] * wavIm[i]
                    val im = sigRe[i] * wavIm[i] + sigIm[i] * wavRe[i]
                    wavRe[i] = r
                    wavIm[i] = im
                }
                
                FFTUtils.inverse(wavRe, wavIm)
                for (i in 0 until n) {
                    coefficients[s][i] = applyThreshold(sqrt(wavRe[i] * wavRe[i] + wavIm[i] * wavIm[i]))
                }

                if (s % 10 == 0 || s == numScales - 1) {
                    val progressVal = (s + 1).toFloat() / numScales
                    val interim = coefficients.slice(0..s).toList()
                    runOnUiThread {
                        waveletView.setProgress(progressVal)
                        waveletView.updateData(interim, isInterim = true)
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { resetToSafeSettings("CWT error: ${e.message}") }
            return
        }
        
        if (requestId == currentRequestId) {
            if (isStopRequested) {
                runOnUiThread { 
                    waveletView.setCalculating(false) 
                    Toast.makeText(this@WaveletActivity, "Calculation Stopped", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    waveletView.setSamplingFreq(targetFreq)
                    waveletView.updateData(coefficients.toList(), isInterim = false)
                }
            }
        }
    }

    private fun resample(input: FloatArray, from: Float, to: Float): FloatArray {
        if (abs(from - to) < 1f) return input
        val ratio = to / from
        val newSize = (input.size * ratio).toInt()
        val output = FloatArray(newSize)
        for (i in 0 until newSize) {
            val srcIdx = i / ratio
            val i0 = floor(srcIdx).toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = srcIdx - i0
            output[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return output
    }

    private fun decompose(signal: FloatArray, h: FloatArray, g: FloatArray): Pair<FloatArray, FloatArray> {
        val n = signal.size
        val m = h.size
        val outSize = (n + m - 1) / 2
        val approx = FloatArray(outSize)
        val detail = FloatArray(outSize)

        for (i in 0 until outSize) {
            var sumA = 0f
            var sumD = 0f
            for (j in 0 until m) {
                val idx = getIdx(2 * i + j, n)
                sumA += signal[idx] * h[j]
                sumD += signal[idx] * g[j]
            }
            approx[i] = sumA
            detail[i] = sumD
        }
        return Pair(approx, detail)
    }

    private fun getDetailFilter(h: FloatArray): FloatArray {
        val g = FloatArray(h.size)
        for (i in h.indices) {
            g[i] = (if (i % 2 == 0) 1f else -1f) * h[h.size - 1 - i]
        }
        return g
    }

    private fun runReconstruct() {
        val originalData = pcmData ?: return
        val requestId = ++currentRequestId
        isStopRequested = false
        Thread {
            try {
                val data = resample(originalData, originalSampleRate, targetFreq)
                val h = filterMap[selectedFamilyIdx]?.get(waveletOrder) ?: return@Thread
                val g = getDetailFilter(h)
                
                runOnUiThread { waveletView.setCalculating(calculating = true) }
                runReconstructInternal(data, h, g, requestId)
            } catch (e: Exception) {
                runOnUiThread { resetToSafeSettings("Reconstruct error: ${e.message}") }
            }
        }.start()
    }

    private fun runReconstructInternal(data: FloatArray, h: FloatArray, g: FloatArray, requestId: Int) {
        val details = mutableListOf<FloatArray>()
        var currentSignal = data
        for (level in 0 until decompositionLevel) {
            if (isStopRequested || requestId != currentRequestId) break
            val (a, d) = decompose(currentSignal, h, g)
            details.add(d.map { applyThreshold(it) }.toFloatArray())
            currentSignal = a
            runOnUiThread {
                waveletView.setProgress((level + 1).toFloat() / (decompositionLevel * 2))
                waveletView.updateData(listOf(currentSignal), isInterim = true)
            }
        }

        if (!isStopRequested && requestId == currentRequestId) {
            var reconstructed = currentSignal
            for (i in details.size - 1 downTo 0) {
                if (isStopRequested || requestId != currentRequestId) break
                reconstructed = reconstruct(reconstructed, details[i], h, g)
                val p = (decompositionLevel + (details.size - i)).toFloat() / (decompositionLevel * 2)
                runOnUiThread {
                    waveletView.setProgress(p)
                    waveletView.updateData(listOf(reconstructed), isInterim = true)
                }
            }
            if (requestId == currentRequestId && !isStopRequested) {
                runOnUiThread {
                    waveletView.setSamplingFreq(targetFreq)
                    waveletView.updateData(listOf(reconstructed), isInterim = false)
                }
            }
        }
    }

    private fun reconstruct(approx: FloatArray, detail: FloatArray, h: FloatArray, g: FloatArray): FloatArray {
        val n = approx.size * 2
        val m = h.size
        val signal = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            for (j in 0 until m) {
                val idx = i - j
                if (idx >= 0 && idx % 2 == 0) {
                    val p = idx / 2
                    if (p < approx.size) {
                        sum += approx[p] * h[j] + detail[p] * g[j]
                    }
                }
            }
            signal[i] = sum
        }
        return signal
    }

    private fun getIdx(i: Int, n: Int): Int {
        return when (selectedBoundaryIdx) {
            0 -> if (i < n) i else 0 
            1 -> i % n 
            2 -> {
                if (i < n) i else (2 * n - 1 - i).coerceIn(0, n - 1)
            }
            else -> i.coerceIn(0, n - 1)
        }
    }

    private fun applyThreshold(d: Float): Float {
        // Floating point precision fix for Material Slider validation
        val roundedThreshold = (threshold * 1000f).roundToInt() / 1000f
        val mag = abs(d)
        return if (isSoftThreshold) {
            if (mag > roundedThreshold) sign(d) * (mag - roundedThreshold) else 0f
        } else {
            if (mag > roundedThreshold) d else 0f
        }
    }
}
