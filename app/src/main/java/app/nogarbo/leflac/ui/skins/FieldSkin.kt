package app.nogarbo.leflac.ui.skins

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.nogarbo.leflac.ui.theme.ChassisBeige
import app.nogarbo.leflac.ui.theme.GridLines
import app.nogarbo.leflac.ui.theme.LcdGreen
import app.nogarbo.leflac.ui.theme.LcdGreenDim
import app.nogarbo.leflac.ui.theme.LcdInk
import app.nogarbo.leflac.ui.theme.MechGrey
import app.nogarbo.leflac.ui.theme.SafetyOrange

/**
 * Accent palette for the two device personalities.
 *
 * The light skin is the Field Edition chassis: safety orange, cyan telemetry,
 * CMYK spectrogram. The LCD skin is a 1-bit handheld (old GameBoy glass
 * crossed with a Playdate): every accent collapses to dark green ink so the
 * whole UI reads as pixels on a single passive-matrix screen.
 */
@Immutable
data class FieldSkin(
    val isLcd: Boolean,
    val accent: Color,        // hero accent: play button, progress, playing markers
    val rng: Color,           // RNG / telemetry cyan
    val chassis: Color,       // content on accent surfaces
    val buttonBase: Color,    // neutral button face
    val buttonContent: Color, // glyph on neutral buttons
    val dim: Color,           // faint lines / secondary text
    val tile: Color,          // album tile face
    val alert: Color,         // drama / warning
    val flash: Color,         // strobe flash (LCD inverts to bright glass)
    val watermark: Color,     // headphones ghost
    val spectroBass: Color,
    val spectroMid: Color,
    val spectroTreble: Color,
    val cubeFront: Color,     // 3D scrubber thumb
    val cubeTop: Color,
    val cubeSide: Color,
    val display: Color,       // segmented Casio readout
    // 2-bit tone ramp between background and ink. On a DMG there are exactly
    // four shades; these are the two in the middle, always SOLID — anything
    // that used to be "ink at some alpha" snaps to one of them.
    val shadeLight: Color,
    val shadeMid: Color
)

// Two voices only: safety orange and cyan. Everything else is chassis.
val LightFieldSkin = FieldSkin(
    isLcd = false,
    accent = SafetyOrange,
    rng = Color.Cyan,
    chassis = ChassisBeige,
    buttonBase = GridLines,
    buttonContent = ChassisBeige,
    dim = Color(0xFF8E8C82), // readable in sunlight; GridLines was near-invisible text
    tile = MechGrey,
    alert = SafetyOrange,
    flash = Color.White,
    watermark = Color.Cyan.copy(alpha = 0.30f),
    spectroBass = SafetyOrange,
    spectroMid = Color(0xFF008B8B),
    spectroTreble = Color.Cyan,
    cubeFront = Color(0xFF008B8B),
    cubeTop = Color(0xFF20B2AA),
    cubeSide = Color(0xFF006060),
    display = Color.Cyan,
    shadeLight = GridLines,
    shadeMid = MechGrey
)

// The DMG ramp: glass -> light shade -> mid shade -> ink. The middle tones
// are the old alpha blends of ink over glass, pre-baked as solid colors.
private val LcdShadeLight = Color(0xFF6F805D) // ~25% ink over glass
private val LcdShadeMid = Color(0xFF586A4C)   // ~45% ink over glass

val LcdFieldSkin = FieldSkin(
    isLcd = true,
    accent = LcdInk,
    rng = LcdInk,
    chassis = LcdGreen,
    buttonBase = LcdGreenDim,
    buttonContent = LcdInk,
    dim = LcdShadeMid,
    tile = LcdGreenDim,
    alert = LcdInk,
    flash = LcdGreen, // a passive LCD "flashes" by clearing to bare glass
    watermark = LcdShadeLight,
    spectroBass = LcdInk,
    spectroMid = LcdShadeMid,
    spectroTreble = LcdShadeLight,
    cubeFront = LcdInk,
    cubeTop = LcdGreenDim,
    cubeSide = Color(0xFF0E1C0E),
    display = LcdInk,
    shadeLight = LcdShadeLight,
    shadeMid = LcdShadeMid
)

val LocalFieldSkin = staticCompositionLocalOf { LightFieldSkin }
