package app.nogarbo.leflac.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @Test
    fun `test Zero Time`() {
        val (main, micro) = TimeFormatter.formatCasioTime(0L)
        assertEquals("00:00", main)
        assertEquals("00", micro)
    }

    @Test
    fun `test Simple Time`() {
        // 1 min, 5 seconds, 500ms
        val millis = 65500L
        val (main, micro) = TimeFormatter.formatCasioTime(millis)
        assertEquals("01:05", main)
        assertEquals("50", micro)
    }

    @Test
    fun `test Microseconds Precision`() {
        // 123ms -> "12"
        val millis = 123L
        val (main, micro) = TimeFormatter.formatCasioTime(millis)
        assertEquals("00:00", main)
        assertEquals("12", micro)
    }
    
    @Test
    fun `test Negative Time (Edge Case)`() {
        val (main, micro) = TimeFormatter.formatCasioTime(-100L)
        assertEquals("00:00", main)
        assertEquals("00", micro)
    }
}
