package app.nogarbo.leflac.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

enum class WorkoutMode(val displayName: String, val shortCode: String) {
    CARDIO("CARDIO", "C"),
    WEIGHTS("WEIGHTS", "W"),
    GRIT("GRIT", "G"),
    STATIC("STATIC", "S");

    companion object {
        fun fromCommand(command: String?): WorkoutMode? = when (command?.lowercase()) {
            "cardio" -> CARDIO
            "weights", "weight" -> WEIGHTS
            "grit", "grift" -> GRIT
            "static" -> STATIC
            else -> null
        }
    }
}

data class WorkoutRating(
    val mode: WorkoutMode,
    val audioFit: Float,
    val confidence: Float,
    val personalizedScore: Float
)

/** One normalized observation from the cached spectral timeline. */
data class WorkoutFrame(
    val timestampMs: Long,
    val kick: Float,
    val snare: Float,
    val cymbals: Float,
    val vocal: Float,
    val synth: Float
)

/**
 * Activity-specific rating. Personal listening history is deliberately a
 * small tie-breaker: liking a ballad must not make it cardio by accident.
 */
object WorkoutScorer {
    fun minimumFit(mode: WorkoutMode): Float = when (mode) {
        WorkoutMode.CARDIO -> 0.50f
        WorkoutMode.WEIGHTS -> 0.48f
        WorkoutMode.GRIT -> 0.52f
        WorkoutMode.STATIC -> 0.56f
    }

    fun rate(
        profile: TrackProfileStore.Profile,
        mode: WorkoutMode,
        affinity: Float = 0f
    ): WorkoutRating {
        val tempo = tempoFit(profile.bpm, mode)
        val sustained = profile.e * (0.55f + 0.45f * profile.s)
        val contrast = (profile.d / 0.45f).coerceIn(0f, 1f)
        val moderateIntensity = (1f - abs(profile.e - 0.48f) / 0.48f).coerceIn(0f, 1f)
        var fit = when (mode) {
            WorkoutMode.CARDIO ->
                0.24f * tempo +
                    0.18f * profile.r +
                    0.18f * profile.p +
                    0.18f * sustained +
                    0.12f * profile.s +
                    0.10f * (1f - profile.z)

            WorkoutMode.WEIGHTS ->
                0.24f * profile.k +
                    0.20f * profile.t +
                    0.18f * profile.a +
                    0.15f * sustained +
                    0.12f * contrast +
                    0.07f * profile.p +
                    0.04f * tempo

            WorkoutMode.GRIT ->
                0.28f * profile.a +
                    0.25f * sustained +
                    0.16f * profile.k +
                    0.12f * profile.t +
                    0.10f * profile.s +
                    0.05f * profile.p +
                    0.04f * tempo

            WorkoutMode.STATIC ->
                0.30f * profile.s +
                    0.24f * moderateIntensity +
                    0.18f * (1f - profile.t) +
                    0.12f * tempo +
                    0.10f * (1f - profile.z) +
                    0.06f * (1f - profile.a)
        }

        fit -= when (mode) {
            WorkoutMode.CARDIO -> 0.08f * profile.z + 0.05f * contrast
            WorkoutMode.WEIGHTS -> 0.18f * profile.z
            WorkoutMode.GRIT -> 0.22f * profile.z
            WorkoutMode.STATIC -> 0.12f * profile.z
        }

        // Mode-specific evidence gates suppress loud-but-featureless masters
        // without turning unknown tempo into an automatic rejection.
        fit *= when (mode) {
            WorkoutMode.CARDIO -> (0.72f + 0.28f * max(profile.r, profile.p))
            WorkoutMode.WEIGHTS -> (0.68f + 0.32f * max(profile.k, profile.t))
            WorkoutMode.GRIT -> (0.68f + 0.32f * max(profile.a, profile.e))
            WorkoutMode.STATIC -> if (profile.e in 0.10f..0.82f) 1f else 0.72f
        }
        fit = fit.coerceIn(0f, 1f)

        val confidence = when (mode) {
            WorkoutMode.CARDIO -> profile.c * (0.55f + 0.45f * profile.r)
            WorkoutMode.WEIGHTS, WorkoutMode.GRIT ->
                profile.c * (0.70f + 0.30f * max(profile.p, profile.t))
            WorkoutMode.STATIC -> profile.c
        }.coerceIn(0f, 1f)

        return WorkoutRating(
            mode = mode,
            audioFit = fit,
            confidence = confidence,
            personalizedScore = (0.92f * fit + 0.08f * affinity.coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
        )
    }

    fun qualifies(rating: WorkoutRating): Boolean =
        rating.audioFit >= minimumFit(rating.mode) && rating.confidence >= 0.35f

    internal fun tempoFit(bpm: Int, mode: WorkoutMode): Float {
        if (bpm <= 0) return when (mode) {
            WorkoutMode.CARDIO -> 0.35f
            WorkoutMode.WEIGHTS, WorkoutMode.GRIT -> 0.55f
            WorkoutMode.STATIC -> 0.65f
        }
        val center = when (mode) {
            WorkoutMode.CARDIO -> 148f
            WorkoutMode.WEIGHTS -> 122f
            WorkoutMode.GRIT -> 142f
            WorkoutMode.STATIC -> 102f
        }
        val width = when (mode) {
            WorkoutMode.CARDIO -> 42f
            WorkoutMode.WEIGHTS -> 50f
            WorkoutMode.GRIT -> 58f
            WorkoutMode.STATIC -> 35f
        }
        val x = (bpm - center) / width
        return exp((-x * x).toDouble()).toFloat()
    }
}

/** Builds a stable workout profile from the raw cached spectral pass. */
object WorkoutProfileAnalyzer {
    private const val STEP_MS = 50L

