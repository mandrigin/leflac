package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CubeIcon(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF9D65FF) // Light Purple/Lilac
) {
    var rotationX by remember { mutableStateOf(30f) }
    var rotationY by remember { mutableStateOf(45f) }

    Canvas(
        modifier = modifier
            .size(256.dp) // 4x Bigger as requested
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rotationY += dragAmount.x * 0.5f
                    rotationX -= dragAmount.y * 0.5f // Invert Y natural feel
                }
            }
    ) {
        val size = size.minDimension * 0.4f // Cube radius
        val centerX = this.size.width / 2
        val centerY = this.size.height / 2

        // Vertices of a cube [-1, 1]
        val vertices = listOf(
            Triple(-1f, -1f, -1f), Triple(1f, -1f, -1f), Triple(1f, 1f, -1f), Triple(-1f, 1f, -1f), // Back face
            Triple(-1f, -1f, 1f), Triple(1f, -1f, 1f), Triple(1f, 1f, 1f), Triple(-1f, 1f, 1f)      // Front face
        )

        // Rotate and project
        val projected = vertices.map { (x, y, z) ->
            val radX = Math.toRadians(rotationX.toDouble())
            val radY = Math.toRadians(rotationY.toDouble())

            // Rotate Y
            val x1 = x * cos(radY) - z * sin(radY)
            val z1 = x * sin(radY) + z * cos(radY)

            // Rotate X
            val y2 = y * cos(radX) - z1 * sin(radX)
            val z2 = y * sin(radX) + z1 * cos(radX)

            // Project (Orthographic for now, effectively just dropping Z but maybe isometric scale)
            Offset(
                centerX + (x1 * size).toFloat(),
                centerY + (y2 * size).toFloat()
            )
        }

        // Draw Edges
        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0, // Back Face
            4 to 5, 5 to 6, 6 to 7, 7 to 4, // Front Face
            0 to 4, 1 to 5, 2 to 6, 3 to 7  // Connecting Lines
        )

        edges.forEach { (start, end) ->
            drawLine(
                color = color,
                start = projected[start],
                end = projected[end],
                strokeWidth = 3f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}
