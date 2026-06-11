package app.nogarbo.leflac.service

import app.nogarbo.leflac.service.SpectrumState
import kotlin.math.*
import kotlin.random.Random

// Hardware Spec Constants
private const val MATRIX_DIM = 25
private const val CENTER_X = 12f
private const val CENTER_Y = 12f
private const val ACTIVE_RADIUS_SQ = 12.5f * 12.5f
private const val MAX_BRIGHTNESS = 2000

class PhoneThreeGlyphEngine {

    // 0..24
    private val outputBuffer = IntArray(MATRIX_DIM * MATRIX_DIM)
    
    // Rotation State
    private var rotationAngle = 0f
    private var zzzPhase = 0f // 0..ZZZ_CYCLE, sleep animation clock

    // Punch-in shutter (EP-style): on play/pause the matrix slams shut to a
    // bright center line and reopens on the new state. Close and open share
    // one envelope, so pause and resume are exact inverses.
    private var lastIsPlaying: Boolean? = null
    private var shutterT = -1f // -1 idle, else 0..1
    // The close phase slams over a freeze-frame of the OLD state; the new
    // state is only revealed as the plates reopen.
    private val frozenFrame = IntArray(MATRIX_DIM * MATRIX_DIM)
    private val SHUTTER_DUR_S = 0.55f

    // Hold-to-punch: ACTION_DOWN charges the shutter over 2s; completing the
    // hold fires the transport toggle, releasing early reopens.
    private var holdT = -1f
    private var holdCompleted = false
    private var openOnlyNextShutter = false
    // After a committed hold the plates stay shut until the player state
    // actually flips (controller + spectrum watchdog lag a few frames) —
    // otherwise the old state flashes fully open before the reveal.
    private var latchClosed = false
    private var latchTimeoutS = 0f
    // Short: the system's own long-press detection (~0.5s) happens first,
    // so the total press is ~1.7s to punch.
    private val HOLD_DUR_S = 1.2f

    private var clockDigits = intArrayOf(0, 0, 0, 0)

    fun greet() { shutterT = 0f } // toy selected: slam shut + reopen

    fun beginHold(hour: Int, minute: Int) {
        holdT = 0f
        holdCompleted = false
        shutterT = -1f // a hold owns the aperture; kill any running slam
        // The hold doubles as a peek: the aperture frames the clock
        clockDigits = intArrayOf(hour / 10, hour % 10, minute / 10, minute % 10)
    }

    /** The OS commits the long-press (EVENT_CHANGE): the transport punch
     *  takes over from wherever the hold's aperture got to. */
    fun commitHold() {
        if (holdT in 0f..1f) {
            openOnlyNextShutter = true
            latchClosed = true
            latchTimeoutS = 1.2f // reopen anyway if no transport flip follows
        }
        holdT = -1f
    }

    fun endHold() {
        if (holdT in 0f..1f && !holdCompleted) {
            // reopen from the current aperture
            shutterT = 1f - holdT / 2f
        }
        holdT = -1f
    }

    fun consumeHoldCompleted(): Boolean {
        val fired = holdCompleted
        if (fired) { holdCompleted = false; holdT = -1f }
        return fired
    }

    fun setClock(hour: Int, minute: Int) {
        clockDigits = intArrayOf(hour / 10, hour % 10, minute / 10, minute % 10)
    }

    fun isHolding(): Boolean = holdT in 0f..1f

    // Physics constants
    private val ROTATION_SPEED_DEG_PER_SEC = 180f // 30 RPM
    private val ZZZ_SPEED_PER_SEC = 1.6f // Zs per second
    
