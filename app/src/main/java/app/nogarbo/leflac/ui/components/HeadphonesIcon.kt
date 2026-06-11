package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

// 3D Point Class
data class Point3D(val x: Float, val y: Float, val z: Float)

@Composable
fun HeadphonesIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    var rotationY by remember { mutableStateOf(35f) } // Matching the angled view
    var rotationX by remember { mutableStateOf(15f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rotationY += dragAmount.x * 0.5f
                    rotationX -= dragAmount.y * 0.5f
                }
            }
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val scale = size.width * 0.40f

        val vertices = mutableListOf<Point3D>()
        val edges = mutableListOf<Pair<Int, Int>>()

        // Helper: Add Line
        fun addEdge(i1: Int, i2: Int) = edges.add(i1 to i2)
        // Helper: Add Point
        fun addVert(x: Float, y: Float, z: Float): Int {
            vertices.add(Point3D(x, y, z))
            return vertices.size - 1
        }

        // --- 1. HEADBAND (Segmented "Pillows") ---
        val bandSegments = 16
        val bandRadio = 0.7f
        val bandWidth = 0.12f // Z-width
        val bandHeight = 0.05f // Thickness

        for (i in 0..bandSegments) {
            val t = PI * i / bandSegments.toDouble()
            val x = (cos(t) * bandRadio).toFloat()
            val y = -(sin(t) * bandRadio * 0.9).toFloat() - 0.2f 

            if (i > 1 && i < bandSegments - 1) {
                // Cross-Section (The "Pillow" Loop)
                val idx0 = addVert(x, y, -bandWidth)
                val idx1 = addVert(x, y - bandHeight, -bandWidth)
                val idx2 = addVert(x, y - bandHeight, bandWidth)
                val idx3 = addVert(x, y, bandWidth)
                
                // Draw the square profile
                addEdge(idx0, idx1)
                addEdge(idx1, idx2)
                addEdge(idx2, idx3)
                addEdge(idx3, idx0)

                // Connect to previous segment (Spine lines)
                if (i > 2) {
                    val prevBase = vertices.size - 8
                    addEdge(prevBase, idx0)
                    addEdge(prevBase+1, idx1)
                    addEdge(prevBase+2, idx2)
                    addEdge(prevBase+3, idx3)
                }
            }
        }

        // --- 2. EAR CUPS (Detailed Grid) ---
        fun createCup(isRight: Boolean) {
            val offsetX = if (isRight) bandRadio else -bandRadio
            val cupY = 0.1f // Lower down
            val rOuter = 0.35f
            val width = 0.15f
            val segments = 24 // High poly circle
            
            val centerIdx = addVert(offsetX, cupY, 0f)

            // Rings
            val outerRingIndices = mutableListOf<Int>()
            val innerRingIndices = mutableListOf<Int>() // Face
            
            for (i in 0 until segments) {
                val theta = 2.0 * PI * i / segments
                val cy = cupY + (cos(theta) * rOuter).toFloat()
                val cz = (sin(theta) * rOuter).toFloat()
                
                // Outer Rim (Away from head)
                val xOut = if (isRight) offsetX + width else offsetX - width
                val idxOut = addVert(xOut, cy, cz)
                outerRingIndices.add(idxOut)
                
                // Inner Rim (Towards head)
                val xIn = if (isRight) offsetX - width/3 else offsetX + width/3
                val idxIn = addVert(xIn, cy, cz)
                innerRingIndices.add(idxIn)
                
                // Radial Lines (Spokes)
                addEdge(centerIdx, idxOut) 
            }

            // Patch the rings
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                // Rim Check
                addEdge(outerRingIndices[i], outerRingIndices[next])
                addEdge(innerRingIndices[i], innerRingIndices[next])
                // Wall
                addEdge(outerRingIndices[i], innerRingIndices[i])
            }
            
            // --- 3. SLIDER ARMS ---
            // Connect Cup Center to Headband End
            // Headband end approx:
            val hbX = if (isRight) 0.65f else -0.65f
            val hbY = -0.2f
            
            val sliderTop = addVert(hbX, hbY, 0f)
            val sliderMid = addVert(offsetX, cupY - rOuter, 0f) // Connect to bottom pivot? Or Center? H8i connects to axis.
            // H8i actually has a yoke. Let's make a simple Yoke.
            val yokeL = addVert(offsetX, cupY, rOuter + 0.05f)
            val yokeR = addVert(offsetX, cupY, -rOuter - 0.05f)
            val yokeTop = addVert(offsetX, cupY - rOuter - 0.1f, 0f) // Top of yoke
            
            addEdge(yokeL, yokeTop)
            addEdge(yokeR, yokeTop)
            addEdge(yokeTop, sliderTop) // Slide into band
            
            // Connect Yoke to Cup Axis
            addEdge(yokeL, centerIdx)
            addEdge(yokeR, centerIdx)
        }

        createCup(false)
        createCup(true)

        // --- RENDER ---
        val radY = Math.toRadians(rotationY.toDouble())
        val radX = Math.toRadians(rotationX.toDouble())
        
        fun rotate(p: Point3D): Point3D {
            // Y-Axis
            val x1 = p.x * cos(radY) - p.z * sin(radY)
            val z1 = p.x * sin(radY) + p.z * cos(radY)
            // X-Axis
            val y2 = p.y * cos(radX) - z1 * sin(radX)
            val z2 = p.y * sin(radX) + z1 * cos(radX)
            return Point3D(x1.toFloat(), y2.toFloat(), z2.toFloat())
        }

        val projected = vertices.map { rotate(it) }

        edges.forEach { (i1, i2) ->
            if (i1 < projected.size && i2 < projected.size) {
                val p1 = projected[i1]
                val p2 = projected[i2]
                drawLine(
                    color = color.copy(alpha = 0.7f), // Wireframe ghost look
                    start = Offset(centerX + p1.x * scale, centerY + p1.y * scale),
                    end = Offset(centerX + p2.x * scale, centerY + p2.y * scale),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}
