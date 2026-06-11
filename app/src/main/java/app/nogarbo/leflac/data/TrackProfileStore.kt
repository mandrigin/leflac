package app.nogarbo.leflac.data

import android.content.Context
import com.google.gson.Gson

/**
 * Per-track fitness profile, derived from the analysis cache:
 * mean energy, dynamics (P90-P25) and BPM from kick autocorrelation.
 * The gym insight: tracks the epic detector REJECTS as "flat and loud"
 * are exactly what you want under a barbell.
 */
object TrackProfileStore {

    data class Profile(
        val e: Float = 0f,   // mean energy 0..1
        val d: Float = 0f,   // dynamics P90-P25
        val bpm: Int = 0,    // 0 = undetected
        val k: Float = 0f    // mean kick density — drive, not just loudness
    )

    private const val PREFS = "flac_profiles_v3"
    private val gson = Gson()

    fun get(context: Context, uriKey: String): Profile? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(uriKey, null) ?: return null
        return try { gson.fromJson(json, Profile::class.java) } catch (e: Exception) { null }
    }

    fun put(context: Context, uriKey: String, p: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(uriKey, gson.toJson(p)).apply()
    }

    fun has(context: Context, uriKey: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(uriKey)

    /**
     * Relentlessness: high sustained energy, low dynamics. A ballad with a
     * big chorus scores low; a flat-loud banger scores high.
     */
    /**
     * CrossFit model: DRIVE (kick density first, loudness second) times
     * relentlessness, times tempo fit around the 140-185 working band
     * (driving half-time folds up). Mellow-but-loud no longer qualifies.
     */
    fun gymScore(p: Profile): Float {
        if (p.e < 0.25f) return 0f
        val drive = 0.6f * p.k + 0.4f * p.e
        val flatness = 1f - (p.d / 0.50f).coerceIn(0f, 1f)
        val bpm = if (p.bpm in 70..95) p.bpm * 2 else p.bpm
        val tempoFit = if (bpm <= 0) 0.7f else {
            val x = (bpm - 158f) / 45f
            kotlin.math.exp((-x * x).toDouble()).toFloat()
        }
        return drive * (0.35f + 0.65f * flatness) * (0.55f + 0.45f * tempoFit)
    }
}
