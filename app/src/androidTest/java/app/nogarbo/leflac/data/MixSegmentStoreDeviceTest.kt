package app.nogarbo.leflac.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device persistence coverage for provisional-to-final mix heat migration. */
@RunWith(AndroidJUnit4::class)
class MixSegmentStoreDeviceTest {

    @Test
    fun provisionalListeningSurvivesFinalCueInstallAndReload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mediaId = "instrumentation://mix-heat-${System.nanoTime()}"
        val prefs = context.getSharedPreferences("flac_mix_segments_v1", android.content.Context.MODE_PRIVATE)
        try {
            val durationMs = 120_000L
            val provisionalCues = listOf(30_000L, 60_000L, 90_000L)
            val realCues = listOf(45_000L, 90_000L)

            val provisional = MixSegmentStore.updateCueMap(
                context,
                mediaId,
                durationMs,
                provisionalCues,
                isProvisional = true
            )!!
            assertTrue(provisional.isProvisional)

            MixSegmentStore.addListening(
                context,
                mediaId,
                durationMs,
                provisionalCues,
                mapOf(1 to 60_000L),
                nowEpochMs = 1L
            )
            val finalized = MixSegmentStore.updateCueMap(
                context,
                mediaId,
                durationMs,
                realCues
            )!!
            assertFalse(finalized.isProvisional)
            assertEquals(listOf(30_000L, 30_000L, 0L), finalized.listenedMs)

            MixSegmentStore.addListening(
                context,
                mediaId,
                durationMs,
                realCues,
                mapOf(1 to 90_000L),
                nowEpochMs = 2L
            )
            val reloaded = MixSegmentStore.get(context, mediaId)!!
            assertEquals(listOf(30_000L, 120_000L, 0L), reloaded.listenedMs)
            val heat = MixSegmentStore.heat(context, mediaId)
            assertTrue(heat[1].isHot)
            assertFalse(heat[0].isHot)
            assertFalse(heat[2].isHot)
        } finally {
            prefs.edit().remove(mediaId).commit()
        }
    }
}
