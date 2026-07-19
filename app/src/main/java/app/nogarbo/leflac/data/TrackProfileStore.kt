package app.nogarbo.leflac.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-track fitness profile, derived from the analysis cache:
 * mean energy, dynamics (P90-P25) and BPM from kick autocorrelation.
 * The gym insight: tracks the epic detector REJECTS as "flat and loud"
 * are exactly what you want under a barbell.
 */
object TrackProfileStore {

    data class Profile(
        val e: Float = 0f,   // robust sustained intensity, 0..1
        val d: Float = 0f,   // dynamic contrast (P90-P25), 0..1
        val bpm: Int = 0,    // 0 = no trustworthy tempo
        val k: Float = 0f,   // low-end impact, 0..1
        val r: Float = 0f,   // rhythmic/tempo confidence, 0..1
        val p: Float = 0f,   // pulse contrast, 0..1
        val o: Float = 0f,   // onset density, 0..1
        val t: Float = 0f,   // transient strength, 0..1
        val s: Float = 0f,   // intensity stability, 0..1
        val a: Float = 0f,   // percussive aggression, 0..1
        val z: Float = 0f,   // silence/break fraction, 0..1
        val c: Float = 0f    // analysis coverage/confidence, 0..1
    )

    // v4 rebuilds profiles from the existing raw analysis cache. v3's FFT
    // aliasing and mean-amplitude "kick density" made results device-dependent.
    private const val PREFS = "flac_profiles_v4"
    private val gson = Gson()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    fun get(context: Context, uriKey: String): Profile? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(uriKey, null) ?: return null
        return try { gson.fromJson(json, Profile::class.java) } catch (e: Exception) { null }
    }

    fun put(context: Context, uriKey: String, p: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(uriKey, gson.toJson(p)).apply()
        _revision.update { it + 1L }
    }

    fun has(context: Context, uriKey: String): Boolean =
        get(context, uriKey) != null

    /** Compatibility score used by the small generic barbell badge. */
    fun gymScore(p: Profile): Float {
        return listOf(WorkoutMode.CARDIO, WorkoutMode.WEIGHTS, WorkoutMode.GRIT)
            .maxOf { WorkoutScorer.rate(p, it).audioFit }
    }
}
