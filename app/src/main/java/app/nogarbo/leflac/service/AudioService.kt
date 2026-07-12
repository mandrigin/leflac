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
import java.util.concurrent.Executors

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
                // Artwork refresh replaces the current MediaItem with the
                // same ID. It is metadata, not a listen boundary.
                if (nextMediaId != trackedMediaId) {
                    settleTrackedPlay()
                    trackedMediaId = nextMediaId
                    trackedDurationMs = player.duration.coerceAtLeast(0L)
                    pendingMixResumeId = nextMediaId
                }
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
        val mediaId = player.currentMediaItem?.mediaId ?: return
        playbackPrefs.edit()
            .putString(PREF_LAST_MEDIA_ID, mediaId)
            .putLong(PREF_LAST_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .apply()
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
        ): ListenableFuture<List<MediaItem>> = libraryExecutor.submit<List<MediaItem>> {
            val snapshot = localLibrary.load()
            mediaItems.mapNotNull { requested ->
                resolveRequestedItem(requested, snapshot)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                // Phone queues already contain playable URIs and must remain
                // byte-for-byte in their smart/sequential order.
                if (mediaItems.isNotEmpty() && mediaItems.all { it.localConfiguration?.uri != null }) {
                    return@submit MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex.coerceIn(0, mediaItems.lastIndex),
                        startPositionMs
                    )
                }

                val snapshot = localLibrary.load()
                if (mediaItems.size == 1) {
                    val requested = mediaItems.first()
                    val selectedMediaId = resolveRequestedMediaId(requested, snapshot)
                    selectedMediaId?.let { autoLibrary.queueFor(it, snapshot) }?.let { (queue, selectedIndex) ->
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
                if (mediaItems.isNotEmpty() && resolved.isEmpty()) {
                    throw IllegalArgumentException("No local media matched the Android Auto request")
                }
                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    if (resolved.isEmpty()) 0 else startIndex.coerceIn(0, resolved.lastIndex),
                    startPositionMs
                )
            }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                val mediaId = playbackPrefs.getString(PREF_LAST_MEDIA_ID, null)
                val positionMs = playbackPrefs.getLong(PREF_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
                val queue = mediaId?.let { autoLibrary.queueFor(it, localLibrary.load()) }
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
            requested.takeIf { it.localConfiguration?.uri != null }?.let { return it }
            resolveRequestedMediaId(requested, snapshot)?.let { mediaId ->
                autoLibrary.playbackItem(mediaId, snapshot)?.let { return it }
            }
            return requested.requestMetadata.mediaUri?.let { uri ->
                requested.buildUpon().setUri(uri).build()
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceResult = super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            AudioCommandBus.ACTION_PLAY_LIST -> if (AudioCommandBus.isAuthorized(intent)) {
                val uris = intent.getParcelableArrayListExtra("URIS", android.net.Uri::class.java)
                val titles = intent.getStringArrayListExtra("TITLES")
                val artists = intent.getStringArrayListExtra("ARTISTS")
                val startIndex = intent.getIntExtra("START_INDEX", 0)
                
                if (!uris.isNullOrEmpty()) {
                    val artworkUri = autoLibrary.artworkUri(AndroidAutoVisualScheme.read(this))
                    val mediaItems = uris.mapIndexed { index, uri ->
                        val title = titles?.getOrNull(index) ?: "Unknown Track"
                        val artist = artists?.getOrNull(index) ?: "Unknown Artist"
                        
                        val metadata = MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .setArtworkUri(artworkUri)
                            .build()
                            
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
