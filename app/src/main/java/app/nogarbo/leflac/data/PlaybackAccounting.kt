package app.nogarbo.leflac.data

enum class PlaybackCredit {
    NONE,
    PLAY,
    SKIP
}

/** Shared service-owned play/skip rule, kept pure for regression tests. */
fun playbackCredit(durationMs: Long, listenedMs: Long): PlaybackCredit {
    if (durationMs <= 0L || listenedMs < 0L) return PlaybackCredit.NONE
    val playThreshold = minOf(durationMs / 2L, 240_000L)
    return when {
        listenedMs >= playThreshold -> PlaybackCredit.PLAY
        listenedMs in 2_000L until (durationMs / 5L) -> PlaybackCredit.SKIP
        else -> PlaybackCredit.NONE
    }
}
