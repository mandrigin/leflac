package app.nogarbo.leflac.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import kotlinx.coroutines.launch
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.nogarbo.leflac.MainActivity

class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

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
    }

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Register local seek receiver
        val filter = android.content.IntentFilter("app.nogarbo.leflac.SEEK")
        // Exported false for security if API 33+ (Though this is dynamic)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(seekReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(seekReceiver, filter)
        }

        // build player
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // The bus's source of truth: the player itself
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackBus.isPlaying.value = isPlaying
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
            
        // Visualizer Removed: offline analysis driven by PlaybackViewModel


        // Create a MediaSession
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(seekReceiver)
        } catch (e: Exception) {}

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            "PLAY_URI" -> {
                // Legacy / Single URI fallback
                val uri = intent.data
                if (uri != null) {
                    val mediaItem = MediaItem.fromUri(uri)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                }
            }
            "PLAY_LIST" -> {
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>("URIS")
                val titles = intent.getStringArrayListExtra("TITLES")
                val artists = intent.getStringArrayListExtra("ARTISTS")
                val startIndex = intent.getIntExtra("START_INDEX", 0)
                
                if (!uris.isNullOrEmpty()) {
                    val mediaItems = uris.mapIndexed { index, uri ->
                        val title = titles?.getOrNull(index) ?: "Unknown Track"
                        val artist = artists?.getOrNull(index) ?: "Unknown Artist"
                        
                        val metadata = MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist(artist)
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
            "SEEK_TO" -> {
                val pos = intent.getLongExtra("POSITION", 0L)
                if (pos >= 0) {
                   player.seekTo(pos)
                }
            }
            else -> {
                // Ignore other actions
            }
        }
        
        return START_STICKY
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
// Fix helper constant if strict mode complains, but START_STICKY is standard. 
// Actually MediaSessionService might want 'super.onStartCommand' result. 
// Let's rely on super for return val or standard. Standard is START_STICKY.
