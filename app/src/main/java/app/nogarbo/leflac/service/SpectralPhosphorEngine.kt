package app.nogarbo.leflac.service

import kotlin.math.*
import kotlin.random.Random
import kotlin.math.roundToInt

// 25x25 Grid
private const val MATRIX_DIM = 25
private const val MAX_PARTICLES = 20

private data class Particle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var life: Float = 0f // 0.0 - 1.0
)

class SpectralPhosphorEngine {

    // Display Buffers
    private val phosphorBuffer = FloatArray(MATRIX_DIM * MATRIX_DIM)
    private val outputBuffer = IntArray(MATRIX_DIM * MATRIX_DIM)

    // Physics State
    private val particles = ArrayList<Particle>(MAX_PARTICLES)

    // Punch-In FX State
    private var isDramaticState = false
    private var dramaticTimer = 0.0f
    
    // Boxer Overlay State
    private var boxX = 10.0f
    private var boxY = 10.0f
    private var boxVX = 1.0f
    private var boxVY = 1.0f
    private val boxSize = 6

    init {
        // Pre-allocate particles? No, we'll spawn them. 
        // Or we can pool them if performance is an issue, but standard allocation for 50 floats is fine.
    }

    private fun idx(x: Int, y: Int): Int {
        return y * MATRIX_DIM + x
    }
    
    // Main Loop: Call this at ~15-60Hz (driven by caller)
    // bands: Array of 6 normalized floats (0.0 - 1.0)
    // dramaticFlag: Trigger for punch-in effect
    // masterBrightness: Global scaler (0.0 - 1.0) for hardware adaptation
    // Returns: IntArray for the Glyph Matrix (ARGB / brightness)
    fun update(bands: FloatArray, dramaticFlag: Boolean, masterBrightness: Float = 1.0f): IntArray {
        // Unpack Parameters
        val pSub    = bands.getOrElse(0) { 0f } // Gravity/Warp
        val pBass   = bands.getOrElse(1) { 0f } // Spawn Rate
        val pLoMid  = bands.getOrElse(2) { 0f } // Decay Rate
        val pHiMid  = bands.getOrElse(3) { 0f } // Flow/Turbulence
        val pHigh   = bands.getOrElse(4) { 0f } // Dither Crunch
        val pAir    = bands.getOrElse(5) { 0f } // Sparkle Chance

        // 1. Particle Spawning (Bass Driven)
        // Check particle count cap
        if (particles.size < MAX_PARTICLES && (Random.nextFloat() < pBass * 0.3f)) {
            spawnParticle()
        }

        // 2. Physics Simulation & Frame Generation
        // Using a temporary float buffer for the current frame accumulation
        val currentFrame = FloatArray(MATRIX_DIM * MATRIX_DIM)

        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()

            // Apply Flow (HiMid)
            p.x += p.vx + (pHiMid - 0.5f) * 0.2f
            p.y += p.vy

            // Apply Gravity Well (Sub)
            if (pSub > 0.1f) {
                val dx = 12.5f - p.x
                val dy = 12.5f - p.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 1.0f) {
                    val force = (pSub * 0.1f) / dist
                    p.vx += dx * force
                    p.vy += dy * force
                }
            }

            // Decay & Bounds Check
            p.vx *= 0.95f
            p.vy *= 0.95f
            p.life -= 0.04f

            if (p.life <= 0f || outOfBounds(p.x, p.y)) {
                iterator.remove()
                continue
            }

            // Rasterize to currentFrame (Anti-aliased splat)
            val ix = p.x.toInt()
            val iy = p.y.toInt()
            
