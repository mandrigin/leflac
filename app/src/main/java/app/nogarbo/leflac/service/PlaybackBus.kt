package app.nogarbo.leflac.service

import app.nogarbo.leflac.data.MixSegmentHeat
import kotlinx.coroutines.flow.MutableStateFlow

data class MixHeatSnapshot(
    val mediaId: String? = null,
    val segments: List<MixSegmentHeat> = emptyList()
)

/**
 * Process-level playback signal bus, owned by the playback side
 * (AudioService feeds isPlaying straight from its player). Replaces the
 * UI-owned "TEMPORARY" VisualizerBus: signals survive UI process teardown
 * because the source of truth is the service that owns the player.
 */
object PlaybackBus {
    val spectrum = MutableStateFlow(SpectrumState())

    /** Effective future after current: manual priority plus generated rail. */
    val upNext = MutableStateFlow<List<UpNextEntry>>(emptyList())

    /** 0..1 while a mix is playing, negative otherwise. */
    val mixProgress = MutableStateFlow(-1f)

    /** Cue-bounded listening preference for the active long mix. */
    val mixHeat = MutableStateFlow(MixHeatSnapshot())

    /** The REAL transport state, straight from the player. */
    val isPlaying = MutableStateFlow(false)
}
