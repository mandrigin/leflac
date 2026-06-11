package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.service.SpectrumState
import app.nogarbo.leflac.service.SpectralPhosphorEngine
import app.nogarbo.leflac.service.BoxerEngine
import app.nogarbo.leflac.service.SimpleBarsEngine
import app.nogarbo.leflac.ui.theme.ChassisBeige
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

@Composable
fun LegacyGlyphToy(
    spectrum: SpectrumState,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val bgColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val gridColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(320.dp) // Fixed width widget feel
                .aspectRatio(1f) // Square
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .border(2.dp, gridColor, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { 
                            onLongClick?.invoke()
                        }
                    )
                }
                .padding(16.dp) 
        ) {
             GlyphCanvas(spectrum, isPlaying)
        }
    }
}

@Composable
fun GlyphCanvas(spectrum: SpectrumState, isPlaying: Boolean) {
    // Engine State
    val s = SpectrumState() // Dummy for now locally, or we can pipe it?
    // Actually the UI simulation should also run SimpleBarsEngine to match hardware
    val engine = remember { SimpleBarsEngine() } 
    // val engine = remember { BoxerEngine() }
    
    // State to hold the current frame to render
    // Start with empty frame
    val currentFrame = remember { mutableStateOf(IntArray(25 * 25)) }
    
    // If paused, force idle state
    val effectiveSpectrum = if (isPlaying) spectrum else SpectrumState()

    // Physics Loop (Decoupled from Recomposition)
    // Run at ~30fps for the UI widget to match the "Toy" feel and not rush
    LaunchedEffect(Unit) {
        while (isActive) {
            val s = if (isPlaying) spectrum else SpectrumState()
            
            // Map Spectrum to 6 Bands
            val bands = floatArrayOf(
                s.kick,
                s.bassGuitar,
                s.snare,
                s.guitar,
                s.vocal,
                s.cymbals
            )
            
            // Update Engine
            // SimpleBarsEngine takes bands + isDramaticInversion + isShockwaveTrigger
            // Re-linked shockwave to isDramatic
            val frameData = engine.update(bands, s.isDramatic, s.isDramatic)
            currentFrame.value = frameData.clone()
            
            // Target ~60Hz (16ms)
            // User noted widget had "higher frame rate" and liked it.
            delay(16)
        }
    }

    val frame = currentFrame.value

    // Blinking logic for Pause Text opacity
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        
        // Grid Params
        val dim = 25
        val cellW = w / dim
        val cellH = h / dim
        val dotSize = minOf(cellW, cellH) * 0.8f
        
        // Draw Grid
        for (y in 0 until dim) {
            for (x in 0 until dim) {
                // Get Color from Frame
                // Frame is IntArray of ARGB ints
                val valInt = frame[y * dim + x]
                
                // New Mode: Raw Brightness Integer (0 - 512)
                // Map to 0.0 - 1.0
                val brightness = (valInt / 512f).coerceIn(0f, 1f)
                
                if (brightness > 0.01f) {
                    // Draw Dot
                    val baseColor = app.nogarbo.leflac.ui.theme.SafetyOrange
                    val dotColor = baseColor.copy(alpha = brightness)
                    
                    drawCircle(
                        color = dotColor,
                        radius = dotSize / 2,
                        center = Offset(
                            x = x * cellW + cellW / 2,
                            y = y * cellH + cellH / 2
                        )
                    )
                } else {
                    // Draw faint ghost dot for grid feeling?
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.1f),
                        radius = dotSize / 6,
                         center = Offset(
                            x = x * cellW + cellW / 2,
                            y = y * cellH + cellH / 2
                        )
                    )
                }
            }
        }
    }
    
    // PAUSED OVERLAY
    if (!isPlaying) {
         Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
             androidx.compose.material3.Text(
                 text = "// SYSTEM PAUSED //",
                 color = app.nogarbo.leflac.ui.theme.SafetyOrange.copy(alpha = if (blinkAlpha > 0.5f) 1f else 0.2f),
                 style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                 fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                 modifier = Modifier
                     .background(ChassisBeige.copy(alpha=0.8f))
                     .padding(horizontal = 8.dp, vertical = 4.dp)
             )
         }
    }
}
