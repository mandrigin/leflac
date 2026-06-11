package app.nogarbo.leflac.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nogarbo.leflac.data.AudioTrack
import app.nogarbo.leflac.ui.theme.FieldTypography

// Ledger tabs: one shelf visible at a time — HOT NOW, MIXES, ALBUMS
private const val TAB_HOT = 0
private const val TAB_MIXES = 1
private const val TAB_ALBUMS = 2

@Composable
fun LibraryGrid(
    viewModel: LibraryViewModel,
    playingTrackId: String? = null,
    onGymStart: () -> Unit = {},
    onTrackSelected: (AudioTrack) -> Unit
) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val hotTrackIds by viewModel.hotTrackIds.collectAsState()
    val mixes by viewModel.mixes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val tracks by viewModel.tracks.collectAsState()

    // Albums holding at least one hot track get the flame on their tile
    val hotFolders = remember(hotTrackIds, tracks) {
        val hot = hotTrackIds.toSet()
        tracks.filter { hot.contains(it.uri.toString()) }.map { it.folderName }.toSet()
    }
    // Hot tracks resolved in heat order, for the HOT NOW shelf
    val hotTracks = remember(hotTrackIds, tracks) {
        val byUri = tracks.associateBy { it.uri.toString() }
        hotTrackIds.mapNotNull { byUri[it] }
    }

    // Tab state: -1 = auto (HOT NOW when there is heat, else ALBUMS)
    var selectedTab by remember { androidx.compose.runtime.mutableStateOf(-1) }
    val activeTab = when {
        selectedTab >= 0 -> selectedTab
        hotTracks.isNotEmpty() -> TAB_HOT
        else -> TAB_ALBUMS
    }

    val scanContext = androidx.compose.ui.platform.LocalContext.current

    // SCAN: type-to-filter across songs and mixes
    var searchQuery by remember { androidx.compose.runtime.mutableStateOf("") }
    val isGymQuery = searchQuery.trim().lowercase().let { it == ">gym" || it == ".gym" }
    val searchResults = remember(searchQuery, tracks, mixes, hotTracks) {
        val q = searchQuery.trim()
        // Deck commands accept > or . — the dot needs no keyboard switch
        val cmd = if (q.startsWith(">") || q.startsWith(".")) q.drop(1).lowercase() else null
        when {
            q.isBlank() -> emptyList()
            cmd == "hot" -> hotTracks
            cmd == "mix" -> mixes
            cmd == "rng" -> tracks.shuffled().take(10)
            // The gym set: relentless tracks, ranked by flat-loud score.
            // BPM shows in service mode (tap the badge).
            cmd == "gym" -> tracks
                .mapNotNull { t ->
                    val key = t.uri.toString()
                    val p = app.nogarbo.leflac.data.TrackProfileStore.get(scanContext, key)
                    // Relentlessness plus YOUR signal: hot tracks train too.
                    // No profile yet (sweep in flight)? Heat alone can carry.
                    val hot = app.nogarbo.leflac.data.PlayStatsStore.hotScore(scanContext, key)
                    // Heat carries real weight: a maxed-heat track outranks
                    // any heuristic-only score. You trained to it; it counts.
                    val rank = (p?.let { app.nogarbo.leflac.data.TrackProfileStore.gymScore(it) } ?: 0f) +
                        0.75f * (hot / 6.0).coerceAtMost(1.0).toFloat()
                    t to rank
                }
                .filter { it.second > 0.24f }
                .sortedByDescending { it.second }
                .take(40)
                .map { it.first }
            else -> (tracks + mixes).filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }.take(50)
        }
    }
    val currentFolder by viewModel.currentFolder.collectAsState()

    // Determine playing folder
    val playingFolder = remember(playingTrackId, tracks) {
        tracks.find { it.uri.toString() == playingTrackId }?.folderName
    }

    // Follow playback: a mix pulls the ledger onto the MIXES shelf; a song
    // refreshes the open album only when ALBUMS is already showing — never
    // yank the reader off HOT NOW or MIXES mid-browse.
    val isMixPlaying = remember(playingTrackId, mixes) {
        playingTrackId != null && mixes.any { it.uri.toString() == playingTrackId }
    }
    androidx.compose.runtime.LaunchedEffect(playingTrackId) {
        if (playingTrackId == null) return@LaunchedEffect
        if (isMixPlaying) {
            selectedTab = TAB_MIXES
        } else if (activeTab == TAB_ALBUMS) {
            val trackFolder = tracks.find { it.uri.toString() == playingTrackId }?.folderName
            if (trackFolder != null && trackFolder != currentFolder) {
                viewModel.enterFolder(trackFolder)
            }
        }
    }

    val seamColor = skin.dim
    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // Plate seam drawn, not laid out: ledger content starts ON the
                // grid line, and SCAN (48) + tabs (32) span exactly two cells.
                drawRect(
                    color = seamColor,
                    size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())
                )
            }
    ) {
        if (currentFolder != null) {
            // OPEN ALBUM: a leaf view — one compact header, then the tracks.
            // No SCAN, no tabs; RETURN is the only chrome an open album needs.
            val folderTracks = tracks.filter { it.folderName == currentFolder }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()

            androidx.compose.runtime.LaunchedEffect(playingTrackId, currentFolder) {
                if (playingTrackId != null) {
                    val index = folderTracks.indexOfFirst { it.uri.toString() == playingTrackId }
                    if (index != -1) {
                        listState.animateScrollToItem(index)
                    }
                }
            }

            // Header bar: one grid cell tall, RETURN + engraved album label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable { viewModel.exitFolder() }
                    .drawBehind {
                        drawLine(
                            color = seamColor,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1f
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "< RETURN",
                    style = FieldTypography.labelMedium,
                    color = skin.accent
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "LIB // ${currentFolder?.uppercase()} · ${folderTracks.size}",
                    style = FieldTypography.labelSmall,
                    color = skin.dim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(folderTracks.size) { index ->
                    val track = folderTracks[index]
                    TrackItem(
                        track = track,
                        isPlaying = track.uri.toString() == playingTrackId,
                        isHot = hotTrackIds.contains(track.uri.toString())
                    ) {
                        onTrackSelected(track)
                    }
                }
            }
        } else {

        // SCAN line: terminal-style filter and command prompt
        ScanField(
            query = searchQuery,
            onQueryChange = { searchQuery = it }
        )

        if (searchQuery.isNotBlank()) {
            // COMMAND RESULTS replace the shelves while the prompt is live
            androidx.compose.foundation.lazy.LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            text = "NO SIGNAL",
                            style = FieldTypography.labelMedium,
                            color = skin.dim,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(searchResults.size) { index ->
                    val track = searchResults[index]
                    TrackItem(
                        track = track,
                        isPlaying = track.uri.toString() == playingTrackId,
                        isHot = hotTrackIds.contains(track.uri.toString())
                    ) {
                        // Playing from the gym list starts a session
                        if (isGymQuery) onGymStart()
                        onTrackSelected(track)
                    }
                }
            }
        } else {
            LedgerTabs(
                active = activeTab,
                onSelect = { selectedTab = it }
            )

            when (activeTab) {
                TAB_HOT -> {
                    // HOT NOW: what the unit knows you are into right now
                    androidx.compose.foundation.lazy.LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (hotTracks.isEmpty()) {
                            item {
                                Text(
                                    text = "NO HEAT YET\n[PLAY · THE UNIT LEARNS]",
                                    style = FieldTypography.labelMedium,
                                    color = skin.dim,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp)
                                )
                            }
                        }
                        items(hotTracks.size) { index ->
                            val track = hotTracks[index]
                            TrackItem(
                                track = track,
                                isPlaying = track.uri.toString() == playingTrackId,
                                isHot = true
                            ) {
                                onTrackSelected(track)
                            }
                        }
                    }
                }

                TAB_MIXES -> {
                    // MIXES grouped by artist, alphabetically
                    val mixesByArtist = remember(mixes) {
                        mixes.groupBy { it.artist.ifBlank { "UNKNOWN ARTIST" } }
                            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    }
                    val mixListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.LaunchedEffect(playingTrackId) {
                        if (playingTrackId == null) return@LaunchedEffect
                        // Flat index of the playing mix, counting artist sub-headers
                        var row = 0
                        var target = -1
                        mixesByArtist.forEach { (_, artistMixes) ->
                            row++ // artist sub-header
                            val i = artistMixes.indexOfFirst { it.uri.toString() == playingTrackId }
                            if (i != -1 && target == -1) target = row + i
                            row += artistMixes.size
                        }
                        if (target != -1) mixListState.animateScrollToItem(target)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = mixListState,
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (mixes.isEmpty()) {
                            item {
                                Text(
                                    text = "NO MIXES ON DECK",
                                    style = FieldTypography.labelMedium,
                                    color = skin.dim,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp)
                                )
                            }
                        }
                        mixesByArtist.forEach { (artist, artistMixes) ->
                            item {
                                Text(
                                    text = "  ${artist.uppercase()} (${artistMixes.size})",
                                    style = FieldTypography.labelSmall,
                                    color = if (skin.isLcd) skin.accent else skin.accent.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            items(artistMixes.size) { index ->
                                val track = artistMixes[index]
                                TrackItem(
                                    track = track,
                                    isPlaying = track.uri.toString() == playingTrackId,
                                    isHot = hotTrackIds.contains(track.uri.toString())
                                ) {
                                    onTrackSelected(track)
                                }
                            }
                        }
                    }
                }

                else -> {
                    // ALBUMS: tile grid of folders (an open album renders
                    // above, replacing the shelves entirely)
                        androidx.compose.foundation.lazy.LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            if (folders.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(100.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (mixes.isEmpty()) "NO MEDIA DETECTED\n[DRAG FILES TO EMULATOR]" else "NO ALBUMS",
                                            style = FieldTypography.labelMedium,
                                            color = skin.dim,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                // LazyColumn can't nest LazyVerticalGrid without a fixed
                                // height, so chunk folders into manual 3-up rows.
                                val chunkedFolders = folders.chunked(3)
                                items(chunkedFolders.size) { rowIndex ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        chunkedFolders[rowIndex].forEach { folder ->
                                            val isPlayingFolder = folder == playingFolder
                                            Box(modifier = Modifier.weight(1f)) {
                                                FolderItem(
                                                    name = folder,
                                                    isPlaying = isPlayingFolder,
                                                    isHot = hotFolders.contains(folder)
                                                ) {
                                                    viewModel.enterFolder(folder)
                                                }
                                            }
                                        }
                                        // Filler for incomplete rows
                                        repeat(3 - chunkedFolders[rowIndex].size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
        }
    }
}

@Composable
private fun LedgerTabs(active: Int, onSelect: (Int) -> Unit) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    // Active tab fill: LCD fakes the tint with an ordered dither, never alpha
    val activeBg: androidx.compose.ui.graphics.Brush =
        if (skin.isLcd) app.nogarbo.leflac.ui.components.rememberDitherBrush(skin.accent, 0.25f)
        else androidx.compose.ui.graphics.SolidColor(skin.accent.copy(alpha = 0.08f))
    val labels = listOf("HOT NOW", "MIXES", "ALBUMS")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .border(1.dp, skin.dim)
    ) {
        labels.forEachIndexed { index, label ->
            val isActive = index == active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isActive) Modifier.background(activeBg) else Modifier)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = FieldTypography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) skin.accent else skin.dim,
                    maxLines = 1
                )
            }
            if (index < labels.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(skin.dim)
                )
            }
        }
    }
}

