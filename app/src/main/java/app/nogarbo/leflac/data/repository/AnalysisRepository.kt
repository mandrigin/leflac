package app.nogarbo.leflac.data.repository

import android.content.Context
import android.net.Uri
import app.nogarbo.leflac.service.AudioTrackAnalyzer
import app.nogarbo.leflac.service.AudioTrackAnalyzer.AnalysisFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages audio analysis tasks:
 * 1. Prioritizes "Now Playing" requests (cancelling previous ones).
 * 2. Manages caching and regeneration.
 * 3. Future: Could handle background queue for library scanning.
 */
class AnalysisRepository(private val context: Context) {

    private val analyzer = AudioTrackAnalyzer(context)
    
    // We use a SupervisorJob to ensure the repository scope stays alive,
    // though for "Now Playing" we usually bind to the ViewModel's scope/lifecycle.
    // But caching operations might need to outlive a quick track switch?
    // Actually, user wants "bugs when switching songs" fixed. 
    // If we switch songs, we SHOULD cancel the old one (as done by Flow cancellation).
    
    // Background Queue
    private val backgroundQueue = kotlinx.coroutines.channels.Channel<Uri>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var backgroundJob: Job? = null
    @Volatile private var currentBackgroundAnalysisJob: Job? = null
    
    // Track "Now Playing" state to manage contention
    private val _isNowPlayingActive = MutableStateFlow(false)
    private val nowPlayingRequestCount = AtomicInteger(0)
    
    init {
        scope.launch { analyzer.purgeLegacyCaches() }
        startBackgroundProcessor()
    }
    
    fun queueBackground(uris: List<Uri>) {
        scope.launch {
            uris.forEach { backgroundQueue.send(it) }
        }
    }
    
    private fun startBackgroundProcessor() {
        backgroundJob = scope.launch {
            for (uri in backgroundQueue) {
                // Wait if Now Playing is active
                _isNowPlayingActive.first { !it }
                
                // Profile minted means cache existed and was profiled: skip
                if (app.nogarbo.leflac.data.TrackProfileStore.has(context, uri.toString())) {
                    continue
                }
                // Check Cache again before processing (loads it once, which
                // also mints the gym profile for already-analyzed tracks)
                if (analyzer.checkCache(uri) != null) {
                    continue
                }
                
                android.util.Log.d("FLAC_BG", "Background Analysis starting for $uri")
                val analysisJob = launch(start = CoroutineStart.LAZY) {
                    try {
                        analyzer.analyze(uri)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("FLAC_BG", "Background Analysis failed/skipped for $uri", e)
                    }
                }
                currentBackgroundAnalysisJob = analysisJob
                // Close the hand-off race between observing the idle flag and
                // publishing the cancellable job. A newly started foreground
                // request either cancels this lazy job or makes this check true.
                if (_isNowPlayingActive.value) {
                    if (currentBackgroundAnalysisJob === analysisJob) {
                        currentBackgroundAnalysisJob = null
                    }
                    analysisJob.cancel()
                    backgroundQueue.send(uri)
                    continue
                }
                analysisJob.start()
                analysisJob.join()
                if (currentBackgroundAnalysisJob === analysisJob) {
                    currentBackgroundAnalysisJob = null
                }
                // Now-playing work may preempt a sweep item. Put it at the
                // tail so no song is permanently lost from the v4 rebuild.
                if (analysisJob.isCancelled && scope.isActive &&
                    !app.nogarbo.leflac.data.TrackProfileStore.has(context, uri.toString())
                ) {
                    backgroundQueue.send(uri)
                }
                
                // Yield to allow UI interactions or cancellation checks
                yield()
            }
        }
    }

    /**
     * Analyzes the track, emitting partial results and then the final result.
     * If the collector stops collecting (e.g. ViewModel job cancelled), the analysis is cancelled automatically
     * thanks to the suspend function in AudioTrackAnalyzer checking for cancellation.
     */
    fun analyzeNowPlaying(uri: Uri, forceRegenerate: Boolean = false): Flow<List<AnalysisFrame>> = channelFlow {
        // A track switch cancels the prior collector without synchronously
        // joining it. Count overlapping hand-offs so the old request's finally
        // block cannot briefly resume the background sweep under the new one.
        if (nowPlayingRequestCount.incrementAndGet() == 1) {
            _isNowPlayingActive.value = true
        }
        try {
            currentBackgroundAnalysisJob?.let { background ->
                background.cancelAndJoin()
                if (currentBackgroundAnalysisJob === background) {
                    currentBackgroundAnalysisJob = null
                }
            }

            // 1. Fast Path
            if (!forceRegenerate) {
                val cached = analyzer.checkCache(uri)
                if (cached != null) {
                    send(cached)
                    return@channelFlow
                }
            }
            
            android.util.Log.d("FLAC_REPO", "Cache Miss/Regen. Starting Analysis Flow for $uri")
            
            val finalResult = analyzer.analyze(uri, forceRegenerate) { partial ->
                trySend(partial)
            }
            send(finalResult)
            
        } finally {
            if (nowPlayingRequestCount.decrementAndGet() == 0) {
                _isNowPlayingActive.value = false
            }
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: AnalysisRepository? = null
        
        fun getInstance(context: Context): AnalysisRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AnalysisRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
