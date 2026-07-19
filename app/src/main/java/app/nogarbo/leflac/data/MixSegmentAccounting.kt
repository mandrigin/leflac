package app.nogarbo.leflac.data

import java.math.BigInteger

const val MIX_SEGMENT_SCHEMA_VERSION = 1
const val MIX_CUE_MAP_READY_FRACTION = 0.97f

/** Partial analysis must never replace the provisional position buckets. */
fun isMixCueMapReady(
    durationMs: Long,
    mixDurationThresholdMs: Long,
    analysisProgress: Float
): Boolean = durationMs >= mixDurationThresholdMs &&
    analysisProgress >= MIX_CUE_MAP_READY_FRACTION

/** A cue-bounded interval. Cue timestamps belong to the segment they start. */
data class MixSegmentBounds(
    val index: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Persistable, UI-independent listening totals for one mix. */
data class MixSegmentListeningRecord(
    val schemaVersion: Int = MIX_SEGMENT_SCHEMA_VERSION,
    val durationMs: Long,
    val cuePointsMs: List<Long>,
    val listenedMs: List<Long>,
    val updatedAtEpochMs: Long = 0L,
    /** Fixed position buckets used until analysis installs real cue bounds. */
    val isProvisional: Boolean = false
)

data class MixSegmentHeat(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val listenedMs: Long,
    /** Full-segment listening equivalents, e.g. 1.5 means 150% of its duration. */
    val segmentCoverage: Double,
    /** Full-mix listening equivalents represented by all mapped listening. */
    val mixCoverage: Double,
    /** Segment coverage divided by mix coverage. */
    val relativeHeat: Double,
    val isHot: Boolean
)

/**
 * A segment must have been substantially replayed, not merely encountered
 * during a partial pass through the mix. The relative checks also prevent a
 * uniformly replayed mix from declaring every segment hot.
 */
data class MixHeatPolicy(
    val minimumSegmentDurationMs: Long = 30_000L,
    val minimumSegmentCoverage: Double = 1.25,
    val minimumCoverageLead: Double = 0.50,
    val minimumRelativeCoverage: Double = 1.25
)

/** Remove duplicate, endpoint and out-of-range cue timestamps. */
fun sanitizeMixCuePoints(durationMs: Long, cuePointsMs: List<Long>): List<Long> {
    if (durationMs <= 0L) return emptyList()
    return cuePointsMs.asSequence()
        .filter { it > 0L && it < durationMs }
        .distinct()
        .sorted()
        .toList()
}

/** Turn cue starts into [0, cue...), ..., [last cue, duration] intervals. */
fun mixSegmentBounds(durationMs: Long, cuePointsMs: List<Long>): List<MixSegmentBounds> {
    if (durationMs <= 0L) return emptyList()
    val cues = sanitizeMixCuePoints(durationMs, cuePointsMs)
    val boundaries = buildList(cues.size + 2) {
        add(0L)
        addAll(cues)
        add(durationMs)
    }
    return List(boundaries.size - 1) { index ->
        MixSegmentBounds(index, boundaries[index], boundaries[index + 1])
    }
}

/**
 * Locate a position in the cue map. Exact cue timestamps select the segment
 * beginning at that cue; positions outside the file clamp to intro/tail.
 */
fun mixSegmentIndexAt(
    durationMs: Long,
    cuePointsMs: List<Long>,
    positionMs: Long
): Int {
    if (durationMs <= 0L) return -1
    val cues = sanitizeMixCuePoints(durationMs, cuePointsMs)
    val position = positionMs.coerceIn(0L, durationMs)
    var low = 0
    var high = cues.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (cues[middle] <= position) low = middle + 1 else high = middle
    }
    return low.coerceAtMost(cues.size)
}

/** Saturating total, defensive against a corrupt or decades-old record. */
fun totalMixSegmentListeningMs(record: MixSegmentListeningRecord): Long =
    record.listenedMs.fold(0L) { total, value ->
        saturatingAdd(total, value.coerceAtLeast(0L))
    }

/**
 * Align an existing record to a cue map. A changed map is rebinned by interval
 * overlap, preserving the listening total instead of silently discarding it.
 */
