package app.nogarbo.leflac.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * Thin SharedPreferences persistence for [MixSegmentListeningRecord].
 * Playback code can aggregate several one-second ticks by segment and flush
 * them with [addListening] in one JSON write.
 */
object MixSegmentStore {

    private const val PREFS = "flac_mix_segments_v1"
    private val gson = Gson()

    /** Nullable lists make malformed/older Gson payloads safe to normalize. */
    private data class StoredRecord(
        val v: Int = MIX_SEGMENT_SCHEMA_VERSION,
        val d: Long = 0L,
        val c: List<Long>? = emptyList(),
        val l: List<Long>? = emptyList(),
        val t: Long = 0L,
        val p: Boolean = false
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(context: Context, mediaId: String): MixSegmentListeningRecord? {
        if (mediaId.isBlank()) return null
        return readUnlocked(context, mediaId)
    }

    /** Install or refresh a cue map, rebinding existing totals when it moves. */
    @Synchronized
    fun updateCueMap(
        context: Context,
        mediaId: String,
        durationMs: Long,
        cuePointsMs: List<Long>,
        isProvisional: Boolean = false
    ): MixSegmentListeningRecord? {
        if (mediaId.isBlank()) return null
        val old = readUnlocked(context, mediaId)
        val aligned = alignMixSegmentListening(old, durationMs, cuePointsMs)
            ?.copy(isProvisional = isProvisional)
            ?: return null
        if (aligned != old) writeUnlocked(context, mediaId, aligned)
        return aligned
    }

    /**
     * Add an already-batched set of deltas and persist it atomically as one
     * preference value. Invalid indices/non-positive deltas are ignored.
     */
    @Synchronized
    fun addListening(
        context: Context,
        mediaId: String,
        durationMs: Long,
        cuePointsMs: List<Long>,
        deltasBySegment: Map<Int, Long>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): MixSegmentListeningRecord? {
        if (mediaId.isBlank()) return null
        val old = readUnlocked(context, mediaId)
        val updated = addMixSegmentListening(
            record = old,
            durationMs = durationMs,
            cuePointsMs = cuePointsMs,
            deltasBySegment = deltasBySegment,
            nowEpochMs = nowEpochMs
        ) ?: return null
        if (updated != old) writeUnlocked(context, mediaId, updated)
        return updated
    }

    /** Convenience for callers that do not maintain their own pending batch. */
    @Synchronized
    fun addListeningAtPosition(
        context: Context,
        mediaId: String,
        durationMs: Long,
        cuePointsMs: List<Long>,
        positionMs: Long,
        listenedDeltaMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): MixSegmentListeningRecord? {
        if (mediaId.isBlank()) return null
        val old = readUnlocked(context, mediaId)
        val updated = addMixSegmentListeningAtPosition(
            record = old,
            durationMs = durationMs,
            cuePointsMs = cuePointsMs,
            positionMs = positionMs,
            listenedDeltaMs = listenedDeltaMs,
            nowEpochMs = nowEpochMs
        ) ?: return null
        if (updated != old) writeUnlocked(context, mediaId, updated)
        return updated
    }

    @Synchronized
    fun heat(
        context: Context,
        mediaId: String,
        policy: MixHeatPolicy = MixHeatPolicy()
    ): List<MixSegmentHeat> =
        get(context, mediaId)?.let { calculateMixSegmentHeat(it, policy) }.orEmpty()

    private fun readUnlocked(context: Context, mediaId: String): MixSegmentListeningRecord? {
        val json = prefs(context).getString(mediaId, null) ?: return null
        return try {
            val stored = gson.fromJson(json, StoredRecord::class.java) ?: return null
            val decoded = MixSegmentListeningRecord(
                schemaVersion = stored.v,
                durationMs = stored.d,
                cuePointsMs = stored.c.orEmpty(),
                listenedMs = stored.l.orEmpty(),
                updatedAtEpochMs = stored.t,
                isProvisional = stored.p
            )
            alignMixSegmentListening(decoded, decoded.durationMs, decoded.cuePointsMs)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(
        context: Context,
        mediaId: String,
        record: MixSegmentListeningRecord
    ) {
        val stored = StoredRecord(
            v = MIX_SEGMENT_SCHEMA_VERSION,
            d = record.durationMs,
            c = record.cuePointsMs,
            l = record.listenedMs,
            t = record.updatedAtEpochMs,
            p = record.isProvisional
        )
        prefs(context).edit().putString(mediaId, gson.toJson(stored)).apply()
    }
}
