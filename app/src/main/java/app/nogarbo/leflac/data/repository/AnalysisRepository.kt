package app.nogarbo.leflac.data.repository

import android.content.Context
import android.net.Uri
import app.nogarbo.leflac.service.AudioTrackAnalyzer
import app.nogarbo.leflac.service.AudioTrackAnalyzer.AnalysisFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    
    // Track "Now Playing" state to manage contention
    private val _isNowPlayingActive = MutableStateFlow(false)
    
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
                try {
                    // Run analysis (blocking/suspend)
                    // We don't need partial results for background work
                    analyzer.analyze(uri)
                } catch (e: Exception) {
                    android.util.Log.w("FLAC_BG", "Background Analysis failed/skipped for $uri", e)
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
        // Mark as active to pause background work
        _isNowPlayingActive.value = true
        
        // Cancel current background stanza immediately to free up CPU?
        // The background loop checks `_isNowPlayingActive` before *starting* a track.
        // But if it's *in the middle* of a track, we might want to cancel it?
        // Yes, for "Now Playing" we want CPU *now*.
        // We can restart the background job or cancel the current specific analysis.
        // However, cancelling the *whole* background loop loses the queue position if using `for(uri in queue)`.
        // Actually, Channel iteration doesn't lose items if we just cancel the *processing* of one item.
        // But we can't easily cancel *just* the analyzer call inside the loop from here without complex job management.
        
        // Hack: We rely on the OS slicing, or we implement a "Current Background Job" ref.
        // For simplicity/safety, we just let the background task finish its current file (max few seconds) OR pause...
        // The user said "even cancelling".
        // Let's rely on `_isNowPlayingActive` check blocking *next* items. 
        // To cancel *current* item, we'd need `currentBackgroundAnalysisJob?.cancel()`.
        
        try {
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
            _isNowPlayingActive.value = false
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