    // Update function
    fun update(spectrum: SpectrumState, isPlaying: Boolean, dtMs: Long, mixProgress: Float = -1f): IntArray {
        val dtSec = dtMs / 1000f

        // Transport toggled: punch in (open-only if a completed hold
        // already slammed the aperture shut)
        if (lastIsPlaying != null && lastIsPlaying != isPlaying) {
            shutterT = if (openOnlyNextShutter) 0.5f else 0f
            openOnlyNextShutter = false
            latchClosed = false // the flip arrived: the reveal owns the plates
            if (shutterT == 0f) {
                // remember what was on screen: it stays up while closing
                System.arraycopy(outputBuffer, 0, frozenFrame, 0, outputBuffer.size)
            }
        }
        lastIsPlaying = isPlaying
        if (shutterT >= 0f) {
            shutterT += dtSec / SHUTTER_DUR_S
            if (shutterT >= 1f) shutterT = -1f
        }
        if (holdT in 0f..1f) {
            holdT += dtSec / HOLD_DUR_S
            if (holdT >= 1f) {
                holdCompleted = true
                // The hold already closed the aperture: the transport punch
                // that follows should only OPEN, not close-then-open again.
                openOnlyNextShutter = true
                latchClosed = true
                latchTimeoutS = 1.2f
            }
        }
        
        // 1. Update Animation State
        if (isPlaying) {
             rotationAngle = (rotationAngle + ROTATION_SPEED_DEG_PER_SEC * dtSec) % 360f
        } else {
             // Sleep Zs clock
             zzzPhase = (zzzPhase + ZZZ_SPEED_PER_SEC * dtSec) % ZZZ_CYCLE
        }
        
        // 2. Render Base Grid (Phone 3 Spec)
        for (y in 0 until MATRIX_DIM) {
            for (x in 0 until MATRIX_DIM) {
                val idx = y * MATRIX_DIM + x
                
                val floatX = x.toFloat()
                val floatY = y.toFloat()
                
                // Hardware Mask
                val dx = floatX - CENTER_X
                val dy = floatY - CENTER_Y
                val distSq = dx * dx + dy * dy
                if (distSq > ACTIVE_RADIUS_SQ) {
                    outputBuffer[idx] = 0
                    continue
                }

                val dist = sqrt(distSq)
                var brightness = 0.0f
                
                if (isPlaying && mixProgress >= 0f) {
                    // === MIXTAPE MODE (a mix is playing) ===
                    brightness = mixtapeBrightness(floatX, floatY, mixProgress)
                } else if (isPlaying) {
                    // === TURNTABLE ZONE (8.0 <= r <= 12.5) ===
                    if (dist >= 8.0f) {
                        var theta = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (theta < 0) theta += 360f
                        
                        val relativeAngle = (theta - rotationAngle + 360f) % 360f
                        val rad = Math.toRadians(relativeAngle.toDouble())
                        
                        val wave = (cos(3 * rad) + 1.0) / 2.0
                        brightness = wave.pow(10.0).toFloat() * 0.6f
                    } 
                    // === SPEAKER ZONE (r < 8.0) ===
                    else {
                        // Band Mapping
                        if (dist < 3.0f) brightness += spectrum.kick * 1.0f
                        if (dist < 5.0f) brightness += spectrum.bassGuitar * 0.6f
                        if (dist >= 3.0f && dist < 6.0f) brightness += spectrum.snare * 0.75f
                        brightness += spectrum.vocal * 0.25f
                        if (dist >= 5.0f) brightness += spectrum.synth * 0.5f
                        if (dist >= 6.0f) {
                            val noise = ((x * 37 + y * 13 + rotationAngle.toInt()) % 100) / 100f
                            brightness += spectrum.cymbals * 0.5f * noise
                        }
                    }
                } else {
                // === PAUSED / IDLE STATE ===
                // Light Ampelmann (4x downscaled, parked lower-left), alpha 0..100.
                val sx = x - AMPELMANN_OFFSET_X
                val sy = y - AMPELMANN_OFFSET_Y
                val alpha = if (sx in 0 until AMPELMANN_SMALL_DIM && sy in 0 until AMPELMANN_SMALL_DIM)
                    AMPELMANN_SMALL[sy * AMPELMANN_SMALL_DIM + sx] / 100f
                else 0f

                // "Make Ampelmann even brighter": increased from 0.2f (+400) to 0.4f (+800)
                brightness = 0.4f * alpha
            }

            // === DRAMATIC INVERSION ===
            if (spectrum.isDramatic && isPlaying) {
                 brightness = 1.0f - brightness
            }

            // Clamp & Output
            brightness = brightness.coerceIn(0f, 1f)
            outputBuffer[idx] = (brightness * MAX_BRIGHTNESS).toInt().coerceIn(0, MAX_BRIGHTNESS)
        }
        }
        
        // 2b. Overlay: Sleep Zs (idle only)
        if (!isPlaying) {
            drawZzz()
        }

        // 3. Overlay: Legacy UI while playing; at rest the bars yield to
        // a quiet clock beside the sleeping ampelmann.
        if (isPlaying) {
            val mixL = ((spectrum.kick + spectrum.bassGuitar) / 1.5f).coerceIn(0f, 1.0f)
            val mixR = ((spectrum.snare + spectrum.cymbals) / 1.5f).coerceIn(0f, 1.0f)
            val vuMain = max(mixL, mixR)

            // Segments
            drawVuSegment(20, 17, 0, vuMain, spectrum.isDramatic)
            drawVuSegment(22, 13, 1, vuMain, spectrum.isDramatic)
            drawVuSegment(22, 9, 2, vuMain, spectrum.isDramatic)
            drawVuSegment(20, 5, 3, vuMain, spectrum.isDramatic)

            // Instruments
            drawSquare(2, 7, 2, spectrum.cymbals, spectrum.isDramatic)
            drawSquare(1, 11, 2, spectrum.snare, spectrum.isDramatic)
            drawSquare(2, 15, 2, spectrum.kick, spectrum.isDramatic)
        } else {
            drawIdleClock()
        }

        // 4. Punch-in shutter slams over EVERYTHING (it IS the punch-in).
        // While closing, show the frozen old state under the plates.
        if (shutterT in 0f..1f) {
            if (shutterT < 0.5f) {
                System.arraycopy(frozenFrame, 0, outputBuffer, 0, outputBuffer.size)
            }
            applyShutter(shutterT)
        }
        // Hold-to-punch doubles as time peek: the playing animation keeps
        // running in the shrinking window while the clock rides ON the
        // shutter plates — hours on the top plate, minutes on the bottom.
        if (holdT in 0f..1f) {
            val half = (1f - holdT) * 13f
            applyAperture(half)
            drawPlateClock(half)
        }

        // Latched shut while waiting for the transport flip to land
        if (latchClosed) {
            latchTimeoutS -= dtSec
            if (latchTimeoutS <= 0f) {
                latchClosed = false
                shutterT = 0.5f // give up waiting: reopen
            } else {
                applyAperture(0f)
                drawPlateClock(0f)
            }
        }

        return outputBuffer
    }
    
