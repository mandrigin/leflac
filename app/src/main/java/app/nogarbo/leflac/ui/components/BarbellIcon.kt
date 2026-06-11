package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** Tiny barbell for gym-rated tracks; opacity carries the rating. */
@Composable
fun BarbellIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        // bar
        drawRect(color, Offset(0f, cy - h * 0.07f), Size(w, h * 0.14f))
        // inner plates (taller) and outer plates (shorter), both ends
        drawRect(color, Offset(w * 0.14f, cy - h * 0.38f), Size(w * 0.10f, h * 0.76f))
        drawRect(color, Offset(w * 0.04f, cy - h * 0.26f), Size(w * 0.08f, h * 0.52f))
        drawRect(color, Offset(w * 0.76f, cy - h * 0.38f), Size(w * 0.10f, h * 0.76f))
        drawRect(color, Offset(w * 0.88f, cy - h * 0.26f), Size(w * 0.08f, h * 0.52f))
    }
}
