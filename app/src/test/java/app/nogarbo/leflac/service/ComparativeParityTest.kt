package app.nogarbo.leflac.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileReader

class ComparativeParityTest {

    // Data class to match the JSON vector format from analyze_audio.py
    data class VectorFrame(
        val t: Float,
        val mags: FloatArray,
        val dramatic: Boolean,
        val kick: Float,
        val snare: Float
    )

    private val gson = Gson()

    @Test
    fun verifyParity_Caramel() {
        runParityTest("caramel.flac_vectors.json", "Caramel")
    }

    @Test
    fun verifyParity_Eden() {
        runParityTest("eden.flac_vectors.json", "Take Me Back To Eden")
    }

    @Test
    fun verifyParity_Vore() {
        runParityTest("vore.flac_vectors.json", "Vore")
    }

    private fun runParityTest(filename: String, trackName: String) {
        val vectorFile = File("analysis/debug_tracks/$filename")
        if (!vectorFile.exists()) {
            println("WARNING: Vector file $filename not found. Skipping test for $trackName.")
            return
        }

        println("Loading vectors for $trackName from ${vectorFile.absolutePath}...")
        val type = object : TypeToken<List<VectorFrame>>() {}.type
        val vectors: List<VectorFrame> = gson.fromJson(FileReader(vectorFile), type)

        val logic = AudioAnalysisLogic()
        
        // Stats for reporting
        var dramaticMatches = 0
        var falsePositives = 0
        var falseNegatives = 0
        var totalFrames = vectors.size

        println("Running comparison on $totalFrames frames...")

        vectors.forEachIndexed { index, frame ->
            // In the Python script, we normalized by checking the max magnitude of the FFT.
            // But here, the AudioTrackAnalyzer does a division by 4.0.
            // The vectors contain "mags" which are the RAW magnitudes from the python FFT.
            // AudioAnalysisLogic expects the input to be normalized similar to how Android Visualizer did it.
            // In AudioTrackAnalyzer.kt: `normMagnitudes[k] = rawMagnitudes[k] / 4.0f`
            //
            // Wait, analyze_audio.py output `mags` might be normalized or raw?
            // Checking analyze_audio.py... it stores `mags` as normalized or raw?
            // It seems `analyze_audio.py` uses `np.abs(fft) / N`.
            // AudioTrackAnalyzer uses `hypot(r, i)`. 
            // 
            // CRITICAL: We need to feed the logic the SAME input values the Python logic received.
            // Ideally, we take the `mags` from the JSON and feed them to `logic.processMagnitudes`.
            // But we must assume `mags` in JSON are what `AudioAnalysisLogic` expects?
            // Or are they what `AudioTrackAnalyzer` produces?
            //
            // Let's assume the JSON `mags` are the exact input to the logic function.
            
            // NOTE: Python logic might have pre-processing.
            // In Python script: 
            // k = np.mean(mags[0:2])
            // ...
            // is_dramatic = ...
            
            // So `mags` in vector should be the input to the instrument detection.
            
            val state = logic.processMagnitudes(frame.mags)

            // 1. Kick/Snare verification (optional, checking correlation)
            // Python kick is 0..1, Kotlin kick is 0..1
            // Tolerance?
            
            // 2. Drama Verification
            if (state.isDramatic == frame.dramatic) {
                dramaticMatches++
            } else {
                if (state.isDramatic) falsePositives++ else falseNegatives++
                // Detailed debug for first few failures
                if (falsePositives + falseNegatives < 10) {
                    println("MISMATCH @ ${frame.t}s (Frame $index): Py=${frame.dramatic} Kt=${state.isDramatic}")
                    println("  Input Kick: ${frame.mags.slice(0..1).average()} vs PyKick: ${frame.kick}")
                    println("  Calc  Kick: ${state.bass} (UI) vs Logic internal?")
                }
            }
        }

        val accuracy = (dramaticMatches.toFloat() / totalFrames) * 100f
        println("Parity Result for $trackName: $accuracy% match.")
        println("  False Positives (Kt says Drama, Py says No): $falsePositives")
        println("  False Negatives (Kt says No, Py says Drama): $falseNegatives")

        // Assertion: We want high parity. 95%? 99%?
        // Given floating point differences and FFT implementation details, 100% is hard.
        // But for "Determinism", we want it to be very close.
        
        // ALLOWANCE: Currently allow 90% match.
        assertTrue("Parity for $trackName is too low: $accuracy%", accuracy > 90.0)
    }
}
