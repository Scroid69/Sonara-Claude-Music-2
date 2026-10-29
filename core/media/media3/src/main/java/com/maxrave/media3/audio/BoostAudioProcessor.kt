package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.sonara.BiquadCoeffs
import com.maxrave.domain.sonara.BiquadState
import com.maxrave.domain.sonara.BoostMath
import com.maxrave.domain.sonara.BoostSettings
import com.maxrave.domain.sonara.SoftLimiter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bass boost and volume boost, as one stage: low shelf, then gain, then a soft limiter.
 *
 * The maths lives in `com.maxrave.domain.sonara.BoostDsp` (pure Kotlin, unit-tested on the JVM); this
 * class only moves PCM16 samples through it. Same shape as [EqualizerAudioProcessor]: settings are read
 * on every buffer so a change is audible immediately, one instance per player, PCM16 only, and the
 * whole thing is a straight copy while both boosts are 0.
 *
 * Gain changes are ramped across each buffer (linearly from the previous gain to the new one) so
 * dragging a slider produces no zipper noise. Filter state is kept when the shelf is re-tuned, so
 * that does not click either.
 */
@UnstableApi
class BoostAudioProcessor(
    private val settings: () -> BoostSettings,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    private var states: Array<BiquadState> = emptyArray()
    private var appliedSettings: BoostSettings? = null
    private var coeffs: BiquadCoeffs? = null
    private var previousGain = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        states = Array(channelCount) { BiquadState() }
        appliedSettings = null
        coeffs = null
        previousGain = 1.0
        return inputAudioFormat
    }

    override fun onFlush() {
        states.forEach { it.reset() }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val s = settings()

        if (s.isOff && previousGain == 1.0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (s !== appliedSettings) {
            coeffs = if (s.bassDb > 0f) BiquadCoeffs.lowShelf(sampleRate, BoostSettings.BASS_CORNER_HZ, s.bassDb.toDouble()) else null
            appliedSettings = s
        }
        val shelf = coeffs
        val targetGain = BoostMath.dbToGain(s.volumeDb.toDouble() + BoostMath.bassHeadroomDb(s.bassDb))
        val frames = remaining / (2 * channelCount)
        val step = if (frames > 0) (targetGain - previousGain) / frames else 0.0
        var gain = previousGain

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        repeat(frames) {
            gain += step
            for (ch in 0 until channelCount) {
                var x = inputBuffer.short / 32768.0
                if (shelf != null) x = states[ch].process(shelf, x)
                x = SoftLimiter.process(x * gain)
                output.putShort((x * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        previousGain = targetGain
        // Contract: consume the whole input even for a ragged tail.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }
}