    // Cassette rendered from geometry, per pixel: body shell, two reels
    // whose tape packs trade size with playback progress, rotating spokes,
    // and the little window. The matrix becomes a progress bar you can
    // read across the room.
    private fun mixtapeBrightness(x: Float, y: Float, progress: Float): Float {
        var b = 0f

        // Shell: rounded-ish rect border x 3..21, y 6..18
        val inShellX = x >= 3f && x <= 21f
        val inShellY = y >= 6f && y <= 18f
        val onShellEdge = (inShellX && (y == 6f || y == 18f)) || (inShellY && (x == 3f || x == 21f))
        if (onShellEdge && inShellX && inShellY) b = 0.35f

        // Reels: left feeds right as the mix progresses
        val reelY = 11.5f
        val leftR = 1.4f + 2.4f * (1f - progress)
        val rightR = 1.4f + 2.4f * progress
        b = maxOf(b, reelBrightness(x, y, 8.0f, reelY, leftR))
        b = maxOf(b, reelBrightness(x, y, 17.0f, reelY, rightR))

        // Tape bridge between the reels (the path over the head)
        if (y == 16f && x >= 8f && x <= 17f) b = maxOf(b, 0.25f)

        // Window: small opening under the reels
        if (y == 16f && x >= 11f && x <= 14f) b = maxOf(b, 0.5f)

        return b
    }

