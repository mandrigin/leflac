package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.nogarbo.leflac.service.PhoneThreeGlyphEngine

/** The mascot, asleep indoors: same 7x7 pixel mask as the glyph matrix. */
@Composable
fun SleepingAmpelmann(modifier: Modifier = Modifier, color: Color) {
    val mask = PhoneThreeGlyphEngine.ampelmannMask()
    Canvas(modifier = modifier) {
        val cell = size.minDimension / 7f
        for (y in 0 until 7) {
            for (x in 0 until 7) {
                val a = mask[y * 7 + x]
                if (a > 20) {
                    drawRect(
                        color = color.copy(alpha = color.alpha * (a / 100f)),
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
}
