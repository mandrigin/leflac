package app.nogarbo.leflac.data

import android.content.Context

data class WorkoutCandidate(
    val track: AudioTrack,
    val rating: WorkoutRating
)

/**
 * One catalog shared by search and playback, so the set shown on the phone is
 * exactly the set a workout session is allowed to play.
 */
fun workoutCandidates(
    context: Context,
    tracks: List<AudioTrack>,
    mode: WorkoutMode,
    now: Long = System.currentTimeMillis()
): List<WorkoutCandidate> = tracks.mapNotNull { track ->
    val mediaId = track.uri.toString()
    val profile = TrackProfileStore.get(context, mediaId) ?: return@mapNotNull null
    val affinity = (PlayStatsStore.hotScore(context, mediaId, now) / 6.0)
        .coerceIn(0.0, 1.0)
        .toFloat()
    val rating = WorkoutScorer.rate(profile, mode, affinity)
    if (WorkoutScorer.qualifies(rating)) WorkoutCandidate(track, rating) else null
}.sortedWith(
    compareByDescending<WorkoutCandidate> { it.rating.personalizedScore }
        .thenByDescending { it.rating.audioFit }
        .thenBy { it.track.uri.toString() }
)
