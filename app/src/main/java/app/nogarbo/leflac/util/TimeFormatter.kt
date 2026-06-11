package app.nogarbo.leflac.util

import java.util.concurrent.TimeUnit

object TimeFormatter {
    fun formatCasioTime(millis: Long): Pair<String, String> {
        if (millis < 0) return "00:00" to "00"
        
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        val hundredths = (millis % 1000) / 10
        
        val mainTime = "%02d:%02d".format(minutes, seconds)
        val micros = "%02d".format(hundredths)
        
        return mainTime to micros
    }
}
