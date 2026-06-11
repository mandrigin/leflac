package app.nogarbo.leflac.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shutter/hold state machine has eaten three field bugs; pin it down. */
class GlyphShutterTest {

    private fun litCount(frame: IntArray) = frame.count { it > 0 }

    private fun step(e: PhoneThreeGlyphEngine, playing: Boolean, ms: Long = 66): IntArray =
        e.update(SpectrumState(), playing, ms)

    @Test
    fun `hold completes once and only once`() {
        val e = PhoneThreeGlyphEngine()
        step(e, true)
        e.beginHold(12, 34)
        repeat(25) { step(e, true) } // 25*66ms > 1.2s charge
        assertTrue(e.consumeHoldCompleted())
        assertFalse(e.consumeHoldCompleted())
    }

    @Test
    fun `early release never completes`() {
        val e = PhoneThreeGlyphEngine()
        step(e, true)
        e.beginHold(12, 34)
        repeat(5) { step(e, true) } // ~0.33s, well under charge
        e.endHold()
        repeat(30) { step(e, true) }
        assertFalse(e.consumeHoldCompleted())
    }

    @Test
    fun `latch keeps plates shut until the transport flips`() {
        val e = PhoneThreeGlyphEngine()
        repeat(3) { step(e, true) }
        e.beginHold(12, 34)
        repeat(25) { step(e, true) }
        assertTrue(e.consumeHoldCompleted())
        // still "playing" (flip not landed): the band between the clock
        // plates and the center line must be plate-dark (no turntable ring)
        val latched = step(e, true)
        val bandLit = (8..10).sumOf { y -> (0 until 25).count { x -> latched[y * 25 + x] > 0 } }
        assertTrue("plates must mask the old state, band lit=$bandLit", bandLit == 0)
        // flip lands: plates reopen over the next half second
        repeat(10) { step(e, false) }
        val open = step(e, false)
        assertTrue("idle scene should be visible after reveal", litCount(open) > 40)
    }

    @Test
    fun `latch times out and reopens if the flip never lands`() {
        val e = PhoneThreeGlyphEngine()
        repeat(3) { step(e, true) }
        e.beginHold(12, 34)
        repeat(25) { step(e, true) }
        e.consumeHoldCompleted()
        repeat(40) { step(e, true) } // > 1.2s timeout + reopen
        val frame = step(e, true)
        assertTrue("matrix must not stay stuck shut", litCount(frame) > 40)
    }
}
