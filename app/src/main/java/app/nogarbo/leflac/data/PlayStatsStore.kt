package app.nogarbo.leflac.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlin.math.max
import kotlin.math.pow

/**
 * Play statistics, v2.
 *
 * Replaces the naive "flac_stats" prefs (raw all-time count keyed by
 * MediaStore URI) with per-track records carrying plays, skips and recency:
 *
 * - A play is credited at >= 50% listened OR >= 4 minutes, whichever comes
 *   first (the Last.fm scrobble rule) — the old 80% rule starved long songs.
 * - A skip (< 20% listened before moving on) is recorded as negative signal.
 * - "Hot" ranking decays exponentially with a 45-day half-life, so the
 *   favorites pool follows current taste instead of all-time history.
 * - Records remember the track's stable identity (title|size) so stats
 *   survive MediaStore rescans that reassign content IDs; [reconcile]
 *   re-keys orphaned records and migrates legacy v1 counts.
 */
object PlayStatsStore {

    data class Stats(
        val p: Int = 0,      // plays
        val s: Int = 0,      // skips
        val t: Long = 0L,    // last played at (epoch ms)
        val k: String? = null, // stable identity: "title|size"
        val pos: Long = 0L   // resume position (mixes)
    )

    private const val PREFS = "flac_stats_v2"
    private const val LEGACY_PREFS = "flac_stats"
    private const val HALF_LIFE_DAYS = 45.0
    private const val DAY_MS = 86_400_000.0

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stableKey(track: AudioTrack): String = "${track.title}|${track.size}"

    fun get(context: Context, uriKey: String): Stats {
        val json = prefs(context).getString(uriKey, null) ?: return Stats()
        return try { gson.fromJson(json, Stats::class.java) } catch (e: Exception) { Stats() }
    }

    private fun put(context: Context, uriKey: String, stats: Stats) {
        prefs(context).edit().putString(uriKey, gson.toJson(stats)).apply()
    }

    fun recordPlay(context: Context, uriKey: String, now: Long = System.currentTimeMillis()) {
        val cur = get(context, uriKey)
        put(context, uriKey, cur.copy(p = cur.p + 1, t = now))
        android.util.Log.i("FLAC_STATS", "PLAY $uriKey -> ${cur.p + 1} plays, ${cur.s} skips")
    }

    /** Resume position for long mixes; 0 clears it. */
    fun savePosition(context: Context, uriKey: String, positionMs: Long) {
        val cur = get(context, uriKey)
        if (cur.pos == positionMs) return
        put(context, uriKey, cur.copy(pos = positionMs))
    }

    fun getPosition(context: Context, uriKey: String): Long = get(context, uriKey).pos

    fun recordSkip(context: Context, uriKey: String) {
        val cur = get(context, uriKey)
        put(context, uriKey, cur.copy(s = cur.s + 1))
        android.util.Log.i("FLAC_STATS", "SKIP $uriKey -> ${cur.p} plays, ${cur.s + 1} skips")
    }

    /**
     * Recency-decayed preference score. Skips subtract half a play; the
     * whole thing halves every [HALF_LIFE_DAYS] since the last listen.
     */
    fun hotScore(stats: Stats, now: Long = System.currentTimeMillis()): Double {
        if (stats.p == 0) return 0.0
        val base = max(stats.p - 0.5 * stats.s, 0.0)
        val days = max(now - stats.t, 0L) / DAY_MS
        return base * 2.0.pow(-days / HALF_LIFE_DAYS)
    }

    fun hotScore(context: Context, uriKey: String, now: Long = System.currentTimeMillis()): Double =
        hotScore(get(context, uriKey), now)

    /**
     * The library's current "hot" tracks: top [limit] by decayed score,
     * requiring a minimum heat so one stray play doesn't light the flame.
     */
    fun hotTrackIds(
        context: Context,
        tracks: List<AudioTrack>,
        limit: Int = 12,
        minScore: Double = 1.5,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        return tracks
            .map { it.uri.toString() to hotScore(context, it.uri.toString(), now) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Run after each library scan:
     * 1. Migrate legacy v1 counts (raw int keyed by URI) into v2 records.
     * 2. Fill in stable identities for records that lack them.
     * 3. Re-key records whose URI vanished (MediaStore rescan) but whose
     *    stable identity matches a current track.
     */
    fun reconcile(context: Context, tracks: List<AudioTrack>) {
        val p = prefs(context)
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val byUri = tracks.associateBy { it.uri.toString() }
        val byStableKey = tracks.associateBy { stableKey(it) }
        val editor = p.edit()

        // 1. Legacy migration: credit old counts as half-decayed history.
        for ((key, value) in legacy.all) {
            val count = value as? Int ?: continue
            if (count <= 0 || !byUri.containsKey(key)) continue
            if (p.contains(key)) continue
            val migrated = Stats(p = count, t = now - (HALF_LIFE_DAYS * DAY_MS / 3).toLong(),
                                 k = byUri[key]?.let { stableKey(it) })
            editor.putString(key, gson.toJson(migrated))
        }
        editor.apply()

        // 2 & 3. Stable identity upkeep and orphan re-keying.
        val editor2 = p.edit()
        for ((uriKey, value) in p.all) {
            val json = value as? String ?: continue
            val stats = try { gson.fromJson(json, Stats::class.java) } catch (e: Exception) { continue }
            val track = byUri[uriKey]
            if (track != null) {
                if (stats.k == null) {
                    editor2.putString(uriKey, gson.toJson(stats.copy(k = stableKey(track))))
                }
            } else if (stats.k != null) {
                val moved = byStableKey[stats.k]
                if (moved != null) {
                    val newKey = moved.uri.toString()
                    if (!p.contains(newKey)) {
                        editor2.putString(newKey, json)
                        android.util.Log.i("FLAC_STATS", "Re-keyed stats ${stats.k} -> $newKey")
                    }
                    editor2.remove(uriKey)
                }
            }
        }
        editor2.apply()
    }
}
