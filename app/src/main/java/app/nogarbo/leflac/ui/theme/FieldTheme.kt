package app.nogarbo.leflac.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable

import androidx.compose.ui.platform.LocalView

// Color Palette - Light Field Edition
val ChassisBeige = Color(0xFFF3F0E6)
val GridLines = Color(0xFFE0DED5)
val InkGrey = Color(0xFF333333)
val SafetyOrange = Color(0xFFFF4400)
val MechGrey = Color(0xFFB0B0B0)
val DeepVoid = Color(0xFF111111)

// Aged GameBoy LCD palette (same hues as the generated artwork in PlaybackViewModel)
val LcdGreen = Color(0xFF8B9B74)   // sun-faded LCD glass
val LcdGreenDim = Color(0xFF7A8B60) // pixel grid shade
val LcdInk = Color(0xFF1A2F1A)     // dark greenish-black pixels

// Low-brightness theme: an old GameBoy LCD screen.
val DarkFieldColorScheme = darkColorScheme(
    primary = LcdInk,
    onPrimary = LcdGreen,
    background = LcdGreen,
    onBackground = LcdInk, // Dark green pixels on faded glass
    surface = LcdGreen,
    onSurface = LcdInk,
    secondary = LcdGreenDim
)

val FieldColorScheme = lightColorScheme(
    primary = SafetyOrange,
    onPrimary = Color.White,
    background = ChassisBeige,
    onBackground = InkGrey,
    surface = ChassisBeige,
    onSurface = InkGrey,
    secondary = MechGrey
)

// Typography - Placeholder for custom fonts (Segment7/Univers)
// Using Monospace as fallback for now to maintain the "Technical" look
// Text color is left unspecified so it resolves per color scheme
// (InkGrey on beige in light mode, ChassisBeige on void in dark mode).
val FieldTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle( // For "STEP 2: OPERATOR" style labels
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 2.sp, // Wide tracking
        color = SafetyOrange
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

// LCD-mode typography: same metrics, but rendered with the GameBoy pixel font.
val LcdFontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(app.nogarbo.leflac.R.font.lcd_font)
)

val LcdFieldTypography = Typography(
    displayLarge = FieldTypography.displayLarge.copy(fontFamily = LcdFontFamily),
    headlineMedium = FieldTypography.headlineMedium.copy(fontFamily = LcdFontFamily, color = LcdInk),
    bodyLarge = FieldTypography.bodyLarge.copy(fontFamily = LcdFontFamily)
)

@Composable
fun FieldTheme(
    isDark: Boolean = false, // Default to light
    content: @Composable () -> Unit
) {
    val colorScheme = if (isDark) DarkFieldColorScheme else FieldColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            // Both chassis beige and LCD green are light backgrounds: dark icons always.
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        app.nogarbo.leflac.ui.skins.LocalFieldSkin provides
            if (isDark) app.nogarbo.leflac.ui.skins.LcdFieldSkin
            else app.nogarbo.leflac.ui.skins.LightFieldSkin
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (isDark) LcdFieldTypography else FieldTypography,
            content = content
        )
    }
}