            // Simple point rendering for now, or "splat"
            if (!outOfBounds(ix.toFloat(), iy.toFloat())) {
                val index = idx(ix, iy)
                if (index in currentFrame.indices) {
                    currentFrame[index] += p.life
                }
            }
        }

        // 3. Handle "Punch-In" Shockwave
        if (dramaticFlag) isDramaticState = true

        if (isDramaticState) {
            dramaticTimer += 0.5f // Expand radius
            val radius = dramaticTimer
            if (radius > 35.0f) {
                isDramaticState = false
                dramaticTimer = 0f
            } else {
                // Draw Ring
                for (y in 0 until MATRIX_DIM) {
                    for (x in 0 until MATRIX_DIM) {
                        val dx = x - 12.5f
                        val dy = y - 12.5f
                        val dist = sqrt(dx * dx + dy * dy)
                        if (abs(dist - radius) < 2.0f) {
                             currentFrame[idx(x, y)] = 1.5f // Overdrive
                        }
                    }
                }
            }
        }

        // 4. Post-Processing Pipeline
        val decayFactor = 0.5f + (pLoMid * 0.49f) // Map 0-1 to 0.5-0.99

        for (i in 0 until MATRIX_DIM * MATRIX_DIM) {

            // A. Phosphor Persistence (VFD Simulation)
            // If new frame is brighter, charge instantly. If dimmer, decay slowly.
            if (currentFrame[i] > phosphorBuffer[i]) {
                phosphorBuffer[i] = currentFrame[i]
            } else {
                phosphorBuffer[i] *= decayFactor
            }

            // B. Sparkle Injection (Air)
            if (Random.nextFloat() < (pAir * 0.005f)) {
                phosphorBuffer[i] = 1.0f
            }

            // C. Dithering / Crunch (High)
            var finalVal = phosphorBuffer[i]
            if (pHigh > 0.1f) {
                // Add noise based on High band
                val noise = (Random.nextFloat() - 0.5f) * pHigh
                finalVal += noise

                // Hard Thresholding for "1-bit" look
                if (pHigh > 0.8f) {
                    finalVal = if (finalVal > 0.5f) 1.0f else 0.0f
                }
            }

            // Clamp to Low Power Limit (30%)
            finalVal = finalVal.coerceIn(0f, 0.3f)

            // Apply Master Brightness (simulated)
            finalVal *= masterBrightness

            // Map to ARGB Int using RGB Modulation (Alpha Fixed at 0xFF)
            // Base Color: Safety Orange (R=255, G=69, B=0) -> 0xFF4500
            val r = (255 * finalVal).toInt().coerceIn(0, 255)
            val g = (69 * finalVal).toInt().coerceIn(0, 255)
            val b = 0 // Blue is 0
            
            // Format: AARRGGBB
            // Alpha is ALWAYS 0xFF (Opaque)
            outputBuffer[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // --- OVERLAY: BOXER (Bouncing Box) ---
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
        val pBassBox = bands.getOrElse(1) { 0f }
        if (pBassBox > 0.5f) {
           boxVX += (Random.nextFloat() - 0.5f) * 0.5f
           boxVY += (Random.nextFloat() - 0.5f) * 0.5f
           boxVX = boxVX.coerceIn(-2f, 2f)
           boxVY = boxVY.coerceIn(-2f, 2f)
        }

        // 2. Draw Box
        // Use Safety Orange at 60% Brightness (0x99) for Visibility/Safety Balance
        // R=153 (0x99), G=41 (0x29)
        // 0xFF992900
        val boxColor = 0xFF992900.toInt()
        val startX = boxX.roundToInt()
        val startY = boxY.roundToInt()
        
        for (y in 0 until boxSize) {
            for (x in 0 until boxSize) {
                // Border only
                if (x == 0 || x == boxSize - 1 || y == 0 || y == boxSize - 1) {
                     val px = startX + x
                     val py = startY + y
                     if (px in 0 until MATRIX_DIM && py in 0 until MATRIX_DIM) {
                         outputBuffer[py * MATRIX_DIM + px] = boxColor
                     }
                }
            }
        }

        // DEBUG: Pixels at Center (Reduced Brightness)
        val debugColor = 0xFF992900.toInt()
        outputBuffer[idx(12, 12)] = debugColor
        outputBuffer[idx(13, 12)] = debugColor
        outputBuffer[idx(12, 13)] = debugColor
        outputBuffer[idx(13, 13)] = debugColor

        return outputBuffer
    }

    private fun spawnParticle() {
        // Spawn at center or random? 
        // "Heavy bass... lens effect... Bass (Matter): Controls spawn rate"
        // Usually spawn near center or random location?
        // Let's spawn near center with random velocity
        val p = Particle(
            x = 12.5f,
            y = 12.5f,
            vx = (Random.nextFloat() - 0.5f) * 2.0f,
            vy = (Random.nextFloat() - 0.5f) * 2.0f,
            life = 1.0f
        )
        particles.add(p)
    }

    private fun outOfBounds(x: Float, y: Float): Boolean {
        return x < 0 || x >= MATRIX_DIM || y < 0 || y >= MATRIX_DIM
    }
}