    fun analyze(input: List<WorkoutFrame>): TrackProfileStore.Profile? {
        if (input.size < 100) return null
        val frames = input
            .filter { it.timestampMs >= 0L }
            .sortedBy(WorkoutFrame::timestampMs)
        if (frames.size < 100) return null
        val startMs = frames.first().timestampMs
        val durationMs = frames.last().timestampMs - startMs
        if (durationMs < 30_000L) return null

        val count = (durationMs / STEP_MS).toInt().coerceIn(1, 12_000)
        val kick = FloatArray(count)
        val snare = FloatArray(count)
        val cymbals = FloatArray(count)
        val vocal = FloatArray(count)
        val synth = FloatArray(count)
        var source = 0
        for (i in 0 until count) {
            val timestamp = startMs + i * STEP_MS
            while (source < frames.lastIndex && frames[source + 1].timestampMs <= timestamp) source++
            val frame = frames[source]
            kick[i] = frame.kick.unit()
            snare[i] = frame.snare.unit()
            cymbals[i] = frame.cymbals.unit()
            vocal[i] = frame.vocal.unit()
            synth[i] = frame.synth.unit()
        }

        // Ignore only the outer 3%; this removes fades without erasing a short
        // song's actual structure.
        val trim = (count * 0.03f).toInt()
        val from = trim.coerceAtMost(count - 1)
        val to = (count - trim).coerceAtLeast(from + 1)
        val energy = FloatArray(to - from)
        val percussion = FloatArray(to - from)
        val groove = FloatArray(to - from)
        for (i in from until to) {
            val out = i - from
            energy[out] = (
                0.36f * kick[i] + 0.32f * snare[i] + 0.18f * cymbals[i] +
                    0.07f * vocal[i] + 0.07f * synth[i]
                ).coerceIn(0f, 1f)
            percussion[out] = ((kick[i] + snare[i] + cymbals[i]) / 3f).coerceIn(0f, 1f)
            groove[out] = (0.68f * kick[i] + 0.32f * snare[i]).coerceIn(0f, 1f)
        }

        val sortedEnergy = energy.sortedArray()
        val p25 = percentile(sortedEnergy, 0.25f)
        val p50 = percentile(sortedEnergy, 0.50f)
        val p70 = percentile(sortedEnergy, 0.70f)
        val p90 = percentile(sortedEnergy, 0.90f)
        val dynamics = (p90 - p25).coerceIn(0f, 1f)
        val stability = (1f - dynamics / max(p90, 0.15f)).coerceIn(0f, 1f)

        val sortedKick = kick.copyOfRange(from, to).also(FloatArray::sort)
        // A clean four-on-the-floor hit may occupy only ~10% of 50 ms
        // samples, so use the upper tail rather than a mean/85th percentile.
        val upperKick = percentile(sortedKick, 0.92f)
        val lowEndImpact = upperKick
        val pulseContrast = (
            (upperKick - percentile(sortedKick, 0.25f)) /
                max(upperKick, 0.15f)
            ).coerceIn(0f, 1f)

        val onset = FloatArray(groove.size)
        for (i in groove.indices) {
            val historyStart = (i - 6).coerceAtLeast(0)
            var local = 0f
            for (j in historyStart until i) local += groove[j]
            val divisor = (i - historyStart).coerceAtLeast(1)
            onset[i] = (groove[i] - local / divisor).coerceAtLeast(0f)
        }
        val sortedOnset = onset.clone().also(FloatArray::sort)
        val transient = (percentile(sortedOnset, 0.92f) * 2.5f).coerceIn(0f, 1f)
        val onsetThreshold = max(0.035f, percentile(sortedOnset, 0.75f))
        val onsetDensity = (onset.count { it >= onsetThreshold }.toFloat() /
            max(onset.size, 1) / 0.18f).coerceIn(0f, 1f)

        val tonal = FloatArray(to - from) { i ->
            (vocal[i + from] + synth[i + from]) / 2f
        }
        val perc70 = percentile(percussion.sortedArray(), 0.70f)
        val tonal70 = percentile(tonal.sortedArray(), 0.70f)
        val percussiveBalance = perc70 / max(perc70 + tonal70, 0.05f)
        val aggression = (perc70 * (0.65f + 0.35f * percussiveBalance)).coerceIn(0f, 1f)
        val silenceGate = max(0.08f, p50 * 0.35f)
        val silenceFraction = energy.count { it < silenceGate }.toFloat() / max(energy.size, 1)
        val tempo = estimateTempo(onset)
        val coverage = ((durationMs / 120_000f).coerceIn(0f, 1f) *
            (frames.size / 600f).coerceIn(0f, 1f)).let { sqrt(it) }

        return TrackProfileStore.Profile(
            e = p70,
            d = dynamics,
            bpm = tempo.first,
            k = lowEndImpact,
            r = tempo.second,
            p = pulseContrast,
            o = onsetDensity,
            t = transient,
            s = stability,
            a = aggression,
            z = silenceFraction.coerceIn(0f, 1f),
            c = coverage.coerceIn(0f, 1f)
        )
    }

