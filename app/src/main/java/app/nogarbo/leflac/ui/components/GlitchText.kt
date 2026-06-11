package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

// Glitch Colors
val GlitchCyan = Color(0xFF00F0FF)
val GlitchMagenta = Color(0xFFFF00F0)

// CRT raster shader (AGSL, API 33+): slices the rendered type into scanlines,
// shears bands of them sideways (tear), and dims alternate lines (phosphor
// raster). `tear` surges during the magnet pass and on drops.
private const val CRT_TEAR_SHADER = """
    uniform shader content;
    uniform float time;
    uniform float tear;
    uniform float density;
    float hash(float n) { return fract(sin(n * 127.1) * 43758.5453); }
    half4 main(float2 coord) {
        float lineH = 3.0 * density;
        float line = floor(coord.y / lineH);
        // gentle standing ripple across the raster
        float shift = sin(coord.y * 0.35 / density + time * 2.4) * 0.6 * density * tear;
        // tear bands: some scanlines shear hard for a frame or two
        float band = hash(line + floor(time * 8.0) * 61.0);
        if (band > 0.8) {
            shift += (band - 0.8) * 30.0 * density * tear;
        }
        half4 c = content.eval(float2(coord.x - shift, coord.y));
        // phosphor raster: alternate lines sit a touch darker
        float dim = 1.0 - 0.10 * step(0.5, fract(coord.y / (2.0 * lineH)));
        return half4(c.rgb * dim, c.a);
    }
"""

