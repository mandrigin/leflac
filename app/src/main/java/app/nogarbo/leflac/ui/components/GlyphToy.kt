package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.service.SpectrumState
import app.nogarbo.leflac.ui.skins.LocalFieldSkin
import kotlinx.coroutines.isActive
import kotlin.math.*

// Hardware Spec Constants
private const val MATRIX_DIM = 25
private const val CENTER_X = 12f
private const val CENTER_Y = 12f
private const val ACTIVE_RADIUS_SQ = 12.5f * 12.5f

@Composable
fun GlyphToy(
    spectrum: SpectrumState,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    // Shared Engine
    val engine = remember { app.nogarbo.leflac.service.PhoneThreeGlyphEngine() }
    
    // Frame State
    val currentFrame = remember { mutableStateOf(IntArray(MATRIX_DIM * MATRIX_DIM)) }
    
    // Animation Loop
    LaunchedEffect(Unit) {
        var lastTime = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { time ->
                val dtMs = (time - lastTime) / 1_000_000
                lastTime = time
                
                // Cap dt to avoid jumps if paused/resumed
                val safeDt = dtMs.coerceAtMost(50)
                
                val cal = java.util.Calendar.getInstance()
                engine.setClock(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                val mixProgress = app.nogarbo.leflac.ui.viewmodel.PlaybackViewModel.VisualizerBus.mixProgress.value
                val frame = engine.update(spectrum, isPlaying, safeDt, mixProgress)
                currentFrame.value = frame.clone() // Clone to trigger state change
            }
        }
    }

    val frame = currentFrame.value
    val skin = LocalFieldSkin.current
    val dotColor = skin.accent

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier
            .width(300.dp)
            .height(300.dp)
        ) {
            val w = size.width
            val h = size.height
            val cellW = w / MATRIX_DIM
            val cellH = h / MATRIX_DIM
            val dotSize = min(cellW, cellH) * 0.85f

            for (y in 0 until MATRIX_DIM) {
                for (x in 0 until MATRIX_DIM) {
                    val idx = y * MATRIX_DIM + x
                    val brightnessVal = frame[idx]
                    
                    // Decode Brightness from Engine (0..2000)
                    if (brightnessVal > 0) {
                        val alpha = (brightnessVal / 2000f).coerceIn(0f, 1f)
                        
                        if (alpha > 0.01f) {
                             // 2-bit on LCD: LED brightness snaps to the tone ramp
                             val toneColor = if (skin.isLcd) when {
                                 alpha > 0.66f -> skin.accent
                                 alpha > 0.33f -> skin.shadeMid
                                 else -> skin.shadeLight
                             } else dotColor.copy(alpha = alpha)
                             drawCircle(
                                color = toneColor,
                                radius = dotSize / 2f,
                                center = Offset(
                                    x * cellW + cellW / 2f,
                                    y * cellH + cellH / 2f
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