    private fun estimateTempo(onset: FloatArray): Pair<Int, Float> {
        if (onset.size < 600) return 0 to 0f
        val mean = onset.average().toFloat()
        val centered = FloatArray(onset.size) { onset[it] - mean }
        val minLag = (60_000f / 200f / STEP_MS).toInt()
        val maxLag = (60_000f / 60f / STEP_MS).toInt()
        val correlations = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0f
            var left = 0f
            var right = 0f
            for (i in 0 until centered.size - lag) {
                val a = centered[i]
                val b = centered[i + lag]
                dot += a * b
                left += a * a
                right += b * b
            }
            correlations[lag] = if (left > 1e-6f && right > 1e-6f) {
                (dot / sqrt(left * right)).coerceIn(-1f, 1f)
            } else 0f
        }

        val positive = correlations.copyOfRange(minLag, maxLag + 1)
            .filter { it > 0f }
            .sorted()
        val floor = positive.getOrElse(positive.size / 2) { 0f }
        var bestLag = 0
        var bestScore = 0f
        for (lag in minLag + 1 until maxLag) {
            if (correlations[lag] < correlations[lag - 1] ||
                correlations[lag] < correlations[lag + 1]
            ) continue
            val harmonic = if (lag * 2 <= maxLag) correlations[lag * 2].coerceAtLeast(0f) else 0f
            val score = correlations[lag] + 0.15f * harmonic
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0) return 0 to 0f
        var bpm = (60_000f / (bestLag * STEP_MS)).toInt()
        while (bpm < 90) bpm *= 2
        val confidence = ((bestScore - floor) * 2.2f).coerceIn(0f, 1f)
        return if (confidence < 0.12f) 0 to confidence else bpm to confidence
    }

    private fun percentile(sorted: FloatArray, quantile: Float): Float {
        if (sorted.isEmpty()) return 0f
        return sorted[((sorted.lastIndex) * quantile.coerceIn(0f, 1f)).toInt()]
    }

    private fun Float.unit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
}
