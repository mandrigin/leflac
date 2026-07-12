package app.nogarbo.leflac.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAccountingTest {

    @Test
    fun songCreditsAtHalfListened() {
        assertEquals(PlaybackCredit.PLAY, playbackCredit(300_000L, 150_000L))
    }

    @Test
    fun longMixCreditsAtFourMinutes() {
        assertEquals(PlaybackCredit.PLAY, playbackCredit(7_200_000L, 240_000L))
    }

    @Test
    fun earlyExitCreditsSkip() {
        assertEquals(PlaybackCredit.SKIP, playbackCredit(300_000L, 30_000L))
    }

    @Test
    fun accidentalTapDoesNotCreditSkip() {
        assertEquals(PlaybackCredit.NONE, playbackCredit(300_000L, 1_999L))
    }

    @Test
    fun middleListenIsNeutral() {
        assertEquals(PlaybackCredit.NONE, playbackCredit(300_000L, 90_000L))
    }
}
