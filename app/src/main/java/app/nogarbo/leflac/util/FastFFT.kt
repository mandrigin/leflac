package app.nogarbo.leflac.util

import kotlin.math.PI
import kotlin.math.cos

object FastFFT {
    
    init {
        try {
            System.loadLibrary("flacplayer")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }
    
    private external fun computeKissFFT(n: Int, input: FloatArray, complexBuffer: FloatArray, outMagnitudes: FloatArray)
    
    // Hanning Window Cache
    private val windowCache = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    
    fun computeMagnitudes(
        input: FloatArray,      // Size N (Real PCM)
        complexBuffer: FloatArray, // Size 2*N (Reusable buffer)
        outMagnitudes: FloatArray // Size N/2
    ) {
        val n = input.size
        
        // Windowing (Apply in Java, pass to C)
        val window = getHanningWindow(n)
        for (i in 0 until n) {
            // We apply window to input in-place? No, 'input' is from Analyzer accumulator.
            // We can write to 'complexBuffer' as temp.
            // C++ expects 'complexBuffer' to be the input complex array?
            // My JNI implementation reads 'input' array directly and ignores 'complexBuffer' mostly (just casts it).
            // WAIT. My JNI code:
            // copies 'input' -> 'cx_in' (which is mapped to 'complexBuffer').
            // So we just need to pass the windowed data in 'input'?
            // 'input' in Analyzer is 'bufferAccumulator'. We shouldn't modify it if it's used elsewhere?
            // Actually 'bufferAccumulator' is ephemeral.
            
            // Let's modify bufferAccumulator? No, safer to write windowed data to complexBuffer[0..n].
            // But my JNI implementation reads 'input'.
            
            // LET'S SIMPLIFY JNI to take ONE buffer: 'complexBuffer' (already populated with Real data).
            // But for now, let's just window in Java (fast enough) and pass to C.
            // Reuse complexBuffer for windowed input?
            // Since JNI implementation reads 'input', let's just use 'input' * 'window' -> 'complexBuffer' (real part)?
            // The JNI code: 
            // cx_in[i].r = inPtr[i]; 
            // So it READS input.
            
            // Optimization: Just pass 'input' and let C do windowing? No, window is not passed.
            // Let's apply window here to a temp buffer?
            // Or: write to 'complexBuffer' (as float array) and pass it as 'input' to JNI?
            // Yes.
            complexBuffer[i] = input[i] * window[i]
        }
        
        // Pass 'complexBuffer' as the input (taking the first N floats)
        // And use the SAME 'complexBuffer' as the scratch/complex buffer for C?
        // My JNI code distinguishes them.
        
        computeKissFFT(n, complexBuffer, complexBuffer, outMagnitudes) 
        // Note: passing same buffer for input and complexBuffer might be weird if I don't handle it.
        // In JNI: 
        // inPtr = Get(complexBuffer)
        // cplxPtr = Get(complexBuffer)
        // cx_in points to cplxPtr.
        // Loop: cx_in[i].r = inPtr[i] -- this is safe (writing to same memory).
        // cx_in[i].i = 0.
        // Setup is fine.
    }
    
    private fun getHanningWindow(n: Int): FloatArray {
        return windowCache.getOrPut(n) {
            FloatArray(n).apply {
                for (i in 0 until n) {
                    this[i] = 0.5f * (1f - cos(2f * PI.toFloat() * i / (n - 1)))
                }
            }
        }
    }
}
