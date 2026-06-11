package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

/**
 * A real-ish 3D wireframe cassette tape, drawn in the same "ghost wireframe"
 * style as [HeadphonesIcon] (reuses [Point3D] from that file, same package).
 *
 * It's a genuine solid: a cuboid shell with the label / window / screws
 * mirrored on BOTH faces, and the two reels modelled as cylinders (front +
 * back rings joined by wall generatrices, spoked hub passing through the body).
 * The reels spin only while [spinning] is true. Drag to orbit the whole model.
 */
@Composable
fun MixtapeIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    spinning: Boolean = true,
    label: String? = null
) {
    var rotationY by remember { mutableStateOf(28f) }
    var rotationX by remember { mutableStateOf(14f) }

    val transition = rememberInfiniteTransition(label = "mixtape")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reelSpin"
    )

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
        val phase = if (spinning) spin else 0f

        val centerX = size.width / 2
        val centerY = size.height / 2
        val scale = size.width * 0.40f

        val vertices = mutableListOf<Point3D>()
        val bodyEdges = mutableListOf<Pair<Int, Int>>()   // structure (dimmer)
        val detailEdges = mutableListOf<Pair<Int, Int>>() // surface detail (brighter)

        fun v(x: Float, y: Float, z: Float): Int {
            vertices.add(Point3D(x, y, z))
            return vertices.size - 1
        }
        fun body(i1: Int, i2: Int) = bodyEdges.add(i1 to i2)
        fun detail(i1: Int, i2: Int) = detailEdges.add(i1 to i2)
        fun fcos(t: Double) = cos(t).toFloat()
        fun fsin(t: Double) = sin(t).toFloat()

        // --- Dimensions ---
        // Compact cassette: 100 x 63.8 x 12mm. The shell must read THIN —
        // a chunky shell is what made it look like a videotape.
        val w = 0.72f   // half width
        val h = 0.46f   // half height
        val d = 0.09f   // half depth (12mm of a 100mm shell)

        // --- 1. BODY (cuboid shell — planes via edges) ---
        val fTL = v(-w, -h, d); val fTR = v(w, -h, d); val fBR = v(w, h, d); val fBL = v(-w, h, d)
        val bTL = v(-w, -h, -d); val bTR = v(w, -h, -d); val bBR = v(w, h, -d); val bBL = v(-w, h, -d)
        body(fTL, fTR); body(fTR, fBR); body(fBR, fBL); body(fBL, fTL)
        body(bTL, bTR); body(bTR, bBR); body(bBR, bBL); body(bBL, bTL)
        body(fTL, bTL); body(fTR, bTR); body(fBR, bBR); body(fBL, bBL)

        // --- helpers shared by both faces ---
        // A closed ring of `seg` verts; returns the vertex indices.
        fun ring(cx: Float, cy: Float, r: Float, z: Float, seg: Int): IntArray {
            val a = IntArray(seg)
            for (i in 0 until seg) {
                val t = 2.0 * PI * i / seg
                a[i] = v(cx + fcos(t) * r, cy + fsin(t) * r, z)
            }
            for (i in 0 until seg) detail(a[i], a[(i + 1) % seg])
            return a
        }
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float, z: Float) {
            val a = v(x0, y0, z); val b = v(x1, y0, z); val c = v(x1, y1, z); val e = v(x0, y1, z)
            detail(a, b); detail(b, c); detail(c, e); detail(e, a)
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, z: Float) =
            detail(v(x0, y0, z), v(x1, y1, z))

        // Flat surface detail painted on a face at depth `z`.
        // Audio-cassette grammar: label band on top, window strip around the
        // hubs mid-height, head-access trapezoid cut into the BOTTOM edge.
        fun faceDetail(z: Float) {
            // Label band (border only — the text is drawn on top later)
            rect(-0.60f, -0.40f, 0.60f, -0.16f, z)
            // Window strip: the tape pack shows through around both hubs
            rect(-0.48f, -0.13f, 0.48f, 0.29f, z)
            // Head-access trapezoid opening on the bottom edge (no bottom
            // line — it shares the shell edge)
            val a = v(-0.24f, h, z); val e = v(-0.16f, 0.32f, z)
            val c = v(0.16f, 0.32f, z); val b = v(0.24f, h, z)
            detail(a, e); detail(e, c); detail(c, b)
            // Capstan holes beside the head access
            ring(-0.34f, 0.38f, 0.03f, z, 8)
            ring(0.34f, 0.38f, 0.03f, z, 8)
            // Four corner screws (little diamonds + cross)
            for (sx in listOf(-w + 0.08f, w - 0.08f)) {
                for (sy in listOf(-h + 0.08f, h - 0.08f)) {
                    val s = 0.035f
                    line(sx - s, sy, sx + s, sy, z); line(sx, sy - s, sx, sy + s, z)
                    val n = v(sx, sy - s, z); val ee = v(sx + s, sy, z)
                    val ss = v(sx, sy + s, z); val ww = v(sx - s, sy, z)
                    detail(n, ee); detail(ee, ss); detail(ss, ww); detail(ww, n)
                }
            }
        }

        // --- 2. FACE DETAIL on BOTH sides ---
        val faceZ = d + 0.001f
        faceDetail(faceZ)    // front
        faceDetail(-faceZ)   // back

        // --- 3. REELS as real CYLINDERS through the body ---
        // rTape varies per reel: a mixtape mid-play has one fat pack and one
        // thin one — the asymmetry is what makes it read AUDIO at a glance.
        fun reel(cx: Float, cy: Float, dir: Float, rTape: Float) {
            val rOuter = 0.20f
            val rHub = 0.07f
            val seg = 26
            val spokes = 8
            val teeth = 8
            val zf = d - 0.01f
            val zb = -(d - 0.01f)

            // Rings on both end caps (outer rim, wound-tape edge, hub bore)
            val outerF = ring(cx, cy, rOuter, zf, seg)
            ring(cx, cy, rTape, zf, seg)
            val hubF = ring(cx, cy, rHub, zf, seg)
            val outerB = ring(cx, cy, rOuter, zb, seg)
            ring(cx, cy, rTape, zb, seg)
            val hubB = ring(cx, cy, rHub, zb, seg)

            // Cylinder walls: generatrices joining front/back rims and hubs
            val gens = 10
            for (k in 0 until gens) {
                val i = (k * seg) / gens
                body(outerF[i], outerB[i])
                body(hubF[i], hubB[i])
            }

            // Spokes + sprocket teeth on each face (rotate with the spin)
            for (z in listOf(zf, zb)) {
                for (s in 0 until spokes) {
                    val t = dir * phase + 2.0 * PI * s / spokes
                    detail(
                        v(cx + fcos(t) * rHub, cy + fsin(t) * rHub, z),
                        v(cx + fcos(t) * rOuter, cy + fsin(t) * rOuter, z)
                    )
                }
                for (s in 0 until teeth) {
                    val t = dir * phase + 2.0 * PI * (s + 0.5) / teeth
                    detail(
                        v(cx + fcos(t) * (rHub * 0.55f), cy + fsin(t) * (rHub * 0.55f), z),
                        v(cx + fcos(t) * rHub, cy + fsin(t) * rHub, z)
                    )
                }
            }
            // Axle through the hub centre
            body(v(cx, cy, zf), v(cx, cy, zb))
        }
        reel(-0.27f, 0.08f, 1f, 0.165f) // supply side: still fat
        reel(0.27f, 0.08f, 1f, 0.095f)  // take-up side: thin

        // Exposed tape strand: reel → capstan → across the head gap → reel,
        // sagging slightly with the spin (front face only)
        val sag = 0.015f * fsin(phase.toDouble())
        line(-0.27f, 0.27f, -0.12f, 0.40f + sag, faceZ)
        line(-0.12f, 0.40f + sag, 0.12f, 0.40f + sag, faceZ)
        line(0.12f, 0.40f + sag, 0.27f, 0.27f, faceZ)

        // --- RENDER ---
        val radY = Math.toRadians(rotationY.toDouble())
        val radX = Math.toRadians(rotationX.toDouble())
        fun rotate(p: Point3D): Point3D {
            val x1 = p.x * cos(radY) - p.z * sin(radY)
            val z1 = p.x * sin(radY) + p.z * cos(radY)
            val y2 = p.y * cos(radX) - z1 * sin(radX)
            val z2 = p.y * sin(radX) + z1 * cos(radX)
            return Point3D(x1.toFloat(), y2.toFloat(), z2.toFloat())
        }
        val projected = vertices.map { rotate(it) }
        fun pt(idx: Int) = Offset(centerX + projected[idx].x * scale, centerY + projected[idx].y * scale)

        // --- FILLED SIDES (the planes) — translucent so it reads as a solid body.
        // Drawn first, under the wireframe edges. The 6 cuboid faces; overlapping
        // translucency naturally builds up depth.
        val faces = listOf(
            intArrayOf(fTL, fTR, fBR, fBL), // front
            intArrayOf(bTL, bTR, bBR, bBL), // back
            intArrayOf(fTL, fTR, bTR, bTL), // top
            intArrayOf(fBL, fBR, bBR, bBL), // bottom
            intArrayOf(fTL, fBL, bBL, bTL), // left
            intArrayOf(fTR, fBR, bBR, bTR)  // right
        )
        faces.forEach { f ->
            val path = Path()
            f.forEachIndexed { i, idx ->
                val o = pt(idx)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, color = color.copy(alpha = color.alpha * 0.20f))
        }

        // --- WIREFRAME EDGES (kept ghostly) on top of the fill.
        fun drawEdges(edges: List<Pair<Int, Int>>, alpha: Float, stroke: Float) {
            edges.forEach { (i1, i2) ->
                if (i1 < projected.size && i2 < projected.size) {
                    drawLine(
                        color = color.copy(alpha = color.alpha * alpha),
                        start = pt(i1),
                        end = pt(i2),
                        strokeWidth = stroke
                    )
                }
            }
        }
        drawEdges(bodyEdges, alpha = 0.55f, stroke = 1.6.dp.toPx())
        drawEdges(detailEdges, alpha = 0.85f, stroke = 1.3.dp.toPx())

        // --- LABEL TEXT printed on the label panel, following its 3D tilt.
        label?.let { txt ->
            // Endpoints of the label baseline (front face), projected.
            val l = rotate(Point3D(-0.42f, -0.235f, faceZ))
            val r = rotate(Point3D(0.42f, -0.235f, faceZ))
            val lx = centerX + l.x * scale; val ly = centerY + l.y * scale
            val rx = centerX + r.x * scale; val ry = centerY + r.y * scale
            val midX = (lx + rx) / 2f; val midY = (ly + ry) / 2f
            val angle = Math.toDegrees(atan2((ry - ly).toDouble(), (rx - lx).toDouble())).toFloat()
            val avail = hypot((rx - lx).toDouble(), (ry - ly).toDouble()).toFloat()

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.copy(alpha = 1f).toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
                )
                textSize = scale * 0.20f
            }
            val measured = paint.measureText(txt)
            if (measured > avail * 0.92f) paint.textSize *= (avail * 0.92f) / measured

            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            canvas.rotate(angle, midX, midY)
            val baseline = midY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(txt, midX, baseline, paint)
            canvas.restoreToCount(save)
        }
    }
}
