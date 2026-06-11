package app.nogarbo.leflac.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SimpleFFTTest {

    @Test
    fun `test Sine Wave Peak Detection`() {
        val size = 512
        val sampleRate = 44100
        val freq = 1000.0 // 1kHz tone
        
        val pcm = FloatArray(size)
        for (i in 0 until size) {
            val t = i.toDouble() / sampleRate
            pcm[i] = sin(2.0 * PI * freq * t).toFloat()
        }
        
        val magnitudes = SimpleFFT.computeMagnitudes(pcm)
        
        // Find peak bin
        var maxBin = -1
        var maxVal = -1f
        for (i in magnitudes.indices) {
            if (magnitudes[i] > maxVal) {
                maxVal = magnitudes[i]
                maxBin = i
            }
        }
        
        // Expected Bin:
        // Freq = Bin * SampleRate / N
        // Bin = Freq * N / SampleRate
        val expectedBin = (freq * size / sampleRate).toInt()
        
        // Hanning window spreads energy, so verify we are close (within 1-2 bins)
        val diff = kotlin.math.abs(maxBin - expectedBin)
        
        println("Peak Bin: $maxBin (Val: $maxVal). Expected: $expectedBin")
        
        assert(diff <= 2) { "Peak frequency bin should be close to expected. Got $maxBin, Expected $expectedBin" }
    }
}