@Composable
fun GlitchText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    spectrum: app.nogarbo.leflac.service.SpectrumState = app.nogarbo.leflac.service.SpectrumState(),
    idle: Boolean = false, // when the unit rests, the type rests too
    straining: Boolean = false, // decoding in progress: interference rises
    isGlitched: Boolean = false // Deprecated/Unused parameter, kept for signature compat if needed, but we will remove it from logic
) {
    // Matrix Text Logic - Internal State
    var displayedText by remember(text) { mutableStateOf(text) }
    
    // The Matrix Rain Effect (Independent UI Loop)
    // Reverted to self-driving loop as per user request to unlink from Engine
    val currentSpectrum by rememberUpdatedState(spectrum)

    val isIdle by rememberUpdatedState(idle)
    val isStraining by rememberUpdatedState(straining)
    LaunchedEffect(text) {
        val originalChars = text.toCharArray()
        val chars = listOf(
            "#", "@", "%", "&", "*", "!", "?", "$", "0", "1", "A", "Z", "X", "Y",
            "ｱ", "ｲ", "ｳ", "ｴ", "ｵ", "ｶ", "ｷ", "ｸ", "ｹ", "ｺ", "ｻ", "ｼ", "ｽ", "ｾ", "ｿ",
            "ﾀ", "ﾁ", "ﾂ", "ﾃ", "ﾄ", "ﾅ", "ﾆ", "ﾇ", "ﾈ", "ﾉ", "ﾊ", "ﾋ", "ﾌ", "ﾍ", "ﾎ",
            "ﾏ", "ﾐ", "ﾑ", "ﾒ", "ﾓ", "ﾔ", "ﾕ", "ﾖ", "ﾗ", "ﾘ", "ﾙ", "ﾚ", "ﾛ", "ﾜ", "ﾝ",
            "Ξ", "Σ", "Π", "Ψ", "Ω", "λ", "π", "σ",
            "Д", "Ж", "Щ", "Ю", "Ф",
            "░", "▒", "▓", "█", "■", "∞", "≠", "≈", "√", "∫"
        )
             
        while(isActive) {
            // DYNAMIC DELAY:
            // If Dramatic: Chaos (500ms - 1500ms)
            // If Idle: Rare (15s - 25s) -> Restoring original feel
            val isDramatic = currentSpectrum.isDramatic
            val delayMs = when {
                isDramatic -> Random.nextLong(500, 1500)
                isStraining -> Random.nextLong(2000, 5000) // machine under load
                else -> Random.nextLong(15000, 25000)
            }
            
            delay(delayMs)
            if (isIdle) continue // stillness while paused
            
            val len = text.length
            if (len > 0) {
                 val count = if (len < 12) len else (len * 0.3).toInt().coerceIn(1, 7)
                 val glitchIndices = (0 until len).shuffled().take(count)
                 
                 val isDramaticNow = currentSpectrum.isDramatic
                 val durationMs = if (isDramaticNow) 150 else 750
                 
                 val startTime = System.currentTimeMillis()
                 while(System.currentTimeMillis() - startTime < durationMs) {
                     val currentChars = originalChars.clone()
                     glitchIndices.forEach { index ->
                         if (Random.nextFloat() < 0.8f) {
                             currentChars[index] = chars.random().first()
                         }
                     }
                     displayedText = String(currentChars)
                     delay(50) 
                 }
                 // Restore
                 displayedText = text
             }
        }
    }
                 

    // Glitch displacement scales with the type: a 24sp mix title gets the
    // same PROPORTIONAL aberration as a 57sp hero, not the same pixels.
    val sizeScale = if (style.fontSize.type == androidx.compose.ui.unit.TextUnitType.Sp)
        (style.fontSize.value / 48f).coerceIn(0.4f, 1.2f) else 1f
    // Movement damps QUADRATICALLY: small type means a long wrapped title,
    // so the block is physically larger — absolute jumps must shrink faster
    // than the font does or small titles read as flailing.
    val motionScale = sizeScale * sizeScale

    // Bass -> Y Offset (Shake)
    // Mid -> Aberration Offset
    // Treble -> Jitter Frequency?
    // Bass Kick displaces Y and Scales (Thump)
    val animatedBass by animateFloatAsState(
        targetValue = if (spectrum.bass > 0.45f) 10f * motionScale else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.4f), // Bouncy
        label = "bass"
    )
    val scaleKick by animateFloatAsState(
        targetValue = if (spectrum.bass > 0.5f) 1f + 0.10f * motionScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "scale"
    )

    // Mid separates colors + Horizontal Shake
    val chromaticSep = remember(spectrum.mid, sizeScale) {
        (spectrum.mid * 8f).coerceIn(2f, 12f) * sizeScale
    }

    // Treble jitters X (High freq noise)
    val trebleJitter = if (spectrum.treble > 0.3f) (Random.nextFloat() * 6f - 3f) * motionScale else 0f

    // MAGNET PASS: every so often a stray field sweeps the tube — the image
    // bows toward it, the beams smear apart vertically, then the degauss
    // coil snaps everything back with a wobble (the spring overshoots).
    val magnet = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(Random.nextLong(7_000, 18_000))
            if (isIdle) continue
            magnet.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
            magnet.animateTo(
                0f,
                spring(dampingRatio = 0.24f, stiffness = Spring.StiffnessLow)
            )
        }
    }
    val magnetPulse = magnet.value
    val magnetSmear = 10f * magnetPulse * sizeScale

    // CRT raster shader: FIELD skin only — the pocket LCD has its own
    // analog story (ghosting, dither), a passive matrix doesn't tear.
    val isLcdThemeEarly = MaterialTheme.colorScheme.background == app.nogarbo.leflac.ui.theme.LcdGreen
    val crtShader = remember(isLcdThemeEarly) {
        if (!isLcdThemeEarly && android.os.Build.VERSION.SDK_INT >= 33)
            android.graphics.RuntimeShader(CRT_TEAR_SHADER)
        else null
    }
    var crtTime by remember { mutableStateOf(0f) }
    LaunchedEffect(idle, crtShader != null) {
        if (crtShader == null || idle) return@LaunchedEffect
        var t0 = -1L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (t0 < 0) t0 = now
                crtTime = (now - t0) / 1_000_000_000f
            }
        }
    }
    // Baseline ripple while playing; the magnet pass and drops shear hard
    val crtTear = if (idle) 0f
        else (0.25f + 0.75f * magnetPulse + (if (spectrum.isDramatic) 0.4f else 0f)).coerceAtMost(1.6f)

    // Dramatic Effect Logic
    val isDramatic = spectrum.isDramatic
    
    // Scale on Dramatic (Zoom In)
    val dramaticScale by animateFloatAsState(
        targetValue = if (isDramatic) 1f + 0.03f * motionScale else 1f, // Reduced from 1.15f
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "dramaticScale"
    )

    // Kerning on Dramatic (Expand) - Subtle
    val dramaticTracking by animateFloatAsState(
        targetValue = if (isDramatic) 1.5f else 0f, // Reduced from 4f
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "dramaticTracking"
    )

    // Alpha on Black layer (Fade out main text to reveal glitches) - Subtle
    // 0.4f was too ghosty. 0.7f retains legibility but shows color fringes.
    val mainAlpha by animateFloatAsState(
        targetValue = if (isDramatic) 0.7f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "mainAlpha"
    )

    // On the GameBoy LCD theme the shift layers become monochrome green
    // ghosting (slow LCD pixels) instead of CRT chromatic aberration.
    val isLcdTheme = MaterialTheme.colorScheme.background == app.nogarbo.leflac.ui.theme.LcdGreen
    val shiftColorA = if (isLcdTheme) app.nogarbo.leflac.ui.theme.LcdInk.copy(alpha = 0.35f) else GlitchCyan.copy(alpha = 0.8f)
    val shiftColorB = if (isLcdTheme) app.nogarbo.leflac.ui.theme.LcdInk.copy(alpha = 0.25f) else GlitchMagenta.copy(alpha = 0.7f)

    Box(modifier = modifier
        .graphicsLayer {
            scaleX = scaleKick * dramaticScale
            // The magnet squashes the raster slightly; the spring's overshoot
            // makes the snap-back visibly wobble.
            scaleY = scaleKick * dramaticScale * (1f - 0.06f * magnetPulse)
            alpha = if (isDramatic) 0.95f else 1f // Global subtle fade if needed
            if (!isLcdTheme) {
                // CRT physics only: the passive LCD doesn't bend near a magnet
                rotationY = 9f * magnetPulse
                rotationX = -5f * magnetPulse
                translationX = 4.dp.toPx() * magnetPulse // the image pulls toward the field
                cameraDistance = 12f * density
            }
            if (crtShader != null) {
                crtShader.setFloatUniform("time", crtTime)
                crtShader.setFloatUniform("tear", crtTear)
                crtShader.setFloatUniform("density", density)
                renderEffect = android.graphics.RenderEffect
                    .createRuntimeShaderEffect(crtShader, "content")
                    .asComposeRenderEffect()
            }
        }
    ) {
        // Cyan Shift Layer (LCD ghost on GameBoy theme)
        Text(
            text = displayedText,
            style = style.copy(
                color = shiftColorA,
                letterSpacing = dramaticTracking.sp // Apply Kerning
            ),
            modifier = Modifier
                .offset(x = (-chromaticSep).dp + trebleJitter.dp, y = (animatedBass + magnetSmear).dp)
                .graphicsLayer {
                    alpha = (0.6f + spectrum.treble + 0.3f * magnetPulse).coerceIn(0f, 1f)
                    translationX = (Random.nextFloat() * 4f - 2f) * spectrum.mid
                }
        )

        // Magenta Shift Layer (LCD ghost on GameBoy theme)
        Text(
            text = displayedText,
            style = style.copy(
                color = shiftColorB,
                letterSpacing = dramaticTracking.sp // Apply Kerning
            ),
            modifier = Modifier
                .offset(x = chromaticSep.dp - trebleJitter.dp, y = (-animatedBass * 0.5f - magnetSmear).dp)
                .graphicsLayer { alpha = (0.6f + spectrum.mid + 0.3f * magnetPulse).coerceIn(0f, 1f) }
        )
        
        // Main Text (Solid)
        // Subtle vibration on drums (Bass)
        val mainShakeX = if (spectrum.bass > 0.35f) (Random.nextFloat() * 0.5f - 0.25f) else 0f
        val mainShakeY = if (spectrum.bass > 0.45f) (Random.nextFloat() * 0.5f - 0.25f) else 0f

        // Typography leaves color unspecified; resolve against the scheme so
        // dark mode renders beige-on-void instead of an invisible default.
        val mainColor = style.color.takeOrElse { MaterialTheme.colorScheme.onBackground }

        Text(
            text = displayedText,
            style = style.copy(
                color = mainColor.copy(alpha = mainAlpha), // Apply Dramatic Alpha
                letterSpacing = dramaticTracking.sp // Apply Kerning
            ),
            modifier = Modifier.offset(x = mainShakeX.dp, y = mainShakeY.dp)
        )
    }
}
