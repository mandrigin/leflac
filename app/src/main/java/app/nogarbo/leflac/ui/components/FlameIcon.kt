package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Tiny solid flame silhouette for "hot" tracks. Pure vector, single color —
 * reads correctly in both the Field chassis and the 1-bit LCD skin (unlike
 * a color emoji).
 */
@Composable
fun FlameIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, 0f)
            // right side: swells out and curls to the base
            cubicTo(w * 0.60f, h * 0.25f, w * 0.95f, h * 0.35f, w * 0.88f, h * 0.66f)
            cubicTo(w * 0.84f, h * 0.88f, w * 0.68f, h, w * 0.50f, h)
            // left side: tighter curve with the characteristic notch
            cubicTo(w * 0.26f, h, w * 0.10f, h * 0.84f, w * 0.16f, h * 0.60f)
            cubicTo(w * 0.20f, h * 0.44f, w * 0.32f, h * 0.40f, w * 0.36f, h * 0.28f)
            cubicTo(w * 0.40f, h * 0.18f, w * 0.44f, h * 0.10f, w * 0.50f, 0f)
            close()
        }
        drawPath(path, color)
    }
}
