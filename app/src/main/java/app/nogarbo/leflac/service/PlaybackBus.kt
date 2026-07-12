package app.nogarbo.leflac.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-level playback signal bus, owned by the playback side
 * (AudioService feeds isPlaying straight from its player). Replaces the
 * UI-owned "TEMPORARY" VisualizerBus: signals survive UI process teardown
 * because the source of truth is the service that owns the player.
 */
object PlaybackBus {
    val spectrum = MutableStateFlow(SpectrumState())

    /** Explicit FIFO segment immediately after the current timeline item. */
    val upNext = MutableStateFlow<List<UpNextEntry>>(emptyList())

    /** 0..1 while a mix is playing, negative otherwise. */
    val mixProgress = MutableStateFlow(-1f)

    /** The REAL transport state, straight from the player. */
    val isPlaying = MutableStateFlow(false)
}
