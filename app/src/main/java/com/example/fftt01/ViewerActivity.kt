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
        val eqIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val eqLabels = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        for (i in eqIds.indices) {
            updateLabelPosition(findViewById(eqIds[i]), findViewById(eqLabels[i]))
        }
        updateLabelPosition(findViewById(R.id.vFftSize), findViewById(R.id.txtFftSizeValue))
        updateLabelPosition(findViewById(R.id.vFftStep), findViewById(R.id.txtFftStepValue))
        updateLabelPosition(findViewById(R.id.vColor), findViewById(R.id.txtVColorName))

        updateLabelPosition(findViewById(R.id.vSliderNoiseFilter), findViewById(R.id.vTxtFilterValue))
        updateLabelPosition(findViewById(R.id.vSliderNoiseRise), findViewById(R.id.vTxtRiseValue))
        updateLabelPosition(findViewById(R.id.vSliderNoiseFall), findViewById(R.id.vTxtFallValue))
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
        }

        findViewById<Button>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerPlay).setOnClickListener { playAudio() }
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
                refreshFft()
            }
        }

        setupNoiseFilter()

        val sizeSlider = findViewById<Slider>(R.id.vFftSize)
        val stepSlider = findViewById<Slider>(R.id.vFftStep)
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
            refreshFft()
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
            if (fromUser) {
                currentFftSize = fftValues[s.value.toInt()]
                txtSizeValue.text = currentFftSize.toString()
                prefs.edit().putInt("fft_size_idx", s.value.toInt()).apply()
                
                validateStepSlider(s, stepSlider)
                updateLabelPosition(s, txtSizeValue)
                refreshFft()
            }
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
            refreshFft()
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
        txtFilterValue?.text = "${(noiseFilterStrength * 100).toInt()}\n%"
        adjustSliderThickness(sliderFilter, txtFilterValue)
        sliderFilter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = "${(value * 100).toInt()}\n%"
            prefs.edit().putFloat("noise_filter_strength", value).apply()
            updateLabelPosition(slider, txtFilterValue)
            refreshFft()
        }

        // Rise: Milliseconds (1 to 1000)
        val savedRiseMs = prefs.getFloat("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
        sliderRise.setSafeValue(savedRiseMs)
        adjustSliderThickness(sliderRise, txtRiseValue)
        sliderRise.addOnChangeListener { slider, value, _ ->
            prefs.edit().putFloat("noise_filter_rise_ms", value).apply()
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtRiseValue)
            refreshFft()
        }

        // Fall: Milliseconds (1 to 1000)
        val savedFallMs = prefs.getFloat("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
        sliderFall.setSafeValue(savedFallMs)
        adjustSliderThickness(sliderFall, txtFallValue)
        sliderFall.addOnChangeListener { slider, value, _ ->
            prefs.edit().putFloat("noise_filter_fall_ms", value).apply()
            updateCoeffsFromMs()
            updateLabelPosition(slider, txtFallValue)
            refreshFft()
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
                runOnUiThread { refreshFft() }
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

    private fun adjustSliderThickness(slider: Slider, label: TextView?) {
        slider.post {
            val parent = slider.parent as? android.view.View ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val density = resources.displayMetrics.density
            val gutterPx = 4f * density
            val maxThickness = 88f * density
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

    private fun updateLabelPosition(slider: Slider, label: TextView?, thumbRadiusPx: Float = (slider.trackHeight / 2f)) {
        if (label == null) return
        if (slider.height == 0) {
            slider.post { updateLabelPosition(slider, label, thumbRadiusPx) }
            return
        }
        val range = slider.valueTo - slider.valueFrom
        if (range == 0f) return

        val normalizedValue = (slider.value - slider.valueFrom) / range
        val totalHeight = slider.height.toFloat()

        val trackTop = thumbRadiusPx
        val trackBottom = totalHeight - thumbRadiusPx
        val trackLength = trackBottom - trackTop

        val thumbY = trackBottom - (normalizedValue * trackLength)
        val viewCenterY = totalHeight / 2f
        label.translationY = thumbY - viewCenterY
    }
}
