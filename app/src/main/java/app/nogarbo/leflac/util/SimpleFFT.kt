package app.nogarbo.leflac.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SimpleFFT {
    
    // Compute FFT and return Magnitudes
    // input: PCM Float Array (-1.0 to 1.0)
    // reusable buffers: ensure they are of size n (or n/2 for magnitudes)
    fun computeMagnitudes(
        input: FloatArray, 
        realBuffer: FloatArray, 
        imagBuffer: FloatArray, 
        outMagnitudes: FloatArray
    ): FloatArray {
        val n = input.size
        
        // 1. Copy Input to Real Buffer
        // System.arraycopy is fast
        System.arraycopy(input, 0, realBuffer, 0, n)
        // Clear Imag Buffer
        java.util.Arrays.fill(imagBuffer, 0f)
        
        // 2. Windowing (Hanning) - Cached
        val window = getHanningWindow(n)
        for (i in 0 until n) {
            realBuffer[i] *= window[i]
        }
        
        // 3. FFT (Recursive/Iterative)
        fft(realBuffer, imagBuffer)
        
        // 4. Compute Magnitudes (first n/2)
        for (i in 0 until n / 2) {
            outMagnitudes[i] = sqrt(realBuffer[i] * realBuffer[i] + imagBuffer[i] * imagBuffer[i])
        }
        
        return outMagnitudes
    }
    
    // Legacy overload for compatibility (allocating) if needed, 
    // but ideally we switch all callers.
    fun computeMagnitudes(pt: FloatArray): FloatArray {
        val n = pt.size
        val real = FloatArray(n)
        val imag = FloatArray(n)
        val mags = FloatArray(n/2)
        return computeMagnitudes(pt, real, imag, mags)
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        
        // Bit Reversal Permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                // Swap
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        
        // Butterfly updates
        var l = 1
        while (l < n) { // l = step size
           // step = 2 * l
           val step = l * 2
           // W_n^k factors
           // e^(-2*pi*i / step)
           val theta = -PI / l
           val wRealStep = cos(theta).toFloat()
           val wImagStep = sin(theta).toFloat()
           
           var wRealCurrent = 1.0f
           var wImagCurrent = 0.0f
           
           for (m in 0 until l) {
               for (i in m until n step step) {
                   val j_idx = i + l
                   
                   // t = w * data[j]
                   val tr = wRealCurrent * real[j_idx] - wImagCurrent * imag[j_idx]
                   val ti = wRealCurrent * imag[j_idx] + wImagCurrent * real[j_idx]
                   
                   // u = data[i]
                   val ur = real[i]
                   val ui = imag[i]
                   
                   // Butterfly
                   real[i] = ur + tr
                   imag[i] = ui + ti
                   real[j_idx] = ur - tr
                   imag[j_idx] = ui - ti
               }
               
               // Update W
               val wr = wRealCurrent
               wRealCurrent = wr * wRealStep - wImagCurrent * wImagStep
               wImagCurrent = wr * wImagStep + wImagCurrent * wRealStep
           }
           l = step
        }
    }

    // Cache for window functions to avoid recomputing cos() N times per frame
    private val windowCache = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    
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
