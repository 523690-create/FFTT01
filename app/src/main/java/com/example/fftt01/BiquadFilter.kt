package com.example.fftt01

import kotlin.math.*

class BiquadFilter(val type: Type, var sampleRate: Float, var frequency: Float, var q: Float, var gainDb: Float) {
    enum class Type { PEAKING }

    private var a0 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        updateCoefficients()
    }

    fun updateCoefficients() {
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val omega = (2.0 * PI * frequency / sampleRate).toFloat()
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / (2.0f * q)

        when (type) {
            Type.PEAKING -> {
                b0 = 1.0f + alpha * a
                b1 = -2.0f * cs
                b2 = 1.0f - alpha * a
                a0 = 1.0f + alpha / a
                a1 = -2.0f * cs
                a2 = 1.0f - alpha / a
            }
        }

        // Normalize
        if (abs(a0) > 1e-9f) {
            b0 /= a0
            b1 /= a0
            b2 /= a0
            a1 /= a0
            a2 /= a0
        }
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    fun process(input: Float): Float {
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }
}
