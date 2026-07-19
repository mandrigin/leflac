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
        require(complexBuffer.size >= n * 2) { "complexBuffer must contain at least 2*N floats" }
        require(outMagnitudes.size >= n / 2) { "outMagnitudes must contain at least N/2 floats" }
        
        // Window the disposable PCM window in place. The analyzer completely
        // refills [input] before the next FFT, so no copy is required here.
        //
        // Keep [input] and [complexBuffer] distinct when crossing JNI. Passing
        // the same Java array for both is not safe: GetFloatArrayElements may
        // return the same backing storage, and the native real-to-complex loop
        // writes cplxPtr[2*i] while it is still reading later inPtr[i] values.
        // That made spectral profiles depend on VM copy/pin behaviour.
        val window = getHanningWindow(n)
        for (i in 0 until n) {
            input[i] *= window[i]
        }

        computeKissFFT(n, input, complexBuffer, outMagnitudes)
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
