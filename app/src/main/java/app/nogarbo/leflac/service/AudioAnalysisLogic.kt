package app.nogarbo.leflac.service

import kotlin.math.hypot
import kotlin.math.log10

/**
 * Pure Kotlin implementation of the Audio Analysis Logic.
 * Decoupled from Android Visualizer to allow for Unit Testing.
 */
class AudioAnalysisLogic {

    private var avgEnergy = 0.3f // Start with a mid-value assumptions
    private var frameCount = 0
    private val WARMUP_FRAMES = 120 // ~2-4 seconds depending on frame rate (usually 20-50fps)

    fun process(fft: ByteArray, sampleRate: Int = 44100): SpectrumState {
        val n = fft.size
        val magnitudes = FloatArray(n / 2)
        
        // 1. Compute Magnitudes
        for (i in 0 until n / 2) {
            // Android Visualizer returns bytes (-128..127).
            // We treat them as signed bytes here.
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            magnitudes[i] = hypot(re, im)
        }
        
        return processMagnitudes(magnitudes, sampleRate)
    }

    // Exposed for Golden Master Testing with raw magnitudes
    fun processMagnitudes(magnitudes: FloatArray, sampleRate: Int = 44100): SpectrumState {
        // Precise Frequency Mapping
        val nyquist = sampleRate / 2.0f
        val binCount = magnitudes.size
        val userHzPerBin = nyquist / binCount
        // Safety check to avoid div by zero if empty
        val hzPerBin = if (userHzPerBin > 0) userHzPerBin else 21.5f // Fallback 44100/1024

        // Helper to sum range
        fun getEnergy(minHz: Float, maxHz: Float): Float {
            val startBin = (minHz / hzPerBin).toInt().coerceIn(1, binCount - 1) // Skip DC
            val endBin = (maxHz / hzPerBin).toInt().coerceIn(startBin, binCount - 1)
            
            if (startBin >= endBin) return magnitudes.getOrElse(startBin) { 0f }
            
            var sum = 0f
            for (i in startBin..endBin) {
                sum += magnitudes[i]
            }
            return sum / (endBin - startBin + 1)
        }

        // Phone 3 Spec Bands
        // 1. Kick (20Hz - 80Hz)
        val rawKick = getEnergy(20f, 80f)
        // 2. Bass Guitar (80Hz - 250Hz)
        val rawBass = getEnergy(80f, 250f)
        // 3. Snare/Guitar (250Hz - 2kHz)
        val rawSnare = getEnergy(250f, 2000f)
        // 4. Vocal (2kHz - 4kHz)
        val rawVocal = getEnergy(2000f, 4000f)
        // 5. Synth (4kHz - 8kHz)
        val rawSynth = getEnergy(4000f, 8000f)
        // 6. Cymbals (6kHz - 22kHz) -- Spec says 6k-22k, Note overlap with Synth is intentional
        val rawCymbals = getEnergy(6000f, 22000f)

        // Normalization (Empirical Scaling based on 8-bit input or standard float)
        // Assuming magnitudes are like 0..255 or 0..1 depending on FFT source.
        // FastFFT usually returns somewhat large numbers.
        // We'll apply a standard gain. 
        val GAIN = 0.5f 
        
        val kickLevel = (rawKick * GAIN).coerceIn(0f, 1f)
        val bassLevel = (rawBass * GAIN).coerceIn(0f, 1f)
        val snareLevel = (rawSnare * GAIN).coerceIn(0f, 1f)
        // Guitar maps to Snare in Spec, but we keep the field for compat
        val guitarLevel = snareLevel 
        val vocalLevel = (rawVocal * GAIN).coerceIn(0f, 1f)
        val synthLevel = (rawSynth * GAIN).coerceIn(0f, 1f)
        val cymbalLevel = (rawCymbals * GAIN).coerceIn(0f, 1f)
        
        // Legacy Mappings for UI visuals
        val normBass = kickLevel
        val normMid = snareLevel
        val normTreble = cymbalLevel
        
        // Dramatic Detection (Instrument Based)
        val currentEnergy = (kickLevel * 0.8f + snareLevel + cymbalLevel * 0.8f) / 2.6f
        
        val learningRate = if (currentEnergy > avgEnergy) 0.01f else 0.05f 
        avgEnergy = avgEnergy * (1 - learningRate) + currentEnergy * learningRate

        // Stricter Flux Drop (1.5 -> 1.8) -> KEEP HIGH to prevent jitter
        val isFluxDrop = currentEnergy > (avgEnergy * 1.5f) // Revert to 1.5, 1.8 might be too safe
        
        // Rock Logic (Middle Ground):
        // 1. "Heavy Hit": Kick AND Snare are hitting hard.
        val isHeavyHit = kickLevel > 0.6f && snareLevel > 0.5f 
        
        // 2. "Cymbal Wash": High energy crash.
        val isCymbalWash = cymbalLevel > 0.5f
        
        // 3. "Full Rock Energy"
        // Require some kick energy even in snare/cymbal heavy sections
        val isRockEnergy = isHeavyHit || (snareLevel > 0.6f && isCymbalWash && kickLevel > 0.4f)
        
        val isLoudEnough = currentEnergy > 0.35f 

        // Drama = Drop + Rock, OR Sustained Loud Rock
        // FIX: "Caramel" triggers at start. Add Warmup to let avgEnergy settle.
        val isWarmup = frameCount < WARMUP_FRAMES
        
        val isDramatic = !isWarmup && ((isFluxDrop && isRockEnergy) || (isRockEnergy && isLoudEnough && avgEnergy > 0.5f))

        if (frameCount % 10 == 0) { // Log every 10th frame to avoid spam but get data
             System.out.println("FLAC_DEBUG: Frame=$frameCount | K=$kickLevel S=$snareLevel C=$cymbalLevel | AvgE=$avgEnergy CurE=$currentEnergy | FluxDrop=$isFluxDrop Rock=$isRockEnergy Loud=$isLoudEnough Dramatic=$isDramatic (Warmup=$isWarmup)")
        }

        // --- MATRIX GLITCH LOGIC (STICKY SIGNAL) ---
        if (glitchTargetFrame == -1) {
            glitchTargetFrame = frameCount + getNextGlitchInterval(isDramatic)
        }
        
        var isMatrixGlitch = false
        
        // Trigger Event
        if (frameCount >= glitchTargetFrame) {
            glitchDurationTimer = 6 // Hold for 6 frames (~150ms) to ensure 15Hz renderer catches it
            // Log for debugging
            System.out.println("FLAC_DEBUG: GLITCH TRIGGERED! Frame=$frameCount")
            glitchTargetFrame = frameCount + getNextGlitchInterval(isDramatic)
        }
        
        // Sticky Signal
        if (glitchDurationTimer > 0) {
            isMatrixGlitch = true
            glitchDurationTimer--
        }

        frameCount++

        return SpectrumState(
            bass = normBass,
            mid = normMid,
            treble = normTreble,
            isDramatic = isDramatic,
            isMatrixGlitch = isMatrixGlitch,
            
            // Debug Data
            avgEnergy = avgEnergy,
            fluxRatio = if (avgEnergy > 0) currentEnergy / avgEnergy else 0f,
            isFullBand = isRockEnergy,
            isFluxDrop = isFluxDrop,
            isLoudEnough = isLoudEnough,
            isHighEndHeavy = isRockEnergy,
            
            // Instrument Data
            kick = kickLevel,
            bassGuitar = bassLevel,
            snare = snareLevel,
            guitar = guitarLevel,
            vocal = vocalLevel,
            synth = synthLevel,
            cymbals = cymbalLevel
        )
    }

    fun reset() {
        avgEnergy = 0.3f
        frameCount = 0
        glitchTargetFrame = -1
        glitchDurationTimer = 0
    }
    
    // Timer State for Matrix Glitch
    private var glitchTargetFrame = -1
    private var glitchDurationTimer = 0
    
    private fun getNextGlitchInterval(isDramatic: Boolean): Int {
        // Assume ~40fps processing rate for offline analysis steps
        // Dramatic: 250ms - 750ms -> 10 - 30 frames (Higher Chaos)
        // Idle: 4s - 8s -> 160 - 320 frames (More Frequent for Visibility)
        if (isDramatic) {
            return (10..30).random()
        } else {
            return (160..320).random()
        }
    }
}
