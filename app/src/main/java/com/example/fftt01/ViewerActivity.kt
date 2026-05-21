package com.example.fftt01

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.edit
import androidx.core.view.isGone
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.*
import kotlin.concurrent.thread
import android.content.res.Configuration

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewerFft: FFTHeatMapView
    private lateinit var sizeSpinner: Spinner
    private lateinit var stepSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var blurSpinner: Spinner
    private lateinit var btnSweep: Button

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var filePath: String? = null

    private var sampleRate = 44100
    private var currentFftSize = 2048
    private var currentStepSize = 1024
    
    private val eqBands = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
    private val filters = Array(5) { i ->
        BiquadFilter(BiquadFilter.Type.PEAKING, sampleRate.toFloat(), eqBands[i], 1.0f, 0f)
    }

    private val fftValues = intArrayOf(256, 512, 1024, 2048, 4096)
    private var rawPcmData: FloatArray? = null
    private lateinit var prefs: SharedPreferences

    private var noiseFilterStrength = 0f
    private var noiseRiseCoeff = 0.015f
    private var noiseFallCoeff = 0.05f
    private var isSweepActive = false

    private val refreshLock = Any()
    private var refreshCount = 0
    private var activeThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        viewerFft = findViewById(R.id.viewerFft)
        filePath = intent.getStringExtra("FILE_PATH")

        setupControls()

        viewerFft.post {
            filePath?.let { path ->
                loadAndDecode(File(path))
            }
            // Initial label positioning
            findViewById<android.view.View>(android.R.id.content).post {
                updateAllLabelPositions()
            }
        }
    }

    private fun updateAllLabelPositions() {
        val sliderFilter = findViewById<Slider>(R.id.vSliderNoiseFilter)
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtFilterValue = findViewById<TextView>(R.id.vTxtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)
        
        if (sliderFilter != null) adjustSliderThickness(sliderFilter, txtFilterValue)
        if (sliderRise != null) adjustSliderThickness(sliderRise, txtRiseValue)
        if (sliderFall != null) adjustSliderThickness(sliderFall, txtFallValue)
        
        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        for (i in eqIds.indices) {
            val slider = findViewById<Slider>(eqIds[i])
            val label = findViewById<TextView>(eqLabels[i])
            if (slider != null && label != null) {
                adjustSliderThickness(slider, label)
            }
        }
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

    private fun setupControls() {
        sizeSpinner = findViewById(R.id.vSizeSpinner)
        stepSpinner = findViewById(R.id.vStepSpinner)
        colorSpinner = findViewById(R.id.vColorSpinner)
        blurSpinner = findViewById(R.id.vBlurSpinner)
        btnSweep = findViewById(R.id.btnViewerSweep)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        findViewById<Button>(R.id.btnViewerGalleryTop).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerListenTop).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        findViewById<Button>(R.id.btnViewerPlay).setOnClickListener { playAudio() }
        
        // btnViewerWavelet is optional in landscape (View placeholder used)
        findViewById<View?>(R.id.btnViewerWavelet)?.setOnClickListener {
            val intent = Intent(this, WaveletActivity::class.java).apply {
                putExtra("FILE_PATH", filePath)
            }
            startActivity(intent)
        }

        setupFftSpinners()
        
        // Landscape loses sweep, wave, color, blur, and filter capability
        if (!isLandscape) {
            setupColorSpinner()
            setupNoiseFilter()
            setupBlurSpinner()
            setupSweepToggle()
        } else {
            // Restore saved settings even if controls are hidden
            val savedColorScheme = prefs.getInt("color_scheme", 0)
            viewerFft.setColorScheme(savedColorScheme)
            val savedBlur = prefs.getInt("blur_radius", 0).coerceIn(0, 10)
            viewerFft.setBlur(savedBlur)
            
            // Sweep is always off in land
            isSweepActive = false
        }
        
        setupEqSliders()
    }

    private fun setupColorSpinner() {
        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        val colorDisplayNames = colorNames.map { getString(R.string.color_prefix, it) }
        val colorAdapter = ArrayAdapter(this, R.layout.spinner_item_gold, colorDisplayNames)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorSpinner.adapter = colorAdapter
        val savedColorScheme = prefs.getInt("color_scheme", 0)
        colorSpinner.setSelection(savedColorScheme)
        viewerFft.setColorScheme(savedColorScheme)

        colorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                viewerFft.setColorScheme(pos)
                prefs.edit { putInt("color_scheme", pos) }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupSweepToggle() {
        btnSweep.setOnClickListener {
            isSweepActive = !isSweepActive
            updateSweepButtonState()
            triggerRefresh()
        }
        updateSweepButtonState()
    }

    private fun updateSweepButtonState() {
        if (isSweepActive) {
            btnSweep.text = getString(R.string.sweep_on)
            btnSweep.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            sizeSpinner.isEnabled = false
            stepSpinner.isEnabled = false
        } else {
            btnSweep.text = getString(R.string.sweep_off)
            btnSweep.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF69B4"))
            sizeSpinner.isEnabled = true
            stepSpinner.isEnabled = true
        }
    }

    private fun setupBlurSpinner() {
        val blurValues = (0..10).toList()
        val displayNames = blurValues.map { getString(R.string.label_blur, it) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_blur, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        blurSpinner.adapter = adapter
        
        val savedBlur = prefs.getInt("blur_radius", 0).coerceIn(0, 10)
        blurSpinner.setSelection(savedBlur)
        viewerFft.setBlur(savedBlur)

        blurSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                viewerFft.setBlur(pos)
                prefs.edit { putInt("blur_radius", pos) }
                triggerRefresh()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupFftSpinners() {
        // Size Spinner
        val sizeDisplayNames = fftValues.map { getString(R.string.fft_size_prefix, it) }
        val sizeAdapter = ArrayAdapter(this, R.layout.spinner_item_small_gray, sizeDisplayNames)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sizeSpinner.adapter = sizeAdapter
        val savedSizeIdx = prefs.getInt("fft_size_idx", 3).coerceIn(0, fftValues.size - 1)
        sizeSpinner.setSelection(savedSizeIdx, false)
        currentFftSize = fftValues[savedSizeIdx]

        // Initialize Step Spinner based on saved Size
        updateStepSpinner(currentFftSize)

        // Listeners
        sizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newSize = fftValues[pos]
                if (newSize != currentFftSize) {
                    currentFftSize = newSize
                    prefs.edit { putInt("fft_size_idx", pos) }
                    updateStepSpinner(currentFftSize)
                    triggerRefresh()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateStepSpinner(selectedSize: Int) {
        // Filter options: Step <= Size / 2
        val maxAllowedStep = selectedSize / 2
        val validSteps = fftValues.filter { it <= maxAllowedStep }
        
        // If no values from our standard set are valid (e.g. Size=256), we must allow at least one smaller step.
        // But since fftValues starts at 256, 256/2 = 128 is valid. I'll ensure 128 is handled if needed.
        val finalSteps = if (validSteps.isEmpty()) listOf(selectedSize / 2) else validSteps

        val stepDisplayNames = finalSteps.map { getString(R.string.fft_step_prefix, it) }
        val stepAdapter = ArrayAdapter(this, R.layout.spinner_item_small_darkgray, stepDisplayNames)
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        // Important: preserve selection if possible, otherwise clamp
        val oldStep = currentStepSize
        stepSpinner.adapter = stepAdapter
        
        val newSelection = finalSteps.indexOf(oldStep).coerceAtLeast(0)
        stepSpinner.setSelection(newSelection, false)
        currentStepSize = finalSteps[newSelection]

        stepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newStep = finalSteps[pos]
                if (newStep != currentStepSize) {
                    currentStepSize = newStep
                    // Save index relative to standard fftValues for simplicity if it exists, otherwise just save value
                    val globalIdx = fftValues.indexOf(newStep)
                    if (globalIdx != -1) prefs.edit { putInt("fft_step_idx", globalIdx) }
                    triggerRefresh()
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupEqSliders() {
        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(eqIds[i])
            val txtValue = findViewById<TextView>(eqLabels[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloat(key, 0f)
            slider.setSafeValue(savedGain)
            txtValue.text = getString(R.string.db_value, slider.value.toInt())
            filters[i].gainDb = slider.value
            filters[i].updateCoefficients()

            adjustSliderThickness(slider, txtValue)

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                txtValue.text = getString(R.string.db_value, value.toInt())
                prefs.edit { putFloat(key, value) }
                updateLabelPosition(s, txtValue)
                triggerRefresh()
            }
        }
    }

    private fun triggerRefresh() {
        val currentRefresh = synchronized(refreshLock) {
            refreshCount++
            refreshCount
        }

        thread {
            synchronized(refreshLock) {
                if (currentRefresh < refreshCount) return@thread
                activeThread?.interrupt()
                activeThread = Thread.currentThread()
            }

            try {
                if (isSweepActive) runFftSweepInternal(currentRefresh) 
                else refreshFftInternal(currentRefresh)
            } catch (e: InterruptedException) {
                // Task cancelled
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                synchronized(refreshLock) {
                    if (activeThread == Thread.currentThread()) {
                        activeThread = null
                    }
                }
            }
        }
    }

    private fun setupNoiseFilter() {
        val sliderFilter = findViewById<Slider>(R.id.vSliderNoiseFilter)
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtFilterValue = findViewById<TextView>(R.id.vTxtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
        sliderFilter.setSafeValue(noiseFilterStrength)
        txtFilterValue?.text = getString(R.string.percent_value, (noiseFilterStrength * 100).toInt())
        adjustSliderThickness(sliderFilter, txtFilterValue)
        sliderFilter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = getString(R.string.percent_value, (value * 100).toInt())
            prefs.edit { putFloat("noise_filter_strength", value) }
            updateLabelPosition(slider, txtFilterValue)
            triggerRefresh()
        }

        // Rise: Milliseconds (1 to 1000) Logarithmic
        sliderRise.valueFrom = 0f
        sliderRise.valueTo = 3f
        sliderRise.stepSize = 0.01f
        val savedRiseMs = prefs.getFloat("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
        sliderRise.setSafeValue(log10(savedRiseMs.toDouble()).toFloat())
        
        adjustSliderThickness(sliderRise, txtRiseValue)
        sliderRise.addOnChangeListener { slider, value, _ ->
            val ms = 10.0.pow(value.toDouble()).toFloat()
            prefs.edit { putFloat("noise_filter_rise_ms", ms) }
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtRiseValue)
            triggerRefresh()
        }

        // Fall: Milliseconds (1 to 1000) Logarithmic
        sliderFall.valueFrom = 0f
        sliderFall.valueTo = 3f
        sliderFall.stepSize = 0.01f
        val savedFallMs = prefs.getFloat("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
        sliderFall.setSafeValue(log10(savedFallMs.toDouble()).toFloat())
        
        adjustSliderThickness(sliderFall, txtFallValue)
        sliderFall.addOnChangeListener { slider, value, _ ->
            val ms = 10.0.pow(value.toDouble()).toFloat()
            prefs.edit { putFloat("noise_filter_fall_ms", ms) }
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtFallValue)
            triggerRefresh()
        }

        updateCoeffsFromMs()
    }

    private fun updateCoeffsFromMs() {
        val sliderRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sliderFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        val frameDurationMs = (currentStepSize.toFloat() / sampleRate) * 1000f
        val k = ln(2.0f) * frameDurationMs

        val riseMs = 10.0.pow(sliderRise.value.toDouble()).toFloat()
        val fallMs = 10.0.pow(sliderFall.value.toDouble()).toFloat()

        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)

        txtRiseValue?.text = getString(R.string.ms_value, riseMs.toInt())
        txtFallValue?.text = getString(R.string.ms_value, fallMs.toInt())
    }

    private fun loadAndDecode(file: File) {
        thread {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                
                // Update filters with correct sample rate
                for (f in filters) {
                    f.sampleRate = sampleRate.toFloat()
                    f.updateCoefficients()
                }

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

                    val outIndex = codec.dequeueOutputBuffer(info, 10000)
                    if (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        while (outBuffer.remaining() >= 2) {
                            pcmList.add(outBuffer.short / 32768f)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
                codec.stop()
                codec.release()
                extractor.release()
                
                rawPcmData = pcmList.toFloatArray()
                runOnUiThread { triggerRefresh() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshFftInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) {
            viewerFft.post { triggerRefresh() }
            return
        }
        
        // Count number of columns first
        var columnCount = 0
        var countOffset = 0
        while (countOffset + currentFftSize <= pcm.size) {
            columnCount++
            countOffset += currentStepSize
        }
        if (columnCount <= 0) return

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            viewerFft.setParams(currentFftSize, sampleRate.toFloat(), currentStepSize)
            viewerFft.setMaxHistory(columnCount)
            viewerFft.clearHistory()
            viewerFft.isFrozen = true
        }

        // Accumulation buffer for standard FFT
        val accumulationBuffer = Array(columnCount) { FloatArray(viewHeight) }

        // Filter PCM
        for (f in filters) f.reset()
        val filteredPcm = FloatArray(pcm.size)
        for (i in pcm.indices) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            var s = pcm[i]
            for (f in filters) s = f.process(s)
            filteredPcm[i] = s
        }

        val hannWindow = FloatArray(currentFftSize) { i ->
            (0.5f * (1 - cos(2 * PI * i / (currentFftSize - 1)))).toFloat()
        }

        val minFreq = 80f
        val maxFreq = 10000f
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        
        // Precalculate mapping
        val mapping = IntArray(viewHeight) { y ->
            val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
            val freq = 10.0.pow(logF.toDouble()).toFloat()
            (freq * currentFftSize / sampleRate).toInt().coerceIn(0, currentFftSize / 2 - 1)
        }

        var globalMax = 1e-9f
        val noiseFloor = FloatArray(currentFftSize / 2)

        var offset = 0
        var c = 0
        while (offset + currentFftSize <= filteredPcm.size) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val real = FloatArray(currentFftSize)
            val imag = FloatArray(currentFftSize)
            for (i in 0 until currentFftSize) {
                real[i] = filteredPcm[offset + i] * hannWindow[i]
            }
            
            FFTUtils.compute(real, imag)
            
            val mags = FloatArray(currentFftSize / 2)
            for (i in 0 until currentFftSize / 2) {
                var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])

                if (noiseFilterStrength > 0f) {
                    if (noiseFloor[i] == 0f) noiseFloor[i] = mag
                    else if (mag < noiseFloor[i]) noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                    else noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                    mag = (mag - noiseFloor[i] * noiseFilterStrength).coerceAtLeast(0f)
                }
                mags[i] = mag
            }

            for (y in 0 until viewHeight) {
                val mag = mags[mapping[y]]
                accumulationBuffer[c][y] = mag
                if (mag > globalMax) globalMax = mag
            }

            offset += currentStepSize
            c++
        }

        val maxDB = 20 * log10(globalMax + 1e-9f)
        for (idx in 0 until columnCount) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val normalized = FloatArray(viewHeight)
            for (y in 0 until viewHeight) {
                val dB = 20 * log10(accumulationBuffer[idx][y] + 1e-9f)
                normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    viewerFft.updateFFT(normalized, force = true)
                }
            }
        }
    }

    private fun runFftSweepInternal(refreshId: Int) {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) {
            viewerFft.post { triggerRefresh() }
            return
        }

        val sweepSizes = intArrayOf(512, 1024, 2048, 4096)
        val sweepSteps = intArrayOf(256, 512, 1024, 2048, 4096)
        
        val baseStep = 256
        val baseSize = 512
        var baseHistory = 0
        var tempOffset = 0
        while (tempOffset + baseSize <= pcm.size) {
            baseHistory++
            tempOffset += baseStep
        }
        if (baseHistory <= 0) return

        val accumulationBuffer = Array(baseHistory) { FloatArray(viewHeight) }
        
        // Filter PCM
        for (f in filters) f.reset()
        val filteredPcm = FloatArray(pcm.size)
        for (i in pcm.indices) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            var s = pcm[i]
            for (f in filters) s = f.process(s)
            filteredPcm[i] = s
        }

        val minFreq = 80f
        val maxFreq = 10000f
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        
        var globalMax = 1e-9f

        for (size in sweepSizes) {
            if (size > filteredPcm.size) continue
            val hannWindow = FloatArray(size) { i -> 
                (0.5f * (1 - cos(2 * PI * i / (size - 1)))).toFloat() 
            }
            
            // Precalculate bin mapping for this resolution
            val mapping = IntArray(viewHeight) { y ->
                val logF = logMax - (y.toFloat() / viewHeight) * (logMax - logMin)
                val freq = 10.0.pow(logF.toDouble()).toFloat()
                (freq * size / sampleRate).toInt().coerceIn(0, size / 2 - 1)
            }

            for (step in sweepSteps) {
                if (step > size) continue
                
                val noiseFloor = FloatArray(size / 2)
                var offset = 0
                while (offset + size <= filteredPcm.size) {
                    if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
                    val c = offset / baseStep
                    if (c >= baseHistory) break

                    val real = FloatArray(size)
                    val imag = FloatArray(size)
                    for (i in 0 until size) {
                        real[i] = filteredPcm[offset + i] * hannWindow[i]
                    }
                    FFTUtils.compute(real, imag)
                    
                    val mags = FloatArray(size / 2)
                    for (i in 0 until size / 2) {
                        var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                        if (noiseFilterStrength > 0f) {
                            if (noiseFloor[i] == 0f) noiseFloor[i] = mag
                            else if (mag < noiseFloor[i]) noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                            else noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                            mag = (mag - noiseFloor[i] * noiseFilterStrength).coerceAtLeast(0f)
                        }
                        mags[i] = mag
                    }

                    for (y in 0 until viewHeight) {
                        val mag = mags[mapping[y]]
                        accumulationBuffer[c][y] += mag
                        if (accumulationBuffer[c][y] > globalMax) globalMax = accumulationBuffer[c][y]
                    }
                    
                    offset += step
                }
            }
        }

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            viewerFft.setParams(baseSize, sampleRate.toFloat(), baseStep)
            viewerFft.setMaxHistory(baseHistory)
            viewerFft.clearHistory()
            viewerFft.isFrozen = true
        }
        
        val maxDB = 20 * log10(globalMax + 1e-9f)
        for (c in 0 until baseHistory) {
            if (Thread.interrupted() || refreshId < refreshCount) throw InterruptedException()
            val normalized = FloatArray(viewHeight)
            for (y in 0 until viewHeight) {
                val dB = 20 * log10(accumulationBuffer[c][y] + 1e-9f)
                normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    viewerFft.updateFFT(normalized, force = true)
                }
            }
        }
    }

    private fun playAudio() {
        val pcm = rawPcmData ?: return
        
        // Stop any current playback
        stopAudio()

        thread {
            for (f in filters) f.reset()
            val filteredPcm = FloatArray(pcm.size)
            for (i in pcm.indices) {
                var s = pcm[i]
                for (f in filters) s = f.process(s)
                filteredPcm[i] = s
            }

            if (noiseFilterStrength > 0f) {
                val noiseFloor = FloatArray(currentFftSize / 2)
                val hannWindow = FloatArray(currentFftSize) { i ->
                    (0.5f * (1 - cos(2 * PI * i / (currentFftSize - 1)))).toFloat()
                }

                // Process in blocks to apply spectral subtraction
                var offset = 0
                while (offset + currentFftSize <= filteredPcm.size) {
                    val real = FloatArray(currentFftSize)
                    val imag = FloatArray(currentFftSize)
                    for (i in 0 until currentFftSize) {
                        real[i] = filteredPcm[offset + i] * hannWindow[i]
                    }

                    FFTUtils.compute(real, imag)

                    for (i in 0 until currentFftSize / 2) {
                        var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                        val phase = atan2(imag[i], real[i])

                        if (noiseFloor[i] == 0f) {
                            noiseFloor[i] = mag
                        } else {
                            if (mag < noiseFloor[i]) {
                                noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                            } else {
                                noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                            }
                        }
                        val reduction = noiseFloor[i] * noiseFilterStrength
                        mag = (mag - reduction).coerceAtLeast(0f)

                        real[i] = mag * cos(phase)
                        imag[i] = mag * sin(phase)
                        // Mirror for IFFT
                        if (i > 0) {
                            real[currentFftSize - i] = real[i]
                            imag[currentFftSize - i] = -imag[i]
                        }
                    }
                    real[currentFftSize / 2] = 0f 
                    imag[currentFftSize / 2] = 0f

                    FFTUtils.inverse(real, imag)

                    for (i in 0 until currentFftSize) {
                        if (offset + i < filteredPcm.size) {
                             filteredPcm[offset + i] = real[i]
                        }
                    }
                    offset += currentStepSize
                }
            }

            // Convert to ShortArray for AudioTrack
            val audioData = ShortArray(filteredPcm.size)
            for (i in filteredPcm.indices) {
                audioData[i] = (filteredPcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(audioData.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } else {
                AudioTrack(
                    attributes,
                    format,
                    audioData.size * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }

            audioTrack?.apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    write(audioData, 0, audioData.size)
                }
                play()
            }
        }
    }

    private fun stopAudio() {
        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {}
        }
        audioTrack = null
        
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }

    private fun adjustSliderThickness(slider: Slider, label: TextView?) {
        slider.post {
            val parent = slider.parent as? android.view.View ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val density = resources.displayMetrics.density
            val gutterPx = 4f * density
            val maxThickness = resources.getDimension(R.dimen.slider_track_height) * 2.5f
            val thickness = (availableWidth - 2 * gutterPx).coerceAtMost(maxThickness)
            
            // Set slider to be invisible but interactive
            slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setHaloTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.thumbRadius = (thickness / 2f).toInt()
            slider.trackHeight = thickness.toInt()
            slider.haloRadius = 0
            
            // Set text box appearance
            label?.let {
                it.setBackgroundColor(Color.WHITE)
                it.setTextColor(Color.BLACK)
                it.elevation = 6f * density
                it.minWidth = (40f * density).toInt()
                val baseSp = resources.getDimension(R.dimen.font_label_small) / density
                it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseSp)
                it.gravity = android.view.Gravity.CENTER
                it.setPadding(0, (2f * density).toInt(), 0, 0)
            }

            updateLabelPosition(slider, label)
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
        val trackTop = slider.paddingTop.toFloat()
        val trackBottom = totalHeight - slider.paddingBottom.toFloat()
        val trackLength = trackBottom - trackTop
        
        val thumbY = trackBottom - (normalizedValue * trackLength)
        
        // Align label center with thumbY
        label.translationY = thumbY - (label.top + label.height / 2f)
        
        // Set label height to fill the remaining space
        val targetHeight = (totalHeight - thumbY).toInt().coerceAtLeast((18f * density).toInt())
        if (label.layoutParams.height != targetHeight) {
            label.layoutParams.height = targetHeight
            label.requestLayout()
        }

        // Ensure text stays at top
        label.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        label.setPadding(0, (2f * density).toInt(), 0, 0)
        
        // Apply theme color to the bar
        val barColor = if (slider.id == R.id.vSliderNoiseFilter || slider.id == R.id.vSliderNoiseRise || slider.id == R.id.vSliderNoiseFall) {
            Color.CYAN
        } else {
            Color.GREEN
        }
        label.setBackgroundColor(barColor)
        label.setTextColor(Color.BLACK)
        label.elevation = 6f * density
    }
}
