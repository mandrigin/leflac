package app.nogarbo.leflac.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import kotlin.math.roundToInt

// Classic 4x4 Bayer threshold matrix: a 1-bit screen fakes shades by
// switching individual pixels on in this order.
private val BAYER4 = intArrayOf(
    0, 8, 2, 10,
    12, 4, 14, 6,
    3, 11, 1, 9,
    15, 7, 13, 5
)

/**
 * A repeating ordered-dither pattern: `level` of the area is covered by
 * opaque [color] pixels, the rest stays transparent. The Playdate way of
 * doing "alpha" on a display that has no alpha. [cellPx] is the size of one
 * dither pixel — chunky on purpose so the pattern reads at arm's length.
 */
fun ditherBrush(color: Color, level: Float, cellPx: Int = 4): ShaderBrush {
    val on = (level.coerceIn(0f, 1f) * 16).roundToInt()
    val tile = 4 * cellPx
    val bitmap = ImageBitmap(tile, tile)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint().apply { this.color = color }
    for (y in 0 until 4) {
        for (x in 0 until 4) {
            if (BAYER4[y * 4 + x] < on) {
                canvas.drawRect(
                    x * cellPx.toFloat(), y * cellPx.toFloat(),
                    (x + 1) * cellPx.toFloat(), (y + 1) * cellPx.toFloat(),
                    paint
                )
            }
        }
    }
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

@Composable
fun rememberDitherBrush(color: Color, level: Float, cellPx: Int = 4): Brush =
    remember(color, level, cellPx) { ditherBrush(color, level, cellPx) }
