package app.nogarbo.leflac.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalysisLogicTest {

    private val logic = AudioAnalysisLogic()

    // Helper to warm up the logic to pass the frameCount check
    private fun primeLogic() {
        val dummy = ByteArray(1024) 
        repeat(150) { logic.process(dummy) }
    }

    // Helper to generate a dummy FFT frame
    private fun generateComplexFft(
        size: Int = 1024,
        lows: Float = 0f, 
        mids: Float = 0f, 
        highs: Float = 0f
    ): ByteArray {
        val fft = ByteArray(size)
        val maxVal = 50.0 
        
        for (i in 1 until size / 2) {
             var valRe = 0.0
             if (i in 1..3) valRe += lows * maxVal // Lows
             if (i in 6..30) valRe += mids * maxVal // Mids
             if (i > 150) valRe += highs * maxVal // Highs
             
             fft[2 * i] = valRe.toInt().coerceIn(-128, 127).toByte()
        }
        return fft
    }

    @Test
    fun `test Silence returns zero levels and no drama`() {
        primeLogic() // WARMUP
        val quietFft = ByteArray(1024)
        val result = logic.process(quietFft)
        
        assertEquals(0f, result.kick, 0.01f)
        assertEquals(0f, result.snare, 0.01f)
        assertFalse("Silence should not be dramatic", result.isDramatic)
    }

    @Test
    fun `test Heavy Rock Moment triggers Drama`() {
        logic.reset()
        primeLogic() // WARMUP
        
        // 1. Prime average with quiet
        for (i in 0..10) {
            val quiet = generateComplexFft(lows = 0.1f, mids = 0.1f, highs = 0.1f)
            logic.process(quiet)
        }
        
        // 2. HIT IT
        val loud = generateComplexFft(lows = 1.0f, mids = 0.8f, highs = 0.8f)
        val result = logic.process(loud)
        
        assertTrue("Kick should be high", result.kick > 0.65f) // Restored
        assertTrue("Drama should trigger", result.isDramatic)
    }
    
    @Test
    fun `test Trap Beat (Kick Only) does NOT trigger Drama`() {
        logic.reset()
        primeLogic() // WARMUP
        
        for (i in 0..5) logic.process(ByteArray(1024))
        
        val trapKick = generateComplexFft(lows = 1.0f, mids = 0.0f, highs = 0.0f)
        val result = logic.process(trapKick)
        
        assertTrue("Kick detected", result.kick > 0.8f) // Restored to high threshold
        assertFalse("Trap beat not dramatic", result.isDramatic)
    }
}
