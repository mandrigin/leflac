package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.nogarbo.leflac.ui.skins.LocalFieldSkin
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Power-on self test: the telemetry digits flicker, then the unit is up. */
@Composable
fun BootOverlay(modifier: Modifier = Modifier) {
    val skin = LocalFieldSkin.current
    var digits by remember { mutableStateOf("0000000") }
    LaunchedEffect(Unit) {
        while (true) {
            digits = (0 until 7).joinToString("") { Random.nextInt(10).toString() }
            delay(70)
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.padding(start = 32.dp)) {
            Text(
                text = "LF-1",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "K:${digits[0]} B:${digits[1]} S:${digits[2]} G:${digits[3]} V:${digits[4]} Y:${digits[5]} C:${digits[6]}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = skin.dim
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "SELF TEST OK",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = skin.accent
            )
        }
    }
}
