package app.nogarbo.leflac.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.ui.theme.ChassisBeige
import app.nogarbo.leflac.ui.theme.SafetyOrange

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun IsometricButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    baseColor: Color = SafetyOrange,
    contentColor: Color = ChassisBeige,
    bassLevel: Float = 0f,
    isOutline: Boolean = false,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
    globalPunchIn: Float = 0f,
    isPunchInSource: Boolean = false
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val depth = 8.dp
    
    // Animate press depth
    val currentDepth by animateDpAsState(
        targetValue = if (isPressed) 2.dp else depth,
        label = "press_depth"
    )
    
    val offset by animateDpAsState(
        targetValue = if (isPressed) 6.dp else 0.dp,
        label = "press_offset"
    )
    
    // Subtle button shake on Bass (Drums) + Violent shake if global punch in
    val baseShakeX = if (bassLevel > 0.65f) (kotlin.random.Random.nextFloat() * 0.5f - 0.25f) else 0f
    val baseShakeY = if (bassLevel > 0.65f) (kotlin.random.Random.nextFloat() * 0.5f - 0.25f) else 0f
    
    val shakeX = baseShakeX + if (globalPunchIn > 0f && !isPunchInSource) (kotlin.random.Random.nextFloat() * 6f - 3f) * globalPunchIn else 0f
    val shakeY = baseShakeY + if (globalPunchIn > 0f && !isPunchInSource) (kotlin.random.Random.nextFloat() * 6f - 3f) * globalPunchIn else 0f

    // T.E. Strobe colors for non-source buttons during punch-in
    val isGlobalStrobing = globalPunchIn > 0f && !isPunchInSource && (globalPunchIn * 30).toInt() % 2 == 0
    val effectiveBaseColor = if (isGlobalStrobing) contentColor else baseColor
    val effectiveContentColor = if (isGlobalStrobing) baseColor else contentColor
    val effectiveIsOutline = if (globalPunchIn > 0f && !isPunchInSource) !isOutline else isOutline

    val animatedBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (effectiveIsOutline) Color.Transparent else effectiveBaseColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )
    val animatedBorderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (effectiveIsOutline) effectiveBaseColor else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )
    val animatedTextColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (effectiveIsOutline) effectiveBaseColor else effectiveContentColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )
    val animatedShadowBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (effectiveIsOutline) Color.Transparent else effectiveBaseColor.copy(alpha = 0.5f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )
    val animatedShadowBorderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (effectiveIsOutline) effectiveBaseColor.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )
    val innerBorderAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (effectiveIsOutline) 0f else 0.1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = if (globalPunchIn > 0f) 0 else 300)
    )

    // Military Target Locking Progress
    val lockProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed && isPunchInSource) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 500, easing = androidx.compose.animation.core.LinearEasing)
    )

    Box(
        modifier = modifier
            .offset(x = shakeX.dp, y = shakeY.dp)
            .size(size + depth) // Reserve space for the 3D extrusion
    ) {
        // T.E. Style Dramatic Punch-in Effect (Only for the source button)
        if (globalPunchIn > 0f && isPunchInSource) {
            val progress = 1f - globalPunchIn // 0.0 to 1.0
            
            // Giant Overflowing Crosshair (fades out)
            val crosshairAlpha = (1f - progress).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .requiredSize(size * 5f) // Massive spread over other controls, ignore parent constraints
                    .align(Alignment.Center)
                    .offset(x = depth / 2, y = depth / 2)
            ) {
                Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(skin.rng.copy(alpha = crosshairAlpha)))
                Box(modifier = Modifier.align(Alignment.Center).fillMaxHeight().width(2.dp).background(skin.rng.copy(alpha = crosshairAlpha)))
            }

            // Multiple Expanding Concentric Geometric Boxes -> Circles
            for (i in 1..3) {
                val scaleDelay = (i - 1) * 0.15f
                val individualProgress = ((progress - scaleDelay) / (1f - scaleDelay)).coerceIn(0f, 1f)
                
                if (individualProgress > 0f) {
                    // Explode massively to fill the whole screen (15x scale)
                    val echoScale = 1f + individualProgress * 15f 
                    // T.E. alternating intense colors
                    val ringColor = if (i == 2) skin.accent else skin.rng
                    val echoAlpha = (1f - individualProgress).coerceIn(0f, 1f)
                    
                    // Transition from 0% (square) to 50% (circle)
                    val roundPercent = (individualProgress * 50).toInt().coerceIn(0, 50)
                    
                    Box(
                        modifier = Modifier
                            .size(size)
                            .offset(x = offset, y = offset)
                            .scale(echoScale)
                            .border(if (i == 1) 4.dp else 2.dp, ringColor.copy(alpha = echoAlpha), RoundedCornerShape(percent = roundPercent))
                    )
                }
            }
            
            // Rapid Strobe Flash Background
            val isStrobeOn = (progress * 30).toInt() % 2 == 0
            if (isStrobeOn) {
                Box(
                    modifier = Modifier
                        .size(size * 1.5f)
                        .offset(x = offset - size * 0.25f, y = offset - size * 0.25f)
                        .background(skin.flash.copy(alpha = 0.8f))
                )
            }
        }

        // Removed military locking from behind the button

        // Shadow / Side Face (Bottom Right)
        // 2-bit on LCD: a half-tone is a 50% Bayer dither, not an alpha blend.
        val shadowDither = remember(effectiveBaseColor) { ditherBrush(effectiveBaseColor, 0.5f) }
        Box(
            modifier = Modifier
                .size(size)
                .offset(x = depth, y = depth) // Fixed at full depth position
                .then(
                    if (skin.isLcd && !effectiveIsOutline) Modifier.background(shadowDither)
                    else Modifier.background(animatedShadowBgColor) // Darker shade for "side"
                )
                .border(if (isOutline) 1.dp else 0.dp, animatedShadowBorderColor)
        )
        
        // Front Face (Main Button)
        Box(
            modifier = Modifier
                .offset(x = offset, y = offset)
                .size(size)
                .background(animatedBgColor)
                .border(1.dp, animatedBorderColor)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null, // Handle visual state manually
                    onClick = {
                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        app.nogarbo.leflac.util.MachineVoice.click()
                        onClick()
                    },
                    onLongClick = onLongClick?.let { lc ->
                        {
                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            lc()
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner Border for detail
            Box(
                modifier = Modifier
                    .size(size - 4.dp)
                    .background(Color.Transparent)
                    .border(1.dp, Color.Black.copy(alpha = innerBorderAlpha))
            )

            Text(
                text = text,
                style = textStyle,
                color = animatedTextColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // Military Locking Target Animation (Drawn ON TOP of button)
        val isPunchInExploding = globalPunchIn > 0f && isPunchInSource
        if ((lockProgress > 0f && lockProgress < 1f) || isPunchInExploding) {
            val currentLockProgress = if (isPunchInExploding) 1f else lockProgress
            val lockAlpha = if (isPunchInExploding) (1f - globalPunchIn).coerceIn(0f, 1f) else 1f
            
            Canvas(
                modifier = Modifier
                    .requiredSize(size * 4f) // Ignore parent constraints so center aligns correctly
                    .align(Alignment.Center)
                    .offset(x = depth / 2, y = depth / 2)
            ) {
                val center = size.toPx() * 2f
                val maxDist = size.toPx() * 1.5f
                val minDist = size.toPx() * 0.55f // Clamps exactly on the outer edge
                val currentDist = maxDist - (maxDist - minDist) * currentLockProgress
                
                val bracketLen = size.toPx() * 0.4f
                val strokeW = 1f + (3.dp.toPx() - 1f) * currentLockProgress
                val paintColor = skin.rng.copy(alpha = lockAlpha * 0.8f)

                // Top-Left Bracket
                drawLine(paintColor, Offset(center - currentDist, center - currentDist), Offset(center - currentDist + bracketLen, center - currentDist), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center - currentDist, center - currentDist), Offset(center - currentDist, center - currentDist + bracketLen), strokeWidth = strokeW)
                // Diagonal pointer
                drawLine(paintColor, Offset(center - currentDist, center - currentDist), Offset(center - currentDist + bracketLen/2, center - currentDist + bracketLen/2), strokeWidth = strokeW)

                // Top-Right Bracket
                drawLine(paintColor, Offset(center + currentDist, center - currentDist), Offset(center + currentDist - bracketLen, center - currentDist), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center + currentDist, center - currentDist), Offset(center + currentDist, center - currentDist + bracketLen), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center + currentDist, center - currentDist), Offset(center + currentDist - bracketLen/2, center - currentDist + bracketLen/2), strokeWidth = strokeW)
                
                // Bottom-Left Bracket
                drawLine(paintColor, Offset(center - currentDist, center + currentDist), Offset(center - currentDist + bracketLen, center + currentDist), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center - currentDist, center + currentDist), Offset(center - currentDist, center + currentDist - bracketLen), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center - currentDist, center + currentDist), Offset(center - currentDist + bracketLen/2, center + currentDist - bracketLen/2), strokeWidth = strokeW)

                // Bottom-Right Bracket
                drawLine(paintColor, Offset(center + currentDist, center + currentDist), Offset(center + currentDist - bracketLen, center + currentDist), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center + currentDist, center + currentDist), Offset(center + currentDist, center + currentDist - bracketLen), strokeWidth = strokeW)
                drawLine(paintColor, Offset(center + currentDist, center + currentDist), Offset(center + currentDist - bracketLen/2, center + currentDist - bracketLen/2), strokeWidth = strokeW)
            }
        }
    }
}
