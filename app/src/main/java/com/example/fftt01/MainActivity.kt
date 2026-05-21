package com.example.fftt01

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.graphics.Color
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.view.isGone
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var fftHeatMap: FFTHeatMapView
    private lateinit var cropOverlay: CropOverlayView
    
    // Nullable controls for orientation safety
    private var micSpinner: Spinner? = null
    private var colorSpinner: Spinner? = null
    private var btnSaveInRow: Button? = null
    private var btnEscInRow: Button? = null
    private var saveEscContainer: View? = null
    private var mainButtonsLayout: View? = null
    private var micAndColorLayout: View? = null
    private var eqSlidersLayout: View? = null
    private var filterControlsLayout: View? = null
    private var sliderNoiseFilter: Slider? = null
    private var sliderNoiseRise: Slider? = null
    private var sliderNoiseFall: Slider? = null
    private var btnLatency: Button? = null

    private var noiseRiseCoeff = 0.015f
    private var noiseFallCoeff = 0.05f
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val sampleRate = 44100
    private val fftSize = 2048
    private val stepSize = 1024
    
    private val eqBands = floatArrayOf(100f, 300f, 1000f, 3000f, 8000f)
    private val filters = Array(5) { i ->
        BiquadFilter(BiquadFilter.Type.PEAKING, sampleRate.toFloat(), eqBands[i], 1.0f, 0f)
    }

    private var noiseFloor = FloatArray(fftSize / 2)
    private var noiseFilterStrength = 0f

    private var selectedDevice: AudioDeviceInfo? = null
    private var availableDevices = mutableListOf<AudioDeviceInfo>()
    private var isCalibrating = false
    @Volatile private var latestFrameEnergy = 0f

    private val audioBufferSize = sampleRate * 3
    private val audioCircularBuffer = FloatArray(audioBufferSize)
    @Volatile private var audioWriteIndex = 0

    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5f * (1 - cos(2 * PI * i.toDouble() / (fftSize - 1)))).toFloat()
    }
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        // Core Views (must exist)
        fftHeatMap = findViewById(R.id.fftHeatMap)
        cropOverlay = findViewById(R.id.cropOverlay)

        // Nullable Controls (Safe lookups)
        micSpinner = findViewById(R.id.micSpinner)
        colorSpinner = findViewById(R.id.colorSpinner)
        btnSaveInRow = findViewById(R.id.btnSaveInRow)
        btnEscInRow = findViewById(R.id.btnEscInRow)
        saveEscContainer = findViewById(R.id.saveEscContainer)
        eqSlidersLayout = findViewById(R.id.eqSlidersLayout)
        filterControlsLayout = findViewById(R.id.filterControlsLayout)
        btnLatency = findViewById(R.id.btnLatency)
        btnLatency?.isAllCaps = false
        
        mainButtonsLayout = findViewById(R.id.mainButtonsLayout)
        micAndColorLayout = findViewById(R.id.micAndColorLayout)
        sliderNoiseFilter = findViewById(R.id.sliderNoiseFilter)
        sliderNoiseRise = findViewById(R.id.sliderNoiseRise)
        sliderNoiseFall = findViewById(R.id.sliderNoiseFall)

        btnLatency?.setOnClickListener { runLatencyMeasurement() }

        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        findViewById<Button>(R.id.btnQuitTop).setOnClickListener {
            showQuitSanityCheck()
        }

        btnSaveInRow?.setOnClickListener { saveFragment() }
        btnEscInRow?.setOnClickListener {
            hideFrozenUi()
            fftHeatMap.isFrozen = false
            startRecording()
        }

        val historySize = (3 * sampleRate) / stepSize
        fftHeatMap.setMaxHistory(historySize)
        fftHeatMap.setParams(fftSize, sampleRate.toFloat(), stepSize)
        
        setupEqSliders()
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            setupNoiseFilter()
            setupColorSpinner()
        } else {
            noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
            val savedColorScheme = prefs.getInt("color_scheme", 0)
            fftHeatMap.setColorScheme(savedColorScheme)
        }

        findViewById<android.view.ViewGroup>(android.R.id.content).post {
            updateAllLabelPositions()
        }

        fftHeatMap.onSingleTap = {
            fftHeatMap.isFrozen = !fftHeatMap.isFrozen
            if (fftHeatMap.isFrozen) {
                stopRecording()
                showFrozenUi()
            } else {
                hideFrozenUi()
                startRecording()
            }
        }

        fftHeatMap.onDoubleTap = {
            fftHeatMap.isFrozen = false
            hideFrozenUi()
            startRecording()
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        } else {
            initAudio()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initAudio()
        }
    }

    private fun initAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            availableDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toMutableList()
            setupMicSpinnerWithDevices(availableDevices)

            selectedDevice?.let { dev ->
                val index = availableDevices.indexOf(dev)
                if (index >= 0) {
                    micSpinner?.setSelection(index)
                }
            }
        } else {
            micSpinner?.visibility = View.GONE
        }

        startRecording()
    }

    private fun showFrozenUi() {
        saveEscContainer?.visibility = View.VISIBLE
        micAndColorLayout?.let { if (it.id != -1) it.visibility = View.GONE }
        mainButtonsLayout?.let { if (it.id != -1) it.visibility = View.GONE }
        cropOverlay.visibility = View.VISIBLE
    }

    private fun hideFrozenUi() {
        saveEscContainer?.visibility = View.GONE
        micAndColorLayout?.let { if (it.id != -1) it.visibility = View.VISIBLE }
        mainButtonsLayout?.let { if (it.id != -1) it.visibility = View.VISIBLE }
        cropOverlay.visibility = View.GONE
    }

    private fun showQuitSanityCheck() {
        AlertDialog.Builder(this)
            .setTitle("Quit")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun saveFragment() {
        val rect = cropOverlay.cropRect
        val w = cropOverlay.width.toFloat()
        
        val startFrac = rect.left / w
        val endFrac = rect.right / w
        
        val totalSamples = audioBufferSize
        val fragmentStart = (startFrac * totalSamples).toInt()
        val fragmentEnd = (endFrac * totalSamples).toInt()
        val fragmentSize = fragmentEnd - fragmentStart
        
        if (fragmentSize <= 0) return

        val fragment = FloatArray(fragmentSize)
        val frozenWriteIndex = audioWriteIndex
        for (i in 0 until fragmentSize) {
            val idx = (frozenWriteIndex - totalSamples + fragmentStart + i + totalSamples * 10) % totalSamples
            fragment[i] = audioCircularBuffer[idx]
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "$timestamp.flac"
        val file = File(getExternalFilesDir(null), fileName)
        
        encodeFlac(file, fragment)
        fftHeatMap.saveIcon(rect, "$timestamp.png")
        
        hideFrozenUi()
        fftHeatMap.isFrozen = false
        startRecording()
    }

    private fun encodeFlac(file: File, data: FloatArray) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val fos = FileOutputStream(file)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleIndex = 0

        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    inputBuffer.clear()
                    
                    val samplesToCopy = min(data.size - sampleIndex, inputBuffer.remaining() / 2)
                    for (unused in 0 until samplesToCopy) {
                        val s = (data[sampleIndex++] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                        inputBuffer.putShort(s)
                    }

                    if (sampleIndex >= data.size) {
                        codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), 0, 0)
                    }
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(info, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                val outData = ByteArray(info.size)
                outputBuffer.get(outData, 0, info.size)
                fos.write(outData)
                codec.releaseOutputBuffer(outputBufferIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        fos.close()
    }

    private fun setupColorSpinner() {
        val spinner = colorSpinner ?: return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return

        val colorNames = arrayOf("Default", "Viridis", "Magma", "Gray")
        val displayNames = colorNames.map { "Color:$it" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_gold, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val savedColorScheme = prefs.getInt("color_scheme", 0)
        spinner.setSelection(savedColorScheme)
        fftHeatMap.setColorScheme(savedColorScheme)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fftHeatMap.setColorScheme(position)
                prefs.edit { putInt("color_scheme", position) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMicSpinnerWithDevices(devices: List<AudioDeviceInfo>) {
        val spinner = micSpinner ?: return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return

        val deviceNames = devices.map { 
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.productName.toString() + " (" + getDeviceTypeName(it.type) + ")" 
            } else {
                "Microphone"
            }
        }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_orange, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isCalibrating) return
                val newDevice = devices[position]
                if (newDevice != selectedDevice) {
                    selectedDevice = newDevice
                    restartRecording()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            else -> "Other"
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

    private fun setupEqSliders() {
        val sliderIds = intArrayOf(R.id.eq100, R.id.eq300, R.id.eq1k, R.id.eq3k, R.id.eq8k)
        val valueTxtIds = intArrayOf(R.id.txtEqValue100, R.id.txtEqValue300, R.id.txtEqValue1k, R.id.txtEqValue3k, R.id.txtEqValue8k)
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(sliderIds[i]) ?: continue
            val valueTxt = findViewById<TextView>(valueTxtIds[i])
            val key = "eq_gain_$i"
            val savedGain = prefs.getFloat(key, 0f)
            slider.setSafeValue(savedGain)
            valueTxt?.text = getString(R.string.db_value, savedGain.toInt())
            filters[i].gainDb = savedGain
            filters[i].updateCoefficients()

            slider.addOnChangeListener { s, value, _ ->
                filters[i].gainDb = value
                filters[i].updateCoefficients()
                valueTxt?.text = getString(R.string.db_value, value.toInt())
                prefs.edit { putFloat(key, value) }
                updateLabelPosition(s, valueTxt)
            }
        }
    }

    private fun setupNoiseFilter() {
        val filter = sliderNoiseFilter ?: return
        val txtFilterValue = findViewById<TextView>(R.id.txtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.txtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.txtFallValue)

        noiseFilterStrength = prefs.getFloat("noise_filter_strength", 0f)
        filter.setSafeValue(noiseFilterStrength)
        txtFilterValue?.text = getString(R.string.percent_value, (noiseFilterStrength * 100).toInt())
        adjustSliderThickness(filter, txtFilterValue)
        filter.addOnChangeListener { slider, value, _ ->
            noiseFilterStrength = value
            txtFilterValue?.text = getString(R.string.percent_value, (value * 100).toInt())
            prefs.edit().putFloat("noise_filter_strength", value).apply()
            if (value == 0f) {
                noiseFloor.fill(0f)
            }
            updateLabelPosition(slider, txtFilterValue)
        }

        sliderNoiseRise?.let { sRise ->
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
            }
        }

        sliderNoiseFall?.let { sFall ->
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
            }
        }

        updateCoeffsFromMs()
    }

    private fun updateCoeffsFromMs() {
        val frameDurationMs = (stepSize.toFloat() / sampleRate) * 1000f
        val k = ln(2.0f) * frameDurationMs
        
        val riseMs = 10.0.pow(sliderNoiseRise?.value?.toDouble() ?: 1.7).toFloat()
        val fallMs = 10.0.pow(sliderNoiseFall?.value?.toDouble() ?: 2.3).toFloat()
        
        noiseRiseCoeff = (k / riseMs).coerceIn(0.001f, 1.0f)
        noiseFallCoeff = (k / fallMs).coerceIn(0.001f, 1.0f)
        
        findViewById<TextView>(R.id.txtRiseValue)?.text = getString(R.string.ms_value, riseMs.toInt())
        findViewById<TextView>(R.id.txtFallValue)?.text = getString(R.string.ms_value, fallMs.toInt())
    }

    private fun updateAllLabelPositions() {
        val txtFilterValue = findViewById<TextView>(R.id.txtFilterValue)
        val txtRiseValue = findViewById<TextView>(R.id.txtRiseValue)
        val txtFallValue = findViewById<TextView>(R.id.txtFallValue)
        
        sliderNoiseFilter?.let { adjustSliderThickness(it, txtFilterValue) }
        sliderNoiseRise?.let { adjustSliderThickness(it, txtRiseValue) }
        sliderNoiseFall?.let { adjustSliderThickness(it, txtFallValue) }
        
        val sliderIds = intArrayOf(R.id.eq100, R.id.eq300, R.id.eq1k, R.id.eq3k, R.id.eq8k)
        val valueTxtIds = intArrayOf(R.id.txtEqValue100, R.id.txtEqValue300, R.id.txtEqValue1k, R.id.txtEqValue3k, R.id.txtEqValue8k)
        for (i in 0 until 5) {
            val slider = findViewById<Slider>(sliderIds[i]) ?: continue
            val label = findViewById<TextView>(valueTxtIds[i]) ?: continue
            adjustSliderThickness(slider, label)
        }
    }

    private fun adjustSliderThickness(slider: Slider, label: TextView?) {
        slider.post {
            val density = resources.displayMetrics.density
            val parent = slider.parent as? android.view.View ?: return@post
            val availableWidth = parent.width.toFloat()
            if (availableWidth <= 0) return@post

            val gutterPx = 4f * density
            val maxThickness = resources.getDimension(R.dimen.slider_track_height) * 2.5f
            val thickness = (availableWidth - 2 * gutterPx).coerceAtMost(maxThickness)
            
            // Set slider to be invisible but interactive
            slider.trackActiveTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            slider.trackInactiveTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            slider.thumbTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            slider.haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            slider.thumbRadius = (thickness / 2f).toInt()
            slider.trackHeight = thickness.toInt()
            slider.haloRadius = 0
            
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

        val barColor = if (slider.id == R.id.sliderNoiseFilter || slider.id == R.id.sliderNoiseRise || slider.id == R.id.sliderNoiseFall) {
            Color.CYAN
        } else {
            Color.GREEN
        }
        label.setBackgroundColor(barColor)
        label.setTextColor(Color.BLACK)
        label.elevation = 6f * density
    }

    private fun startRecording() {
        if (recording.get()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (selectedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audioManager.setCommunicationDevice(selectedDevice!!)
            }
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = max(minBufferSize, fftSize * 4)
        
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize
            )
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            selectedDevice?.let { record.setPreferredDevice(it) }
        }
        
        audioRecord = record
        recording.set(true)
        try {
            record.startRecording()
        } catch (e: Exception) {
            recording.set(false)
            return
        }

        recordingThread = Thread {
            val audioBuffer = FloatArray(stepSize)
            val fftInput = FloatArray(fftSize)
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            val shortBuffer = ShortArray(stepSize)

            while (recording.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    record.read(audioBuffer, 0, stepSize, AudioRecord.READ_BLOCKING)
                } else {
                    // Fallback for API 21-22: Read as short and convert
                    val sRead = record.read(shortBuffer, 0, stepSize)
                    if (sRead > 0) {
                        for (i in 0 until sRead) {
                            audioBuffer[i] = shortBuffer[i] / 32768f
                        }
                    }
                    sRead
                }

                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        var s = audioBuffer[i]
                        for (f in filters) s = f.process(s)
                        audioBuffer[i] = s
                    }

                    for (i in 0 until readCount) {
                        audioCircularBuffer[audioWriteIndex] = audioBuffer[i]
                        audioWriteIndex = (audioWriteIndex + 1) % audioBufferSize
                    }

                    System.arraycopy(fftInput, readCount, fftInput, 0, fftSize - readCount)
                    System.arraycopy(audioBuffer, 0, fftInput, fftSize - readCount, readCount)

                    for (i in 0 until fftSize) {
                        real[i] = fftInput[i] * hannWindow[i]
                        imag[i] = 0f
                    }

                    FFTUtils.compute(real, imag)

                    val magnitudes = FloatArray(fftSize / 2)
                    var energy = 0f
                    for (i in 0 until fftSize / 2) {
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

                        energy += mag
                        magnitudes[i] = (20 * log10(mag + 1e-9f) + 80) / 80f
                    }
                    latestFrameEnergy = energy
                    runOnUiThread { fftHeatMap.updateFFT(magnitudes) }
                }
            }
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        recording.set(false)
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            if (audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audioManager.clearCommunicationDevice()
            }
        }
    }

    private fun restartRecording() {
        stopRecording()
        startRecording()
    }

    private fun runLatencyMeasurement() {
        val chirpDurationMs = 100
        val numSamples = (sampleRate * chirpDurationMs / 1000f).toInt()
        val chirp = FloatArray(numSamples)
        val f0 = 1000f
        val f1 = 8000f
        val t1 = chirpDurationMs / 1000f
        
        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val k = (f1 / f0).pow(1f / t1)
            val phase = 2f * PI.toFloat() * f0 * (k.pow(t) - 1f) / ln(k)
            chirp[i] = sin(phase) * (0.5f * (1f - cos(2f * PI.toFloat() * i / (numSamples - 1))))
        }

        Thread {
            var player: AudioTrack? = null
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                
                player = AudioTrack(
                    attributes,
                    format,
                    chirp.size * 4,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                player.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)
                val captureSize = sampleRate
                val captured = FloatArray(captureSize)
                val startWriteIndex = audioWriteIndex
                
                player.play()
                Thread.sleep(1200)
                
                for (i in 0 until captureSize) {
                    val idx = (startWriteIndex + i) % audioBufferSize
                    captured[i] = audioCircularBuffer[idx]
                }
                player.stop()

                var maxCorr = -1f
                var maxLag = 0
                for (lag in 0 until captureSize - chirp.size) {
                    var corr = 0f
                    for (j in 0 until chirp.size) {
                        corr += captured[lag + j] * chirp[j]
                    }
                    if (corr > maxCorr) {
                        maxCorr = corr
                        maxLag = lag
                    }
                }
                val latencyMs = (maxLag.toFloat() / sampleRate * 1000).toInt()
                runOnUiThread { btnLatency?.text = getString(R.string.latency_display_prefix, latencyMs) }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                player?.release()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }
}
