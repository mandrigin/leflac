#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <android/log.h>

// Constants
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Custom Interleaved FFT (Cooley-Tukey)
// data: float array of size 2*N (Real, Imag, Real, Imag...)
void fft_interleaved(float* data, int n) {
    // 1. Bit Reversal Permutation
    int j = 0;
    for (int i = 0; i < n - 1; ++i) {
        if (i < j) {
            // Swap complex[i] and complex[j]
            float tr = data[2*i]; 
            float ti = data[2*i+1];
            data[2*i] = data[2*j];
            data[2*i+1] = data[2*j+1];
            data[2*j] = tr;
            data[2*j+1] = ti;
        }
        int k = n / 2;
        while (k <= j) {
            j -= k;
            k /= 2;
        }
        j += k;
    }

    // 2. Butterfly Operations
    for (int l = 1; l < n; l <<= 1) { // L is half-period
        int step = l << 1;            // Step is full period (2*L)
        float theta = -M_PI / l;
        float wr_step = cosf(theta);
        float wi_step = sinf(theta);
        
        float wr = 1.0f;
        float wi = 0.0f;
        
        for (int m = 0; m < l; ++m) {
            // Do butterflies for this sub-factor
            for (int i = m; i < n; i += step) {
                int j_idx = i + l;
                
                // Temp = W * data[j]
                float dr_j = data[2*j_idx];
                float di_j = data[2*j_idx+1];
                
                float tr = wr * dr_j - wi * di_j;
                float ti = wr * di_j + wi * dr_j;
                
                // U = data[i]
                float ur = data[2*i];
                float ui = data[2*i+1];
                
                // Butterfly
                data[2*i] = ur + tr;
                data[2*i+1] = ui + ti;
                
                data[2*j_idx] = ur - tr;
                data[2*j_idx+1] = ui - ti;
            }
            
            // Recalculate W
            // wr_new = wr * w_step - wi * w_step_i
            float t = wr;
            wr = t * wr_step - wi * wi_step;
            wi = t * wi_step + wi * wr_step;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_nogarbo_leflac_util_FastFFT_computeKissFFT(
        JNIEnv* env,
        jobject /* this */,
        jint n,
        jfloatArray input,
        jfloatArray complexBuffer, // Size 2*N
        jfloatArray outputMags     // Size N/2
) {
    // Note: Function name 'computeKissFFT' is legacy now, but kept for signature compatibility.
    // It now runs "CustomNativeFFT".

    // 1. Get Pointers
    jfloat* inPtr = env->GetFloatArrayElements(input, nullptr);
    jfloat* cplxPtr = env->GetFloatArrayElements(complexBuffer, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outputMags, nullptr);

    // 2. Prepare Input (Real -> Complex Interleaved)
    // We use cplxPtr (complexBuffer) as working storage.
    // Fill it with input.
    for (int i = 0; i < n; ++i) {
        cplxPtr[2*i] = inPtr[i]; 
        cplxPtr[2*i+1] = 0.0f;
    }

    // 3. Compute FFT (In-Place on cplxPtr)
    fft_interleaved(cplxPtr, n);

    // 4. Compute Magnitudes
    // Also Find Peak for Debug
    int limit = n / 2;
    float maxVal = 0.0f;
    int maxIdx = -1;
    
    for (int k = 0; k < limit; ++k) {
        float re = cplxPtr[2*k];
        float im = cplxPtr[2*k+1];
        float mag = sqrtf(re*re + im*im);
        outPtr[k] = mag;
        
        if (mag > maxVal) {
            maxVal = mag;
            maxIdx = k;
        }
    }
    
    // DEBUG: Print Peak (Keeping debugging momentarily for verification, low cost)
    if (n > 5) {
        static int log_count = 0;
        if (log_count < 5) {
             __android_log_print(ANDROID_LOG_INFO, "FastFFT_Native", "Output: Peak Bin %d, Mag %f", maxIdx, maxVal);
             log_count++;
        }
    }

    // 5. Release
    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(complexBuffer, cplxPtr, 0); // Commit? No, scratch.
    env->ReleaseFloatArrayElements(outputMags, outPtr, 0); // Commit output
}
