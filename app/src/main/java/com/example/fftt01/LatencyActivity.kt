package com.example.fftt01

import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
    }

    private fun runLatencyTest() {
        btnMeasure.isEnabled = false
        txtResult.text = "Measuring..."

        Thread {
            try {
                val chirp = generateChirp()
                val recorded = FloatArray(bufferSize)
                
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(bufferSize * 4)
                )

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val player = AudioTrack(
                    attributes,
                    format,
                    chirp.size * 4,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                player.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)

                recorder.startRecording()
                player.play()
                
                var totalRead = 0
                while (totalRead < bufferSize) {
                    val read = recorder.read(recorded, totalRead, bufferSize - totalRead, AudioRecord.READ_BLOCKING)
                    if (read > 0) totalRead += read else break
                }

                recorder.stop()
                recorder.release()
                player.stop()
                player.release()

                val latencyMs = calculateLatency(chirp, recorded)
                runOnUiThread {
                    txtResult.text = "Result: ${String.format("%.2f", latencyMs)} ms"
                    btnMeasure.isEnabled = true
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    btnMeasure.isEnabled = true
                    txtResult.text = "Result: Error"
                }
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
            // Exponential chirp
            val k = (f1 / f0).pow(1f / t1)
            val phase = 2f * PI.toFloat() * f0 * (k.pow(t) - 1f) / ln(k)
            chirp[i] = sin(phase) * (0.5f * (1f - cos(2f * PI.toFloat() * i / (numSamples - 1)))) // Hann windowed chirp
        }
        return chirp
    }

    private fun calculateLatency(chirp: FloatArray, recorded: FloatArray): Float {
        // Cross-correlation
        val n = recorded.size
        val m = chirp.size
        var maxCorr = -1f
        var maxLag = 0

        // Coarse search to save time/CPU
        for (lag in 0 until n - m) {
            var corr = 0f
            for (i in 0 until m) {
                corr += recorded[lag + i] * chirp[i]
            }
            if (corr > maxCorr) {
                maxCorr = corr
                maxLag = lag
            }
        }

        return (maxLag.toFloat() / sampleRate) * 1000f
    }
}
