package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun OwlAnimation(
    isDramatic: Boolean,
    modifier: Modifier = Modifier
) {
    // State to track if we are currently flying
    // We want to trigger ONLY on the rising edge (False -> True)
    var isFlying by remember { mutableStateOf(false) }
    
    // Y Position (Randomized per flight)
    var flyHeightRatio by remember { mutableStateOf(0.5f) }
    
    // X Animatble (0f = Right Edge, 1f = Left Edge - but we usually think in pixels)
    // Let's use absolute pixel offsets for cleaner control in BoxWithConstraints
    // Start: ScreenWidth + Buffer
    // End: -Buffer
    
    val previousDramatic = remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        
        // Start off-screen right
        val startX = widthPx + 100f
        val endX = -200f
        
        val owlX = remember { Animatable(startX) }
        
        // Scope for independent animation (won't be cancelled if isDramatic changes)
        val scope = rememberCoroutineScope()

        // Detect Rising Edge
        LaunchedEffect(isDramatic) {
            if (isDramatic && !previousDramatic.value) {
                // Rising Edge! Trigger Flight!
                if (!isFlying) {
                    isFlying = true
                    flyHeightRatio = Random.nextFloat() * 0.8f + 0.1f // 10% to 90% height
                    
                    // Reset to start
                    scope.launch {
                        owlX.snapTo(startX)
                        
                        // Fly!
                        owlX.animateTo(
                            targetValue = endX,
                            animationSpec = tween(durationMillis = 3500, easing = LinearEasing) // Slower flight (3.5s)
                        )
                        isFlying = false
                    }
                }
            }
            previousDramatic.value = isDramatic
        }
        
        if (isFlying) {
            // Nintendo LCD Style Movement:
            // Add a stepped Y-bob to make it look like it's fighting gravity
            // discrete steps? Or smooth sine? Reference image is natural, request is "Nintendo LCD".
            // LCD games usually had fixed Y positions or very discrete jumps.
            // Let's do a smooth sine but with the LCD *frames* flapping.
            
            val timestamp = java.lang.System.currentTimeMillis()
            val frameIndex = ((timestamp / 150) % 4).toInt() // 4 frames, 150ms each
            
            val yBob = kotlin.math.sin(timestamp / 200.0) * 20f

            LCDOwl(
                modifier = Modifier
                    .offset { 
                        IntOffset(
                            x = owlX.value.roundToInt(),
                            y = ((heightPx * flyHeightRatio) + yBob).roundToInt()
                        ) 
                    },
                frameIndex = frameIndex
            )
        }
    }
}

@Composable
fun LCDOwl(
    modifier: Modifier = Modifier,
    frameIndex: Int // 0..3
) {
    // Reverted to Emoji per user request ("Racing Car")
    // "Small, faded and glitchy"
    
    val activeColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    
    // Glitch Logic:
    // We want it to "twitch" randomly.
    // frameIndex changes every 150ms. Use it to seed random jitter?
    // Or just use a LaunchedEffect for faster twitching if needed.
    // The user said "flitchy" -> "glitchy" + "flickey"?
    
    // Random Offset (Jitter)
    // We use a side-effect to generate a random offset on every recomposition or frame change
    val jitterX = remember(frameIndex) { (Random.nextFloat() - 0.5f) * 6f } // +/- 3dp jitter
    val jitterY = remember(frameIndex) { (Random.nextFloat() - 0.5f) * 6f }
    
    // Random Alpha (Flicker)
    val flickerAlpha = remember(frameIndex) { 0.3f + Random.nextFloat() * 0.4f } // 0.3 to 0.7
    
    Box(
        modifier = modifier
            .size(40.dp) // Small container
            .offset(x = jitterX.dp, y = jitterY.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = "🏎️",
            fontSize = 24.sp, // Small
            modifier = Modifier.alpha(flickerAlpha) // Faded & Flickering
        )
        
        // "Ghost" / Chromatic Aberration effect?
        // Let's add a second fainter one slightly offset for "Glitch" feel
        if (frameIndex % 2 == 0) {
            androidx.compose.material3.Text(
                text = "🏎️",
                fontSize = 24.sp,
                color = activeColor.copy(alpha = 0.1f), // Tinted ghost? Emoji usually has its own color.
                // We can't easily tint standard emojis without blending modes.
                // Just duplicate it with low alpha.
                modifier = Modifier
                    .offset(x = 2.dp, y = 0.dp)
                    .alpha(0.2f)
            )
        }
    }
}
