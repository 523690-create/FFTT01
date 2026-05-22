package com.example.fftt01

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.*
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isGone
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.*

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewerFft: FFTHeatMapView
    private lateinit var sizeSpinner: Spinner
    private lateinit var stepSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var blurSpinner: Spinner
    private lateinit var btnSweep: Button

    private var audioTrack: AudioTrack? = null
    private var filePath: String? = null

    private var sampleRate = 44100
    private var currentFftSize = 2048
    private var currentStepSize = 1024

    private val eqBands = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
    private val filters = Array(5) { i ->
        BiquadFilter(BiquadFilter.Type.PEAKING, 44100f, eqBands[i], 1.0f, 0f)
    }

    private val fftValues = intArrayOf(256, 512, 1024, 2048, 4096, 8192)
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

        viewerFft = findViewById(R.id.viewerFft)
        sizeSpinner = findViewById(R.id.vSizeSpinner)
        stepSpinner = findViewById(R.id.vStepSpinner)
        colorSpinner = findViewById(R.id.vColorSpinner)
        blurSpinner = findViewById(R.id.vBlurSpinner)
        btnSweep = findViewById(R.id.btnViewerSweep)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        
        setupFftSpinners()
        setupColorSpinner()
        setupBlurSpinner()
        setupNoiseFilter()
        setupEqSliders()
        setupSweepToggle()

        filePath = intent.getStringExtra("FILE_PATH")
        filePath?.let { loadAndDecode(File(it)) }

        findViewById<Button>(R.id.btnViewerGalleryTop).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerListenTop).setOnClickListener { finish() }
        
        // Auto scale buttons and titles
        UiUtils.autoScaleText(findViewById(R.id.txtTitleViewer))
        UiUtils.autoScaleText(findViewById(R.id.btnViewerSweep))
        UiUtils.autoScaleText(findViewById(R.id.btnViewerGalleryTop))
        UiUtils.autoScaleText(findViewById(R.id.btnViewerListenTop))
        UiUtils.autoScaleText(findViewById(R.id.btnViewerPlay))
        UiUtils.autoScaleText(findViewById(R.id.btnViewerWavelet))
        
        findViewById<Button>(R.id.btnViewerPlay).setOnClickListener { playAudio() }
        findViewById<Button>(R.id.btnViewerWavelet).setOnClickListener {
            val intent = Intent(this, WaveletActivity::class.java)
            intent.putExtra("FILE_PATH", filePath)
            startActivity(intent)
        }
    }

    private fun setupColorSpinner() {
        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        val colorAdapter = ArrayAdapter(this, R.layout.spinner_item_large, colorNames)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorSpinner.adapter = colorAdapter
        val savedColorScheme = prefs.getInt("color_scheme", 0)
        colorSpinner.setSelection(savedColorScheme)
        viewerFft.setColorScheme(savedColorScheme)

        colorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                viewerFft.setColorScheme(pos)
                prefs.edit().putInt("color_scheme", pos).apply()
                triggerRefresh()
                styleColorSpinner(colorSpinner, pos)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        styleColorSpinner(colorSpinner, savedColorScheme)
    }

    private fun styleColorSpinner(spinner: Spinner, schemeIdx: Int) {
        spinner.post {
            val scheme = viewerFft.colorSchemes[schemeIdx.coerceIn(0, viewerFft.colorSchemes.size - 1)]
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
            btnSweep.setText(R.string.sweep_on)
            btnSweep.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED))
        } else {
            btnSweep.setText(R.string.sweep_off)
            btnSweep.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF69B4")))
        }
        UiUtils.autoScaleText(btnSweep)
    }

    private fun setupBlurSpinner() {
        val blurValues = (0..10).toList()
        val displayNames = blurValues.map { it.toString() }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_large, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        blurSpinner.adapter = adapter
        
        val savedBlur = prefs.getInt("blur_radius", 0).coerceIn(0, 10)
        blurSpinner.setSelection(savedBlur)
        viewerFft.setBlur(savedBlur)

        blurSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                viewerFft.setBlur(pos)
                prefs.edit().putInt("blur_radius", pos).apply()
                triggerRefresh()
                
                val selectedView = blurSpinner.selectedView as? TextView
                if (selectedView != null) {
                    selectedView.text = getString(R.string.label_blur_simple)
                    UiUtils.autoScaleText(selectedView)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        blurSpinner.post {
            val selectedView = blurSpinner.selectedView as? TextView
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_blur_simple)
                UiUtils.autoScaleText(selectedView)
            }
        }
    }

    private fun setupFftSpinners() {
        val sizeDisplayNames = fftValues.map { it.toString() }
        val sizeAdapter = ArrayAdapter(this, R.layout.spinner_item_large, sizeDisplayNames)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sizeSpinner.adapter = sizeAdapter
        val savedSizeIdx = prefs.getInt("fft_size_idx", 3).coerceIn(0, fftValues.size - 1)
        sizeSpinner.setSelection(savedSizeIdx, false)
        currentFftSize = fftValues[savedSizeIdx]

        updateStepSpinner(currentFftSize)

        sizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newSize = fftValues[pos]
                if (newSize != currentFftSize) {
                    currentFftSize = newSize
                    prefs.edit().putInt("fft_size_idx", pos).apply()
                    updateStepSpinner(currentFftSize)
                    triggerRefresh()
                }
                val selectedView = sizeSpinner.selectedView as? TextView
                if (selectedView != null) {
                    selectedView.text = getString(R.string.label_size_simple)
                    UiUtils.autoScaleText(selectedView)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        sizeSpinner.post {
            val selectedView = sizeSpinner.selectedView as? TextView
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_size_simple)
                UiUtils.autoScaleText(selectedView)
            }
        }
    }

    private fun updateStepSpinner(selectedSize: Int) {
        val maxAllowedStep = selectedSize / 2
        val validSteps = fftValues.filter { it <= maxAllowedStep }.toMutableList()
        if (validSteps.isEmpty()) validSteps.add(selectedSize / 2)
        
        val stepDisplayNames = validSteps.map { it.toString() }
        val stepAdapter = ArrayAdapter(this, R.layout.spinner_item_large, stepDisplayNames)
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        val oldStep = currentStepSize
        stepSpinner.adapter = stepAdapter
        
        val newSelection = validSteps.indexOf(oldStep).coerceAtLeast(0)
        stepSpinner.setSelection(newSelection, false)
        currentStepSize = validSteps[newSelection]

        stepSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                val newStep = validSteps[pos]
                if (newStep != currentStepSize) {
                    currentStepSize = newStep
                    prefs.edit().putInt("fft_step_idx", pos).apply()
                    triggerRefresh()
                }
                val selectedView = stepSpinner.selectedView as? TextView
                if (selectedView != null) {
                    selectedView.text = getString(R.string.label_step_simple)
                    UiUtils.autoScaleText(selectedView)
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        stepSpinner.post {
            val selectedView = stepSpinner.selectedView as? TextView
            if (selectedView != null) {
                selectedView.text = getString(R.string.label_step_simple)
                UiUtils.autoScaleText(selectedView)
            }
        }
    }

    private fun setupEqSliders() {
        val sliderIds = intArrayOf(R.id.vEq100, R.id.vEq300, R.id.vEq1k, R.id.vEq3k, R.id.vEq8k)
        val valueTxtIds = intArrayOf(R.id.txtEq100Value, R.id.txtEq300Value, R.id.txtEq1kValue, R.id.txtEq3kValue, R.id.txtEq8kValue)
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(sliderIds[i]) ?: continue
            val label = findViewById<TextView>(valueTxtIds[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloat(key, 0f)
            slider.setSafeValue(savedGain)
            label?.text = getString(R.string.db_value, savedGain.toInt())
            filters[i].gainDb = savedGain
            filters[i].updateCoefficients()

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                label?.text = getString(R.string.db_value, value.toInt())
                prefs.edit { putFloat(key, value) }
                updateLabelPosition(s, label)
                triggerRefresh()
            }
            adjustSliderThickness(slider, label)
        }
    }

    private fun triggerRefresh() {
        synchronized(refreshLock) {
            refreshCount++
            val myId = refreshCount
            activeThread?.interrupt()
            
            activeThread = thread {
                try {
                    if (myId == refreshCount) {
                        refreshFftInternal(myId)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun setupNoiseFilter() {
        val filter = findViewById<Slider>(R.id.vSliderNoiseFilter) ?: return
        val txtFilterValue = findViewById<TextView>(R.id.vTxtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.vTxtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.vTxtFallValue)

        noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
        filter.setSafeValue(noiseFilterStrength)
        txtFilterValue?.text = getString(R.string.percent_value, (noiseFilterStrength * 100).toInt())
        adjustSliderThickness(filter, txtFilterValue)
        
        filter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = getString(R.string.percent_value, (value * 100).toInt())
            prefs.edit().putFloat("noise_filter_strength", value).apply()
            triggerRefresh()
            updateLabelPosition(slider, txtFilterValue)
        }

        findViewById<Slider>(R.id.vSliderNoiseRise)?.let { sRise ->
            sRise.valueFrom = 0f
            sRise.valueTo = 3f
            sRise.stepSize = 0.01f
            val savedRiseMs = prefs.getFloat("noise_filter_rise_ms", 50f).coerceIn(1f, 1000f)
            sRise.setSafeValue(log10(savedRiseMs.toDouble()).toFloat())
            adjustSliderThickness(sRise, txtRiseValue)
            sRise.addOnChangeListener { slider, value, _ ->
                val ms = 10.0.pow(value.toDouble()).toFloat()
                prefs.edit { putFloat("noise_filter_rise_ms", ms) }
                updateCoeffsFromMs()
                updateLabelPosition(slider, txtRiseValue)
                triggerRefresh()
            }
        }

        findViewById<Slider>(R.id.vSliderNoiseFall)?.let { sFall ->
            sFall.valueFrom = 0f
            sFall.valueTo = 3f
            sFall.stepSize = 0.01f
            val savedFallMs = prefs.getFloat("noise_filter_fall_ms", 200f).coerceIn(1f, 1000f)
            sFall.setSafeValue(log10(savedFallMs.toDouble()).toFloat())
            adjustSliderThickness(sFall, txtFallValue)
            sFall.addOnChangeListener { slider, value, _ ->
                val ms = 10.0.pow(value.toDouble()).toFloat()
                prefs.edit { putFloat("noise_filter_fall_ms", ms) }
                updateCoeffsFromMs()
                updateLabelPosition(slider, txtFallValue)
                triggerRefresh()
            }
        }
        updateCoeffsFromMs()
    }

    private fun updateCoeffsFromMs() {
        val frameDurationMs = (currentStepSize.toFloat() / sampleRate) * 1000f
        val k = ln(2.0f) * frameDurationMs
        
        val sRise = findViewById<Slider>(R.id.vSliderNoiseRise)
        val sFall = findViewById<Slider>(R.id.vSliderNoiseFall)
        
        val riseMs = 10.0.pow(sRise?.value?.toDouble() ?: 1.7).toFloat()
        val fallMs = 10.0.pow(sFall?.value?.toDouble() ?: 2.3).toFloat()
        
        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)
        
        findViewById<TextView>(R.id.vTxtRiseValue)?.let {
            it.text = getString(R.string.ms_value, riseMs.toInt())
            UiUtils.autoScaleText(it)
        }
        findViewById<TextView>(R.id.vTxtFallValue)?.let {
            it.text = getString(R.string.ms_value, fallMs.toInt())
            UiUtils.autoScaleText(it)
        }
    }

    private fun loadAndDecode(file: File) {
        thread {
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
                    return@thread
                }

                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                
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

                rawPcmData = pcmList.toFloatArray()
                runOnUiThread {
                    viewerFft.setParams(currentFftSize, sampleRate.toFloat(), currentStepSize)
                    triggerRefresh()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshFftInternal(requestId: Int) {
        val data = rawPcmData ?: return
        val fftSize = currentFftSize
        val step = currentStepSize
        
        viewerFft.setParams(fftSize, sampleRate.toFloat(), step)
        val history = mutableListOf<FloatArray>()
        val hann = FloatArray(fftSize) { i -> (0.5f * (1 - cos(2 * PI * i / (fftSize - 1)))).toFloat() }
        
        val noiseFloor = FloatArray(fftSize / 2)
        
        var offset = 0
        while (offset + fftSize <= data.size) {
            if (requestId != refreshCount) return
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            for (i in 0 until fftSize) {
                var s = data[offset + i]
                for (f in filters) s = f.process(s)
                real[i] = s * hann[i]
            }
            
            FFTUtils.compute(real, imag)
            val magnitudes = FloatArray(fftSize / 2)
            for (i in 0 until fftSize / 2) {
                var mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                
                if (noiseFilterStrength > 0f) {
                    if (noiseFloor[i] == 0f) noiseFloor[i] = mag
                    else {
                        if (mag < noiseFloor[i]) noiseFloor[i] = noiseFloor[i] * (1f - noiseFallCoeff) + mag * noiseFallCoeff
                        else noiseFloor[i] = noiseFloor[i] * (1f - noiseRiseCoeff) + mag * noiseRiseCoeff
                    }
                    mag = (mag - noiseFloor[i] * noiseFilterStrength).coerceAtLeast(0f)
                }
                
                magnitudes[i] = (20 * log10(mag + 1e-9f) + 80) / 80f
            }
            history.add(magnitudes)
            offset += step
        }
        
        if (requestId == refreshCount) {
            if (isSweepActive) runFftSweepInternal(requestId)
            else runOnUiThread { viewerFft.setFullHistory(history) }
        }
    }

    private fun runFftSweepInternal(requestId: Int) {
        val data = rawPcmData ?: return
        val fftSize = currentFftSize
        val step = currentStepSize
        val hann = FloatArray(fftSize) { i -> (0.5f * (1 - cos(2 * PI * i / (fftSize - 1)))).toFloat() }
        
        var offset = 0
        while (offset + fftSize <= data.size) {
            if (requestId != refreshCount || !isSweepActive) break
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            for (i in 0 until fftSize) {
                var s = data[offset + i]
                for (f in filters) s = f.process(s)
                real[i] = s * hann[i]
            }
            FFTUtils.compute(real, imag)
            val magnitudes = FloatArray(fftSize / 2)
            for (i in 0 until fftSize / 2) {
                val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                magnitudes[i] = (20 * log10(mag + 1e-9f) + 80) / 80f
            }
            runOnUiThread { viewerFft.updateFFT(magnitudes) }
            offset += step
            Thread.sleep(10)
        }
    }

    private fun playAudio() {
        val pcm = rawPcmData ?: return
        thread {
            val filteredPcm = FloatArray(pcm.size)
            var offset = 0
            val fftSize = currentFftSize
            val step = currentStepSize
            val hann = FloatArray(fftSize) { i -> (0.5f * (1 - cos(2 * PI * i / (fftSize - 1)))).toFloat() }

            while (offset + fftSize <= pcm.size) {
                val real = FloatArray(fftSize)
                val imag = FloatArray(fftSize)
                for (i in 0 until fftSize) {
                    var s = pcm[offset + i]
                    for (f in filters) s = f.process(s)
                    real[i] = s * hann[i]
                }
                FFTUtils.compute(real, imag)
                
                FFTUtils.inverse(real, imag)
                for (i in 0 until fftSize) {
                    if (offset + i < filteredPcm.size) filteredPcm[offset + i] = real[i]
                }
                offset += step
            }

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

            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

            audioTrack?.write(audioData, 0, audioData.size)
            audioTrack?.play()
        }
    }

    private fun stopAudio() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
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
            
            slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.setHaloTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT))
            slider.thumbRadius = (thickness / 2f).toInt()
            slider.trackHeight = thickness.toInt()
            slider.haloRadius = 0
            
            label?.let {
                it.setBackgroundColor(Color.WHITE)
                it.setTextColor(Color.BLACK)
                it.elevation = 6f * density
                it.minWidth = (40f * density).toInt()
                it.gravity = android.view.Gravity.CENTER
                it.setPadding(0, (2f * density).toInt(), 0, 0)
                UiUtils.autoScaleText(it)
            }
            updateLabelPosition(slider, label)
        }
    }

    private fun updateLabelPosition(slider: Slider, label: TextView?) {
        if (label == null) return
        if (slider.isGone || label.isGone) return

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
        
        val barColor = if (slider.id == R.id.vSliderNoiseFilter || slider.id == R.id.vSliderNoiseRise || slider.id == R.id.vSliderNoiseFall) Color.CYAN else Color.GREEN
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

    private fun setupControls() {}
}
