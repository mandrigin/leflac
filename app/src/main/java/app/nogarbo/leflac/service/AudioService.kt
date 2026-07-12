package app.nogarbo.leflac.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.nogarbo.leflac.MainActivity
import app.nogarbo.leflac.data.AndroidAutoVisualScheme
import app.nogarbo.leflac.data.LocalAudioLibrary
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private val libraryExecutor = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "leflac-car-library").apply { isDaemon = true }
        }
    )
    private val autoLibrary by lazy { AndroidAutoLibrary(applicationContext) }
    private val localLibrary by lazy {
        LocalAudioLibrary(
            context = applicationContext,
            observeChanges = true,
            onInvalidated = ::notifyLibraryContentChanged
        )
    }
    private val playbackPrefs by lazy { getSharedPreferences("flac_prefs", MODE_PRIVATE) }
    private var trackedMediaId: String? = null
    private var trackedDurationMs = 0L
    private var trackedListenedMs = 0L
    private var runtimePendingMs = 0L
    private var lastStatsTickMs = android.os.SystemClock.elapsedRealtime()
    private var pendingMixResumeId: String? = null
    private var lastMixPositionSaveMs = 0L
    private var pruningConsumedUpNext = false
    private val prunedUpNextTokens = mutableSetOf<String>()
    private val queueGeneration = AtomicLong(0L)

    private val seekReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "app.nogarbo.leflac.SEEK") {
                val pos = intent.getLongExtra("POS", 0L)
                if (pos >= 0 && ::player.isInitialized) {
                    player.seekTo(pos)
                }
            }
        }
    }

    companion object {
        var instance: AudioService? = null
        private const val PREF_LAST_MEDIA_ID = "playback_last_media_id"
        private const val PREF_LAST_POSITION_MS = "playback_last_position_ms"
        private const val PREF_UP_NEXT_MEDIA_IDS = "playback_up_next_media_ids"
        private const val PREF_FUTURE_MEDIA_IDS = "playback_future_media_ids"
        private const val MAX_PERSISTED_FUTURE_ITEMS = 512
    }

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Register local seek receiver
        val filter = android.content.IntentFilter("app.nogarbo.leflac.SEEK")
        // Exported false for security if API 33+ (Though this is dynamic)
        registerReceiver(seekReceiver, filter, RECEIVER_NOT_EXPORTED)

        // build player
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // The bus's source of truth: the player itself
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackBus.isPlaying.value = isPlaying
                persistPlaybackState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val nextMediaId = mediaItem?.mediaId
                // Artwork/rail refresh replaces the current MediaItem with
                // the same ID using a playlist-changed transition. Automatic,
                // seek, and repeat transitions are real boundaries even when
                // a generated RNG queue places the same ID twice in a row.
                val metadataOnlyRefresh =
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                        nextMediaId == trackedMediaId
                if (!metadataOnlyRefresh) {
                    settleTrackedPlay()
                    trackedMediaId = nextMediaId
                    trackedDurationMs = player.duration.coerceAtLeast(0L)
                    pendingMixResumeId = nextMediaId
                }
                if (!metadataOnlyRefresh) pruneConsumedUpNextDuplicates()
                publishUpNext()
                persistPlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    trackedDurationMs = player.duration.coerceAtLeast(0L)
                    maybeResumeMix()
                } else if (playbackState == Player.STATE_ENDED) {
                    settleTrackedPlay()
                }
            }

            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int
            ) {
                if (player.duration > 0L) trackedDurationMs = player.duration
                publishUpNext()
                persistPlaybackState()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) player.shuffleModeEnabled = false
            }
        })
            
        // Subscribe to direct command bus
        serviceScope.launch {
            AudioCommandBus.seekEvents.collect { pos ->
                android.util.Log.d("FLAC_DEBUG", "SERVICE: received seek to $pos")
                if (pos >= 0) {
                    player.seekTo(pos)
                    android.util.Log.d("FLAC_DEBUG", "SERVICE: player.seekTo called. position=${player.currentPosition}, duration=${player.duration}, seekable=${player.isCurrentMediaItemSeekable}")
                }
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                updatePlaybackAccounting()
            }
        }
            
        // Visualizer Removed: offline analysis driven by PlaybackViewModel


        // Create a MediaSession
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()

        restoreTimelineWithoutAutoplay()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        persistPlaybackState()
        settleTrackedPlay()
        flushRuntime()
        try {
            unregisterReceiver(seekReceiver)
        } catch (e: Exception) {}

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        localLibrary.close()
        serviceScope.cancel()
        libraryExecutor.shutdown()
        instance = null
        super.onDestroy()
    }

    /** Refresh app-owned car artwork/layout after the rear-panel setting moves. */
    fun notifyAndroidAutoVisualSchemeChanged() {
        if (::player.isInitialized && player.currentMediaItemIndex != C.INDEX_UNSET) {
            val current = player.currentMediaItem
            if (current != null) {
                val scheme = AndroidAutoVisualScheme.read(this)
                val metadata = current.mediaMetadata.buildUpon()
                    .setArtworkData(null, null)
                    .setArtworkUri(autoLibrary.artworkUri(scheme))
                    .build()
                player.replaceMediaItem(
                    player.currentMediaItemIndex,
                    current.buildUpon().setMediaMetadata(metadata).build()
                )
            }
        }
        notifyLibraryContentChanged()
    }

    fun refreshLocalAudioLibrary() {
        localLibrary.invalidate()
    }

    private fun notifyLibraryContentChanged() {
        if (libraryExecutor.isShutdown) return
        libraryExecutor.execute {
            val session = mediaSession ?: return@execute
            val snapshot = localLibrary.load()
            val scheme = AndroidAutoVisualScheme.read(this)
            val params = LibraryParams.Builder()
                .setOffline(true)
                .setExtras(autoLibrary.libraryParamsExtras(scheme))
                .build()
            session.notifyChildrenChanged(
                AndroidAutoLibrary.ROOT_ID,
                autoLibrary.rootChildren(snapshot, scheme).size,
                params
            )
            if (!snapshot.hasPermission) return@execute
            val hotCount = app.nogarbo.leflac.data.PlayStatsStore
                .hotTrackIds(this, snapshot.songs)
                .size
                .coerceAtLeast(1)
            session.notifyChildrenChanged(AndroidAutoLibrary.HOT_ID, hotCount, params)
            session.notifyChildrenChanged(AndroidAutoLibrary.ALBUMS_ID, snapshot.folders.size, params)
            session.notifyChildrenChanged(AndroidAutoLibrary.MIXES_ID, snapshot.mixes.size, params)
            session.notifyChildrenChanged(AndroidAutoLibrary.ALL_TRACKS_ID, snapshot.songs.size, params)
            snapshot.folders.forEach { folder ->
                session.notifyChildrenChanged(
                    AndroidAutoLibrary.encodeFolderId(folder),
                    snapshot.tracksInFolder(folder).size,
                    params
                )
            }
        }
    }

    private fun persistPlaybackState() {
        if (!::player.isInitialized) return
        val pendingJson = JSONArray().apply {
            UpNextQueue.pendingItems(player).forEach { item ->
                put(item.mediaId)
            }
        }.toString()
        val currentIndex = player.currentMediaItemIndex
        val futureJson = JSONArray().apply {
            if (currentIndex != C.INDEX_UNSET) {
                val pendingCount = UpNextQueue.pendingItems(player).size
                val end = (
                    currentIndex.toLong() + 1L + pendingCount + MAX_PERSISTED_FUTURE_ITEMS
                ).coerceAtMost(player.mediaItemCount.toLong()).toInt()
                for (index in currentIndex until end) {
                    put(player.getMediaItemAt(index).mediaId)
                }
            }
        }.toString()
        val editor = playbackPrefs.edit()
            .putString(PREF_UP_NEXT_MEDIA_IDS, pendingJson)
            .putString(PREF_FUTURE_MEDIA_IDS, futureJson)
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId == null) {
            editor.apply()
            return
        }
        editor
            .putString(PREF_LAST_MEDIA_ID, mediaId)
            .putLong(PREF_LAST_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    private fun publishUpNext() {
        if (!::player.isInitialized) return
        PlaybackBus.upNext.value = UpNextQueue.pendingEntries(player)
    }

    /**
     * Scheduling leaves the underlying rail intact so remove/clear are lossless.
     * Once a marked occurrence has actually been consumed, remove its first
     * remaining natural duplicate to complete the promotion without replaying it.
     */
    private fun pruneConsumedUpNextDuplicates() {
        if (pruningConsumedUpNext || !::player.isInitialized) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return
        val consumed = (0..currentIndex)
            .map(player::getMediaItemAt)
            .filter { item ->
                UpNextQueue.isMarked(item) &&
                    UpNextQueue.entryId(item) !in prunedUpNextTokens
            }
            .distinctBy(UpNextQueue::entryId)
        if (consumed.isEmpty()) return

        val timelineItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val timelineIds = timelineItems.map(MediaItem::mediaId)
        val marked = timelineItems.map(UpNextQueue::isMarked)
        val claimed = mutableSetOf<Int>()
        val duplicates = consumed.mapNotNull { consumedItem ->
            val duplicate = firstUnmarkedFutureIndex(
                currentIndex = currentIndex,
                timelineIds = timelineIds,
                marked = marked,
                targetId = consumedItem.mediaId,
                excludedIndices = claimed
            )
            UpNextQueue.entryId(consumedItem)?.let(prunedUpNextTokens::add)
            duplicate?.also(claimed::add)
        }
        if (duplicates.isEmpty()) return

        pruningConsumedUpNext = true
        try {
            duplicates.sortedDescending().forEach(player::removeMediaItem)
        } finally {
            pruningConsumedUpNext = false
        }
    }

    private fun restoredUpNextMediaIds(): List<String> {
        val raw = playbackPrefs.getString(PREF_UP_NEXT_MEDIA_IDS, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (index in 0 until json.length()) {
                    json.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun restoredFutureMediaIds(): List<String> {
        val raw = playbackPrefs.getString(PREF_FUTURE_MEDIA_IDS, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (index in 0 until json.length()) {
                    json.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun restoredQueue(
        snapshot: app.nogarbo.leflac.data.LocalAudioLibrarySnapshot,
        mediaId: String
    ): Pair<List<MediaItem>, Int>? {
        val currentTrack = snapshot.findByMediaId(mediaId) ?: return null
        val pool = currentTrack.queuePool()
        val persisted = restoredFutureMediaIds()
            .mapNotNull(snapshot::findByMediaId)
            .filter { it.queuePool() == pool }

        if (persisted.firstOrNull()?.uri?.toString() == mediaId) {
            val pending = restoredUpNextMediaIds()
                .filter { pendingId ->
                    snapshot.findByMediaId(pendingId)?.queuePool() == pool
                }
            val markedIndices = mutableSetOf<Int>()
            var searchFrom = 1
            pending.forEach { scheduledId ->
                val index = (searchFrom until persisted.size).firstOrNull { candidate ->
                    persisted[candidate].uri.toString() == scheduledId
                }
                if (index != null) {
                    markedIndices += index
                    searchFrom = index + 1
                }
            }
            val items = persisted.mapIndexed { index, track ->
                val item = autoLibrary.playbackItem(track)
                if (index in markedIndices) UpNextQueue.mark(item) else item
            }
            return items to 0
        }

        val base = autoLibrary.queueFor(mediaId, snapshot) ?: return null
        val restored = restoredUpNextMediaIds().mapNotNull { scheduledId ->
            val track = snapshot.findByMediaId(scheduledId)
                ?: return@mapNotNull null
            if (track.queuePool() != pool) return@mapNotNull null
            UpNextQueue.mark(autoLibrary.playbackItem(track))
        }
        return insertPriorityAfterCurrent(base.first, base.second, restored) to base.second
    }

    private fun restoreTimelineWithoutAutoplay() {
        libraryExecutor.execute {
            val mediaId = playbackPrefs.getString(PREF_LAST_MEDIA_ID, null) ?: return@execute
            val snapshot = localLibrary.load()
            val restored = restoredQueue(snapshot, mediaId) ?: return@execute
            val positionMs = playbackPrefs.getLong(PREF_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
            serviceScope.launch {
                if (player.mediaItemCount == 0) {
                    player.setMediaItems(restored.first, restored.second, positionMs)
                    player.prepare()
                    // Preparation is local and paused; never set playWhenReady
                    // merely because a phone/car controller connected.
                }
            }
        }
    }

    private fun updatePlaybackAccounting() {
        if (!::player.isInitialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        val delta = (now - lastStatsTickMs).coerceIn(0L, 5_000L)
        lastStatsTickMs = now
        if (!player.isPlaying) return

        trackedListenedMs += delta
        runtimePendingMs += delta
        if (runtimePendingMs >= 60_000L) flushRuntime()

        val duration = player.duration.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L)
        PlaybackBus.mixProgress.value =
            if (duration >= app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS && duration > 0L) {
                (position.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                -1f
            }

        val mediaId = trackedMediaId
        if (mediaId != null &&
            duration >= app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS &&
            now - lastMixPositionSaveMs >= 5_000L
        ) {
            lastMixPositionSaveMs = now
            app.nogarbo.leflac.data.PlayStatsStore.savePosition(this, mediaId, position)
        }

        if (now % 5_000L < 1_000L) persistPlaybackState()
    }

    private fun maybeResumeMix() {
        val mediaId = trackedMediaId ?: return
        if (mediaId != pendingMixResumeId) return
        pendingMixResumeId = null
        val duration = player.duration
        if (duration < app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS) return
        val saved = app.nogarbo.leflac.data.PlayStatsStore.getPosition(this, mediaId)
        if (saved in 30_000L until (duration - 60_000L) && player.currentPosition < 5_000L) {
            player.seekTo(saved)
        }
    }

    private fun settleTrackedPlay() {
        val mediaId = trackedMediaId
        val duration = trackedDurationMs
        val listened = trackedListenedMs
        trackedListenedMs = 0L
        if (mediaId.isNullOrBlank() || duration <= 0L) return

        var statsChanged = false
        when (app.nogarbo.leflac.data.playbackCredit(duration, listened)) {
            app.nogarbo.leflac.data.PlaybackCredit.PLAY -> {
                app.nogarbo.leflac.data.PlayStatsStore.recordPlay(this, mediaId)
                statsChanged = true
                if (duration >= app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS &&
                    listened >= duration * 9L / 10L
                ) {
                    app.nogarbo.leflac.data.PlayStatsStore.savePosition(this, mediaId, 0L)
                }
            }
            app.nogarbo.leflac.data.PlaybackCredit.SKIP -> {
                app.nogarbo.leflac.data.PlayStatsStore.recordSkip(this, mediaId)
                statsChanged = true
            }
            app.nogarbo.leflac.data.PlaybackCredit.NONE -> Unit
        }
        if (statsChanged) notifyLibraryContentChanged()
    }

    private fun flushRuntime() {
        if (runtimePendingMs <= 0L) return
        val total = playbackPrefs.getLong("runtime_ms", 0L) + runtimePendingMs
        playbackPrefs.edit().putLong("runtime_ms", total).apply()
        runtimePendingMs = 0L
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                        .build()
                )
                .build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val scheme = AndroidAutoVisualScheme.read(this@AudioService)
            val resultParams = LibraryParams.Builder()
                .setOffline(true)
                .setExtras(autoLibrary.libraryParamsExtras(scheme))
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(autoLibrary.rootItem(scheme), resultParams)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val snapshot = localLibrary.load()
                val scheme = AndroidAutoVisualScheme.read(this@AudioService)
                val children = autoLibrary.children(parentId, snapshot, scheme)
                    ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                val requestedPage = AndroidAutoLibrary.page(children, page, pageSize)
                    ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                LibraryResult.ofItemList(requestedPage, params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            libraryExecutor.submit<LibraryResult<MediaItem>> {
                val item = autoLibrary.item(
                    mediaId,
                    localLibrary.load(),
                    AndroidAutoVisualScheme.read(this@AudioService)
                ) ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                LibraryResult.ofItem(item, null)
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> =
            libraryExecutor.submit<LibraryResult<Void>> {
                val resultCount = autoLibrary.search(
                    query,
                    localLibrary.load(),
                    AndroidAutoVisualScheme.read(this@AudioService)
                ).size
                session.notifySearchResultChanged(browser, query, resultCount, params)
                LibraryResult.ofVoid(params)
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val results = autoLibrary.search(
                    query,
                    localLibrary.load(),
                    AndroidAutoVisualScheme.read(this@AudioService)
                )
                val requestedPage = AndroidAutoLibrary.page(results, page, pageSize)
                    ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                LibraryResult.ofItemList(requestedPage, params)
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            if (mediaItems.isEmpty()) return Futures.immediateFuture(emptyList())
            val currentMediaId = player.currentMediaItem?.mediaId
            val currentDuration = player.currentMediaItem?.mediaMetadata?.durationMs
                ?: player.duration.takeIf { it > 0L }
            val currentPool = poolFromDuration(currentDuration)
            val canResolveImmediately = mediaItems.all { item ->
                item.localConfiguration?.uri != null && mediaItemPool(item, null) != null
            }
            if (canResolveImmediately) {
                return Futures.immediateFuture(
                    samePoolItems(mediaItems, currentPool, null)
                )
            }

            val generation = queueGeneration.get()
            return libraryExecutor.submit<List<MediaItem>> {
                if (generation != queueGeneration.get()) return@submit emptyList()
                val snapshot = localLibrary.load()
                val requiredPool = currentMediaId
                    ?.let(snapshot::findByMediaId)
                    ?.queuePool()
                    ?: currentPool
                val resolved = mediaItems.mapNotNull { requested ->
                    resolveRequestedItem(requested, snapshot)
                }
                if (generation != queueGeneration.get()) return@submit emptyList()
                samePoolItems(resolved, requiredPool, snapshot)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val generation = queueGeneration.incrementAndGet()
            // Phone queues already contain playable URIs; resolve these on the
            // session thread so a following play-now command cannot be overtaken
            // by a deferred add from the previous context.
            if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.uri != null }) {
                val resolved = samePoolItems(mediaItems, null, null)
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        resolved,
                        if (resolved.isEmpty()) 0 else startIndex.coerceIn(0, resolved.lastIndex),
                        startPositionMs
                    )
                )
            }

            return libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                if (generation != queueGeneration.get()) {
                    throw IllegalStateException("Playback queue context changed")
                }
                val snapshot = localLibrary.load()
                if (mediaItems.size == 1) {
                    val requested = mediaItems.first()
                    val selectedMediaId = resolveRequestedMediaId(requested, snapshot)
                    selectedMediaId?.let { autoLibrary.queueFor(it, snapshot) }?.let { (queue, selectedIndex) ->
                        if (generation != queueGeneration.get()) {
                            throw IllegalStateException("Playback queue context changed")
                        }
                        return@submit MediaSession.MediaItemsWithStartPosition(
                            queue,
                            selectedIndex,
                            startPositionMs
                        )
                    }
                }

                val resolved = mediaItems.mapNotNull { requested ->
                    resolveRequestedItem(requested, snapshot)
                }
                if (generation != queueGeneration.get()) {
                    throw IllegalStateException("Playback queue context changed")
                }
                val samePool = samePoolItems(resolved, null, snapshot)
                if (mediaItems.isNotEmpty() && samePool.isEmpty()) {
                    throw IllegalArgumentException("No local media matched the Android Auto request")
                }
                MediaSession.MediaItemsWithStartPosition(
                    samePool,
                    if (samePool.isEmpty()) 0 else startIndex.coerceIn(0, samePool.lastIndex),
                    startPositionMs
                )
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val generation = queueGeneration.get()
            return libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                val mediaId = playbackPrefs.getString(PREF_LAST_MEDIA_ID, null)
                val positionMs = playbackPrefs.getLong(PREF_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
                val queue = mediaId?.let { restoredQueue(localLibrary.load(), it) }
                if (generation != queueGeneration.get()) {
                    throw IllegalStateException("Playback queue context changed")
                }
                if (queue == null) {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                } else if (!isForPlayback) {
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(queue.first[queue.second]),
                        0,
                        positionMs
                    )
                } else {
                    MediaSession.MediaItemsWithStartPosition(queue.first, queue.second, positionMs)
                }
            }
        }

        private fun resolveRequestedMediaId(
            requested: MediaItem,
            snapshot: app.nogarbo.leflac.data.LocalAudioLibrarySnapshot
        ): String? {
            if (snapshot.findByMediaId(requested.mediaId) != null) return requested.mediaId
            val extras = requested.requestMetadata.extras ?: android.os.Bundle.EMPTY
            val title = extras.getString(android.provider.MediaStore.EXTRA_MEDIA_TITLE)
            val artist = extras.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST)
            val album = extras.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM)
            val playlist = extras.getString(android.provider.MediaStore.EXTRA_MEDIA_PLAYLIST)
            val genre = extras.getString(android.provider.MediaStore.EXTRA_MEDIA_GENRE)

            if (!playlist.isNullOrBlank() && playlist.contains("mix", ignoreCase = true)) {
                snapshot.mixes.firstOrNull()?.let { return it.uri.toString() }
            }

            if (!title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()) {
                snapshot.allTracks.firstOrNull { track ->
                    (title.isNullOrBlank() || track.title.contains(title, ignoreCase = true)) &&
                        (artist.isNullOrBlank() || track.artist.contains(artist, ignoreCase = true)) &&
                        (album.isNullOrBlank() || track.folderName.contains(album, ignoreCase = true)) &&
                        (genre.isNullOrBlank() || track.folderName.contains(genre, ignoreCase = true))
                }?.let { return it.uri.toString() }
            }

            val explicitQuery = requested.requestMetadata.searchQuery
            val extrasQuery = listOfNotNull(title, artist, album, playlist, genre)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
            val query = explicitQuery?.takeIf(String::isNotBlank)
                ?: extrasQuery
                ?: explicitQuery
                ?: return null
            return autoLibrary.search(
                query,
                snapshot,
                AndroidAutoVisualScheme.read(this@AudioService)
            ).firstOrNull()?.mediaId
        }

        private fun resolveRequestedItem(
            requested: MediaItem,
            snapshot: app.nogarbo.leflac.data.LocalAudioLibrarySnapshot
        ): MediaItem? {
            requested.takeIf { it.localConfiguration?.uri != null }?.let {
                return withCarArtwork(it)
            }
            resolveRequestedMediaId(requested, snapshot)?.let { mediaId ->
                autoLibrary.playbackItem(mediaId, snapshot)?.let { return it }
            }
            return requested.requestMetadata.mediaUri?.let { uri ->
                withCarArtwork(requested.buildUpon().setUri(uri).build())
            }
        }

        private fun withCarArtwork(item: MediaItem): MediaItem {
            if (item.mediaMetadata.artworkData != null || item.mediaMetadata.artworkUri != null) {
                return item
            }
            val metadata = item.mediaMetadata.buildUpon()
                .setArtworkUri(
                    autoLibrary.artworkUri(AndroidAutoVisualScheme.read(this@AudioService))
                )
                .build()
            return item.buildUpon().setMediaMetadata(metadata).build()
        }

        private fun poolFromDuration(durationMs: Long?): QueuePool? =
            durationMs?.takeIf { it >= 0L && it != C.TIME_UNSET }?.let { duration ->
                if (duration >= app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS) {
                    QueuePool.MIX
                } else {
                    QueuePool.SONG
                }
            }

        private fun mediaItemPool(
            item: MediaItem,
            snapshot: app.nogarbo.leflac.data.LocalAudioLibrarySnapshot?
        ): QueuePool? {
            val track = snapshot?.findByMediaId(item.mediaId)
                ?: snapshot?.let { library ->
                    item.localConfiguration?.uri?.toString()?.let(library::findByMediaId)
                }
            return track?.queuePool() ?: poolFromDuration(item.mediaMetadata.durationMs)
        }

        private fun samePoolItems(
            items: List<MediaItem>,
            requiredPool: QueuePool?,
            snapshot: app.nogarbo.leflac.data.LocalAudioLibrarySnapshot?
        ): List<MediaItem> {
            var targetPool = requiredPool
            return buildList {
                items.forEach { item ->
                    val pool = mediaItemPool(item, snapshot) ?: return@forEach
                    if (targetPool == null) targetPool = pool
                    if (pool == targetPool) add(withCarArtwork(item))
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceResult = super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            AudioCommandBus.ACTION_PLAY_LIST -> if (AudioCommandBus.isAuthorized(intent)) {
                queueGeneration.incrementAndGet()
                val uris = intent.getParcelableArrayListExtra("URIS", android.net.Uri::class.java)
                val titles = intent.getStringArrayListExtra("TITLES")
                val artists = intent.getStringArrayListExtra("ARTISTS")
                val durations = intent.getLongArrayExtra("DURATIONS")
                val startIndex = intent.getIntExtra("START_INDEX", 0)
                
                if (!uris.isNullOrEmpty()) {
                    val artworkUri = autoLibrary.artworkUri(AndroidAutoVisualScheme.read(this))
                    val mediaItems = uris.mapIndexed { index, uri ->
                        val title = titles?.getOrNull(index) ?: "Unknown Track"
                        val artist = artists?.getOrNull(index) ?: "Unknown Artist"
                        val metadataBuilder = MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .setArtworkUri(artworkUri)
                        durations?.getOrNull(index)?.takeIf { it >= 0L }?.let(metadataBuilder::setDurationMs)
                        val metadata = metadataBuilder.build()
                            
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(uri.toString())
                            .setMediaMetadata(metadata)
                            .build()
                    }
                    player.setMediaItems(mediaItems)
                    player.seekTo(startIndex, 0L)
                    player.prepare()
                    player.play()
                }
            }
            else -> {
                // Ignore other actions
            }
        }
        
        return serviceResult
    }

    // Custom method to simulate tape speed scrubbing
    fun setTapeSpeed(speed: Float) {
        if (::player.isInitialized) {
            player.setPlaybackSpeed(speed)
        }
    }
    
    fun forceSeek(positionMs: Long) {
        if (::player.isInitialized) {
            android.util.Log.d("FLAC_DEBUG", "SERVICE: Force Seek to pos=$positionMs")
            player.seekTo(positionMs)
        }
    }
    
    // Live Visualizer Removed (Using Pre-Analysis)
    fun setVisualizerEnabled(enabled: Boolean) {
        // No-op
    }
}
