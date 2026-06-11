package app.nogarbo.leflac.service

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GlyphToyService : Service() {

    private var glyphManager: GlyphMatrixManager? = null
    private var callback: GlyphMatrixManager.Callback? = null
    
    // Lifecycle Ignored per user request, removing Handler/Messenger to prevent build errors
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var renderJob: Job? = null
    private var analysisJob: Job? = null

    // Engine Instance
    private val engine = PhoneThreeGlyphEngine()
    
    // Latest state for decoupled rendering
    private var latestSpectrum: SpectrumState = SpectrumState()
    private var lastSpectrumUpdate: Long = 0L

    // Glyph button events arrive through this Messenger while the toy is
    // active. Measured on this OS build (see FLAC_TOY traces): a long press
    // delivers a single EVENT_CHANGE on release — action_down/action_up are
    // never sent. So EVENT_CHANGE IS the action: toggle play/pause. The
    // punch-in shutter follows automatically from the transport flip. The
    // down/up handlers stay for future OS builds that may send them.
    private val toyMessenger = android.os.Messenger(android.os.Handler(
        android.os.Looper.getMainLooper()
    ) { msg ->
        android.util.Log.i("FLAC_TOY", "msg what=${msg.what} data=${msg.data?.keySet()?.joinToString { k -> "$k=${msg.data?.get(k)}" }}")
        if (msg.what == GlyphToy.MSG_GLYPH_TOY) {
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            when (event) {
                // While a hold is live, CHANGE is just the OS marking its
                // long-press threshold — the down/up flow owns the gesture.
                // Alone (some OS builds send only CHANGE), it is the toggle.
                GlyphToy.EVENT_CHANGE -> if (!engine.isHolding()) togglePlayback()
                GlyphToy.EVENT_ACTION_DOWN -> {
                    val cal = java.util.Calendar.getInstance()
                    engine.beginHold(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                }
                GlyphToy.EVENT_ACTION_UP -> engine.endHold()
                GlyphToy.EVENT_AOD -> {
                    // One clock, one face: AOD shows the same sleep scene
                    val cal = java.util.Calendar.getInstance()
                    engine.setClock(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                }
            }
        }
        true
    })

    // Direct line to our own session: AudioManager.dispatchMediaKeyEvent
    // proved unreliable from a bound service (verified on device).
    private var mediaController: androidx.media3.session.MediaController? = null

    private fun connectController() {
        if (mediaController != null) return
        try {
            val token = androidx.media3.session.SessionToken(
                this, ComponentName(this, AudioService::class.java)
            )
            val future = androidx.media3.session.MediaController.Builder(this, token)
                .setListener(object : androidx.media3.session.MediaController.Listener {
                    override fun onDisconnected(controller: androidx.media3.session.MediaController) {
                        // AudioService died: drop and reconnect so the
                        // button never silently goes dead.
                        android.util.Log.w("FLAC_TOY", "controller disconnected; reconnecting")
                        mediaController = null
                        mainHandler.postDelayed({ connectController() }, 1_000)
                    }
                })
                .buildAsync()
            future.addListener(
                { try { mediaController = future.get() } catch (e: Exception) { e.printStackTrace() } },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun togglePlayback() {
        // MediaController is main-thread-only; the hold-completion path
        // arrives from the render coroutine, so always hop to main.
        mainHandler.post {
            val c = mediaController
            if (c == null) {
                connectController()
                return@post
            }
            android.util.Log.i("FLAC_TOY", "togglePlayback isPlaying=${c.isPlaying}")
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    private fun dispatchMediaKey(keyCode: Int) { togglePlayback() }

    override fun onBind(intent: Intent?): IBinder? {
        android.util.Log.i("FLAC_TOY", "onBind: ${intent?.action} extras=${intent?.extras?.keySet()?.joinToString()}")
        initGlyphManager()
        connectController()
        startRendering()
        return toyMessenger.binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        stopRendering()
        glyphManager?.closeAppMatrix()
        glyphManager?.unInit()
        glyphManager = null
        return super.onUnbind(intent)
    }

    private fun initGlyphManager() {
        try {
            glyphManager = GlyphMatrixManager.getInstance(applicationContext)
            callback = object : GlyphMatrixManager.Callback {
                override fun onServiceConnected(componentName: ComponentName?) {
                    try {
                        // Register specifically for the Glyph Matrix (Device 23112? or similar)
                        glyphManager?.register(Glyph.DEVICE_23112)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onServiceDisconnected(componentName: ComponentName?) {
                    // Reconnect logic if needed
                }
            }
            glyphManager?.init(callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRendering() {
        renderJob?.cancel()
        analysisJob?.cancel()

        // 1. Collect Spectrum Data independently
        analysisJob = serviceScope.launch {
            PlaybackViewModel.VisualizerBus.spectrum.collectLatest { spectrum ->
                latestSpectrum = spectrum
                lastSpectrumUpdate = System.currentTimeMillis()
            }
        }

        // 2. Physics & Render Loop (Throttled to 15Hz for debugging/stability)
        renderJob = serviceScope.launch {
            val targetFrameInterval = 66_666_667L // ~15 FPS

            while (isActive) {
                val frameStart = System.nanoTime()

                if (glyphManager != null) {
                    // Watchdog: If no data for > 200ms, zero the bands
                    val isStale = (System.currentTimeMillis() - lastSpectrumUpdate) > 200
                    val s = if (isStale) SpectrumState() else latestSpectrum

                    // Transport state comes from the player itself, not from
                    // spectrum staleness — the punch-in must land exactly on
                    // the real flip. Long staleness (dead UI process state)
                    // still falls back to idle.
                    val isPlaying = PlaybackViewModel.VisualizerBus.isPlaying.value &&
                        (System.currentTimeMillis() - lastSpectrumUpdate) < 5_000
                    
                    val dtTarget = targetFrameInterval / 1_000_000 // ~66ms
                    
                    // Update Engine (engine carries the wall clock)
                    val cal = java.util.Calendar.getInstance()
                    engine.setClock(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                    val mixProgress = PlaybackViewModel.VisualizerBus.mixProgress.value
                    val frame = engine.update(s, isPlaying, dtTarget, mixProgress)
                    // A completed 2s hold IS the punch-in: toggle transport
                    if (engine.consumeHoldCompleted()) {
                        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    try {
                        // This call might be blocking, so we measure time around it
                        glyphManager?.setMatrixFrame(frame)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                val frameEnd = System.nanoTime()
                val elapsed = frameEnd - frameStart
                val waitNanos = targetFrameInterval - elapsed
                
                if (waitNanos > 0) {
                    delay(waitNanos / 1_000_000) // Convert to ms
                } else {
                    // Running behind, yield to allow other coroutines to breathe
                    yield()
                }
            }
        }
    }

    private fun stopRendering() {
        renderJob?.cancel()
        analysisJob?.cancel()
        serviceScope.cancel()
    }
}
