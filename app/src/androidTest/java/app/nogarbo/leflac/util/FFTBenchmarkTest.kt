package app.nogarbo.leflac.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Test
import java.util.Random

@RunWith(AndroidJUnit4::class)
class FFTBenchmarkTest {

    @Test
    fun benchmarkFFT() {
        val n = 1024
        val iterations = 50000 // Heavy load
        val input = FloatArray(n)
        val stats = FloatArray(n)
        val rng = Random()
        
        // Fill input
        // Fill input with Sine wave (Freq 10 bins)
        for (i in 0 until n) {
            // Freq 10 means 10 cycles per window
            input[i] = kotlin.math.sin(2.0 * kotlin.math.PI * 10.0 * i / n).toFloat()
        }
        
        // --- 1. Warmup ---
        val warmupR = FloatArray(n)
        val warmupI = FloatArray(n)
        val warmupM = FloatArray(n/2)
        val warmupC = FloatArray(2*n) // FastFFT buffer
        
        repeat(1000) {
            SimpleFFT.computeMagnitudes(input, warmupR, warmupI, warmupM)
            FastFFT.computeMagnitudes(input, warmupC, warmupM)
        }
        
        // --- 2. SimpleFFT Benchmark ---
        val simpleR = FloatArray(n)
        val simpleI = FloatArray(n)
        val simpleM = FloatArray(n/2)
        
        val startSimple = System.nanoTime()
        for (i in 0 until iterations) {
            // Re-fill input slightly to avoid branch prediction cheating? (Optional)
            SimpleFFT.computeMagnitudes(input, simpleR, simpleI, simpleM)
        }
        val endSimple = System.nanoTime()
        val durationSimpleMs = (endSimple - startSimple) / 1_000_000.0
        
        
        // --- 3. FastFFT Benchmark ---
        val fastC = FloatArray(2*n) // JTransforms often safer with 2*n for realForward? 
        // Docs say n is enough for realForward, but let's give it scratch space if needed.
        // Our wrapper uses 'complexBuffer' which we pass.
        // In FastFFT.kt we execute `cachedFFT?.realForward(complexBuffer)`.
        
        val fastM = FloatArray(n/2)
        
        val startFast = System.nanoTime()
        for (i in 0 until iterations) {
            FastFFT.computeMagnitudes(input, fastC, fastM)
        }
        val endFast = System.nanoTime()
        val durationFastMs = (endFast - startFast) / 1_000_000.0
        
        // --- Report ---
        println("========== FFT BENCHMARK ($iterations iterations, N=$n) ==========")
        println("SimpleFFT: ${String.format("%.2f", durationSimpleMs)} ms")
        println("FastFFT  : ${String.format("%.2f", durationFastMs)} ms")
        val speedup = durationSimpleMs / durationFastMs
        println("Speedup  : ${String.format("%.2fx", speedup)}")
        println("==============================================================")
        
        // Verify Correctness (roughly)
        SimpleFFT.computeMagnitudes(input, simpleR, simpleI, simpleM)
        FastFFT.computeMagnitudes(input, fastC, fastM)
        
        var errorSum = 0f
        for(i in 0 until n/2) {
            errorSum += kotlin.math.abs(simpleM[i] - fastM[i])
        }
        println("Total Diff Error: $errorSum (avg ${errorSum/(n/2)})")
        
        // Find Peaks
        var maxSimple = 0f
        var maxSimpleIdx = -1
        var maxFast = 0f
        var maxFastIdx = -1
        
        for(i in 0 until n/2) {
            if(simpleM[i] > maxSimple) { maxSimple = simpleM[i]; maxSimpleIdx = i }
            if(fastM[i] > maxFast) { maxFast = fastM[i]; maxFastIdx = i }
        }
        
        println("PEAK Check (Expected ~10):")
        println("Simple: Bin $maxSimpleIdx, Mag $maxSimple")
        println("Fast  : Bin $maxFastIdx, Mag $maxFast")
        
        // Assert speedup
        assert(speedup > 1.0) { "FastFFT was not faster!" }
    }
}
