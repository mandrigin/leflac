package app.nogarbo.leflac

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nogarbo.leflac.ui.theme.ChassisBeige
import app.nogarbo.leflac.ui.theme.FieldTheme
import app.nogarbo.leflac.ui.theme.GridLines
import app.nogarbo.leflac.ui.theme.SafetyOrange

@androidx.compose.foundation.ExperimentalFoundationApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Brightness Monitoring
        val brightnessFlow = kotlinx.coroutines.flow.callbackFlow {
            val contentResolver = applicationContext.contentResolver
            val uri = android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS)
            
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(getSystemBrightness(contentResolver))
                }
            }
            
            contentResolver.registerContentObserver(uri, false, observer)
            trySend(getSystemBrightness(contentResolver)) // Initial value
            
            awaitClose {
                contentResolver.unregisterContentObserver(observer)
            }
        }

        setContent {
            val brightness by brightnessFlow.collectAsState(initial = 255) // Default to max
            // Low brightness threshold: 25% of 255 is approx 64.
            val isLowBrightness = brightness < 64

            // Rear-panel DIP switches: skin override and machine voice
            val devicePrefs = remember { getSharedPreferences("flac_prefs", MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(devicePrefs.getInt("theme_mode", 0)) } // 0 AUTO, 1 FIELD, 2 POCKET
            var androidAutoVisualScheme by remember {
                mutableStateOf(app.nogarbo.leflac.data.AndroidAutoVisualScheme.read(this@MainActivity))
            }
            var voiceOn by remember { mutableStateOf(devicePrefs.getBoolean("ui_voice", false)) }
            LaunchedEffect(voiceOn) { app.nogarbo.leflac.util.MachineVoice.enabled = voiceOn }
            val isDark = when (themeMode) { 1 -> false; 2 -> true; else -> isLowBrightness }

            FieldTheme(isDark = isDark) {
                // ... rest of content ...
                val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
                val context = LocalContext.current
                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            if (android.os.Build.VERSION.SDK_INT >= 33) 
                                android.Manifest.permission.READ_MEDIA_AUDIO
                            else 
                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { permissions ->
                        hasPermission = permissions.containsValue(false).not()
                        if (hasPermission) {
                            app.nogarbo.leflac.service.AudioService.instance
                                ?.refreshLocalAudioLibrary()
                        }
                    }
                )

                LaunchedEffect(Unit) {
                    if (!hasPermission) {
                        launcher.launch(
                            if (android.os.Build.VERSION.SDK_INT >= 33) 
                                arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                            else 
                                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        )
                    }
                }

                val playbackViewModel: app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel = viewModel()
                val libraryViewModel: app.nogarbo.leflac.ui.library.LibraryViewModel = viewModel()
                val isPlaying by playbackViewModel.isPlaying.collectAsState()
                val duration by playbackViewModel.duration.collectAsState()
                val spectrum by playbackViewModel.spectrum.collectAsState()
                val isShuffleEnabled by playbackViewModel.isShuffleEnabled.collectAsState()
                val currentMediaId by playbackViewModel.currentMediaId.collectAsState()
                val upNext by playbackViewModel.upNext.collectAsState()
                val mixes by libraryViewModel.mixes.collectAsState()
                val isMixPlaying = currentMediaId != null && mixes.any { it.uri.toString() == currentMediaId }

                // Headphone Detection (Bluetooth + Wired)
                var isHeadphonesConnected by remember { mutableStateOf(false) }
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                
                DisposableEffect(Unit) {
                    val callback = object : android.media.AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                            updateHeadphoneState(audioManager) { isHeadphonesConnected = it }
                        }
                        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                            updateHeadphoneState(audioManager) { isHeadphonesConnected = it }
                        }
                    }
                    
                    // Initial check
                    updateHeadphoneState(audioManager) { isHeadphonesConnected = it }
                    
                    audioManager.registerAudioDeviceCallback(callback, null)
                    onDispose {
                        audioManager.unregisterAudioDeviceCallback(callback)
                    }
                }
                
                // Flip-to-rear: the manual is printed on the back of the unit.
                // First run opens on the back — unboxing means reading the manual.
                var rearVisible by remember {
                    mutableStateOf(devicePrefs.getBoolean("first_run_rear", true))
                }
                LaunchedEffect(Unit) {
                    if (devicePrefs.getBoolean("first_run_rear", true)) {
                        devicePrefs.edit().putBoolean("first_run_rear", false).apply()
                        kotlinx.coroutines.delay(5_000)
                        rearVisible = false
                    }
                }

                // Power-on self test — once a day, so it stays a greeting
                val today = (System.currentTimeMillis() / 86_400_000L).toInt()
                var booting by remember {
                    mutableStateOf(devicePrefs.getInt("last_boot_day", -1) != today)
                }
                LaunchedEffect(Unit) {
                    if (booting) {
                        devicePrefs.edit().putInt("last_boot_day", today).apply()
                        app.nogarbo.leflac.util.MachineVoice.boot()
                        kotlinx.coroutines.delay(950)
                        booting = false
                    }
                }

                // First entry into pocket mode gets one etched announcement
                var pocketEtch by remember { mutableStateOf(false) }
                LaunchedEffect(isDark) {
                    if (isDark && !devicePrefs.getBoolean("seen_pocket", false)) {
                        devicePrefs.edit().putBoolean("seen_pocket", true).apply()
                        pocketEtch = true
                        kotlinx.coroutines.delay(1_400)
                        pocketEtch = false
                    }
                }
                val flipAngle by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (rearVisible) 180f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(650),
                    label = "rearFlip"
                )

                // Main Container with Grid Background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationY = flipAngle
                            cameraDistance = 18f * density
                        }
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                ) {
                    val dropTension by playbackViewModel.dropTension.collectAsState()
                    val tensionAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = 1f - 0.7f * dropTension,
                        label = "tension"
                    )
                    // One strong haptic when the drop lands
                    val hapticHost = androidx.compose.ui.platform.LocalView.current
                    LaunchedEffect(spectrum.isDramatic) {
                        if (spectrum.isDramatic && isPlaying) {
                            hapticHost.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tensionAlpha }) {
                        // Phase the chassis grid to the 16dp content margin and the
                        // 28dp system-strip seam so the plates register to the print.
                        GridBackground(offsetX = 16.dp, offsetY = 28.dp)
                    }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        val gymOn by playbackViewModel.gymMode.collectAsState()
                        val position by playbackViewModel.position.collectAsState()
                        val cuePoints by playbackViewModel.cuePoints.collectAsState()

                        // Racked sideways the unit becomes a desk deck: faceplate
                        // and transport on the left, the ledger as a side panel.
                        val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE

                        // ZONE 1 — SYSTEM STRIP: nameplate, mode, rail, glyph state, time
                        SystemStrip(
                            isPocket = skin.isLcd,
                            isPlaying = isPlaying,
                            isMixPlaying = isMixPlaying,
                            isShuffleEnabled = isShuffleEnabled,
                            cueIndex = if (isMixPlaying && cuePoints.isNotEmpty())
                                cuePoints.count { it <= position } else -1,
                            gymOn = gymOn,
                            onGymEnd = { playbackViewModel.setGymMode(false) },
                            onNameplate = { rearVisible = true },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // ZONE 2 — FACEPLATE: now playing owns this plate, nothing else.
                        // Portrait: 200dp = exactly five grid cells, seam on a grid line.
                        // Landscape: whatever height the transport leaves it.
                        val faceplateZone: @Composable (Modifier) -> Unit = { faceModifier ->
                        Box(
                            modifier = faceModifier
                                .fillMaxWidth()
                        ) {
                            // val spectrum by playbackViewModel.spectrum.collectAsState() // Lifted to top level

                            // LAYER 0: HEADPHONES WATERMARK
                            if (isHeadphonesConnected) {
                                app.nogarbo.leflac.ui.components.HeadphonesIcon(
                                    modifier = Modifier
                                        .size(if (isLandscape) 130.dp else 200.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = if (isLandscape) 30.dp else 50.dp, y = (-16).dp), // Offset to sit nicely in corner
                                    color = skin.watermark // Ghost look
                                )
                            }

                            // LAYER 0b: MIXTAPE — the cassette IS the mix ornament: shifted
                            // half its width toward centre so it owns the middle band and
                            // leaves the corner to the headphones ghost. Slight loaded-tape
                            // tilt (the rail tilts too — it's in the language).
                            if (isMixPlaying) {
                                app.nogarbo.leflac.ui.components.MixtapeIcon(
                                    modifier = Modifier
                                        .size(if (isLandscape) 96.dp else 136.dp)
                                        .align(Alignment.CenterEnd)
                                        .offset(x = if (isLandscape) (-48).dp else (-68).dp, y = 8.dp)
                                        .graphicsLayer { rotationZ = -4f },
                                    color = if (skin.isLcd) skin.accent else skin.accent.copy(alpha = 0.9f), // Accent, matching the mix_##m tag
                                    spinning = isPlaying, // reels only scroll while actually playing
                                    label = "mix_${duration / 60000L}m" // printed on the cassette label
                                )
                            }

                            val metadata by playbackViewModel.mediaMetadata.collectAsState()
                            val mediaTitle = metadata.title?.toString() ?: "LE FLAC"
                            val titleText = mediaTitle.uppercase()
                            
                            // ZERO SPECTRUM IF PAUSED (Global "Dial Down")
                            val effectiveSpectrum = if (isPlaying) spectrum else app.nogarbo.leflac.service.SpectrumState()
                            val analysisProgress by playbackViewModel.analysisProgress.collectAsState()
                            
                            // STANDARD UI
                                // TITLE LOADER (Layer 0 - Background)
                                // Mix titles are long, so step the now-playing title down a
                                // perfect-fourth (1.333) modular scale — the Swiss typographic
                                // scale — from 57sp: 57 → 42.8 → 32 → 24sp.
                                val titleStyle = androidx.compose.material3.MaterialTheme.typography.displayLarge
                                val wordCount = titleText.split(Regex("\\s+")).count { it.isNotBlank() }
                                // Mixes get the same hero treatment: size follows length, not type.
                                val fitStyle = when {
                                    wordCount >= 5 || titleText.length > 24 ->
                                        titleStyle.copy(fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = 0.sp)
                                    wordCount >= 3 || titleText.length > 16 ->
                                        titleStyle.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = 0.sp)
                                    titleText.length >= 12 ->
                                        titleStyle.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 0.sp)
                                    else -> titleStyle.copy(fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = 0.sp)
                                }

                                Column(
                                    modifier = Modifier
                                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                                        .align(Alignment.TopStart)
                                ) {
                                    // No zone label: a real unit doesn't narrate its own
                                    // faceplate. The type IS the print.
                                    app.nogarbo.leflac.ui.components.GlitchText(
                                        text = titleText,
                                        style = fitStyle,
                                        spectrum = effectiveSpectrum,
                                        idle = !isPlaying,
                                        straining = analysisProgress < 0.97f,
                                        modifier = Modifier
                                    )

                                    // Artist · album line, engraved under the hero title
                                    val artistLine = listOf(
                                        metadata.artist?.toString().orEmpty(),
                                        metadata.albumTitle?.toString().orEmpty()
                                    ).filter {
                                        // <unknown> is filler, not information — engrave nothing
                                        it.isNotBlank() && !it.trim('<', '>').equals("unknown", ignoreCase = true)
                                    }.joinToString(" · ").uppercase()
                                    if (artistLine.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = artistLine,
                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                letterSpacing = 0.sp
                                            ),
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = skin.dim,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                            // CUE LADDER — a mix faceplate is a deck sheet: the printed
                            // cue index fills the quiet band, and it's functional —
                            // tap a row to jump the tape there.
                            if (isMixPlaying && cuePoints.isNotEmpty()) {
                                val cueIdx = cuePoints.count { it <= position }
                                val ladderStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    letterSpacing = 0.sp
                                )
                                fun cueTime(ms: Long): String {
                                    val totalSec = ms / 1000
                                    val h = totalSec / 3600
                                    val m = (totalSec / 60) % 60
                                    val s = totalSec % 60
                                    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                                        else String.format("%02d:%02d", m, s)
                                }
                                // Current segment start plus the next two cues
                                val ladder = buildList {
                                    add(cueIdx to (if (cueIdx > 0) cuePoints[cueIdx - 1] else 0L))
                                    if (cueIdx < cuePoints.size) add(cueIdx + 1 to cuePoints[cueIdx])
                                    if (cueIdx + 1 < cuePoints.size) add(cueIdx + 2 to cuePoints[cueIdx + 1])
                                }
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 16.dp, bottom = 32.dp)
                                ) {
                                    ladder.forEachIndexed { i, (n, ms) ->
                                        val isCurrent = i == 0
                                        Text(
                                            text = "${if (isCurrent) "▸" else " "} CUE ${String.format("%02d", n)} · ${cueTime(ms)}",
                                            style = ladderStyle,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = if (isCurrent) skin.accent else skin.dim,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clickable { playbackViewModel.seekTo(ms) }
                                                .padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            // NOW-META: telemetry strip + analysis state, pinned to the plate's foot
                            val mixBpm = remember(currentMediaId, isMixPlaying) {
                                if (isMixPlaying) currentMediaId?.let {
                                    app.nogarbo.leflac.data.TrackProfileStore.get(context, it)?.bpm
                                } ?: 0 else 0
                            }
                            val telemetry = if (isMixPlaying) {
                                (if (mixBpm > 0) "BPM:$mixBpm · " else "") + "CUES:${cuePoints.size}"
                            } else {
                                "K:${(spectrum.kick * 10).toInt()} B:${(spectrum.bassGuitar * 10).toInt()} S:${(spectrum.snare * 10).toInt()} G:${(spectrum.guitar * 10).toInt()} V:${(spectrum.vocal * 10).toInt()} Y:${(spectrum.synth * 10).toInt()} C:${(spectrum.cymbals * 10).toInt()}"
                            }
                            val faceState = when {
                                analysisProgress < 0f -> "ANALYSIS FAILED"
                                analysisProgress < 0.97f -> "ANALYZING ${(analysisProgress * 100).toInt()}%"
                                isMixPlaying -> "MIX MAP"
                                else -> "ANALYZED"
                            }
                            val faceStateColor = if (analysisProgress < 0.97f) skin.accent else skin.dim
                            val metaStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 0.sp
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = telemetry,
                                    style = metaStyle,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = skin.rng,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer { alpha = tensionAlpha }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, faceStateColor)
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = faceState,
                                        style = metaStyle.copy(fontSize = 9.sp),
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = faceStateColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        }

                        // ZONE 3 — TRANSPORT DECK: scrubber, time, immediate controls.
                        // Compact in landscape: the deck flattens to leave the faceplate air.
                        val transportZone: @Composable () -> Unit = {
                            val controlsBg = androidx.compose.material3.MaterialTheme.colorScheme.background
                            // LCD has no alpha: the fade becomes a stepped Bayer dither ramp.
                            val ditherRamp = remember(skin.isLcd, controlsBg) {
                                if (skin.isLcd) (1..6).map { app.nogarbo.leflac.ui.components.ditherBrush(controlsBg, it / 6f) }
                                else emptyList()
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (skin.isLcd) Modifier.drawBehind {
                                            val fadeH = 400f
                                            val bandH = fadeH / ditherRamp.size
                                            ditherRamp.forEachIndexed { i, brush ->
                                                drawRect(
                                                    brush = brush,
                                                    topLeft = androidx.compose.ui.geometry.Offset(0f, i * bandH),
                                                    size = androidx.compose.ui.geometry.Size(size.width, bandH + 1f)
                                                )
                                            }
                                            if (size.height > fadeH) {
                                                drawRect(
                                                    color = controlsBg,
                                                    topLeft = androidx.compose.ui.geometry.Offset(0f, fadeH),
                                                    size = androidx.compose.ui.geometry.Size(size.width, size.height - fadeH)
                                                )
                                            }
                                        } else Modifier.background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                colors = listOf(
                                                    controlsBg.copy(alpha = 0f),
                                                    controlsBg.copy(alpha = 0.95f), // Stronger fade for legibility
                                                    controlsBg
                                                ),
                                                startY = 0f,
                                                endY = 400f
                                            )
                                        )
                                    )
                            ) {
                                // TAPE REEL
                                val history by playbackViewModel.spectralHistory.collectAsState()
                                val fullTimeline by playbackViewModel.fullTimeline.collectAsState()

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (isLandscape) 88.dp else 120.dp)
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    app.nogarbo.leflac.ui.components.FieldScrubber(
                                        position = position,
                                        duration = duration,
                                        history = history,
                                        fullTimeline = fullTimeline,
                                        cuePoints = cuePoints,
                                        onSeek = { pos -> playbackViewModel.seekTo(pos) },
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                                
                                // TRANSPORT CONTROLS
                                val globalPunchInAnimatable = remember { androidx.compose.animation.core.Animatable(0f) }
                                val scope = androidx.compose.runtime.rememberCoroutineScope()
                                val transportLabelStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    letterSpacing = 0.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                val transportInk = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                                val neutralContent = if (skin.isLcd) skin.buttonContent else transportInk
                                val prevLabel = if (isMixPlaying) "CUE-" else "PREV"
                                val nextLabel = if (isMixPlaying) "CUE+" else "NEXT"

                                // One hardware baseline: every control sits on the same
                                // deck line, only the taller primary rises above it.
                                // Portrait: 12 + 84 + 24 = 120dp — with the 120dp scrubber
                                // the deck is six grid cells, so the ledger seam hits a line.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = if (isLandscape) 4.dp else 12.dp,
                                            bottom = if (isLandscape) 12.dp else 24.dp
                                        ),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    // PREV
                                    app.nogarbo.leflac.ui.components.IsometricButton(
                                        text = prevLabel,
                                        onClick = {
                                            if (isMixPlaying || !isShuffleEnabled) {
                                                playbackViewModel.playPreviousSequential(libraryViewModel.allTracks.value)
                                            } else {
                                                playbackViewModel.skipToPrevious()
                                            }
                                        },
                                        onLongClick = {
                                            scope.launch {
                                                globalPunchInAnimatable.snapTo(1f)
                                                globalPunchInAnimatable.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                                            }
                                            playbackViewModel.playPreviousSequentialFile(libraryViewModel.allTracks.value)
                                        },
                                        size = 48.dp,
                                        baseColor = skin.buttonBase,
                                        contentColor = neutralContent,
                                        bassLevel = spectrum.bass,
                                        textStyle = transportLabelStyle,
                                        globalPunchIn = globalPunchInAnimatable.value,
                                        isPunchInSource = true
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // PLAY / PAUSE — the loudest control says its name
                                    app.nogarbo.leflac.ui.components.IsometricButton(
                                        text = if (isPlaying) "PAUSE" else "PLAY",
                                        onClick = { playbackViewModel.togglePlayPause() },
                                        size = 64.dp,
                                        baseColor = skin.accent,
                                        contentColor = skin.chassis,
                                        bassLevel = spectrum.bass,
                                        textStyle = transportLabelStyle.copy(fontSize = 11.sp),
                                        globalPunchIn = globalPunchInAnimatable.value
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // NEXT + RNG under one latch: it bridges exactly the
                                    // two controls whose future it reroutes.
                                    Column {
                                        RailLatch(
                                            rngOn = isShuffleEnabled,
                                            onToggle = {
                                                playbackViewModel.setAutoMode(
                                                    !isShuffleEnabled,
                                                    libraryViewModel.allTracks.value
                                                )
                                            },
                                            modifier = Modifier.width(124.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            app.nogarbo.leflac.ui.components.IsometricButton(
                                                text = nextLabel,
                                                onClick = {
                                                    if (isMixPlaying) {
                                                        playbackViewModel.playNextSequential(libraryViewModel.allTracks.value)
                                                    } else if (isShuffleEnabled) {
                                                        playbackViewModel.playNextRandom(libraryViewModel.allTracks.value)
                                                    } else {
                                                        playbackViewModel.playNextSequential(libraryViewModel.allTracks.value)
                                                    }
                                                },
                                                onLongClick = {
                                                    scope.launch {
                                                        globalPunchInAnimatable.snapTo(1f)
                                                        globalPunchInAnimatable.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                                                    }
                                                    playbackViewModel.playNextSequentialFile(libraryViewModel.allTracks.value)
                                                },
                                                size = 48.dp,
                                                baseColor = skin.buttonBase,
                                                contentColor = neutralContent,
                                                bassLevel = spectrum.bass,
                                                textStyle = transportLabelStyle,
                                                globalPunchIn = globalPunchInAnimatable.value
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            // RNG one-shot: a real button, never an empty
                                            // outline — soft cyan face, deck-cyan label.
                                            app.nogarbo.leflac.ui.components.IsometricButton(
                                                text = "RNG",
                                                onClick = { playbackViewModel.playNextRandom(libraryViewModel.allTracks.value) },
                                                size = 48.dp,
                                                baseColor = if (skin.isLcd) skin.buttonBase
                                                    else skin.rng.copy(alpha = 0.12f).compositeOver(controlsBg),
                                                contentColor = if (skin.isLcd) skin.buttonContent else skin.cubeFront,
                                                bassLevel = spectrum.bass,
                                                textStyle = transportLabelStyle,
                                                globalPunchIn = globalPunchInAnimatable.value
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ZONE 4 — LEDGER: the collection, calm and readable
                        // LIBRARY SECTION
                        val ledgerZone: @Composable () -> Unit = {
                        if (hasPermission) {
                             
                             LaunchedEffect(Unit) {
                                  libraryViewModel.scanLibrary()
                             }

                             app.nogarbo.leflac.ui.library.LibraryGrid(
                                  viewModel = libraryViewModel,
                                  playingTrackId = currentMediaId,
                                  upNext = upNext,
                                  onGymStart = { playbackViewModel.setGymMode(true) },
                                  onTracksQueued = { tracks ->
                                      val result = playbackViewModel.scheduleUpNext(
                                          tracks,
                                          libraryViewModel.allTracks.value
                                      )
                                      val message = when (result) {
                                          app.nogarbo.leflac.service.ScheduleUpNextResult.ADDED ->
                                              "UP NEXT · ${tracks.size.toString().padStart(2, '0')}"
                                          app.nogarbo.leflac.service.ScheduleUpNextResult.ARMED ->
                                              "DECK ARMED · PRESS PLAY"
                                          app.nogarbo.leflac.service.ScheduleUpNextResult.ALREADY_QUEUED ->
                                              "ALREADY UP NEXT"
                                          app.nogarbo.leflac.service.ScheduleUpNextResult.WRONG_POOL ->
                                              "QUEUE LOCKED TO ${if (isMixPlaying) "MIXES" else "SONGS"}"
                                          app.nogarbo.leflac.service.ScheduleUpNextResult.UNAVAILABLE ->
                                              "QUEUE CONTROLLER OFFLINE"
                                      }
                                      android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                      result != app.nogarbo.leflac.service.ScheduleUpNextResult.WRONG_POOL &&
                                          result != app.nogarbo.leflac.service.ScheduleUpNextResult.UNAVAILABLE
                                  },
                                  onUpNextRemoved = playbackViewModel::removeFromUpNext,
                                  onUpNextCleared = playbackViewModel::clearUpNext,
                                  onTrackSelected = { track ->
                                       // Smart queue stays within the track's pool: mixes shuffle
                                       // among mixes, songs among songs.
                                       val allTracks = libraryViewModel.allTracks.value
                                       val playlist = if (isShuffleEnabled) {
                                           playbackViewModel.generateSmartQueue(allTracks, track)
                                       } else {
                                           app.nogarbo.leflac.service.playbackPool(allTracks, track)
                                       }

                                       val uris = ArrayList<android.os.Parcelable>(playlist.map { it.uri })
                                       val titles = ArrayList<String>(playlist.map { it.title })
                                       val artists = ArrayList<String>(playlist.map { it.artist })
                                       val durations = playlist.map { it.duration }.toLongArray()
                                       val startIndex = if (isShuffleEnabled) 0 else playlist.indexOf(track).coerceAtLeast(0)

                                       val intent = Intent(context, app.nogarbo.leflac.service.AudioService::class.java)
                                       intent.action = app.nogarbo.leflac.service.AudioCommandBus.ACTION_PLAY_LIST
                                       intent.putParcelableArrayListExtra("URIS", uris)
                                       intent.putStringArrayListExtra("TITLES", titles)
                                       intent.putStringArrayListExtra("ARTISTS", artists)
                                       intent.putExtra("DURATIONS", durations)
                                       intent.putExtra("START_INDEX", startIndex)
                                       context.startForegroundService(
                                           app.nogarbo.leflac.service.AudioCommandBus.authorize(intent)
                                       )
                                  }
                             )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        // Deliver on the promise: open this app's settings page
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", context.packageName, null)
                                        )
                                        context.startActivity(intent)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "ACCESS DENIED\n[TAP SETTINGS]",
                                    style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                                    color = skin.accent,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        }

                        // THE RACK: portrait stacks the plates; landscape splits
                        // deck (left) from ledger (right) along a vertical seam.
                        if (isLandscape) {
                            Row(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.weight(0.56f)) {
                                    faceplateZone(Modifier.weight(1f))
                                    transportZone()
                                }
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(skin.dim)
                                )
                                Box(modifier = Modifier.weight(0.44f)) {
                                    ledgerZone()
                                }
                            }
                        } else {
                            faceplateZone(Modifier.height(200.dp))
                            transportZone()
                            ledgerZone()
                        }
                    }

                    // OWL OVERLAY (Top Z-Index)
                    app.nogarbo.leflac.ui.components.OwlAnimation(
                        isDramatic = spectrum.isDramatic,
                        modifier = Modifier.fillMaxSize().zIndex(10f) // Ensure it's on top
                    )

                    // LCD GLASS (above everything: the UI lives behind the screen)
                    if (skin.isLcd) {
                        app.nogarbo.leflac.ui.components.LcdScreenOverlay(
                            modifier = Modifier.fillMaxSize().zIndex(11f),
                            resting = !isPlaying
                        )
                    }

                    if (pocketEtch) {
                        Box(
                            modifier = Modifier.fillMaxSize().zIndex(19f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "POCKET MODE",
                                style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    // POWER-ON SELF TEST
                    if (booting) {
                        app.nogarbo.leflac.ui.components.BootOverlay(
                            modifier = Modifier.fillMaxSize().zIndex(20f)
                        )
                    }

                    // REAR OF THE UNIT (visible past 90 degrees of flip)
                    if (flipAngle > 90f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(12f)
                                .graphicsLayer { rotationY = 180f } // un-mirror
                        ) {
                            app.nogarbo.leflac.ui.components.RearPanel(
                                themeMode = themeMode,
                                onThemeModeChange = {
                                    themeMode = it
                                    devicePrefs.edit().putInt("theme_mode", it).apply()
                                },
                                androidAutoVisualScheme = androidAutoVisualScheme,
                                onAndroidAutoVisualSchemeChange = { scheme ->
                                    androidAutoVisualScheme = scheme
                                    app.nogarbo.leflac.data.AndroidAutoVisualScheme.write(
                                        this@MainActivity,
                                        scheme
                                    )
                                    app.nogarbo.leflac.service.AudioService.instance
                                        ?.notifyAndroidAutoVisualSchemeChanged()
                                    playbackViewModel.refreshCarArtwork()
                                },
                                voiceOn = voiceOn,
                                onVoiceChange = {
                                    voiceOn = it
                                    devicePrefs.edit().putBoolean("ui_voice", it).apply()
                                },
                                onTap = { rearVisible = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getSystemBrightness(resolver: android.content.ContentResolver): Int {
        return try {
            android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: android.provider.Settings.SettingNotFoundException) {
            255
        }
    }
}


@Composable
fun SystemStrip(
    isPocket: Boolean,
    isPlaying: Boolean,
    isMixPlaying: Boolean,
    isShuffleEnabled: Boolean,
    cueIndex: Int, // -1 = not a mix, or no cue map yet
    gymOn: Boolean,
    onGymEnd: () -> Unit,
    onNameplate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val statusTime by androidx.compose.runtime.produceState(initialValue = "") {
        while (true) {
            value = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
            kotlinx.coroutines.delay(30_000)
        }
    }
    val stripStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        letterSpacing = 0.sp
    )
    // Hairline plate seam; a solid shade, never an alpha blend
    val seam = skin.dim

    Row(
        modifier = modifier
            .height(28.dp)
            .drawBehind {
                drawLine(
                    color = seam,
                    start = Offset(0f, size.height - 0.5f),
                    end = Offset(size.width, size.height - 0.5f),
                    strokeWidth = 1f
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // NAMEPLATE — the model badge lives on the strip; tap to flip the
        // unit over and read the manual.
        Box(
            modifier = Modifier
                .border(1.dp, skin.dim)
                .clickable(onClick = onNameplate)
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = "LF-1",
                style = stripStyle.copy(fontSize = 9.sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = skin.dim,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        // Live LED: lit while the deck runs
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isPlaying) skin.accent else skin.dim)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${if (isPocket) "POCKET" else "FIELD"} · ${if (isMixPlaying) "MIX" else "SONG"}",
            style = stripStyle,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = skin.dim,
            maxLines = 1
        )
        if (gymOn) {
            Text(
                text = " · [GYM · TAP TO END]",
                style = stripStyle,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = skin.accent,
                maxLines = 1,
                modifier = Modifier.clickable { onGymEnd() }
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "RAIL ${if (isShuffleEnabled) "RNG" else "ORDER"} · ${
                if (cueIndex >= 0) String.format("CUE %02d", cueIndex) else "GLYPH READY"
            } · $statusTime",
            style = stripStyle,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = skin.dim,
            maxLines = 1
        )
    }
}

@Composable
fun RailLatch(
    rngOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    // A small hardware slide switch: recessed slot, engraved position labels,
    // and a pseudo-3D knob that travels to whichever rail owns the future.
    val knobBias by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (rngOn) 1f else -1f,
        label = "latchKnob"
    )
    val knobColor by androidx.compose.animation.animateColorAsState(
        // The knob speaks rail color: ink = ORDER, deck cyan = RNG
        targetValue = if (rngOn) { if (skin.isLcd) skin.rng else skin.cubeFront } else ink,
        label = "latchKnobColor"
    )
    val latchLabelStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
        fontSize = 8.sp,
        letterSpacing = 0.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    )

    Box(
        modifier = modifier
            .height(20.dp)
            .border(1.dp, skin.dim)
            .clickable(onClick = onToggle)
            .padding(2.dp)
    ) {
        // Engraved position labels on the slot floor
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "ORDER", style = latchLabelStyle, color = skin.dim, maxLines = 1)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "RNG", style = latchLabelStyle, color = skin.dim, maxLines = 1)
            }
        }
        // The knob: a small extruded plate, same 3D language as the buttons
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.BiasAlignment(knobBias, 0f))
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 2.dp, y = 2.dp)
                    .background(skin.shadeMid)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(knobColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (rngOn) "RNG" else "ORDER",
                    style = latchLabelStyle,
                    color = skin.chassis,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun GridBackground(
    offsetX: androidx.compose.ui.unit.Dp = 0.dp,
    offsetY: androidx.compose.ui.unit.Dp = 0.dp
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    // 2-bit on LCD: a solid shade, never an alpha blend
    val gridColor = if (skin.isLcd) skin.shadeLight
        else androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 40.dp.toPx()
        val width = size.width
        val height = size.height

        // Registration: the grid is phased so lines land on the content
        // margin and the plate seams — components sit ON the print.
        var x = offsetX.toPx() % step
        while (x <= width) {
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )
            x += step
        }

        var y = offsetY.toPx() % step
        while (y <= height) {
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )
            y += step
        }
    }
}

fun updateHeadphoneState(audioManager: android.media.AudioManager, onResult: (Boolean) -> Unit) {
    val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
    val hasHeadphones = devices.any { device ->
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
    }
    onResult(hasHeadphones)
}
