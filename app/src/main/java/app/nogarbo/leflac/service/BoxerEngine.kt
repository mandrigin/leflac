package app.nogarbo.leflac.service

import kotlin.math.roundToInt
import kotlin.random.Random

// 25x25 Grid
private const val MATRIX_DIM = 25

class BoxerEngine {

    // Display Buffer (AARRGGBB)
    private val outputBuffer = IntArray(MATRIX_DIM * MATRIX_DIM)

    // Physics State
    private var boxX = 10.0f
    private var boxY = 10.0f
    private var boxVX = 1.0f
    private var boxVY = 1.0f
    
    // Box Size
    private val boxSize = 6

    // Color: Safety Orange (Alpha FF, R 255, G 69, B 0)
    // We use this exact int because it's known to work.
    private val BOX_COLOR = 0xFFFF4500.toInt()

    fun update(bands: FloatArray): IntArray {
        // 1. Update Physics
        boxX += boxVX
        boxY += boxVY

        // Bounce
        if (boxX <= 0 || boxX + boxSize >= MATRIX_DIM) {
            boxVX *= -1
            boxX = boxX.coerceIn(0f, (MATRIX_DIM - boxSize).toFloat())
        }
        if (boxY <= 0 || boxY + boxSize >= MATRIX_DIM) {
            boxVY *= -1
            boxY = boxY.coerceIn(0f, (MATRIX_DIM - boxSize).toFloat())
        }
        
        // Randomly perturb velocity slightly based on music (Bass)
        val pBass = bands.getOrElse(1) { 0f }
        if (pBass > 0.5f) {
           // Speed up or change direction slightly
           boxVX += (Random.nextFloat() - 0.5f) * 0.5f
           boxVY += (Random.nextFloat() - 0.5f) * 0.5f
           
           // Clamp speed
           boxVX = boxVX.coerceIn(-2f, 2f)
           boxVY = boxVY.coerceIn(-2f, 2f)
        }

        // 2. Render
        // Clear Buffer
        outputBuffer.fill(0)

        // Draw Box
        val startX = boxX.roundToInt()
        val startY = boxY.roundToInt()
        
        for (y in 0 until boxSize) {
            for (x in 0 until boxSize) {
                // Border only? Or fill? Let's do border for "Boxer" look.
                if (x == 0 || x == boxSize - 1 || y == 0 || y == boxSize - 1) {
                     val px = startX + x
                     val py = startY + y
                     if (px in 0 until MATRIX_DIM && py in 0 until MATRIX_DIM) {
                         outputBuffer[py * MATRIX_DIM + px] = BOX_COLOR
                     }
                }
            }
        }

        return outputBuffer
    }
}