@Composable
fun FolderItem(name: String, isPlaying: Boolean = false, isHot: Boolean = false, onClick: () -> Unit) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(if (isPlaying) skin.accent else skin.tile)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isHot) {
            // This album holds something hot now.
            app.nogarbo.leflac.ui.components.FlameIcon(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(12.dp),
                color = if (isPlaying) skin.chassis else skin.accent
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
             Text(
                text = if (isPlaying) "PLAYING" else "DIR",
                style = FieldTypography.labelSmall,
                color = if (isPlaying) skin.chassis else skin.dim,
                fontSize = 10.sp
            )
            Text(
                // Full name on up to two lines; ellipsis beats amputation.
                text = name.uppercase(),
                style = FieldTypography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = 16.sp,
                // Tile faces stay light in both skins, so black text stays correct.
                color = if (isPlaying) skin.chassis else Color.Black
            )
        }
    }
}

@Composable
fun TrackItem(track: AudioTrack, isPlaying: Boolean = false, isHot: Boolean = false, onClick: () -> Unit) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    // Tap the tech badge to flip the row into service mode (play stats)
    var serviceMode by remember(track.uri) { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // LCD has no alpha: the playing highlight becomes a Bayer dither field.
    val playingBg: androidx.compose.ui.graphics.Brush =
        if (skin.isLcd) app.nogarbo.leflac.ui.components.rememberDitherBrush(skin.accent, 0.25f)
        else androidx.compose.ui.graphics.SolidColor(skin.accent.copy(alpha = 0.2f))
    val idleBg = androidx.compose.ui.graphics.SolidColor(
        if (skin.isLcd) Color.Transparent else Color.White.copy(alpha = 0.05f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp) // Increased height for tech info
            .background(if (isPlaying) playingBg else idleBg)
            .border(1.dp, if (isPlaying) skin.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track Number / ID
        Text(
            text = String.format("%03d", track.id % 1000),
            style = FieldTypography.labelSmall,
            color = if (isPlaying) skin.accent else skin.dim,
            modifier = Modifier
                .width(40.dp)
                .clickable { serviceMode = true }
        )

        Column(modifier = Modifier.weight(1f)) {
            if (serviceMode) {
                val stats = remember(serviceMode) {
                    app.nogarbo.leflac.data.PlayStatsStore.get(context, track.uri.toString())
                }
                val heat = app.nogarbo.leflac.data.PlayStatsStore.hotScore(stats)
                val profile = remember(serviceMode) {
                    app.nogarbo.leflac.data.TrackProfileStore.get(context, track.uri.toString())
                }
                val daysAgo = if (stats.t > 0) (System.currentTimeMillis() - stats.t) / 86_400_000L else -1L
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "SERVICE // ${stats.p} PLAYS · ${stats.s} SKIPS",
                        style = FieldTypography.bodyMedium,
                        maxLines = 1,
                        color = skin.accent,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "[EXIT]",
                        style = FieldTypography.labelSmall.copy(fontSize = 8.sp),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = skin.accent,
                        modifier = Modifier.clickable { serviceMode = false }
                    )
                }
                Text(
                    text = "HEAT ${"%.1f".format(heat)} · ${if (profile?.bpm ?: 0 > 0) "${profile!!.bpm}BPM · " else ""}LAST ${if (daysAgo >= 0) "${daysAgo}D AGO" else "NEVER"} · ${track.extension} ${track.bitrate}KBPS",
                    style = FieldTypography.labelSmall,
                    color = skin.dim,
                    maxLines = 1
                )
            } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = track.title.uppercase(),
                    style = FieldTypography.bodyMedium,
                    maxLines = 1,
                    color = if (isPlaying) skin.accent else androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isHot) {
                    Spacer(modifier = Modifier.width(4.dp))
                    app.nogarbo.leflac.ui.components.FlameIcon(
                        modifier = Modifier.size(10.dp),
                        color = skin.accent
                    )
                }
                // Gym rating: barbell opacity carries the score
                val gymRating = remember(track.uri) {
                    app.nogarbo.leflac.data.TrackProfileStore.get(context, track.uri.toString())
                        ?.let { app.nogarbo.leflac.data.TrackProfileStore.gymScore(it) } ?: 0f
                }
                if (gymRating > 0.24f) {
                    Spacer(modifier = Modifier.width(4.dp))
                    app.nogarbo.leflac.ui.components.BarbellIcon(
                        modifier = Modifier.size(12.dp),
                        // LCD has no alpha: a weak score uses the mid shade instead
                        color = if (skin.isLcd) {
                            if (gymRating >= 0.40f) skin.accent else skin.shadeMid
                        } else skin.rng.copy(alpha = (gymRating / 0.40f).coerceIn(0.35f, 1f))
                    )
                }
            }
            Text(
                text = track.artist.uppercase(),
                style = FieldTypography.labelSmall,
                color = if (isPlaying) { if (skin.isLcd) skin.accent else skin.accent.copy(alpha = 0.8f) } else skin.dim,
                maxLines = 1
            )
            }
        }

        Text(
            text = formatDuration(track.duration),
            style = FieldTypography.labelSmall,
             color = if (isPlaying) skin.accent else androidx.compose.material3.MaterialTheme.colorScheme.onBackground
        )
    }
}

fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hours = totalSec / 3600
    // Mixes run for hours, so include the hours field when present.
    return if (hours > 0) String.format("%d:%02d:%02d", hours, min, sec)
    else String.format("%02d:%02d", min, sec)
}

@Composable
fun ScanField(query: String, onQueryChange: (String) -> Unit) {
    val skin = app.nogarbo.leflac.ui.skins.LocalFieldSkin.current
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp) // SCAN + tabs = 80dp: two cells of the chassis grid
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SCAN >",
            style = FieldTypography.labelMedium,
            color = if (query.isBlank()) skin.dim else skin.accent
        )
        Spacer(modifier = Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = FieldTypography.labelMedium.copy(color = ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(skin.accent),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, if (query.isBlank()) skin.dim else skin.accent)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        if (query.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "[X]",
                style = FieldTypography.labelMedium,
                color = skin.accent,
                modifier = Modifier.clickable { onQueryChange("") }
            )
        }
    }
}