fun alignMixSegmentListening(
    record: MixSegmentListeningRecord?,
    durationMs: Long,
    cuePointsMs: List<Long>
): MixSegmentListeningRecord? {
    if (durationMs <= 0L) return null
    val cues = sanitizeMixCuePoints(durationMs, cuePointsMs)
    val targetCount = cues.size + 1
    if (record == null || record.durationMs <= 0L) {
        return MixSegmentListeningRecord(
            durationMs = durationMs,
            cuePointsMs = cues,
            listenedMs = List(targetCount) { 0L }
        )
    }

    val oldCues = sanitizeMixCuePoints(record.durationMs, record.cuePointsMs)
    val oldListening = normalizeListeningVector(record.listenedMs, oldCues.size + 1)
    val alignedListening = if (record.durationMs == durationMs && oldCues == cues) {
        normalizeListeningVector(oldListening, targetCount)
    } else {
        rebinMixSegmentListening(
            oldDurationMs = record.durationMs,
            oldCuePointsMs = oldCues,
            oldListenedMs = oldListening,
            newDurationMs = durationMs,
            newCuePointsMs = cues
        )
    }

    return MixSegmentListeningRecord(
        durationMs = durationMs,
        cuePointsMs = cues,
        listenedMs = alignedListening,
        updatedAtEpochMs = record.updatedAtEpochMs,
        isProvisional = record.isProvisional
    )
}

/**
 * Add a batch of service-owned deltas in one immutable update. Unknown indices
 * and non-positive deltas are ignored, making a pending Map<Int, Long> safe to
 * flush directly.
 */
fun addMixSegmentListening(
    record: MixSegmentListeningRecord?,
    durationMs: Long,
    cuePointsMs: List<Long>,
    deltasBySegment: Map<Int, Long>,
    nowEpochMs: Long = System.currentTimeMillis()
): MixSegmentListeningRecord? {
    val aligned = alignMixSegmentListening(record, durationMs, cuePointsMs) ?: return null
    val totals = aligned.listenedMs.toMutableList()
    var changed = false
    for ((index, delta) in deltasBySegment) {
        if (index !in totals.indices || delta <= 0L) continue
        totals[index] = saturatingAdd(totals[index], delta)
        changed = true
    }
    return aligned.copy(
        listenedMs = totals,
        updatedAtEpochMs = if (changed) nowEpochMs else aligned.updatedAtEpochMs
    )
}

/** Convenience for a single playback-accounting tick. */
fun addMixSegmentListeningAtPosition(
    record: MixSegmentListeningRecord?,
    durationMs: Long,
    cuePointsMs: List<Long>,
    positionMs: Long,
    listenedDeltaMs: Long,
    nowEpochMs: Long = System.currentTimeMillis()
): MixSegmentListeningRecord? {
    val index = mixSegmentIndexAt(durationMs, cuePointsMs, positionMs)
    val deltas = if (index >= 0 && listenedDeltaMs > 0L) {
        mapOf(index to listenedDeltaMs)
    } else {
        emptyMap()
    }
    return addMixSegmentListening(
        record = record,
        durationMs = durationMs,
        cuePointsMs = cuePointsMs,
        deltasBySegment = deltas,
        nowEpochMs = nowEpochMs
    )
}

/** Calculate normalized, cue-bounded heat without Android or persistence. */
fun calculateMixSegmentHeat(
    record: MixSegmentListeningRecord,
    policy: MixHeatPolicy = MixHeatPolicy()
): List<MixSegmentHeat> {
    val aligned = alignMixSegmentListening(
        record,
        record.durationMs,
        record.cuePointsMs
    ) ?: return emptyList()
    val bounds = mixSegmentBounds(aligned.durationMs, aligned.cuePointsMs)
    val total = totalMixSegmentListeningMs(aligned)
    val mixCoverage = total.toDouble() / aligned.durationMs.toDouble()

    return bounds.map { segment ->
        val listened = aligned.listenedMs.getOrElse(segment.index) { 0L }
        val coverage = if (segment.durationMs > 0L) {
            listened.toDouble() / segment.durationMs.toDouble()
        } else {
            0.0
        }
        val relative = if (mixCoverage > 0.0) coverage / mixCoverage else 0.0
        val hot = segment.durationMs >= policy.minimumSegmentDurationMs &&
            coverage >= policy.minimumSegmentCoverage &&
            coverage >= mixCoverage + policy.minimumCoverageLead &&
            coverage >= mixCoverage * policy.minimumRelativeCoverage
        MixSegmentHeat(
            index = segment.index,
            startMs = segment.startMs,
            endMs = segment.endMs,
            listenedMs = listened,
            segmentCoverage = coverage,
            mixCoverage = mixCoverage,
            relativeHeat = relative,
            isHot = hot
        )
    }
}

