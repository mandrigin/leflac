package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SegmentedDisplay(
    text: String,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.Black,
    inactiveColor: Color = Color.Black.copy(alpha = 0.1f),
    digitWidth: Dp = 20.dp,
    digitHeight: Dp = 36.dp,
    spacing: Dp = 4.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        text.forEach { char ->
            if (char == ':') {
                Colon(
                    width = digitWidth / 2,
                    height = digitHeight,
                    color = activeColor
                )
            } else {
                SevenSegmentDigit(
                    char = char,
                    width = digitWidth,
                    height = digitHeight,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor
                )
            }
        }
    }
}

@Composable
fun Colon(width: Dp, height: Dp, color: Color) {
    Canvas(modifier = Modifier.width(width).height(height)) {
        val radius = size.width / 3
        drawCircle(color, radius = radius, center = Offset(size.width / 2, size.height * 0.3f))
        drawCircle(color, radius = radius, center = Offset(size.width / 2, size.height * 0.7f))
    }
}

@Composable
fun SevenSegmentDigit(
    char: Char,
    width: Dp,
    height: Dp,
    activeColor: Color,
    inactiveColor: Color
) {
    // Map char to segments (a, b, c, d, e, f, g)
    // a: top, b: top-right, c: bottom-right, d: bottom, e: bottom-left, f: top-left, g: mid
    val segments = when (char) {
        '0' -> setOf('a', 'b', 'c', 'd', 'e', 'f')
        '1' -> setOf('b', 'c')
        '2' -> setOf('a', 'b', 'd', 'e', 'g')
        '3' -> setOf('a', 'b', 'c', 'd', 'g')
        '4' -> setOf('b', 'c', 'f', 'g')
        '5' -> setOf('a', 'c', 'd', 'f', 'g')
        '6' -> setOf('a', 'c', 'd', 'e', 'f', 'g')
        '7' -> setOf('a', 'b', 'c')
        '8' -> setOf('a', 'b', 'c', 'd', 'e', 'f', 'g')
        '9' -> setOf('a', 'b', 'c', 'd', 'f', 'g')
        else -> emptySet()
    }

    Canvas(modifier = Modifier.width(width).height(height)) {
        val w = size.width
        val h = size.height
        val thickness = w * 0.25f // Thicker segments (was 0.18f)
        val gap = thickness * 0.2f
        
        // Coordinates
        // We use a skewed logic for "retro" look? Or standard straight.
        // Standard straight is easier for implementation.
        
        // Define paths for 7 segments
        fun drawSeg(id: Char, isActive: Boolean, block: Path.() -> Unit) {
            val color = if (isActive) activeColor else inactiveColor
            // Only draw inactive if we want that "ghost" effect. 
            // Usually yes for casio.
            drawPath(
                path = Path().apply { block() },
                color = color
            )
        }
        
        val halfH = h / 2
        
        // Segment A (Top)
        drawSeg('a', segments.contains('a')) {
            moveTo(thickness + gap, 0f)
            lineTo(w - thickness - gap, 0f)
            lineTo(w - thickness - gap - thickness, thickness)
            lineTo(thickness + gap + thickness, thickness)
            close()
        }
        
        // Segment B (Top-Right)
        drawSeg('b', segments.contains('b')) {
            moveTo(w, gap)
            lineTo(w, halfH - gap/2)
            lineTo(w - thickness, halfH - gap/2 - thickness/2)
            lineTo(w - thickness, thickness + gap)
            close()
        }
        
        // Segment C (Bottom-Right)
        drawSeg('c', segments.contains('c')) {
            moveTo(w, halfH + gap/2)
            lineTo(w, h - gap)
            lineTo(w - thickness, h - thickness - gap)
            lineTo(w - thickness, halfH + gap/2 + thickness/2)
            close()
        }
        
        // Segment D (Bottom)
        drawSeg('d', segments.contains('d')) {
            moveTo(thickness + gap, h)
            lineTo(w - thickness - gap, h)
            lineTo(w - thickness - gap - thickness, h - thickness)
            lineTo(thickness + gap + thickness, h - thickness)
            close()
        }
        
        // Segment E (Bottom-Left)
        drawSeg('e', segments.contains('e')) {
            moveTo(0f, h - gap)
            lineTo(0f, halfH + gap/2)
            lineTo(thickness, halfH + gap/2 + thickness/2)
            lineTo(thickness, h - thickness - gap)
            close()
        }
        
        // Segment F (Top-Left)
        drawSeg('f', segments.contains('f')) {
            moveTo(0f, gap)
            lineTo(0f, halfH - gap/2)
            lineTo(thickness, halfH - gap/2 - thickness/2)
            lineTo(thickness, thickness + gap)
            close()
        }
        
        // Segment G (Middle)
        drawSeg('g', segments.contains('g')) {
            moveTo(thickness + gap, halfH)
            lineTo(w - thickness - gap, halfH)
            lineTo(w - thickness - gap - thickness/2, halfH + thickness/2)
            lineTo(thickness + gap + thickness/2, halfH + thickness/2)
            // Bottom half of hex
            lineTo(thickness + gap + thickness/2, halfH - thickness/2)
            close()
        }
    }
}
