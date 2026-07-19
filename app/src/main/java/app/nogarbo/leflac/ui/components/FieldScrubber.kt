package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.ui.theme.GridLines
import app.nogarbo.leflac.ui.theme.SafetyOrange

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FieldScrubber(
    position: Long,
    duration: Long,
    history: List<app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel.SpectralPoint> = emptyList(),
    fullTimeline: List<app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel.SpectralPoint> = emptyList(),
    cuePoints: List<Long> = emptyList(),
    hotSegments: List<app.nogarbo.leflac.data.MixSegmentHeat> = emptyList(),
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    var lastDetent by remember { androidx.compose.runtime.mutableStateOf(-1L) }

    // Local state for smooth dragging
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val progress = if (duration > 0L) {
        (if (isDragging) dragProgress else position.toFloat() / duration.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }
    LaunchedEffect(duration) {
        lastDetent = -1L
        if (duration <= 0L) {
            isDragging = false
            dragProgress = 0f
        }
    }

    // Use a Box to stack the custom drawing and the invisible touch target
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp) // Touch target height
    ) {
        // 1. VISUAL LAYER (Passive)
        val railColor = if (skin.isLcd) skin.shadeLight
            else androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
        val thumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(vertical = 8.dp)
                .align(androidx.compose.ui.Alignment.Center)
                // Only the printed rail is canted. The clock and the touch
                // coordinate system stay registered to the chassis grid.
                .graphicsLayer(rotationZ = -2f)
        ) {
            val width = size.width
            val height = size.height
            val barHeight = 4.dp.toPx()
            val centerY = height / 2f
            
            // 1. Track Background (Rail)
            drawRect(
                color = railColor,
                topLeft = Offset(0f, centerY - barHeight / 2),
                size = Size(width, barHeight)
            )

            // Learned mix heat: a quiet band behind replayed cue intervals.
            if (duration > 0L) {
                hotSegments.forEach { segment ->
                    val startX = width * (segment.startMs.toFloat() / duration).coerceIn(0f, 1f)
                    val endX = width * (segment.endMs.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(
                        color = if (skin.isLcd) skin.shadeMid else skin.rng.copy(alpha = 0.22f),
                        topLeft = Offset(startX, centerY - barHeight * 1.5f),
                        size = Size((endX - startX).coerceAtLeast(2f), barHeight * 3f)
                    )
                }
            }

            // 1.5 CMYK Spectrogram (Intense)
            // DECISION: Full Timeline vs History
            val pointsToDraw = if (fullTimeline.isNotEmpty()) fullTimeline else history
            val isFullMode = fullTimeline.isNotEmpty()

            // OPTIMIZATION: If full mode, downsample to pixel width prevents overdraw
            // e.g. if 3000 points and width 1000px, step = 3
            val step = if (isFullMode && pointsToDraw.size > width) (pointsToDraw.size / width).toInt().coerceAtLeast(1) else 1
            
            // Render Loop
            for (i in pointsToDraw.indices step step) {
                val point = pointsToDraw[i]
                
                // Position
                val markerX = width * point.relativePos
                // In Full Mode, bars are 1px wide lines for density. In History mode, 3dp blocks.
                val barW = if (isFullMode) 2f else 3.dp.toPx() 
                
                // Yellow Layer (Bass) - Bottom/Heavy
                if (point.bass > 0.2f) {
                     val alpha = point.bass.coerceIn(0.5f, 1f)
                     val heightMod = (point.bass * barHeight * 3f).coerceAtMost(barHeight * 3)
                     drawRect(
                        color = if (skin.isLcd) skin.spectroBass else skin.spectroBass.copy(alpha = alpha * 0.3f),
                        topLeft = Offset(markerX, centerY + barHeight / 2),
                        size = Size(barW, heightMod)
                     )
                }

                // Magenta Layer (Mid) - Center/Left
                if (point.mid > 0.2f) {
                    val alpha = point.mid.coerceIn(0.4f, 1f)
                    val heightMod = (point.mid * barHeight * 2.5f)
                    drawRect(
                        color = if (skin.isLcd) skin.spectroMid else skin.spectroMid.copy(alpha = alpha * 0.25f),
                        topLeft = Offset(markerX - 1f, centerY - barHeight / 2 - (heightMod * 0.5f)),
                        size = Size(barW, heightMod)
                    )
                }
                
                // Cyan Layer (Treble) - Top/Right
                if (point.treble > 0.2f) {
                    val alpha = point.treble.coerceIn(0.4f, 1f)
                    val heightMod = (point.treble * barHeight * 2f)
                    drawRect(
                        color = if (skin.isLcd) skin.spectroTreble else skin.spectroTreble.copy(alpha = alpha * 0.25f),
                        topLeft = Offset(markerX + 1f, centerY - barHeight / 2 - heightMod),
                        size = Size(barW, heightMod)
                    )
                }

                // Dramatic Key (Black/Red) - Center Overlay
                // MOVED to separate loop to avoid subsampling
            }

            // 1.8 DRAMA MARKERS (Overlay - No Subsampling)
            // Iterate ALL points to ensure we don't miss short bursts due to 'step' skipping
            pointsToDraw.forEach { point ->
                if (point.isDramatic) {
                    val markerX = width * point.relativePos
                    // Make it TALL and VISIBLE (SafetyOrange)
                    // Full height of the canvas (24dp) + bit of bleed if we could, but clipped.
                    // Just fill the 24dp.
                    drawRect(
                        color = if (skin.isLcd) skin.accent else skin.accent.copy(alpha = 0.8f),
                        topLeft = Offset(markerX, 0f), 
                        size = Size(if(isFullMode) 2f else 3.dp.toPx(), height)
                    )
                }
            }
            
            // 2. Progress Fill
            val drawProgress = (if (isDragging) dragProgress else progress).coerceIn(0f, 1f)
            val progressWidth = width * drawProgress
            drawRect(
                color = skin.accent,
                topLeft = Offset(0f, centerY - barHeight / 2),
                size = Size(progressWidth, barHeight)
            )
            
            // 3. Thumb / Reticle
            val thumbSize = 12.dp.toPx()
            val offset = thumbSize * 0.4f // 3D depth
            val minThumbX = thumbSize / 2f
            val maxThumbX = (width - thumbSize / 2f - offset).coerceAtLeast(minThumbX)
            val thumbX = progressWidth.coerceIn(minThumbX, maxThumbX)
            val thumbY = centerY - thumbSize / 2
            

            // Cube Colors
            val baseCyan = skin.cubeFront
            val topCyan = skin.cubeTop
            val sideCyan = skin.cubeSide
            
            val path = androidx.compose.ui.graphics.Path()
            
            // 1. Front Face
            drawRect(
                color = baseCyan,
                topLeft = Offset(thumbX - thumbSize / 2, thumbY),
                size = Size(thumbSize, thumbSize)
            )
            
            // 2. Top Face (Parallelogram)
            path.reset()
            path.moveTo(thumbX - thumbSize / 2, thumbY)
            path.lineTo(thumbX - thumbSize / 2 + offset, thumbY - offset)
            path.lineTo(thumbX + thumbSize / 2 + offset, thumbY - offset)
            path.lineTo(thumbX + thumbSize / 2, thumbY)
            path.close()
            drawPath(path, topCyan)
            
            // 3. Side Face (Parallelogram)
            path.reset()
            path.moveTo(thumbX + thumbSize / 2, thumbY)
            path.lineTo(thumbX + thumbSize / 2 + offset, thumbY - offset)
            path.lineTo(thumbX + thumbSize / 2 + offset, thumbY + thumbSize - offset)
            path.lineTo(thumbX + thumbSize / 2, thumbY + thumbSize)
            path.close()
            drawPath(path, sideCyan)
            
            // 3.5 Cue Points (mix track boundaries): tall ticks above the rail
            if (duration > 0 && cuePoints.isNotEmpty()) {
                for (cue in cuePoints) {
                    val cx = width * (cue.toFloat() / duration).coerceIn(0f, 1f)
                    val isHotStart = hotSegments.any { it.startMs == cue }
                    drawLine(
                        color = if (isHotStart) skin.rng else
                            skin.accent.copy(alpha = if (skin.isLcd) 1f else 0.7f),
                        start = Offset(cx, centerY - barHeight * 2.5f),
                        end = Offset(cx, centerY - barHeight / 2),
                        strokeWidth = if (isHotStart) 4f else 2f
                    )
                }
            }

            // 4. Time Ticks (Decorative)
            // Draw vertical ticks every 10%
            for (i in 1..9) {
                val tickX = width * (i / 10f)
                drawLine(
                    color = if (skin.isLcd) skin.shadeLight else skin.dim.copy(alpha = 0.5f),
                    start = Offset(tickX, centerY - barHeight),
                    end = Offset(tickX, centerY + barHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        
        // 3. CASIO TIME DISPLAY (Retro)
        // Shows current drag time or playback time
        val displayTimeMillis = if (duration > 0) {
           if (isDragging) (dragProgress * duration).toLong() else position
        } else {
            0L
        }.coerceIn(0L, duration.coerceAtLeast(0L))
        
        val (timeString, microString) = app.nogarbo.leflac.util.TimeFormatter.formatCasioTime(displayTimeMillis)
        val totalTimeString = app.nogarbo.leflac.util.TimeFormatter
            .formatCasioTime(duration.coerceAtLeast(0L)).first
        
        // Position: Top Right of the scrubber box? Or Centered above?
        // Let's put it TopEnd to mimic a device readout.
        
        // Use a Row for Main Time + Microseconds
        androidx.compose.foundation.layout.Row(
             modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(end = 4.dp, top = 0.dp),
             verticalAlignment = androidx.compose.ui.Alignment.Bottom
        ) {
            SegmentedDisplay(
                text = timeString,
                activeColor = if (skin.isLcd) skin.display else skin.display.copy(alpha = 0.6f),
                inactiveColor = if (skin.isLcd) skin.shadeLight else skin.display.copy(alpha = 0.1f),
                digitWidth = 14.dp,
                digitHeight = 24.dp,
                spacing = 2.dp
            )
            
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            
            // Microseconds (Smaller)
            SegmentedDisplay(
                text = microString,
                activeColor = if (skin.isLcd) skin.display else skin.display.copy(alpha = 0.6f),
                inactiveColor = if (skin.isLcd) skin.shadeLight else skin.display.copy(alpha = 0.1f),
                digitWidth = 10.dp, // Smaller
                digitHeight = 16.dp, // Smaller
                spacing = 1.dp
            )
        }
            
        // 2. INTERACTION LAYER (Active Invisible Slider)
        // We map the slider 0f..1f to the duration
        androidx.compose.material3.Slider(
            value = (if (isDragging) dragProgress else progress).coerceIn(0f, 1f),
            enabled = duration > 0L,
            onValueChange = { 
                isDragging = true
                dragProgress = it
                // Jog detents: a tick every 10 seconds of audio
                if (duration > 0) {
                    val detent = (it * duration).toLong() / 10_000L
                    if (detent != lastDetent) {
                        lastDetent = detent
                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            },
            onValueChangeFinished = {
                isDragging = false
                if (duration > 0) {
                    val targetPos = (dragProgress * duration).toLong()
                    android.util.Log.d("FLAC_DEBUG", "SCRUBBER: Slider Release. Target=$targetPos")
                    onSeek(targetPos)
                }
            },
            thumb = {}, // Hides the thumb completely
            track = {}, // The custom Canvas is the only visible rail.
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(androidx.compose.ui.Alignment.Center)
                .semantics {
                    contentDescription = "Playback position"
                    stateDescription = if (duration > 0L) {
                        "$timeString of $totalTimeString" + if (hotSegments.isNotEmpty()) {
                            ", ${hotSegments.size} hot ${if (hotSegments.size == 1) "segment" else "segments"}"
                        } else ""
                    } else {
                        "No track loaded"
                    }
                }
        )
    }
}
