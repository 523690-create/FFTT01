package com.example.fftt01

import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.*

class LatencyActivity : AppCompatActivity() {

    private lateinit var btnMeasure: Button
    private lateinit var txtResult: TextView
    private val sampleRate = 44100
    private val chirpDurationMs = 100
    private val bufferSize = 44100 // 1 second buffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_latency)

        btnMeasure = findViewById(R.id.btnMeasureLatency)
        txtResult = findViewById(R.id.txtLatencyResult)
        findViewById<Button>(R.id.btnLatencyBack).setOnClickListener { finish() }

        btnMeasure.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            } else {
                runLatencyTest()
            }
        }
        
        UiUtils.autoScaleText(btnMeasure)
        UiUtils.autoScaleText(findViewById(R.id.btnLatencyBack))
        UiUtils.autoScaleText(txtResult)
        UiUtils.autoScaleText(findViewById(R.id.txtTitleLatency))
    }

    private fun runLatencyTest() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        btnMeasure.isEnabled = false
        txtResult.text = getString(R.string.latency_measuring)
        UiUtils.autoScaleText(txtResult)

        Thread {
            val chirp = generateChirp()
            
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            // On API 22, we must use PCM_16BIT
            val useFloat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            val encoding = if (useFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val audioTrack = AudioTrack(
                attributes,
                format,
                chirp.size * (if (useFloat) 4 else 2),
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                encoding,
                max(minBufferSize, bufferSize * (if (useFloat) 4 else 2))
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runOnUiThread {
                    txtResult.text = getString(R.string.latency_error)
                    UiUtils.autoScaleText(txtResult)
                    btnMeasure.isEnabled = true
                }
                return@Thread
            }

            val recordedData = FloatArray(bufferSize)
            
            if (useFloat && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)
            } else {
                val shortChirp = ShortArray(chirp.size) { i -> (chirp[i] * 32767).toInt().toShort().coerceIn(-32768, 32767).toShort() }
                audioTrack.write(shortChirp, 0, shortChirp.size)
            }
            
            record.startRecording()
            audioTrack.play()

            if (useFloat && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var totalRead = 0
                while (totalRead < bufferSize) {
                    val read = record.read(recordedData, totalRead, bufferSize - totalRead, AudioRecord.READ_BLOCKING)
                    if (read > 0) totalRead += read
                    else break
                }
            } else {
                val shortBuffer = ShortArray(bufferSize)
                var totalRead = 0
                while (totalRead < bufferSize) {
                    val read = record.read(shortBuffer, totalRead, bufferSize - totalRead)
                    if (read > 0) {
                        for (i in 0 until read) {
                            recordedData[totalRead + i] = shortBuffer[totalRead + i] / 32768f
                        }
                        totalRead += read
                    } else break
                }
            }

            audioTrack.stop()
            record.stop()
            audioTrack.release()
            record.release()

            val latencyMs = calculateLatency(chirp, recordedData)
            
            runOnUiThread {
                if (latencyMs >= 0) {
                    txtResult.text = getString(R.string.latency_result, latencyMs)
                } else {
                    txtResult.text = getString(R.string.latency_error)
                }
                UiUtils.autoScaleText(txtResult)
                btnMeasure.isEnabled = true
            }
        }.start()
    }

    private fun generateChirp(): FloatArray {
        val numSamples = (sampleRate * chirpDurationMs / 1000f).toInt()
        val chirp = FloatArray(numSamples)
        val f0 = 1000f
        val f1 = 8000f
        val t1 = chirpDurationMs / 1000f
        
        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val k = (f1 / f0).pow(1f / t1)
            val phase = 2f * PI.toFloat() * f0 * (k.pow(t) - 1f) / ln(k)
            val window = 0.54f - 0.46f * cos(2f * PI.toFloat() * i / (numSamples - 1))
            chirp[i] = sin(phase) * window
        }
        return chirp
    }

    private fun calculateLatency(chirp: FloatArray, recorded: FloatArray): Float {
        var maxCorr = -1f
        var maxLag = -1
        val searchLimit = min(recorded.size - chirp.size, sampleRate / 2)
        for (lag in 0 until searchLimit) {
            var corr = 0f
            for (i in chirp.indices) {
                corr += chirp[i] * recorded[lag + i]
            }
            if (corr > maxCorr) {
                maxCorr = corr
                maxLag = lag
            }
        }
        return if (maxLag >= 0) (maxLag.toFloat() / sampleRate) * 1000f else -1f
    }
}
