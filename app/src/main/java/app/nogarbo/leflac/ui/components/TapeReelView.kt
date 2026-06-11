package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.ui.theme.DeepVoid
import app.nogarbo.leflac.ui.theme.InkGrey
import app.nogarbo.leflac.ui.theme.SafetyOrange
import kotlin.math.abs
import kotlin.math.atan2

@Composable
fun TapeReelView(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onScrub: (Float) -> Unit = {}
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current

    // Physics State
    var rotation by remember { mutableFloatStateOf(0f) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Animation Loop for Playback
    LaunchedEffect(isPlaying, isDragging) {
        val targetVelocity = if (isPlaying && !isDragging) 5f else 0f
        while (true) {
            // Simple physics integration
            if (!isDragging) {
                // Decay velocity if not playing, or accelerate to target if playing
                if (isPlaying) {
                     velocity += (targetVelocity - velocity) * 0.1f
                } else {
                     velocity *= 0.95f // Friction
                }
            }
            
            rotation += velocity
            
            // Send velocity as "scrub speed" (faked)
            if (abs(velocity) > 0.1f) {
                onScrub(velocity)
            }
            
            withFrameNanos { _ -> } // Wait for next frame
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Calculate angular delta for realistic rotation
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val position = change.position
                        val prevPosition = position - dragAmount
                        
                        val angleNow = atan2(position.y - center.y, position.x - center.x)
                        val anglePrev = atan2(prevPosition.y - center.y, prevPosition.x - center.x)
                        
                        var deltaAngle = Math.toDegrees((angleNow - anglePrev).toDouble()).toFloat()
                        
                        // Apply to rotation
                        rotation += deltaAngle
                        velocity = deltaAngle // Instant velocity transfer
                    }
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 4.dp.toPx()
        
        rotate(rotation, center) {
            // 1. Outer Ring (Heavy Industrial)
            drawCircle(
                color = if (skin.isLcd) skin.accent else InkGrey,
                radius = radius,
                style = Stroke(width = 8.dp.toPx())
            )
            
            // 2. The Disc Structure (3 spokes)
            for (i in 0 until 3) {
                rotate(i * 120f, center) {
                    drawRect(
                        color = if (skin.isLcd) skin.shadeMid else InkGrey,
                        topLeft = Offset(center.x - 15.dp.toPx(), center.y - radius),
                        size = androidx.compose.ui.geometry.Size(30.dp.toPx(), radius),
                        alpha = if (skin.isLcd) 1f else 0.8f // 2-bit: solid tone, no blend
                    )
                }
            }
            
            // 3. Center Hub (Orange Accent)
            drawCircle(
                color = skin.accent,
                radius = 24.dp.toPx()
            )
            
            // 4. Glitch/Tape Markers (Jittering)
            val jitter = if (isPlaying) (Math.random().toFloat() - 0.5f) * 10f else 0f
            
            drawLine(
                color = if (skin.isLcd) skin.chassis else DeepVoid, // glass-on-ink in LCD
                start = Offset(center.x + jitter, center.y - 10.dp.toPx() + jitter),
                end = Offset(center.x + jitter, center.y + 10.dp.toPx() + jitter),
                strokeWidth = 4.dp.toPx()
            )
             drawLine(
                color = if (skin.isLcd) skin.chassis else DeepVoid,
                start = Offset(center.x - 10.dp.toPx() - jitter, center.y - jitter),
                end = Offset(center.x + 10.dp.toPx() - jitter, center.y - jitter),
                strokeWidth = 4.dp.toPx()
            )
        }
    }
}