/**
 * Reallocate old cue-bounded totals according to interval overlap. Allocation
 * uses exact integer largest-remainder rounding, so the total is preserved.
 * If a changed duration clips an old interval, all of that interval's total is
 * still distributed over its remaining overlap (or the nearest new segment).
 */
fun rebinMixSegmentListening(
    oldDurationMs: Long,
    oldCuePointsMs: List<Long>,
    oldListenedMs: List<Long>,
    newDurationMs: Long,
    newCuePointsMs: List<Long>
): List<Long> {
    val newBounds = mixSegmentBounds(newDurationMs, newCuePointsMs)
    if (newBounds.isEmpty()) return emptyList()
    val result = MutableList(newBounds.size) { 0L }
    val oldBounds = mixSegmentBounds(oldDurationMs, oldCuePointsMs)
    if (oldBounds.isEmpty()) return result
    val listening = normalizeListeningVector(oldListenedMs, oldBounds.size)

    for (oldSegment in oldBounds) {
        val amount = listening[oldSegment.index]
        if (amount <= 0L) continue
        val weightedTargets = newBounds.mapNotNull { newSegment ->
            val overlap = minOf(oldSegment.endMs, newSegment.endMs) -
                maxOf(oldSegment.startMs, newSegment.startMs)
            if (overlap > 0L) newSegment.index to overlap else null
        }
        if (weightedTargets.isEmpty()) {
            val midpoint = oldSegment.startMs + oldSegment.durationMs / 2L
            val target = mixSegmentIndexAt(newDurationMs, newCuePointsMs, midpoint)
                .coerceIn(result.indices)
            result[target] = saturatingAdd(result[target], amount)
            continue
        }

        val allocations = allocateByWeight(amount, weightedTargets)
        for ((index, allocated) in allocations) {
            result[index] = saturatingAdd(result[index], allocated)
        }
    }
    return result
}

private fun normalizeListeningVector(values: List<Long>, expectedSize: Int): List<Long> {
    if (expectedSize <= 0) return emptyList()
    val normalized = MutableList(expectedSize) { 0L }
    for (index in values.indices) {
        val target = index.coerceAtMost(expectedSize - 1)
        normalized[target] = saturatingAdd(normalized[target], values[index].coerceAtLeast(0L))
    }
    return normalized
}

private data class ExactAllocation(
    val index: Int,
    val base: Long,
    val remainder: BigInteger
)

private fun allocateByWeight(
    amount: Long,
    weightedTargets: List<Pair<Int, Long>>
): List<Pair<Int, Long>> {
    if (amount <= 0L || weightedTargets.isEmpty()) return emptyList()
    val totalWeight = weightedTargets.fold(0L) { total, (_, weight) ->
        saturatingAdd(total, weight.coerceAtLeast(0L))
    }
    if (totalWeight <= 0L) return listOf(weightedTargets.first().first to amount)

    val amountBig = BigInteger.valueOf(amount)
    val totalBig = BigInteger.valueOf(totalWeight)
    val exact = weightedTargets.map { (index, weight) ->
        val divided = amountBig.multiply(BigInteger.valueOf(weight)).divideAndRemainder(totalBig)
        ExactAllocation(index, divided[0].toLong(), divided[1])
    }
    val values = exact.associate { it.index to it.base }.toMutableMap()
    val allocated = exact.fold(0L) { total, part -> saturatingAdd(total, part.base) }
    var remainderUnits = (amount - allocated).coerceAtLeast(0L)
    val remainderOrder = exact.sortedWith(
        compareByDescending<ExactAllocation> { it.remainder }.thenBy { it.index }
    )
    var cursor = 0
    while (remainderUnits > 0L && remainderOrder.isNotEmpty()) {
        val index = remainderOrder[cursor % remainderOrder.size].index
        values[index] = saturatingAdd(values.getValue(index), 1L)
        remainderUnits--
        cursor++
    }
    return weightedTargets.map { (index, _) -> index to values.getValue(index) }
}

private fun saturatingAdd(left: Long, right: Long): Long {
    if (right <= 0L) return left
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
