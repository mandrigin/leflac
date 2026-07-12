package app.nogarbo.leflac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.nogarbo.leflac.ui.skins.LocalFieldSkin
import app.nogarbo.leflac.data.AndroidAutoVisualScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The back of the unit — Pocket Operator style: the manual is printed on
 * the device. Flip it over (tap the LF-1 nameplate), read the legend,
 * tap anywhere to flip back.
 */
@Composable
fun RearPanel(
    themeMode: Int = 0,
    onThemeModeChange: (Int) -> Unit = {},
    androidAutoVisualScheme: AndroidAutoVisualScheme = AndroidAutoVisualScheme.POCKET,
    onAndroidAutoVisualSchemeChange: (AndroidAutoVisualScheme) -> Unit = {},
    voiceOn: Boolean = false,
    onVoiceChange: (Boolean) -> Unit = {},
    onTap: () -> Unit
) {
    val skin = LocalFieldSkin.current
    val bg = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val context = androidx.compose.ui.platform.LocalContext.current
    val devicePrefs = androidx.compose.runtime.remember {
        context.getSharedPreferences("flac_prefs", android.content.Context.MODE_PRIVATE)
    }
    // A real serial: minted once per install, etched forever
    val serial = androidx.compose.runtime.remember {
        devicePrefs.getString("serial", null) ?: "%07d".format((1..9_999_999).random()).also {
            devicePrefs.edit().putString("serial", it).apply()
        }
    }
    val runtimeH = androidx.compose.runtime.remember { devicePrefs.getLong("runtime_ms", 0L) / 3_600_000L }
    val fw = androidx.compose.runtime.remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0" }
        catch (e: Exception) { "0" }
    }
    val batteryPct = androidx.compose.runtime.remember {
        try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(onClick = onTap)
    ) {
        // The board. Chips are filled IC packages with etched part numbers,
        // sitting on a dot-grid substrate; traces run in clear channels.
        // Orange = audio path, cyan = data path.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val hot = skin.accent
            val cool = skin.rng.copy(alpha = 0.9f)
            val faint = skin.dim.copy(alpha = 0.45f)

            fun text(t: String, x: Float, y: Float, c: androidx.compose.ui.graphics.Color, sizePx: Float, bold: Boolean = false) {
                drawContext.canvas.nativeCanvas.drawText(t, x, y,
                    android.graphics.Paint().apply {
                        color = c.toArgb(); textSize = sizePx; isAntiAlias = true
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    })
            }

            // dot-grid substrate, only in the board bands
            for (band in listOf(0.030f..0.175f, 0.815f..0.945f)) {
                var yy = band.start
                while (yy < band.endInclusive) {
                    var xx = 0.04f
                    while (xx < 0.97f) {
                        drawCircle(skin.dim.copy(alpha = 0.18f), 1.5f, Offset(xx * w, yy * h))
                        xx += 0.045f
                    }
                    yy += 0.022f
                }
            }

            fun chip(x0: Float, x1: Float, yf: Float, name: String, des: String, accent: androidx.compose.ui.graphics.Color) {
                val x = x0 * w; val cw = (x1 - x0) * w
                val y = yf * h; val ch = 0.042f * h
                // pins first (under the body edges)
                val pinCount = (cw / 36f).toInt().coerceIn(3, 8)
                for (i in 0 until pinCount) {
                    val px = x + cw * (i + 0.5f) / pinCount
                    drawLine(skin.dim, Offset(px, y - 8f), Offset(px, y), 4f)
                    drawLine(skin.dim, Offset(px, y + ch), Offset(px, y + ch + 8f), 4f)
                }
                // drop shadow then body: a package sitting proud of the board
                drawRect(ink.copy(alpha = 0.30f), Offset(x + 6f, y + 6f), androidx.compose.ui.geometry.Size(cw, ch))
                drawRect(ink, Offset(x, y), androidx.compose.ui.geometry.Size(cw, ch))
                // pin-1 dot and etched part number
                drawCircle(bg, 4f, Offset(x + 12f, y + 12f))
                text(name, x + 24f, y + ch / 2f + 6f, bg, 17f, bold = true)
                // designator in the path color, above the package
                text(des, x, y - 12f, accent, 13f)
            }
            fun via(xf: Float, yf: Float, c: androidx.compose.ui.graphics.Color) {
                drawCircle(c, 5f, Offset(xf * w, yf * h))
                drawCircle(bg, 2f, Offset(xf * w, yf * h))
            }
            fun wire(pts: List<Pair<Float, Float>>, c: androidx.compose.ui.graphics.Color) {
                for (i in 1 until pts.size) {
                    drawLine(c, Offset(pts[i - 1].first * w, pts[i - 1].second * h),
                        Offset(pts[i].first * w, pts[i].second * h), 3f)
                }
            }
            // Small two-lead passive (R/C) sitting on a trace
            fun passive(xf: Float, yf: Float, des: String) {
                val cx = xf * w; val cy = yf * h
                drawLine(skin.dim, Offset(cx - 16f, cy), Offset(cx + 16f, cy), 3f)
                drawRect(skin.dim, Offset(cx - 9f, cy - 6f), androidx.compose.ui.geometry.Size(18f, 12f))
                text(des, cx - 11f, cy - 12f, faint, 10f)
            }

            // corner screws
            for (c in listOf(Offset(40f, 50f), Offset(w - 40f, 50f), Offset(40f, h - 40f), Offset(w - 40f, h - 40f))) {
                drawCircle(skin.dim, 16f, c)
                drawCircle(bg, 11f, c)
                drawLine(skin.dim, Offset(c.x - 9f, c.y), Offset(c.x + 9f, c.y), 3f)
            }

            // The board is the REAL architecture: every package is a source
            // file, every trace a call path. Orange = audio, cyan = data.

            // TOP BAND — ingestion & analysis
            chip(0.06f, 0.26f, 0.042f, "LOCAL.LIB", "U1", cool)    // LocalAudioLibrary
            chip(0.34f, 0.55f, 0.042f, "ANLYS.REPO", "U2", cool)   // AnalysisRepository
            chip(0.63f, 0.86f, 0.042f, "TRK.ANLYZR", "U3", cool)   // AudioTrackAnalyzer
            chip(0.06f, 0.21f, 0.122f, "FLAC.DEC", "U4", hot)      // native-lib.cpp
            chip(0.29f, 0.41f, 0.122f, "FASTFFT", "X1", cool)      // FastFFT / JTransforms
            chip(0.49f, 0.67f, 0.122f, "PROFILE.ST", "U5", cool)   // TrackProfileStore
            chip(0.75f, 0.94f, 0.122f, "PLAYSTATS", "U6", hot)     // PlayStatsStore (heat)
            // off-board tap: the OS media index feeds the library
            wire(listOf(0.0f to 0.063f, 0.06f to 0.063f), faint)
            text("J3·MEDIASTORE", 0.005f * w, 0.052f * h, faint, 11f)
            // call paths through the clear channel
            wire(listOf(0.26f to 0.063f, 0.34f to 0.063f), cool)                                     // library -> repo
            wire(listOf(0.55f to 0.063f, 0.63f to 0.063f), cool)                                     // repo -> analyzer
            wire(listOf(0.66f to 0.084f, 0.66f to 0.097f, 0.13f to 0.097f, 0.13f to 0.122f), hot)    // analyzer -> decoder
            wire(listOf(0.72f to 0.084f, 0.72f to 0.106f, 0.35f to 0.106f, 0.35f to 0.122f), cool)   // analyzer -> fft
            wire(listOf(0.79f to 0.084f, 0.79f to 0.113f, 0.58f to 0.113f, 0.58f to 0.122f), cool)   // analyzer -> profiles
            wire(listOf(0.84f to 0.084f, 0.84f to 0.122f), hot)                                      // analyzer -> playstats
            via(0.13f, 0.097f, hot); via(0.35f, 0.106f, cool); via(0.58f, 0.113f, cool)
            passive(0.30f, 0.063f, "R1"); passive(0.45f, 0.143f, "C2")
            // silkscreen
            text("PCB LF-1 REV.C — ANALYSIS DECK", 0.06f * w, 0.192f * h, faint, 12f)

            // BOTTOM BAND — playback & glyph output
            chip(0.06f, 0.24f, 0.825f, "AUDIO.SVC", "U7", hot)     // AudioService
            chip(0.32f, 0.52f, 0.825f, "EXOPLAYER", "U8", hot)     // Media3 (3rd-party silicon)
            chip(0.60f, 0.77f, 0.825f, "PLYB.BUS", "U9", hot)      // PlaybackBus
            chip(0.84f, 0.94f, 0.825f, "PLYB.VM", "U10", hot)      // PlaybackViewModel
            chip(0.06f, 0.24f, 0.895f, "GLYPH.SVC", "U11", cool)   // GlyphToyService
            chip(0.32f, 0.50f, 0.895f, "PHOSPHOR", "U12", cool)    // SpectralPhosphorEngine
            chip(0.58f, 0.77f, 0.895f, "P3.ENGINE", "U13", cool)   // PhoneThreeGlyphEngine
            chip(0.84f, 0.94f, 0.895f, "M.VOICE", "U14", faint)    // MachineVoice
            // off-board: the OS mixer feeds the speaker
            wire(listOf(0.0f to 0.846f, 0.06f to 0.846f), hot)
            text("J1·AUDIOFLINGER", 0.005f * w, 0.835f * h, faint, 11f)
            // Projected Android Auto enters through the browsable Media3
            // session; it never bypasses the one playback owner.
            wire(
                listOf(1.0f to 0.800f, 0.92f to 0.800f, 0.92f to 0.790f,
                    0.15f to 0.790f, 0.15f to 0.825f),
                cool
            )
            text("J4·ANDROID AUTO · AUTO.LIB", 0.60f * w, 0.782f * h, cool, 11f)
            // audio path along row A
            wire(listOf(0.24f to 0.846f, 0.32f to 0.846f), hot)                                      // service -> exoplayer
            wire(listOf(0.52f to 0.846f, 0.60f to 0.846f), hot)                                      // exoplayer -> bus
            wire(listOf(0.77f to 0.846f, 0.84f to 0.846f), hot)                                      // bus -> viewmodel
            // glyph path: bus drops to the matrix row
            wire(listOf(0.64f to 0.867f, 0.64f to 0.881f, 0.15f to 0.881f, 0.15f to 0.895f), cool)   // bus -> glyph service
            via(0.15f, 0.881f, cool)
            wire(listOf(0.24f to 0.916f, 0.32f to 0.916f), cool)                                     // glyph -> phosphor
            wire(listOf(0.50f to 0.916f, 0.58f to 0.916f), cool)                                     // phosphor -> p3 engine
            wire(listOf(0.89f to 0.867f, 0.89f to 0.895f), faint)                                    // viewmodel -> voice
            // the 25x25 matrix hangs off the bottom edge
            wire(listOf(0.70f to 0.937f, 0.70f to 0.958f), cool)
            text("J2·GLYPH 25×25", 0.72f * w, 0.955f * h, faint, 11f)
            passive(0.28f, 0.846f, "R3"); passive(0.54f, 0.916f, "C4")
            // rails carry the analysis results down past the legend
            wire(listOf(0.035f to 0.165f, 0.035f to 0.825f), cool)
            wire(listOf(0.965f to 0.165f, 0.965f to 0.825f), hot)
            via(0.035f, 0.165f, cool); via(0.035f, 0.825f, cool)
            via(0.965f, 0.165f, hot); via(0.965f, 0.825f, hot)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 24.dp)
        ) {
            Text(
                text = "LF-1 — OPERATOR'S MANUAL",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = skin.accent
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(ink))
            Spacer(modifier = Modifier.height(14.dp))

            ManualRow("[>]", "PLAY / PAUSE")
            ManualRow("[<][>]", "PREV / NEXT — IN A MIX: JUMPS BETWEEN CUES")
            ManualRow("HOLD [<]", "PUNCH-IN — SEQUENTIAL REWIND + TARGET LOCK")
            ManualRow("RNG", "RANDOM TRACK — RESPECTS SONG/MIX POOLS")
            ManualRow("HOLD TRACK", "SELECT UP NEXT · TAP MORE · [QUEUE]")
            ManualRow("UP NEXT", "PRIORITY BUS — RUNS BEFORE EITHER RAIL")
            ManualRow("ORDER/RNG", "RAIL LATCH — NEXT FOLLOWS ORDER OR SMART FUTURE")
            ManualRow("RAIL", "DRAG TO SEEK · TALL TICKS = CUE POINTS", info = true)
            ManualRow("FLAME", "HOT NOW — YOUR CURRENT HEAT", info = true)
            ManualRow("INVERT", "EPIC SEGMENT — UNIT HOLDS BREATH 10S PRIOR", info = true)
            ManualRow("DIM <25%", "POCKET MODE — 2-BIT LCD SKIN", info = true)

            Spacer(modifier = Modifier.height(14.dp))

            // DIP SWITCHES — settings live on the back, like hardware
            Text(
                text = "CONFIG",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = skin.accent
            )
            Spacer(modifier = Modifier.height(6.dp))
            DipSwitch(
                label = "SKIN",
                options = listOf("AUTO", "FIELD", "POCKET"),
                selectedIndex = when (themeMode) { 1 -> 1; 2 -> 2; else -> 0 },
                onSelect = { onThemeModeChange(when (it) { 1 -> 1; 2 -> 2; else -> 0 }) }
            )
            Spacer(modifier = Modifier.height(4.dp))
            DipSwitch(
                label = "CAR SKIN",
                options = listOf("POCKET", "FIELD"),
                selectedIndex = if (androidAutoVisualScheme == AndroidAutoVisualScheme.POCKET) 0 else 1,
                onSelect = {
                    onAndroidAutoVisualSchemeChange(
                        if (it == 0) AndroidAutoVisualScheme.POCKET else AndroidAutoVisualScheme.FIELD
                    )
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            DipSwitch(
                label = "VOICE",
                options = listOf("OFF", "ON"),
                selectedIndex = if (voiceOn) 1 else 0,
                onSelect = { onVoiceChange(it == 1) }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "SN-$serial · FW $fw · RUNTIME ${runtimeH}H · MADE ON EARTH",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = skin.dim
            )
        }
    }
}

@Composable
private fun DipSwitch(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val skin = LocalFieldSkin.current
    val ink = MaterialTheme.colorScheme.onBackground
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = ink,
            modifier = Modifier.width(86.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        options.forEachIndexed { i, opt ->
            val active = i == selectedIndex
            Box(
                modifier = Modifier
                    .border(1.dp, if (active) skin.accent else skin.dim)
                    .background(if (active) skin.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics {
                        selected = active
                        contentDescription = "$label: $opt"
                    }
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = opt,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (active) skin.chassis else skin.dim
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
private fun ManualRow(key: String, description: String, info: Boolean = false) {
    val skin = LocalFieldSkin.current
    val ink = MaterialTheme.colorScheme.onBackground
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (info) skin.rng else skin.accent,
            modifier = Modifier.width(86.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = ink
        )
    }
}
