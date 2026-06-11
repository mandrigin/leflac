package app.nogarbo.leflac.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// 25x25 Grid
private const val MATRIX_DIM = 25

class SimpleBarsEngine {

    // Display Buffers
    private val outputBuffer = IntArray(MATRIX_DIM * MATRIX_DIM)
    private val phosphorBuffer = FloatArray(MATRIX_DIM * MATRIX_DIM)
    
    // Hardware Dynamic Range
    private val MAX_BRIGHTNESS = 512 // User specified max
    private val BG_MAX_BRIGHTNESS = 450 // Boosted for visibility (Phosphor Glow)

    // Physics State
    private data class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var life: Float = 0f
    )
    private val particles = ArrayList<Particle>()
    private val MAX_PARTICLES = 50 // Increased to match spec

    // Punch-In FX State
    private var isDramaticState = false
    private var dramaticTimer = 0.0f
    private var prevShockwaveTrigger = false
    private var shockwaveCooldownTimer = 0
    private val waterfallBuffer = Array(30) { FloatArray(6) }
    
    // Helper Maps (Precalc distances?)
    // No, calculate on fly for 25x25 is cheap.

    fun update(bands: FloatArray, isDramaticInversion: Boolean, isShockwaveTrigger: Boolean): IntArray {
        // Unpack Parameters
        val pSub    = bands.getOrElse(0) { 0f }
        val pBass   = bands.getOrElse(1) { 0f }
        val pLoMid  = bands.getOrElse(2) { 0f }
        val pHiMid  = bands.getOrElse(3) { 0f }
        val pHigh   = bands.getOrElse(4) { 0f }
        val pAir    = bands.getOrElse(5) { 0f }

        // --- 1. PARTICLE SPAWNING ---
        // Removed per user request ("annoying so much physics")
        /*
        if (particles.size < MAX_PARTICLES && (Random.nextFloat() < pBass * 0.3f)) {
            spawnParticle()
        }
        */
        
        // --- 2. PHYSICS SIMULATION ---
        val currentFrame = FloatArray(MATRIX_DIM * MATRIX_DIM)
        // Removed Physics Loop
        /*
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx + (pHiMid - 0.5f) * 0.2f
            p.y += p.vy
            if (pSub > 0.1f) {
                val dx = 12.5f - p.x
                val dy = 12.5f - p.y
                val dist = sqrt(dx*dx + dy*dy)
                if (dist > 1.0f) {
                    p.vx += dx * (pSub * 0.1f) / dist
                    p.vy += dy * (pSub * 0.1f) / dist
                }
            }
            p.vx *= 0.95f
            p.vy *= 0.95f
            p.life -= 0.04f
            if (p.life <= 0f || outOfBounds(p.x, p.y)) { iterator.remove(); continue }
            val ix = p.x.toInt(); val iy = p.y.toInt()
            if (!outOfBounds(ix.toFloat(), iy.toFloat())) currentFrame[iy * MATRIX_DIM + ix] += p.life
        }
        */
        
    // --- 3. PUNCH-IN SHOCKWAVE (Mapped to DRAMATIC with Filtering) ---
        // Cooldown Management
        if (shockwaveCooldownTimer > 0) shockwaveCooldownTimer--

        // Rising Edge Detection + Random Filter (1 in 6 chance) + Cooldown Check
        if (isShockwaveTrigger && !prevShockwaveTrigger && shockwaveCooldownTimer == 0) {
             if (Random.nextInt(6) == 0) {
                 isDramaticState = true
                 // Cooldown: 10s - 15s @ 15Hz ~= 150 - 225 frames
                 shockwaveCooldownTimer = Random.nextInt(150, 225) 
             }
        }
        prevShockwaveTrigger = isShockwaveTrigger
        
        if (isDramaticState) {
            dramaticTimer += 1.0f // 2x Speed (15Hz)
            val radius = dramaticTimer
            // Edge at ~18. +1s (15 frames) = 33.
            if (radius > 33.0f) {
                isDramaticState = false
                dramaticTimer = 0f
            } else {
                for (y in 0 until MATRIX_DIM) {
                    for (x in 0 until MATRIX_DIM) {
                        val dx = x - 12.5f; val dy = y - 12.5f
                        val dist = sqrt(dx*dx + dy*dy)
                        if (abs(dist - radius) < 2.0f) currentFrame[y * MATRIX_DIM + x] = 1.5f
                    }
                }
            }
        }
        
        // --- 4. POST-PROCESSING ---
        val decayFactor = 0.8f + (pLoMid * 0.19f) // Slower decay for phosphor persistence
        for (i in 0 until MATRIX_DIM * MATRIX_DIM) {
            if (currentFrame[i] > phosphorBuffer[i]) phosphorBuffer[i] = currentFrame[i]
            else phosphorBuffer[i] *= decayFactor
            
            // Removed Random Air/High Glitches
            /*
            if (Random.nextFloat() < (pAir * 0.005f)) phosphorBuffer[i] = 1.0f
            var finalVal = phosphorBuffer[i]
            if (pHigh > 0.1f) {
                finalVal += (Random.nextFloat() - 0.5f) * pHigh
                if (pHigh > 0.8f) finalVal = if (finalVal > 0.5f) 1.0f else 0.0f
            }
            */
            val finalVal = phosphorBuffer[i]
            outputBuffer[i] = (finalVal.coerceIn(0f, 1f) * BG_MAX_BRIGHTNESS).toInt()
        }

        // --- 5. SPECTROGRAPH WATERFALL ---
        // Shift Waterfall
        val lastRow = waterfallBuffer[29]
        for (i in 29 downTo 1) {
            waterfallBuffer[i] = waterfallBuffer[i - 1]
        }
        waterfallBuffer[0] = lastRow
        
        // Fill New Data (6 Bands)
        for (j in 0 until 6) {
           waterfallBuffer[0][j] = bands.getOrElse(j) { 0f }
        }
        
        // Render Waterfall (Top -> Bottom)
        // Center X = 12.5. 6 Width -> Indices 10, 11, 12, 13, 14, 15
        val startX = 10
        val startY = 0 // Start from Top
        
        for (r in 0 until MATRIX_DIM) { // Fill full height (0..24)
            val screenY = startY + r
            if (screenY >= MATRIX_DIM) break
            
            // Linear Decay down the screen
            // Since we cover 0..24, we map r=0 (fresh) to 1.0, r=24 to 0.2?
            val decay = (1.0f - (r / 30.0f)).coerceIn(0f, 1f)
            
            for (c in 0 until 6) {
                // Dimmer trail
                val rawLevel = waterfallBuffer[r][c] * decay 
                
                // STARK CONTRAST LOGIC
                // If signal is weak, kill it. If strong, boost it.
                val brightness = if (rawLevel > 0.15f) {
                    // Boost high values
                    (rawLevel * 1.2f).coerceIn(0.3f, 1.0f)
                } else {
                    0f // Hard cutoff for black background
                }
                
                val valInt = getBrightnessValue(brightness, isDramaticState)
                
                // Additive Blending
                if (brightness > 0.01f) { 
                   val idx = screenY * MATRIX_DIM + (startX + c)
                   val current = outputBuffer[idx]
                   outputBuffer[idx] = (current + valInt).coerceAtMost(MAX_BRIGHTNESS)
                }
            }
        }

        // --- 6. FOREGROUND UI LAYOUT ---
        val kick = bands.getOrElse(0) { 0f }
        val bass = bands.getOrElse(1) { 0f }
        val snare = bands.getOrElse(2) { 0f }
        val cymbals = bands.getOrElse(5) { 0f }
        val mixL = ((kick + bass) / 1.5f).coerceIn(0f, 1.0f)
        val mixR = ((snare + cymbals) / 1.5f).coerceIn(0f, 1.0f)
        val vuMain = maxOf(mixL, mixR)
        
        // Segments
        drawVuSegment(20, 17, 0, vuMain, isDramaticState)
        drawVuSegment(22, 13, 1, vuMain, isDramaticState)
        drawVuSegment(22, 9, 2, vuMain, isDramaticState)
        drawVuSegment(20, 5, 3, vuMain, isDramaticState)

        // Instruments
        drawSquare(2, 7, 2, cymbals, "HATS", isDramaticState)
        drawSquare(1, 11, 2, snare, "SNARE", isDramaticState)
        drawSquare(2, 15, 2, kick, "KICK", isDramaticState)
        
        // --- 7. SPRITE REMOVED ---
        
        return outputBuffer
    }
    
    private fun spawnParticle() {
        val p = Particle(
            x = 12.5f, // Spawn at center (or random near center?) Spec just says spawnParticle()
            // C++ spec implementation didn't show body, but previous Kotlin code used random.
            // Let's assume random near center for "lens" effect.
            y = 12.5f,
            vx = (Random.nextFloat() - 0.5f) * 2.0f,
            vy = (Random.nextFloat() - 0.5f) * 2.0f,
            life = 1.0f
        )
        // Physics tweak: Distribute spawn slightly
        p.x += (Random.nextFloat() - 0.5f) * 4.0f
        p.y += (Random.nextFloat() - 0.5f) * 4.0f
        particles.add(p)
    }
    
    private fun outOfBounds(x: Float, y: Float): Boolean {
        return x < 0 || x >= MATRIX_DIM || y < 0 || y >= MATRIX_DIM
    }

    // Draws a single VU segment at specific coords
    private fun drawVuSegment(x: Int, y: Int, index: Int, globalLevel: Float, isDramatic: Boolean) {
        val numSegments = 4
        // Height 3px, Width 2px
        val w = 2
        val h = 3
        
        val scaledLevel = globalLevel * numSegments
        
        // Check this specific segment's fill
        val fill = (scaledLevel - index).coerceIn(0f, 1f)
        
        // Map Fill to Brightness (0.1 .. 1.0)
        val brightness = 0.1f + (0.9f * fill)
        val value = getBrightnessValue(brightness, isDramatic)
        
        for (py in y until y + h) {
            for (px in x until x + w) {
                if (px < MATRIX_DIM && py < MATRIX_DIM) {
                    outputBuffer[py * MATRIX_DIM + px] = value
                }
            }
        }
    }

    // Draws a Square with direct opacity mapping
    private fun drawSquare(xOpt: Int, yOpt: Int, size: Int, level: Float, label: String, isDramatic: Boolean) {
        // Brightness Map: Direct (0.0 - 1.0)
        // Clamp min to 0.1 for outline visibility
        val brightness = level.coerceAtLeast(0.1f).coerceAtMost(1.0f)
        val value = getBrightnessValue(brightness, isDramatic)
        
        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = xOpt + x
                val py = yOpt + y
                
                // Draw filled square
                if (px < MATRIX_DIM && py < MATRIX_DIM) {
                    outputBuffer[py * MATRIX_DIM + px] = value
                }
            }
        }
    }
    
    private fun getBrightnessValue(brightness: Float, isDramatic: Boolean): Int {
        // Map 0.0-1.0 to 0-2047
        var b = brightness.coerceIn(0f, 1.0f)
        
        // DRAMATIC INVERSION
        if (isDramatic) {
            // Louder (Higher b) -> Darker (Lower output)
            // Quiet (Lower b) -> Brighter (Higher output)
            // But usually 'brightness' here is 0.1..1.0.
            // So if b=1.0 (Loud), output -> 0.
            // If b=0.1 (Quiet), output -> 0.9.
            b = 1.0f - b
        }
        
        return (b * MAX_BRIGHTNESS).roundToInt()
    }
}