    private fun reelBrightness(x: Float, y: Float, cx: Float, cy: Float, tapeR: Float): Float {
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        // Hub is always visible
        if (dist <= 0.8f) return 1.0f
        if (dist > tapeR + 0.5f) return 0f
        // Rotating spoke across the tape pack (reels spin while playing)
        var theta = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (theta < 0) theta += 360f
        val spin = (rotationAngle * 0.35f) % 360f
        val diff = ((theta - spin + 540f) % 360f) - 180f
        if (kotlin.math.abs(diff) < 22f) return 0.9f
        // Tape pack body, with a brighter rim
        return if (dist >= tapeR - 0.5f) 0.55f else 0.3f
    }

    // Close-then-open aperture around the center row, with a hot edge.
    private fun applyShutter(t: Float) {
        applyAperture(if (t < 0.5f) (1f - 2f * t) * 13f else (2f * t - 1f) * 13f)
    }

    private fun applyAperture(half: Float) {
        for (y in 0 until MATRIX_DIM) {
            val dy = kotlin.math.abs(y - 12f)
            val rowStart = y * MATRIX_DIM
            if (dy > half) {
                for (x in 0 until MATRIX_DIM) outputBuffer[rowStart + x] = 0
            } else if (dy > half - 1.3f) {
                // the shutter edge burns bright as it travels
                for (x in 0 until MATRIX_DIM) {
                    val dx = x - 12f
                    val dyc = y - 12f
                    if (dx * dx + dyc * dyc <= ACTIVE_RADIUS_SQ) {
                        outputBuffer[rowStart + x] = MAX_BRIGHTNESS
                    }
                }
            }
        }
        // center line flash at the slam point
        if (half < 1.5f) {
            val rowStart = 12 * MATRIX_DIM
            for (x in 0 until MATRIX_DIM) {
                val dx = x - 12f
                if (dx * dx <= ACTIVE_RADIUS_SQ) outputBuffer[rowStart + x] = MAX_BRIGHTNESS
            }
        }
    }

    // Hours printed on the top shutter plate, minutes on the bottom one;
    // digits only show where their plate has already closed.
    private fun drawPlateClock(half: Float) {
        drawDigit(clockDigits[0], 9, 2, maxYExcl = 12f - half)
        drawDigit(clockDigits[1], 13, 2, maxYExcl = 12f - half)
        drawDigit(clockDigits[2], 9, 18, minYExcl = 12f + half)
        drawDigit(clockDigits[3], 13, 18, minYExcl = 12f + half)
    }

    // Quiet resident clock for the sleep screen, right of the ampelmann
    private fun drawIdleClock() {
        drawDigit(clockDigits[0], 15, 5, bright = MAX_BRIGHTNESS / 2)
        drawDigit(clockDigits[1], 19, 5, bright = MAX_BRIGHTNESS / 2)
        drawDigit(clockDigits[2], 15, 12, bright = MAX_BRIGHTNESS / 2)
        drawDigit(clockDigits[3], 19, 12, bright = MAX_BRIGHTNESS / 2)
    }

    private fun drawDigit(
        d: Int, left: Int, top: Int,
        bright: Int = MAX_BRIGHTNESS * 7 / 10,
        minYExcl: Float = -1f, maxYExcl: Float = 100f
    ) {
        val glyph = DIGIT_FONT[d]
        for (r in 0 until 5) {
            for (c in 0 until 3) {
                if (glyph and (1 shl (14 - (r * 3 + c))) != 0) {
                    val px = left + c
                    val py = top + r
                    if (py <= minYExcl || py >= maxYExcl) continue
                    val dx = px - CENTER_X; val dy = py - CENTER_Y
                    if (dx * dx + dy * dy <= ACTIVE_RADIUS_SQ) {
                        outputBuffer[py * MATRIX_DIM + px] = bright
                    }
                }
            }
        }
    }

    private val DIGIT_FONT = intArrayOf(
        0b111101101101111, 0b010110010010111, 0b111001111100111, 0b111001111001111,
        0b101101111001001, 0b111100111001111, 0b111100111101111, 0b111001001001001,
        0b111101111101111, 0b111101111001111
    )

    // --- Overlay Helpers ---
    
