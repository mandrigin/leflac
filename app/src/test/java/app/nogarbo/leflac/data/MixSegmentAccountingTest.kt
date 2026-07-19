package app.nogarbo.leflac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixSegmentAccountingTest {

    @Test
    fun partialMixAnalysisCannotFinalizeTheCueMap() {
        val mixThreshold = 1_200_000L

        assertFalse(isMixCueMapReady(3_600_000L, mixThreshold, 0.50f))
        assertFalse(isMixCueMapReady(3_600_000L, mixThreshold, 0.969f))
        assertTrue(isMixCueMapReady(3_600_000L, mixThreshold, 0.97f))
        assertFalse(isMixCueMapReady(240_000L, mixThreshold, 1f))
    }

    @Test
    fun cueSanitizationRemovesDuplicatesEndpointsAndInvalidValues() {
        assertEquals(
            listOf(100_000L, 200_000L),
            sanitizeMixCuePoints(
                durationMs = 300_000L,
                cuePointsMs = listOf(
                    300_000L,
                    200_000L,
                    -1L,
                    100_000L,
                    0L,
                    200_000L,
                    400_000L
                )
            )
        )
    }

    @Test
    fun boundsIncludeIntroAndTailAndExactCueStartsNextSegment() {
        val cues = listOf(100_000L, 200_000L)
        assertEquals(
            listOf(
                MixSegmentBounds(0, 0L, 100_000L),
                MixSegmentBounds(1, 100_000L, 200_000L),
                MixSegmentBounds(2, 200_000L, 300_000L)
            ),
            mixSegmentBounds(300_000L, cues)
        )

        assertEquals(0, mixSegmentIndexAt(300_000L, cues, -50L))
        assertEquals(0, mixSegmentIndexAt(300_000L, cues, 99_999L))
        assertEquals(1, mixSegmentIndexAt(300_000L, cues, 100_000L))
        assertEquals(2, mixSegmentIndexAt(300_000L, cues, 200_000L))
        assertEquals(2, mixSegmentIndexAt(300_000L, cues, 300_000L))
        assertEquals(2, mixSegmentIndexAt(300_000L, cues, 999_000L))
        assertEquals(-1, mixSegmentIndexAt(0L, cues, 0L))
    }

    @Test
    fun oneUniformFullPassDoesNotMakeAnySegmentHot() {
        val record = MixSegmentListeningRecord(
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            listenedMs = listOf(100_000L, 100_000L, 100_000L)
        )

        val heat = calculateMixSegmentHeat(record)

        assertEquals(3, heat.size)
        assertTrue(heat.all { it.segmentCoverage == 1.0 })
        assertTrue(heat.all { it.mixCoverage == 1.0 })
        assertTrue(heat.none { it.isHot })
    }

    @Test
    fun replayedSegmentBecomesHotRelativeToTheMix() {
        val record = MixSegmentListeningRecord(
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            // One complete mix pass, then replay the middle cue once.
            listenedMs = listOf(100_000L, 200_000L, 100_000L)
        )

        val heat = calculateMixSegmentHeat(record)

        assertFalse(heat[0].isHot)
        assertTrue(heat[1].isHot)
        assertEquals(2.0, heat[1].segmentCoverage, 0.0001)
        assertEquals(4.0 / 3.0, heat[1].mixCoverage, 0.0001)
        assertFalse(heat[2].isHot)
    }

    @Test
    fun deliberatelyTargetedSegmentCanBecomeHotWithoutAWholeMixPass() {
        val record = MixSegmentListeningRecord(
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            listenedMs = listOf(0L, 150_000L, 0L)
        )

        val heat = calculateMixSegmentHeat(record)

        assertTrue(heat[1].isHot)
        assertEquals(1.5, heat[1].segmentCoverage, 0.0001)
        assertEquals(0.5, heat[1].mixCoverage, 0.0001)
    }

    @Test
    fun partialSequentialBrowsingDoesNotMistakeProgressForPreference() {
        val record = MixSegmentListeningRecord(
            durationMs = 400_000L,
            cuePointsMs = listOf(100_000L, 200_000L, 300_000L),
            // The listener simply stopped halfway through their first pass.
            listenedMs = listOf(100_000L, 100_000L, 0L, 0L)
        )

        val heat = calculateMixSegmentHeat(record)

        assertEquals(0.5, heat.first().mixCoverage, 0.0001)
        assertTrue(heat.none { it.isHot })
    }

    @Test
    fun batchedAddsIgnoreBadDeltasAndUpdateOnce() {
        val updated = addMixSegmentListening(
            record = null,
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            deltasBySegment = mapOf(0 to 1_000L, 1 to -5L, 2 to 2_000L, 8 to 9_000L),
            nowEpochMs = 1234L
        )!!

        assertEquals(listOf(1_000L, 0L, 2_000L), updated.listenedMs)
        assertEquals(3_000L, totalMixSegmentListeningMs(updated))
        assertEquals(1234L, updated.updatedAtEpochMs)
    }

    @Test
    fun positionAddUsesExactCueBoundaryRules() {
        val updated = addMixSegmentListeningAtPosition(
            record = null,
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            positionMs = 100_000L,
            listenedDeltaMs = 1_000L,
            nowEpochMs = 99L
        )!!

        assertEquals(listOf(0L, 1_000L, 0L), updated.listenedMs)
    }

    @Test
    fun cueMapRebinUsesIntervalOverlapAndPreservesTotal() {
        val rebinned = rebinMixSegmentListening(
            oldDurationMs = 300_000L,
            oldCuePointsMs = listOf(100_000L, 200_000L),
            oldListenedMs = listOf(1_000L, 2_001L, 3_000L),
            newDurationMs = 300_000L,
            newCuePointsMs = listOf(150_000L)
        )

        // Middle segment splits equally; the deterministic largest remainder
        // gives its odd millisecond to the earlier segment.
        assertEquals(listOf(2_001L, 4_000L), rebinned)
        assertEquals(6_001L, rebinned.sum())
    }

    @Test
    fun cueMapRebinPreservesListeningWhenNewDurationClipsOldTail() {
        val rebinned = rebinMixSegmentListening(
            oldDurationMs = 300_000L,
            oldCuePointsMs = listOf(100_000L, 200_000L),
            oldListenedMs = listOf(1_000L, 2_000L, 3_000L),
            newDurationMs = 250_000L,
            newCuePointsMs = listOf(125_000L)
        )

        assertEquals(6_000L, rebinned.sum())
        assertEquals(2, rebinned.size)
    }

    @Test
    fun alignmentRebinsChangedCueMapAndNormalizesCorruptVector() {
        val old = MixSegmentListeningRecord(
            durationMs = 300_000L,
            cuePointsMs = listOf(100_000L, 200_000L),
            // An extra persisted cell is folded into the old tail.
            listenedMs = listOf(1_000L, 2_000L, 3_000L, 4_000L),
            updatedAtEpochMs = 42L
        )

        val aligned = alignMixSegmentListening(
            old,
            durationMs = 300_000L,
            cuePointsMs = listOf(150_000L)
        )!!

        assertEquals(listOf(2_000L, 8_000L), aligned.listenedMs)
        assertEquals(10_000L, totalMixSegmentListeningMs(aligned))
        assertEquals(42L, aligned.updatedAtEpochMs)
        assertEquals(MIX_SEGMENT_SCHEMA_VERSION, aligned.schemaVersion)
    }

    @Test
    fun alignmentPreservesProvisionalPositionBucketsUntilRealCuesArrive() {
        val provisional = MixSegmentListeningRecord(
            durationMs = 120_000L,
            cuePointsMs = listOf(30_000L, 60_000L, 90_000L),
            listenedMs = listOf(0L, 30_000L, 0L, 0L),
            isProvisional = true
        )

        val aligned = alignMixSegmentListening(
            provisional,
            durationMs = 120_000L,
            cuePointsMs = listOf(45_000L, 90_000L)
        )!!

        assertTrue(aligned.isProvisional)
        assertEquals(30_000L, totalMixSegmentListeningMs(aligned))
        // The listened 30-60s bucket overlaps the first real segment for 15s
        // and the second for 15s instead of being smeared across the whole mix.
        assertEquals(listOf(15_000L, 15_000L, 0L), aligned.listenedMs)
    }
}
