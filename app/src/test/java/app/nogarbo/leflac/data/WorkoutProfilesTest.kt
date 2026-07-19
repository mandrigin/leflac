package app.nogarbo.leflac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutProfilesTest {

    @Test
    fun workoutCommandsIncludeGriftAlias() {
        assertEquals(WorkoutMode.CARDIO, WorkoutMode.fromCommand("cardio"))
        assertEquals(WorkoutMode.WEIGHTS, WorkoutMode.fromCommand("weights"))
        assertEquals(WorkoutMode.GRIT, WorkoutMode.fromCommand("grit"))
        assertEquals(WorkoutMode.GRIT, WorkoutMode.fromCommand("grift"))
        assertEquals(WorkoutMode.STATIC, WorkoutMode.fromCommand("static"))
    }

    @Test
    fun eachPurposeBuiltProfilePrefersItsWorkoutMode() {
        assertBest(
            TrackProfileStore.Profile(
                e = 0.72f, d = 0.08f, bpm = 150, k = 0.62f,
                r = 0.95f, p = 0.92f, o = 0.75f, t = 0.48f,
                s = 0.94f, a = 0.48f, z = 0.02f, c = 1f
            ),
            WorkoutMode.CARDIO
        )
        assertBest(
            TrackProfileStore.Profile(
                e = 0.68f, d = 0.44f, bpm = 108, k = 1f,
                r = 0.50f, p = 0.78f, o = 0.58f, t = 1f,
                s = 0.45f, a = 0.74f, z = 0.04f, c = 1f
            ),
            WorkoutMode.WEIGHTS
        )
        assertBest(
            TrackProfileStore.Profile(
                e = 0.96f, d = 0.10f, bpm = 145, k = 0.82f,
                r = 0.58f, p = 0.72f, o = 0.86f, t = 0.75f,
                s = 0.94f, a = 1f, z = 0f, c = 1f
            ),
            WorkoutMode.GRIT
        )
        assertBest(
            TrackProfileStore.Profile(
                e = 0.46f, d = 0.03f, bpm = 96, k = 0.22f,
                r = 0.72f, p = 0.28f, o = 0.18f, t = 0.04f,
                s = 0.96f, a = 0.12f, z = 0.02f, c = 1f
            ),
            WorkoutMode.STATIC
        )
    }

    @Test
    fun personalAffinityCannotPromoteAudioBelowTheGate() {
        val unsuitable = TrackProfileStore.Profile(
            e = 0.05f,
            d = 0.01f,
            bpm = 0,
            k = 0.02f,
            c = 1f,
            z = 0.9f
        )
        val rating = WorkoutScorer.rate(unsuitable, WorkoutMode.WEIGHTS, affinity = 1f)

        assertFalse(WorkoutScorer.qualifies(rating))
        assertTrue(rating.personalizedScore > rating.audioFit)
    }

    @Test
    fun pulseTrainOutranksConstantSubForRhythmAndTransients() {
        val pulse = WorkoutProfileAnalyzer.analyze(frames { index ->
            if (index % 10 == 0) 1f else 0.08f
        })
        val constant = WorkoutProfileAnalyzer.analyze(frames { 0.72f })

        assertNotNull(pulse)
        assertNotNull(constant)
        pulse!!
        constant!!
        assertTrue("pulse contrast", pulse.p > constant.p)
        assertTrue("transient strength", pulse.t > constant.t)
        assertTrue("rhythm confidence", pulse.r > constant.r)
        assertTrue("tempo detected", pulse.bpm in 110..130)
    }

    @Test
    fun shortAnalysisIsRejectedAndAllFeaturesStayBounded() {
        assertNull(
            WorkoutProfileAnalyzer.analyze(
                List(40) { index -> WorkoutFrame(index * 50L, 1f, 1f, 1f, 0f, 0f) }
            )
        )

        val profile = WorkoutProfileAnalyzer.analyze(frames { index ->
            if (index % 8 == 0) Float.POSITIVE_INFINITY else -2f
        })!!
        listOf(
            profile.e, profile.d, profile.k, profile.r, profile.p, profile.o,
            profile.t, profile.s, profile.a, profile.z, profile.c
        ).forEach { value -> assertTrue(value.isFinite() && value in 0f..1f) }
    }

    private fun assertBest(profile: TrackProfileStore.Profile, expected: WorkoutMode) {
        val scores = WorkoutMode.entries.associateWith { mode ->
            WorkoutScorer.rate(profile, mode).audioFit
        }
        assertEquals(scores.toString(), expected, scores.maxBy { it.value }.key)
    }

    private fun frames(kickAt: (Int) -> Float): List<WorkoutFrame> =
        List(2_400) { index ->
            val kick = kickAt(index)
            WorkoutFrame(
                timestampMs = index * 50L,
                kick = kick,
                snare = if (index % 10 == 5) kick.coerceAtLeast(0.55f) else 0.12f,
                cymbals = if (index % 20 == 5) 0.55f else 0.10f,
                vocal = 0.18f,
                synth = 0.16f
            )
        }
}