    // Draws a single VU segment at specific coords
    private fun drawVuSegment(x: Int, y: Int, index: Int, globalLevel: Float, isDramatic: Boolean) {
        val numSegments = 4
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
                    val idx = py * MATRIX_DIM + px
                    // Check mask? Legacy overlay usually ignores mask or is within it.
                    // Coordinates (20,17) etc are within the 12.5 radius?
                    // 20-12=8. 17-12=5. 64+25=89 < 156. Yes.
                    outputBuffer[idx] = value
                }
            }
        }
    }

    // Draws a Square with direct opacity mapping
    private fun drawSquare(xOpt: Int, yOpt: Int, size: Int, level: Float, isDramatic: Boolean) {
        // Brightness Map: Direct (0.0 - 1.0)
        // Clamp min to 0.1 for outline visibility
        val brightness = level.coerceAtLeast(0.1f).coerceAtMost(1.0f)
        val value = getBrightnessValue(brightness, isDramatic)
        
        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = xOpt + x
                val py = yOpt + y
                
                if (px < MATRIX_DIM && py < MATRIX_DIM) {
                    outputBuffer[py * MATRIX_DIM + px] = value
                }
            }
        }
    }
    
    // Classic comic-strip sleep Zs: each Z fades in one after another along a
    // diagonal trail from the ampelmann's head, then the whole train fades out.
    private fun drawZzz() {
        for (i in Z_TRAIL.indices) {
            val t = zzzPhase - i
            var a = (t / 0.5f).coerceIn(0f, 1f) // fade in over ~0.3s
            if (zzzPhase > Z_TRAIL.size) {
                a *= (ZZZ_CYCLE - zzzPhase).coerceIn(0f, 1f) // collective fade out
            }
            if (a <= 0f) continue
            val (zx, zy) = Z_TRAIL[i]
            drawZ(zx, zy, 3, 0.7f * a)
        }
    }

    // Draws a Z glyph: full top and bottom rows joined by the anti-diagonal.
    private fun drawZ(x0: Int, y0: Int, size: Int, brightness: Float) {
        val value = (brightness.coerceIn(0f, 1f) * MAX_BRIGHTNESS).toInt()
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (r != 0 && r != size - 1 && c != size - 1 - r) continue
                val px = x0 + c
                val py = y0 + r
                if (px !in 0 until MATRIX_DIM || py !in 0 until MATRIX_DIM) continue
                // Respect the circular hardware mask
                val dx = px - CENTER_X
                val dy = py - CENTER_Y
                if (dx * dx + dy * dy > ACTIVE_RADIUS_SQ) continue
                val idx = py * MATRIX_DIM + px
                outputBuffer[idx] = max(outputBuffer[idx], value)
            }
        }
    }

    private fun getBrightnessValue(brightness: Float, isDramatic: Boolean): Int {
        var b = brightness.coerceIn(0f, 1.0f)
        
        // DRAMATIC INVERSION
        // If Logic says "Drama", we INVERT the overlay too
        if (isDramatic) {
            b = 1.0f - b
        }
        
        return (b * MAX_BRIGHTNESS).toInt().coerceIn(0, MAX_BRIGHTNESS)
    }

    companion object {
        // Sleep animation: one beat per Z plus one for the fade-out
        private const val ZZZ_CYCLE = 5f
        // Diagonal trail up-right from the small ampelmann's head (all within the circle)
        private val Z_TRAIL = arrayOf(7 to 10, 9 to 7, 11 to 4, 13 to 1)

        // Small ampelmann placement (lower-left of the matrix)
        private const val AMPELMANN_OFFSET_X = 3
        private const val AMPELMANN_OFFSET_Y = 13
        private const val AMPELMANN_SMALL_DIM = 7
        private const val AMPELMANN_SCALE = 4

        /** The sleeping mascot's pixel mask, shared with the in-app UI. */
        fun ampelmannMask(): IntArray = AMPELMANN_SMALL

        // 4x box-downsampled copy of AMPELMANN_ALPHA, renormalized so the
        // brightest pixel matches the original mask's peak (~90).
        private val AMPELMANN_SMALL: IntArray by lazy {
            val small = IntArray(AMPELMANN_SMALL_DIM * AMPELMANN_SMALL_DIM)
            var maxV = 1
            for (sy in 0 until AMPELMANN_SMALL_DIM) {
                for (sx in 0 until AMPELMANN_SMALL_DIM) {
                    var sum = 0
                    var n = 0
                    for (dy in 0 until AMPELMANN_SCALE) {
                        for (dx in 0 until AMPELMANN_SCALE) {
                            val x = sx * AMPELMANN_SCALE + dx
                            val y = sy * AMPELMANN_SCALE + dy
                            if (x < MATRIX_DIM && y < MATRIX_DIM) {
                                sum += AMPELMANN_ALPHA[y * MATRIX_DIM + x]
                                n++
                            }
                        }
                    }
                    val v = sum / n
                    small[sy * AMPELMANN_SMALL_DIM + sx] = v
                    if (v > maxV) maxV = v
                }
            }
            for (i in small.indices) small[i] = small[i] * 90 / maxV
            small
        }

        // 25x25 grayscale mask for the Ampelmann figure (0..100)
        private val AMPELMANN_ALPHA = intArrayOf(
              0,   0,   0,   0,   0,   0,   1,   2,   0,   3,  38,  58,  63,  59,  41,   5,   0,   2,   1,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,   2,  62,  85,  78,  79,  78,  79,  63,   4,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   1,  19,  35,  54,  84,  80,  81,  75,  73,  74,  79,  57,  34,  20,   2,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   1,  22,  41,  78,  82,  77,  80,  81,  82,  82,  79,  81,  43,  24,   2,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,  47,  88,  78,  80,  83,  83,  80,  90,  53,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   1,   2,   9,  69,  91,  84,  81,  82,  89,  76,  12,   2,   2,   0,   0,   0,   0,   0,   0,
              0,   1,   1,   1,   1,   0,   0,   0,   0,   2,  54,  84,  82,  86,  57,   3,   0,   0,   0,   1,   1,   1,   1,   1,   0,
              0,   0,   0,   0,   0,   0,   0,   1,  22,  52,  72,  81,  80,  83,  76,  54,  21,   1,   0,   0,   0,   0,   0,   0,   0,
              5,  33,  27,  40,  59,  53,  60,  67,  83,  87,  83,  81,  79,  79,  84,  89,  82,  66,  61,  55,  56,  42,  27,  35,   4,
             52,  85,  80,  83,  85,  76,  79,  80,  77,  79,  81,  81,  79,  81,  80,  79,  80,  77,  80,  80,  81,  81,  81,  90,  57,
             56,  87,  84,  81,  83,  82,  78,  83,  80,  80,  83,  81,  80,  80,  80,  82,  81,  79,  79,  83,  80,  81,  80,  86,  62,
             10,  46,  40,  44,  67,  69,  69,  74,  77,  76,  81,  78,  78,  80,  81,  82,  83,  73,  68,  67,  65,  45,  40,  51,  11,
              0,   0,   0,   0,   0,   0,   3,   5,  36,  80,  78,  80,  79,  78,  75,  85,  41,   6,   3,   1,   0,   0,   0,   0,   0,
              0,   1,   1,   0,   0,   0,   0,   0,   4,  76,  79,  80,  83,  78,  78,  80,   6,   0,   0,   0,   0,   0,   1,   1,   1,
              0,   0,   0,   0,   0,   0,   0,   0,   1,  66,  85,  79,  79,  75,  82,  69,   1,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  58,  86,  81,  81,  78,  85,  62,   0,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  49,  86,  81,  82,  82,  88,  51,   0,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  40,  85,  81,  81,  82,  87,  41,   0,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  28,  84,  83,  80,  78,  88,  32,   0,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  21,  88,  82,  82,  80,  86,  23,   0,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   1,   0,  13,  84,  83,  82,  80,  81,  14,   0,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,   0,   6,  77,  84,  81,  84,  75,   7,   0,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,   0,   0,  69,  90,  79,  85,  66,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,   0,  24,  83,  91,  87,  90,  83,  24,   2,   1,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,   0,  30,  54,  65,  73,  66,  59,  29,   0,   0,   0,   0,   0,   0,   0,   0,   0
        )
    }
}
