package com.example.fftt01

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.*
import kotlin.concurrent.thread

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewerFft: FFTHeatMapView
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
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            root.post {
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
        
        adjustSliderThickness(sliderFilter, txtFilterValue, isFilter = true)
        adjustSliderThickness(sliderRise, txtRiseValue, isFilter = true)
        adjustSliderThickness(sliderFall, txtFallValue, isFilter = true)
        
        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        for (i in eqIds.indices) {
            val slider = findViewById<Slider>(eqIds[i])
            val label = findViewById<TextView>(eqLabels[i])
            adjustSliderThickness(slider, label)
        }
        
        adjustSliderThickness(findViewById(R.id.vFftSize), findViewById(R.id.txtFftSizeValue))
        adjustSliderThickness(findViewById(R.id.vFftStep), findViewById(R.id.txtFftStepValue))
        adjustSliderThickness(findViewById(R.id.vColor), findViewById(R.id.txtVColorName))
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
        val btnFilter = findViewById<Button>(R.id.btnViewerFilter)
        val eqLayout = findViewById<android.widget.LinearLayout>(R.id.vEqSlidersLayout)
        val filterLayout = findViewById<android.widget.LinearLayout>(R.id.vFilterControlsLayout)
        val txtEqTitle = findViewById<TextView>(R.id.txtViewerEqTitle) // The "EQUALIZER" title
        val btnSweep = findViewById<Button>(R.id.btnViewerSweep)
        val sizeSlider = findViewById<Slider>(R.id.vFftSize)
        val stepSlider = findViewById<Slider>(R.id.vFftStep)

        btnFilter.setOnClickListener {
            if (eqLayout.visibility == android.view.View.VISIBLE) {
                eqLayout.visibility = android.view.View.GONE
                filterLayout.visibility = android.view.View.VISIBLE
                btnFilter.text = "EQ"
                txtEqTitle?.text = "FILTER"
            } else {
                eqLayout.visibility = android.view.View.VISIBLE
                filterLayout.visibility = android.view.View.GONE
                btnFilter.text = "FILTER"
                txtEqTitle?.text = "EQUALIZER"
            }
            updateAllLabelPositions()
        }

        findViewById<Button>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerPlay).setOnClickListener { playAudio() }
        
        btnSweep.setOnClickListener {
            isSweepActive = !isSweepActive
            if (isSweepActive) {
                btnSweep.text = "SWEEP/ON"
                btnSweep.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
                sizeSlider.isEnabled = false
                stepSlider.isEnabled = false
                runFftSweep()
            } else {
                btnSweep.text = "SWEEP/OFF"
                btnSweep.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF69B4"))
                sizeSlider.isEnabled = true
                stepSlider.isEnabled = true
                refreshFft()
            }
        }

        findViewById<Button>(R.id.btnViewerWavelet).setOnClickListener {
            val intent = Intent(this, WaveletActivity::class.java).apply {
                putExtra("FILE_PATH", filePath)
            }
            startActivity(intent)
        }

        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(eqIds[i])
            val txtValue = findViewById<TextView>(eqLabels[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloat(key, 0f)
            slider.setSafeValue(savedGain)
            txtValue.text = "${slider.value.toInt()}\ndB"
            filters[i].gainDb = slider.value
            filters[i].updateCoefficients()

            adjustSliderThickness(slider, txtValue)

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                txtValue.text = "${value.toInt()}\ndB"
                prefs.edit().putFloat(key, value).apply()
                updateLabelPosition(s, txtValue)
                triggerRefresh()
            }
        }

        setupNoiseFilter()

        val colorSlider = findViewById<Slider>(R.id.vColor)
        val txtSizeValue = findViewById<TextView>(R.id.txtFftSizeValue)
        val txtStepValue = findViewById<TextView>(R.id.txtFftStepValue)
        val txtVColorName = findViewById<TextView>(R.id.txtVColorName)

        adjustSliderThickness(sizeSlider, txtSizeValue)
        adjustSliderThickness(stepSlider, txtStepValue)
        adjustSliderThickness(colorSlider, txtVColorName)

        val savedColorScheme = prefs.getInt("color_scheme", 0)
        viewerFft.setColorScheme(savedColorScheme)
        colorSlider.setSafeValue(savedColorScheme.toFloat())
        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        txtVColorName.text = colorNames[colorSlider.value.toInt().coerceIn(0, 3)]
        
        colorSlider.addOnChangeListener { s, value, _ ->
            val idx = value.toInt()
            viewerFft.setColorScheme(idx)
            txtVColorName.text = colorNames[idx.coerceIn(0, 3)]
            prefs.edit().putInt("color_scheme", idx).apply()
            updateLabelPosition(s, txtVColorName)
            triggerRefresh()
        }

        val savedSizeIdx = prefs.getInt("fft_size_idx", 3).coerceIn(0, 4)
        sizeSlider.setSafeValue(savedSizeIdx.toFloat())
        currentFftSize = fftValues[sizeSlider.value.toInt()]
        txtSizeValue.text = currentFftSize.toString()
        
        val maxStepIdx = (fftValues.indexOfFirst { it > currentFftSize / 2 } - 1).let { if (it < -1) 4 else if (it == -1) 0 else it }
        val savedStepIdx = prefs.getInt("fft_step_idx", 2).coerceIn(0, maxStepIdx)
        stepSlider.setSafeValue(savedStepIdx.toFloat())
        currentStepSize = fftValues[stepSlider.value.toInt()]
        txtStepValue.text = currentStepSize.toString()

        sizeSlider.addOnChangeListener { s, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            var idx = value.toInt()
            if (idx == 0) { 
                idx = 1
                sizeSlider.setSafeValue(1f)
            }
            currentFftSize = fftValues[sizeSlider.value.toInt()]
            txtSizeValue.text = currentFftSize.toString()
            prefs.edit().putInt("fft_size_idx", sizeSlider.value.toInt()).apply()
            
            validateStepSlider(sizeSlider, stepSlider)
            updateLabelPosition(s, txtSizeValue)
            triggerRefresh()
        }

        stepSlider.addOnChangeListener { s, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val maxSIdx = fftValues.indexOf(currentFftSize / 2).coerceAtLeast(0)
            if (value.toInt() > maxSIdx) {
                stepSlider.setSafeValue(maxSIdx.toFloat())
            }
            val idx = stepSlider.value.toInt()
            currentStepSize = fftValues[idx]
            txtStepValue.text = currentStepSize.toString()
            prefs.edit().putInt("fft_step_idx", idx).apply()
            updateLabelPosition(s, txtStepValue)
            triggerRefresh()
        }
    }

    private fun triggerRefresh() {
        if (isSweepActive) runFftSweep() else refreshFft()
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
        txtFilterValue?.text = "${(noiseFilterStrength * 100).toInt()}\n%"
        adjustSliderThickness(sliderFilter, txtFilterValue, isFilter = true)
        sliderFilter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = "${(value * 100).toInt()}\n%"
            prefs.edit().putFloat("noise_filter_strength", value).apply()
            updateLabelPosition(slider, txtFilterValue)
            triggerRefresh()
        }

        // Rise: Milliseconds (1 to 1000)
        val savedRiseMs = prefs.getFloat("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
        sliderRise.setSafeValue(savedRiseMs)
        adjustSliderThickness(sliderRise, txtRiseValue, isFilter = true)
        sliderRise.addOnChangeListener { slider, value, _ ->
            prefs.edit().putFloat("noise_filter_rise_ms", value).apply()
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtRiseValue)
            triggerRefresh()
        }

        // Fall: Milliseconds (1 to 1000)
        val savedFallMs = prefs.getFloat("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
        sliderFall.setSafeValue(savedFallMs)
        adjustSliderThickness(sliderFall, txtFallValue, isFilter = true)
        sliderFall.addOnChangeListener { slider, value, _ ->
            prefs.edit().putFloat("noise_filter_fall_ms", value).apply()
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

        val riseMs = sliderRise.value
        val fallMs = sliderFall.value

        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)

        txtRiseValue?.text = "${riseMs.toInt()}\nms"
        txtFallValue?.text = "${fallMs.toInt()}\nms"
    }

    private fun validateStepSlider(sizeSlider: Slider, stepSlider: Slider) {
        val maxStepValue = currentFftSize / 2
        val maxStepIdx = fftValues.indexOf(maxStepValue).coerceAtLeast(0)
        if (stepSlider.value.toInt() > maxStepIdx) {
            stepSlider.setSafeValue(maxStepIdx.toFloat())
            currentStepSize = fftValues[maxStepIdx]
            prefs.edit().putInt("fft_step_idx", maxStepIdx).apply()
        }
    }


    private fun loadAndDecode(file: File) {
        Thread {
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
        }.start()
    }

    private fun refreshFft() {
        val pcm = rawPcmData ?: return
        
        // Count number of columns first to set maxHistory for stretching
        var columnCount = 0
        var countOffset = 0
        while (countOffset + currentFftSize <= pcm.size) {
            columnCount++
            countOffset += currentStepSize
        }
        
        viewerFft.setParams(currentFftSize, sampleRate.toFloat(), currentStepSize)
        viewerFft.setMaxHistory(columnCount)
        viewerFft.clearHistory()
        viewerFft.isFrozen = true

        for (f in filters) f.reset()
        val filteredPcm = FloatArray(pcm.size)
        for (i in pcm.indices) {
            var s = pcm[i]
            for (f in filters) s = f.process(s)
            filteredPcm[i] = s
        }

        val hannWindow = FloatArray(currentFftSize) { i ->
            (0.5f * (1 - cos(2 * PI * i / (currentFftSize - 1)))).toFloat()
        }

        val allMagnitudes = mutableListOf<FloatArray>()
        var globalMaxMag = 1e-9f

        val noiseFloor = FloatArray(currentFftSize / 2)

        var offset = 0
        while (offset + currentFftSize <= filteredPcm.size) {
            val real = FloatArray(currentFftSize)
            val imag = FloatArray(currentFftSize)
            for (i in 0 until currentFftSize) {
                real[i] = filteredPcm[offset + i] * hannWindow[i]
            }
            
            FFTUtils.compute(real, imag)
            
            val magnitudes = FloatArray(currentFftSize / 2)
            for (i in 0 until currentFftSize / 2) {
                var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])

                if (noiseFilterStrength > 0f) {
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
                }

                magnitudes[i] = mag
                if (mag > globalMaxMag) globalMaxMag = mag
            }
            allMagnitudes.add(magnitudes)
            offset += currentStepSize
        }

        val maxDB = 20 * log10(globalMaxMag + 1e-9f)
        for (mags in allMagnitudes) {
            val normalized = FloatArray(mags.size)
            for (i in mags.indices) {
                val dB = 20 * log10(mags[i] + 1e-9f)
                normalized[i] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
            }
            viewerFft.updateFFT(normalized, true)
        }
    }

    private fun runFftSweep() {
        val pcm = rawPcmData ?: return
        val viewHeight = viewerFft.height
        if (viewHeight <= 0) {
            viewerFft.post { runFftSweep() }
            return
        }

        thread {
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
            if (baseHistory <= 0) return@thread

            val accumulationBuffer = Array(baseHistory) { FloatArray(viewHeight) }
            
            // Filter PCM
            for (f in filters) f.reset()
            val filteredPcm = FloatArray(pcm.size)
            for (i in pcm.indices) {
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
                viewerFft.setParams(baseSize, sampleRate.toFloat(), baseStep)
                viewerFft.setMaxHistory(baseHistory)
                viewerFft.clearHistory()
                viewerFft.isFrozen = true
            }
            
            val maxDB = 20 * log10(globalMax + 1e-9f)
            for (c in 0 until baseHistory) {
                val normalized = FloatArray(viewHeight)
                for (y in 0 until viewHeight) {
                    val dB = 20 * log10(accumulationBuffer[c][y] + 1e-9f)
                    normalized[y] = ((dB - (maxDB - 80)) / 80f).coerceIn(0f, 1f)
                }
                viewerFft.updateFFT(normalized, true)
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
                    real[currentFftSize / 2] = 0f // Nyquist usually zeroed or handled
                    imag[currentFftSize / 2] = 0f

                    FFTUtils.inverse(real, imag)

                    // Overlap-add could be better, but simple replace for now 
                    // (since refreshFft also processes blocks with stepSize)
                    // Note: stepSize vs currentFftSize. 
                    // If stepSize < fftSize, we have overlap.
                    for (i in 0 until currentFftSize) {
                        // Very basic window compensation if overlapping
                        // For simplicity, we'll just write the center part or similar
                        // or just use the same logic as visualization.
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

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(audioData.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.apply {
                write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
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

    private fun adjustSliderThickness(slider: Slider, label: TextView?, isFilter: Boolean = false) {
        slider.post {
            val parent = slider.parent as? android.view.View ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val density = resources.displayMetrics.density
            val gutterPx = 4f * density
            val maxThickness = (if (isFilter) 54f else 88f) * density
            val thickness = (availableWidth - 2 * gutterPx).coerceAtMost(maxThickness)

            if (thickness > 0) {
                slider.trackHeight = thickness.toInt()
                slider.thumbRadius = (thickness / 2f).toInt()
                
                // Scale text size based on thickness
                label?.let {
                    val sp = if (thickness < 40f * density) 8f else if (thickness < 60f * density) 10f else 12f
                    it.textSize = sp
                }
                
                updateLabelPosition(slider, label)
            }
        }
    }

    private fun updateLabelPosition(slider: Slider, label: TextView?) {
        if (label == null) return
        
        // If the view is effectively hidden (GONE), skip to avoid infinite layout loops.
        if (slider.visibility == android.view.View.GONE || label.visibility == android.view.View.GONE) return

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
        
        // 1. Determine center of thumb circle (thumbY relative to parent)
        val thumbRadius = slider.thumbRadius.toFloat()
        val density = label.resources.displayMetrics.density
        val edgeMargin = 4f * density 
        
        val trackTop = slider.paddingTop + thumbRadius + edgeMargin
        val trackBottom = slider.height - slider.paddingBottom - thumbRadius - edgeMargin
        val trackLength = trackBottom - trackTop
        
        val thumbYInSlider = trackBottom - (normalizedValue * trackLength)
        val thumbYInParent = slider.top + thumbYInSlider

        // 2. Determine geometric center of the text (relative to label view)
        val layout = label.layout
        val firstLineTop = layout.getLineTop(0).toFloat()
        val lastLineBottom = layout.getLineBottom(layout.lineCount - 1).toFloat()
        val textHeight = lastLineBottom - firstLineTop
        
        val contentHeight = label.height - label.paddingTop - label.paddingBottom
        val layoutTopOffset = label.paddingTop + (contentHeight - layout.height) / 2f
        val textCenterInLabel = layoutTopOffset + firstLineTop + (textHeight / 2f)

        // 3. Align centers: label.top + translationY + textCenterInLabel = thumbYInParent
        label.translationY = 0f 
        val translationNeeded = thumbYInParent - (label.top + textCenterInLabel)
        label.translationY = translationNeeded
    }
}
