package app.nogarbo.leflac.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nogarbo.leflac.service.AudioService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import app.nogarbo.leflac.R
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _mediaMetadata = MutableStateFlow(MediaMetadata.EMPTY)
    val mediaMetadata = _mediaMetadata.asStateFlow()

    private val prefs = application.getSharedPreferences("flac_prefs", android.content.Context.MODE_PRIVATE)

    private val _isShuffleEnabled = MutableStateFlow(prefs.getBoolean("is_shuffle", false))
    val isShuffleEnabled = _isShuffleEnabled.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position = _position.asStateFlow()

    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId = _currentMediaId.asStateFlow()

    // GYM SESSION: queue stays inside the gym pool until manually ended
    private val _gymMode = MutableStateFlow(false)
    val gymMode = _gymMode.asStateFlow()
    fun setGymMode(on: Boolean) { _gymMode.value = on }
    
    // Alias kept for call-site stability; the bus itself now lives with
    // playback (see service/PlaybackBus) and is fed by AudioService.
    object VisualizerBus {
        val spectrum get() = app.nogarbo.leflac.service.PlaybackBus.spectrum
        val mixProgress get() = app.nogarbo.leflac.service.PlaybackBus.mixProgress
        val isPlaying get() = app.nogarbo.leflac.service.PlaybackBus.isPlaying
    }
    
    val spectrum = VisualizerBus.spectrum.asStateFlow()

    private var mediaController: MediaController? = null

    init {
        initializeController()
        startPositionUpdater()
    }

    private fun initializeController() {
        viewModelScope.launch {
            val sessionToken = SessionToken(
                getApplication(),
                ComponentName(getApplication(), AudioService::class.java)
            )
            
            try {
                val controller = MediaController.Builder(getApplication(), sessionToken)
                    .buildAsync()
                    .await()
                
                mediaController = controller
                setupPlayerListener(controller)
                
                // Initial Sync
                updateState(controller)
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateState(player: Player) {
        _isPlaying.value = player.isPlaying
        VisualizerBus.isPlaying.value = player.isPlaying
        // Decouple from native shuffle since we use custom Smart Shuffle
        // _isShuffleEnabled.value = player.shuffleModeEnabled
        val oldId = _currentMediaId.value
        _mediaMetadata.value = player.mediaMetadata
        _duration.value = player.duration.coerceAtLeast(0)
        _position.value = player.currentPosition.coerceAtLeast(0)
        
        player.currentMediaItem?.let {
            _currentMediaId.value = it.mediaId
            if (it.mediaId != oldId) {
                 it.localConfiguration?.uri?.let { uri -> analyzeTrack(uri) }
            }
        }
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                VisualizerBus.isPlaying.value = isPlaying
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                _mediaMetadata.value = mediaMetadata
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _duration.value = player.duration.coerceAtLeast(0)
                    // Snapshot for play credit: belongs to the item playing NOW
                    currentItemDurationMs = player.duration.coerceAtLeast(0)
                    maybeResumeMix(player)
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Queue finished: there is no upcoming transition to settle
                    // the last track's credit, so do it here.
                    settlePlayCredit()
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                 _duration.value = player.duration.coerceAtLeast(0)
                 if (player.duration > 0) currentItemDurationMs = player.duration
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Settle play/skip credit for the track we are leaving
                settlePlayCredit()

                _currentMediaId.value = mediaItem?.mediaId
                pendingMixResumeId = mediaItem?.mediaId
                currentItemDurationMs = player.duration.coerceAtLeast(0)
                _duration.value = player.duration.coerceAtLeast(0)
                _spectralHistory.value = emptyList() // Reset history for new track
                
                // Trigger Analysis
                mediaItem?.localConfiguration?.uri?.let { uri -> analyzeTrack(uri) }
                
                // Dynamically generate and set LCD artwork
                if (mediaItem != null) {
                    generateAndSetArtwork(player, mediaItem)
                }
            }
            
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // Ignore native shuffle mode changes to preserve our UI state
            }
            
            override fun onEvents(player: Player, events: Player.Events) {
                // updates
            }
        })
    }

    // --- Analysis ---
    private var analysisJob: kotlinx.coroutines.Job? = null
    // Raw frames for visualization lookup
    private var analysisTimeline: List<app.nogarbo.leflac.service.AudioTrackAnalyzer.AnalysisFrame>? = null
    
    // We observe the repository
    private val repository by lazy { app.nogarbo.leflac.data.repository.AnalysisRepository.getInstance(getApplication()) }

    private val _fullTimeline = MutableStateFlow<List<SpectralPoint>>(emptyList())
    val fullTimeline = _fullTimeline.asStateFlow()

    // Track-boundary cue points inside long mixes (ms positions)
    private val _cuePoints = MutableStateFlow<List<Long>>(emptyList())
    val cuePoints = _cuePoints.asStateFlow()

    // 0..1 ramp over the 10s before the next epic segment: the UI knows
    // the drop is coming and holds its breath.
    private val _dropTension = MutableStateFlow(0f)
    val dropTension = _dropTension.asStateFlow()

    private val _isAnalysisComplete = MutableStateFlow(false)

    // 0..1 fraction of the track covered by analysis frames (1 = done)
    private val _analysisProgress = MutableStateFlow(1f)
    val analysisProgress = _analysisProgress.asStateFlow()

    // Trigger analysis when track changes
    private fun analyzeTrack(uri: android.net.Uri) {
        // Cancel previous job
        analysisJob?.cancel()
        
        // Reset State
        analysisTimeline = null
        _fullTimeline.value = emptyList()
        _isAnalysisComplete.value = false
        _analysisProgress.value = 0f
        
        analysisJob = viewModelScope.launch {
            try {
                repository.analyzeNowPlaying(uri, forceRegenerate = false)
                    .flowOn(kotlinx.coroutines.Dispatchers.IO)
                    .collect { frames ->
                        // Store Raw Frames for Viz
                        analysisTimeline = frames
                        
                        // Update UI
                        updateTimelineFromFrames(frames)
                        
                        _isAnalysisComplete.value = true
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Track changed mid-analysis: the new track owns the progress
                // state now, so a cancelled sweep must not stamp FAILED on it.
                throw e
            } catch (e: Exception) {
                android.util.Log.e("FLAC_VM", "Analysis flow error", e)
                _analysisProgress.value = -1f // surfaced as ANALYSIS FAILED
            }
        }
    }

    fun regenerateCurrentTrack() {
        val currentUri = mediaController?.currentMediaItem?.localConfiguration?.uri
        if (currentUri != null) {
            android.util.Log.i("FLAC_VM", "Force Regenerating Cache for $currentUri")
             // Cancel previous job
            analysisJob?.cancel()
            
            // Reset State
            analysisTimeline = null
            _fullTimeline.value = emptyList()
            _isAnalysisComplete.value = false
            
            analysisJob = viewModelScope.launch {
                try {
                    repository.analyzeNowPlaying(currentUri, forceRegenerate = true)
                        .flowOn(kotlinx.coroutines.Dispatchers.IO)
                        .collect { frames ->
                            analysisTimeline = frames
                            updateTimelineFromFrames(frames)
                            _isAnalysisComplete.value = true
                        }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("FLAC_VM", "Analysis flow error", e)
                }
            }
        }
    }

    private fun updateTimelineFromFrames(frames: List<app.nogarbo.leflac.service.AudioTrackAnalyzer.AnalysisFrame>) {
        if (frames.isEmpty()) return
        run {
            val dur = _duration.value
            _analysisProgress.value =
                if (dur > 0) (frames.last().timestampMs.toFloat() / dur).coerceIn(0f, 1f) else 1f
        }
        
        // Use Duration from Player if avail, else estimate from frames
        val durationMs = _duration.value.coerceAtLeast(frames.last().timestampMs)
        val validDuration = if (durationMs > 0) durationMs else 1L
        
        val points = frames.map { frame ->
            SpectralPoint(
                relativePos = frame.timestampMs.toFloat() / validDuration.toFloat(),
                bass = frame.state.bass,
                mid = frame.state.mid,
                treble = frame.state.treble,
                isDramatic = frame.state.isDramatic
            )
        }
        _fullTimeline.value = points

        // Mixes: extract cue points (track transitions) from novelty peaks
        _cuePoints.value = if (frames.last().timestampMs >= MIX_DURATION_THRESHOLD_MS) {
            extractCuePoints(frames)
        } else emptyList()
    }

    /**
     * Track boundaries inside a DJ set show up as strong positive spectral
     * change. Take novelty peaks at least [minSpacingMs] apart, strongest
     * first, capped to a sane count.
     */
    private fun extractCuePoints(
        frames: List<app.nogarbo.leflac.service.AudioTrackAnalyzer.AnalysisFrame>,
        minSpacingMs: Long = 90_000L,
        maxCues: Int = 40
    ): List<Long> {
        val n = frames.size
        if (n < 10) return emptyList()
        val novelty = FloatArray(n)
        for (i in 1 until n) {
            val a = frames[i - 1].state
            val b = frames[i].state
            novelty[i] = maxOf(b.kick - a.kick, 0f) + maxOf(b.snare - a.snare, 0f) +
                maxOf(b.cymbals - a.cymbals, 0f) + maxOf(b.vocal - a.vocal, 0f) +
                maxOf(b.synth - a.synth, 0f) + maxOf(b.bassGuitar - a.bassGuitar, 0f)
        }
        val candidates = (1 until n).sortedByDescending { novelty[it] }
        val chosen = mutableListOf<Long>()
        for (idx in candidates) {
            if (chosen.size >= maxCues) break
            val t = frames[idx].timestampMs
            if (t < 30_000L) continue // ignore the intro
            if (chosen.all { kotlin.math.abs(it - t) >= minSpacingMs }) chosen.add(t)
        }
        return chosen.sorted()
    }

    private var lastSeekTime = 0L

    data class SpectralPoint(
        val relativePos: Float,
        val bass: Float,
        val mid: Float,
        val treble: Float,
        val isDramatic: Boolean
    )

    private val _spectralHistory = MutableStateFlow<List<SpectralPoint>>(emptyList())
    val spectralHistory = _spectralHistory.asStateFlow()

    private var currentTrackListenedMs = 0L
    private var runtimeAccMs = 0L
    private var currentItemDurationMs = 0L
    private var lastUpdateTimeMs = System.currentTimeMillis()

    // DJ sets must pick up where you left off. Set on transition, consumed
    // once the duration is known (STATE_READY).
    private var pendingMixResumeId: String? = null
    private var lastMixPositionSaveMs = 0L

    private fun maybeResumeMix(player: Player) {
        val id = _currentMediaId.value ?: return
        if (id != pendingMixResumeId) return
        pendingMixResumeId = null
        val dur = player.duration
        if (dur < MIX_DURATION_THRESHOLD_MS) return
        val saved = app.nogarbo.leflac.data.PlayStatsStore.getPosition(getApplication(), id)
        // Resume only if meaningfully in: past 30s, more than a minute left
        if (saved in 30_000 until (dur - 60_000) && player.currentPosition < 5_000) {
            android.util.Log.i("FLAC_STATS", "Resuming mix $id at ${saved / 1000}s")
            player.seekTo(saved)
        }
    }

    /**
     * Settle the play/skip credit for the track being left. Scrobble-style
     * rules: a play needs >= 50% listened or >= 4 minutes (whichever is
     * less); leaving before 20% (but after at least 2s) counts as a skip.
     * Resets the listen clock, so calling it twice is harmless.
     */
    private fun settlePlayCredit() {
        val id = _currentMediaId.value
        val dur = currentItemDurationMs
        val listened = currentTrackListenedMs
        currentTrackListenedMs = 0L
        if (id == null || dur <= 0) return
        val ctx = getApplication<Application>()
        val playThreshold = minOf(dur / 2, 240_000L)
        when {
            listened >= playThreshold -> {
                app.nogarbo.leflac.data.PlayStatsStore.recordPlay(ctx, id)
                // A finished mix starts from the top next time
                if (dur >= MIX_DURATION_THRESHOLD_MS && listened >= dur * 9 / 10) {
                    app.nogarbo.leflac.data.PlayStatsStore.savePosition(ctx, id, 0L)
                }
            }
            listened in 2_000L until dur / 5 ->
                app.nogarbo.leflac.data.PlayStatsStore.recordSkip(ctx, id)
        }
    }

    private fun startPositionUpdater() {
        viewModelScope.launch {
            while (true) {
                val controller = mediaController
                val timeSinceSeek = System.currentTimeMillis() - lastSeekTime
                
                val now = System.currentTimeMillis()
                val delta = now - lastUpdateTimeMs
                lastUpdateTimeMs = now
                
                if (controller != null && controller.isPlaying) {
                     currentTrackListenedMs += delta
                     // Lifetime runtime, etched on the rear panel
                     runtimeAccMs += delta
                     if (runtimeAccMs > 60_000) {
                         val total = prefs.getLong("runtime_ms", 0L) + runtimeAccMs
                         prefs.edit().putLong("runtime_ms", total).apply()
                         runtimeAccMs = 0L
                     }
                     val pos = controller.currentPosition.coerceAtLeast(0)

                     // Pre-drop tension: ramp up during the 10s before the
                     // next epic segment begins (0 while inside one).
                     run {
                         val timelinePts = _fullTimeline.value
                         val dur = _duration.value
                         var tension = 0f
                         if (timelinePts.isNotEmpty() && dur > 0) {
                             val rel = pos.toFloat() / dur
                             var idx = timelinePts.binarySearchBy(rel) { it.relativePos }
                             if (idx < 0) idx = -idx - 1
                             val inside = timelinePts.getOrNull(idx)?.isDramatic == true
                             if (!inside) {
                                 var j = idx
                                 while (j < timelinePts.size && !timelinePts[j].isDramatic) j++
                                 if (j < timelinePts.size) {
                                     val msToDrop = (timelinePts[j].relativePos - rel) * dur
                                     if (msToDrop in 0f..10_000f) tension = 1f - msToDrop / 10_000f
                                 }
                             }
                         }
                         _dropTension.value = tension
                     }

                     // Feed the glyph mixtape: progress through the mix
                     VisualizerBus.mixProgress.value =
                         if (_duration.value >= MIX_DURATION_THRESHOLD_MS && _duration.value > 0)
                             (pos.toFloat() / _duration.value).coerceIn(0f, 1f)
                         else -1f

                     // Persist mix resume position every ~5s
                     val curId = _currentMediaId.value
                     if (curId != null && _duration.value >= MIX_DURATION_THRESHOLD_MS &&
                         now - lastMixPositionSaveMs > 5_000) {
                         lastMixPositionSaveMs = now
                         app.nogarbo.leflac.data.PlayStatsStore.savePosition(getApplication(), curId, pos)
                     }
                     if (timeSinceSeek > 2000) {
                        _position.value = pos
                     }
                     
                     // PRE-ANALYSIS INTERPOLATION
                     val timeline = analysisTimeline
                     if (timeline != null) {
                         // Find frame at current position
                         // Simple linear scan / binary search is fast enough for <10k frames
                         val frame = timeline.minByOrNull { kotlin.math.abs(it.timestampMs - pos) }
                         
                         if (frame != null) {
                             // Push to Bus
                             // Removed Drama Gating: Show partial results live since they now use look-ahead
                             VisualizerBus.spectrum.value = frame.state
                         }
                     }
                     
                     // History Tracking for Visualization
                     val spec = VisualizerBus.spectrum.value
                     val duration = controller.duration
                     if (duration > 0) {
                         val relativePos = pos.toFloat() / duration.toFloat()
                         val currentList = _spectralHistory.value.toMutableList()
                         
                         val totalEnergy = spec.bass + spec.mid + spec.treble
                         val hasEnergy = totalEnergy > 0.1f
                         
                         if (hasEnergy && (currentList.isEmpty() || kotlin.math.abs(currentList.last().relativePos - relativePos) > 0.005f)) {
                             currentList.add(SpectralPoint(
                                 relativePos = relativePos,
                                 bass = spec.bass,
                                 mid = spec.mid,
                                 treble = spec.treble,
                                 isDramatic = spec.isDramatic
                             ))
                             _spectralHistory.value = currentList
                         }
                     }
                }
                
                delay(30) // 30fps update
            }
        }
    }

    fun togglePlayPause() {
        if (mediaController?.isPlaying == true) app.nogarbo.leflac.util.MachineVoice.thunk()
        mediaController?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        lastSeekTime = System.currentTimeMillis()
        android.util.Log.d("FLAC_DEBUG", "VM: requesting seek to $positionMs")
        
        // Use Direct Singleton Access
        val service = app.nogarbo.leflac.service.AudioService.instance
        if (service != null) {
            service.forceSeek(positionMs)
        } else {
            // Fallback
            app.nogarbo.leflac.service.AudioCommandBus.triggerSeek(positionMs)
        }
        
        _position.value = positionMs // Immediate feedback
    }

    fun seekRelative(deltaMs: Long) {
        val newPos = (_position.value + deltaMs).coerceIn(0, _duration.value)
        seekTo(newPos)
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
    }

    fun skipToNext() {
        mediaController?.seekToNext()
    }

    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    private fun isMixTrack(track: app.nogarbo.leflac.data.AudioTrack): Boolean =
        track.duration >= MIX_DURATION_THRESHOLD_MS

    fun generateSmartQueue(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>, startTrack: app.nogarbo.leflac.data.AudioTrack): List<app.nogarbo.leflac.data.AudioTrack> {
        // Shuffle pools never cross: a mix start shuffles among mixes only,
        // a song start shuffles among songs only.
        val startIsMix = isMixTrack(startTrack)
        val pool = libraryTracks.filter { isMixTrack(it) == startIsMix }

        val now = System.currentTimeMillis()
        val ctx = getApplication<Application>()

        // GYM SESSION: the pool is the gym set, weighted by drive
        val gymOn = _gymMode.value && !startIsMix
        val pool2 = if (gymOn) {
            val g = pool.filter { t ->
                val key = t.uri.toString()
                val pr = app.nogarbo.leflac.data.TrackProfileStore.get(ctx, key)
                val byDrive = (pr?.let { app.nogarbo.leflac.data.TrackProfileStore.gymScore(it) } ?: 0f) > 0.24f
                val byHeat = app.nogarbo.leflac.data.PlayStatsStore.hotScore(ctx, key, now) > 3.0
                byDrive || byHeat
            }
            if (g.size >= 10) (g + startTrack).distinct() else pool
        } else pool

        // Favorites are ranked by recency-decayed hot score (plays minus
        // skips, halving every ~45 days) — or by gym drive in a session.
        val trackStats = pool2.map { track ->
            val hot = app.nogarbo.leflac.data.PlayStatsStore.hotScore(ctx, track.uri.toString(), now)
            val score = if (gymOn) {
                val pr = app.nogarbo.leflac.data.TrackProfileStore.get(ctx, track.uri.toString())
                (pr?.let { app.nogarbo.leflac.data.TrackProfileStore.gymScore(it) } ?: 0f) +
                    0.75 * (hot / 6.0).coerceAtMost(1.0)
            } else hot
            track to score.toDouble()
        }

        val sortedByPlays = trackStats.sortedByDescending { it.second }
        val topCount = (pool2.size * 0.2).toInt().coerceAtLeast(1)

        val topTracks = sortedByPlays.take(topCount)
            .filter { it.second > 0.0 }
            .map { it.first }
            
        val megaPlaylist = mutableListOf<app.nogarbo.leflac.data.AudioTrack>()
        megaPlaylist.add(startTrack)
        
        for (cycle in 0..2) {
            val baseQueue = pool2.shuffled().toMutableList()
            if (cycle == 0) {
                baseQueue.remove(startTrack)
            }
            
            var tracksSinceLastTop = 0
            var nextInsertAt = kotlin.random.Random.nextInt(5, 21)
            
            for (track in baseQueue) {
                megaPlaylist.add(track)
                tracksSinceLastTop++
                
                if (topTracks.isNotEmpty() && tracksSinceLastTop >= nextInsertAt) {
                    var chosenTopTrack: app.nogarbo.leflac.data.AudioTrack? = null
                    val candidates = topTracks.shuffled()
                    for (candidate in candidates) {
                        val last1 = megaPlaylist.lastOrNull()
                        val last2 = if (megaPlaylist.size >= 2) megaPlaylist[megaPlaylist.size - 2] else null
                        
                        val isSameTrack = candidate.uri == last1?.uri || candidate.uri == last2?.uri
                        val isSameArtist = candidate.artist.isNotBlank() && (candidate.artist == last1?.artist || candidate.artist == last2?.artist)
                        
                        if (!isSameTrack && !isSameArtist) {
                            chosenTopTrack = candidate
                            break
                        }
                    }
                    
                    if (chosenTopTrack == null) {
                         chosenTopTrack = candidates.firstOrNull { it.uri != megaPlaylist.lastOrNull()?.uri }
                    }
                    if (chosenTopTrack == null) {
                        chosenTopTrack = candidates.first()
                    }
                    
                    megaPlaylist.add(chosenTopTrack)
                    tracksSinceLastTop = 0
                    nextInsertAt = kotlin.random.Random.nextInt(5, 21)
                }
            }
        }
        return megaPlaylist
    }

    fun setAutoMode(isRng: Boolean, libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        if (_isShuffleEnabled.value == isRng) return
        _isShuffleEnabled.value = isRng
        prefs.edit().putBoolean("is_shuffle", isRng).apply()
        
        val currentTrackId = _currentMediaId.value
        val currentTrack = libraryTracks.find { it.uri.toString() == currentTrackId } ?: libraryTracks.firstOrNull() ?: return
        
        if (isRng) {
            // Pool selection (mixes vs songs) happens inside generateSmartQueue.
            val playlist = generateSmartQueue(libraryTracks, currentTrack)
            updateQueueInternal(playlist, 0)
        } else {
            val playlist = libraryTracks
            val startIndex = playlist.indexOf(currentTrack).coerceAtLeast(0)
            updateQueueInternal(playlist, startIndex)
        }
    }

    private fun updateQueueInternal(playlist: List<app.nogarbo.leflac.data.AudioTrack>, startIndex: Int) {
        mediaController?.shuffleModeEnabled = false 
        
        val controller = mediaController ?: return
        val pos = controller.currentPosition
        
        val mediaItems = playlist.map { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .build()
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaId(track.uri.toString())
                .setMediaMetadata(metadata)
                .build()
        }
        
        val playingItem = controller.currentMediaItem
        if (playingItem != null && playingItem.mediaId == mediaItems[startIndex].mediaId) {
            val currentIdx = controller.currentMediaItemIndex
            
            // Remove items AFTER current
            if (controller.mediaItemCount > currentIdx + 1) {
                controller.removeMediaItems(currentIdx + 1, controller.mediaItemCount)
            }
            
            // Remove items BEFORE current
            if (currentIdx > 0) {
                controller.removeMediaItems(0, currentIdx)
            }
            
            // Now the playing item is at index 0.
            // Insert new items BEFORE current
            if (startIndex > 0) {
                controller.addMediaItems(0, mediaItems.subList(0, startIndex))
            }
            
            // Insert new items AFTER current
            if (startIndex + 1 < mediaItems.size) {
                controller.addMediaItems(startIndex + 1, mediaItems.subList(startIndex + 1, mediaItems.size))
            }
        } else {
            if (_isShuffleEnabled.value && controller.currentMediaItemIndex != -1) {
                // In shuffle mode, preserve history. Append the new track and its generated future to the current position.
                val currentIdx = controller.currentMediaItemIndex
                if (controller.mediaItemCount > currentIdx + 1) {
                    controller.removeMediaItems(currentIdx + 1, controller.mediaItemCount)
                }
                
                val itemsToAdd = mediaItems.subList(startIndex, mediaItems.size)
                controller.addMediaItems(currentIdx + 1, itemsToAdd)
                controller.seekToNextMediaItem()
                controller.seekTo(0L) // Ensure the new track starts at the beginning
                controller.prepare()
            } else {
                // Sequential mode or first track: establish full album queue
                controller.setMediaItems(mediaItems, startIndex, 0L)
                controller.prepare()
            }
        }
    }

    fun playNextSequential(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        // Inside a mix, "next" means the next track in the set
        if (_duration.value >= MIX_DURATION_THRESHOLD_MS) {
            val pos = mediaController?.currentPosition ?: 0L
            val next = _cuePoints.value.firstOrNull { it > pos + 5_000L }
            if (next != null) {
                mediaController?.seekTo(next)
                return
            }
        }
        playNextSequentialFile(libraryTracks)
    }

    fun playNextSequentialFile(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        val currentTrackId = _currentMediaId.value
        val currentIndex = libraryTracks.indexOfFirst { it.uri.toString() == currentTrackId }
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % libraryTracks.size else 0
        val nextTrack = libraryTracks[nextIndex]
        
        if (_isShuffleEnabled.value) {
            updateQueueInternal(generateSmartQueue(libraryTracks, nextTrack), 0)
        } else {
            mediaController?.seekTo(nextIndex, 0)
        }
    }

    fun playPreviousSequential(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        // Inside a mix, "previous" means the previous track in the set
        if (_duration.value >= MIX_DURATION_THRESHOLD_MS) {
            val pos = mediaController?.currentPosition ?: 0L
            val prev = _cuePoints.value.lastOrNull { it < pos - 5_000L }
            if (prev != null || pos > 10_000L) {
                mediaController?.seekTo(prev ?: 0L)
                return
            }
        }
        playPreviousSequentialFile(libraryTracks)
    }

    fun playPreviousSequentialFile(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        val currentTrackId = _currentMediaId.value
        val currentIndex = libraryTracks.indexOfFirst { it.uri.toString() == currentTrackId }
        val prevIndex = if (currentIndex != -1) {
            if (currentIndex - 1 >= 0) currentIndex - 1 else libraryTracks.size - 1
        } else 0
        val prevTrack = libraryTracks[prevIndex]
        
        if (_isShuffleEnabled.value) {
            updateQueueInternal(generateSmartQueue(libraryTracks, prevTrack), 0)
        } else {
            updateQueueInternal(libraryTracks, prevIndex)
        }
    }

    fun playNextRandom(libraryTracks: List<app.nogarbo.leflac.data.AudioTrack>) {
        if (_isShuffleEnabled.value) {
            mediaController?.seekToNext()
        } else {
            val currentTrackId = _currentMediaId.value
            val currentTrack = libraryTracks.find { it.uri.toString() == currentTrackId } ?: libraryTracks.firstOrNull() ?: return

            // From a mix this picks a random mix; from a song, a random song.
            val smartQueue = generateSmartQueue(libraryTracks, currentTrack)
            val nextRandomTrack = if (smartQueue.size > 1) smartQueue[1] else currentTrack
            
            val nextIndex = libraryTracks.indexOf(nextRandomTrack).coerceAtLeast(0)
            mediaController?.seekTo(nextIndex, 0)
        }
    }

    private fun generateAndSetArtwork(player: Player, mediaItem: MediaItem) {
        if (mediaItem.mediaMetadata.artworkData != null) return // Already set, prevent loops
        
        val trackTitle = mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        val trackArtist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown"

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            
            // 16:9 ratio, wide enough to allow good text scaling
            val width = 1024
            val height = 576 
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Aged GameBoy LCD flat background color
            canvas.drawColor(android.graphics.Color.parseColor("#8B9B74"))

            // LCD Pixel Grid Pattern
            val gridPaint = Paint().apply {
                color = android.graphics.Color.parseColor("#7A8B60") // Slightly darker green
                strokeWidth = 2f
            }
            val gridSize = 8f
            var xPos = 0f
            while (xPos < width) {
                canvas.drawLine(xPos, 0f, xPos, height.toFloat(), gridPaint)
                xPos += gridSize
            }
            var yPos = 0f
            while (yPos < height) {
                canvas.drawLine(0f, yPos, width.toFloat(), yPos, gridPaint)
                yPos += gridSize
            }

            // Subtle Screen Bleed / Vignette
            val vignettePaint = Paint().apply {
                shader = android.graphics.RadialGradient(
                    width / 2f, height / 2f, width * 0.8f,
                    intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.parseColor("#331A2F1A")),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

            // Load the downloaded pixel font
            val pixelTypeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.lcd_font) ?: Typeface.MONOSPACE
            } catch (e: Exception) {
                Typeface.MONOSPACE
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#1A2F1A") // Dark greenish-black LCD pixels
                typeface = pixelTypeface
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                // Subtle LCD ghosting / glass reflection
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.parseColor("#441A2F1A"))
            }

            val centerX = width / 2f
            var centerY = height / 2f
            val padding = width * 0.1f // 10% padding
            val maxTextWidth = width - (padding * 2)

            // Draw rail indicator if RNG is latched.
            if (_isShuffleEnabled.value) {
                paint.textSize = height * 0.08f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("[ RAIL RNG ]", centerX, padding, paint)
            }
            
            // Dynamic Font Sizing for Title
            var titleSize = height * 0.20f
            paint.textSize = titleSize
            while (paint.measureText(trackTitle) > maxTextWidth && titleSize > 20f) {
                titleSize -= 2f
                paint.textSize = titleSize
            }
            
            // Draw Title
            canvas.drawText(trackTitle, centerX, centerY, paint)
            
            // Dynamic Font Sizing for Artist
            paint.isFakeBoldText = false
            var artistSize = height * 0.12f
            paint.textSize = artistSize
            while (paint.measureText(trackArtist) > maxTextWidth && artistSize > 16f) {
                artistSize -= 2f
                paint.textSize = artistSize
            }
            
            // Draw Artist below Title
            centerY += height * 0.15f
            canvas.drawText(trackArtist, centerX, centerY, paint)

            val outputStream = ByteArrayOutputStream()
            // PNG compression is fine here as it's fully programmatic and has very few colors
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val artworkData = outputStream.toByteArray()
            bitmap.recycle()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                // Double check if the item is still the current one before replacing
                if (player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                    val newMetadata = mediaItem.mediaMetadata.buildUpon()
                        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                    val newItem = mediaItem.buildUpon().setMediaMetadata(newMetadata).build()
                    
                    val currentIdx = player.currentMediaItemIndex
                    if (currentIdx != -1) {
                        player.replaceMediaItem(currentIdx, newItem)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't lose the in-flight listen credit when the app goes away
        settlePlayCredit()
        mediaController?.release()
    }

    companion object {
        // Tracks at or above 20 minutes are treated as mixes (must match LibraryViewModel's MIX_THRESHOLD).
        const val MIX_DURATION_THRESHOLD_MS = 1_200_000L
    }
}
