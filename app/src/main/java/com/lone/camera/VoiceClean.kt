package com.lone.camera

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Voice clean-up for video export: 90 Hz high-pass (wind / handling rumble), 7.5 kHz low-pass
 * (hiss), and an adaptive noise gate that learns the background noise floor and ducks it
 * between words. This is classic DSP, not a neural denoiser.
 */
@UnstableApi
class VoiceCleanProcessor : BaseAudioProcessor() {

    private var ch = 2
    private lateinit var xPrev: FloatArray
    private lateinit var yPrev: FloatArray
    private lateinit var lp: FloatArray
    private lateinit var env: FloatArray
    private lateinit var floor: FloatArray
    private lateinit var gain: FloatArray
    private var hpA = 0f
    private var lpB = 0f
    private var atk = 0f
    private var rel = 0f
    private var gAtk = 0f
    private var gRel = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        ch = inputAudioFormat.channelCount
        val sr = inputAudioFormat.sampleRate.toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        val rc = 1f / (twoPi * 90f)
        hpA = rc / (rc + 1f / sr)
        lpB = 1f - exp(-twoPi * 7500f / sr)
        atk = 1f - exp(-1f / (0.005f * sr))
        rel = 1f - exp(-1f / (0.12f * sr))
        gAtk = 1f - exp(-1f / (0.004f * sr))
        gRel = 1f - exp(-1f / (0.18f * sr))
        xPrev = FloatArray(ch); yPrev = FloatArray(ch); lp = FloatArray(ch)
        env = FloatArray(ch); floor = FloatArray(ch) { 0.01f }; gain = FloatArray(ch) { 1f }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        val frames = size / (2 * ch)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val x = inputBuffer.short / 32768f
                val y = hpA * (yPrev[c] + x - xPrev[c])
                xPrev[c] = x; yPrev[c] = y
                lp[c] += lpB * (y - lp[c])
                val v = lp[c]
                val a = abs(v)
                env[c] += (a - env[c]) * (if (a > env[c]) atk else rel)
                val e = env[c]
                // Floor falls fast to quiet gaps, rises very slowly so speech never becomes "noise".
                floor[c] += (e - floor[c]) * (if (e < floor[c]) 0.01f else 0.00002f)
                if (floor[c] < 0.0003f) floor[c] = 0.0003f
                val thr = floor[c] * 2.4f
                val target = when {
                    e >= thr * 2f -> 1f
                    e <= thr -> 0.1f
                    else -> 0.1f + 0.9f * (e - thr) / thr
                }
                gain[c] += (target - gain[c]) * (if (target > gain[c]) gAtk else gRel)
                val s = (v * gain[c]).coerceIn(-1f, 1f)
                out.putShort((s * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }
}
