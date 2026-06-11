package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import app.nogarbo.leflac.ui.theme.LcdInk

/**
 * Passive-matrix glass simulation, drawn over the entire UI in LCD mode —
 * the same treatment the generated artwork applies to its bitmap: a pixel
 * grid, a corner vignette, and a faint diagonal glass glare. Content under
 * it becomes "pixels behind glass".
 */
@Composable
fun LcdScreenOverlay(modifier: Modifier = Modifier, resting: Boolean = false) {
    // One grid cell as a tiny tile, repeated by the GPU: right and bottom
    // edges carry the inter-pixel gap line.
    val gridBrush = remember {
        val cell = 7
        val bitmap = ImageBitmap(cell, cell)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = Paint().apply { color = LcdInk.copy(alpha = 0.10f) }
        canvas.drawRect(cell - 1f, 0f, cell.toFloat(), cell.toFloat(), paint)
        canvas.drawRect(0f, cell - 1f, cell.toFloat(), cell.toFloat(), paint)
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }

    // Row-refresh sweep: a passive matrix redraws line by line, and every few
    // seconds you catch the scan. One cycle is 7s; the band is only visible
    // for the first ~18% of it, the rest is idle.
    val sweepTransition = rememberInfiniteTransition(label = "lcdSweep")
    val sweepCycle by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lcdSweepCycle"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // 1. Pixel grid
        drawRect(brush = gridBrush)

        // 1b. Row-refresh scan band sweeping top to bottom (rests when idle)
        val sweepWindow = 0.18f
        if (!resting && sweepCycle < sweepWindow) {
            val p = sweepCycle / sweepWindow
            val bandH = size.height * 0.07f
            val bandCenter = p * (size.height + 2f * bandH) - bandH
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to LcdInk.copy(alpha = 0.10f),
                    0.5f to LcdInk.copy(alpha = 0.14f),
                    0.55f to LcdInk.copy(alpha = 0.10f),
                    1f to Color.Transparent,
                    startY = bandCenter - bandH,
                    endY = bandCenter + bandH
                ),
                topLeft = Offset(0f, bandCenter - bandH),
                size = androidx.compose.ui.geometry.Size(size.width, 2f * bandH)
            )
        }

        // 2. Vignette / screen bleed towards the bezel
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, LcdInk.copy(alpha = 0.22f)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.maxDimension * 0.75f
            )
        )

        // 3. Diagonal glass glare, barely there
        drawRect(
            brush = Brush.linearGradient(
                0.00f to Color.Transparent,
                0.42f to Color.Transparent,
                0.50f to Color.White.copy(alpha = 0.05f),
                0.58f to Color.Transparent,
                1.00f to Color.Transparent,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height * 0.7f)
            )
        )
    }
}
