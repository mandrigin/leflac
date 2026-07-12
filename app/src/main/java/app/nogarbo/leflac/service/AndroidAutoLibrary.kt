package app.nogarbo.leflac.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import app.nogarbo.leflac.R
import app.nogarbo.leflac.data.AndroidAutoVisualScheme
import app.nogarbo.leflac.data.AudioTrack
import app.nogarbo.leflac.data.LocalAudioLibrarySnapshot
import app.nogarbo.leflac.data.PlayStatsStore

/**
 * Builds the host-rendered Android Auto media tree.
 *
 * The car owns layout and typography. LE FLAC supplies stable IDs, concise
 * driving-safe labels, content-style hints and local artwork URIs.
 */
@OptIn(UnstableApi::class)
class AndroidAutoLibrary(private val context: Context) {

    fun rootItem(scheme: AndroidAutoVisualScheme): MediaItem = browsableItem(
        id = ROOT_ID,
        title = "LE FLAC",
        subtitle = "${scheme.name} car deck",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        scheme = scheme,
        childBrowsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        childPlayableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )

    fun rootChildren(
        snapshot: LocalAudioLibrarySnapshot,
        scheme: AndroidAutoVisualScheme
    ): List<MediaItem> {
        if (!snapshot.hasPermission) return listOf(permissionItem(scheme))
        if (snapshot.allTracks.isEmpty()) return listOf(noMediaItem(scheme))
        return buildList {
            if (snapshot.songs.isNotEmpty()) add(browsableItem(
                id = HOT_ID,
                title = "Hot now",
                subtitle = "Your current heat",
                mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                scheme = scheme
            ))
            if (snapshot.folders.isNotEmpty()) add(browsableItem(
                id = ALBUMS_ID,
                title = "Albums",
                subtitle = "Folders on this phone",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                scheme = scheme,
                childBrowsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            ))
            if (snapshot.mixes.isNotEmpty()) add(browsableItem(
                id = MIXES_ID,
                title = "Mixes",
                subtitle = "Long-form tapes",
                mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                scheme = scheme
            ))
            if (snapshot.songs.isNotEmpty()) add(browsableItem(
                id = ALL_TRACKS_ID,
                title = "All songs",
                subtitle = "Every song on deck",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                scheme = scheme
            ))
        }
    }

    fun children(
        parentId: String,
        snapshot: LocalAudioLibrarySnapshot,
        scheme: AndroidAutoVisualScheme
    ): List<MediaItem>? = when {
        parentId == ROOT_ID -> rootChildren(snapshot, scheme)
        parentId == PERMISSION_ID || parentId == NO_HEAT_ID || parentId == NO_MEDIA_ID -> emptyList()
        !snapshot.hasPermission -> emptyList()
        parentId == HOT_ID -> hotTracks(snapshot).map { browserTrack(it, scheme) }
            .ifEmpty { listOf(noHeatItem(scheme)) }
        parentId == ALBUMS_ID -> snapshot.folders.map { folderItem(it, scheme) }
        parentId == MIXES_ID -> snapshot.mixes.map { browserTrack(it, scheme) }
        parentId == ALL_TRACKS_ID -> snapshot.songs.map { browserTrack(it, scheme) }
        parentId.startsWith(FOLDER_PREFIX) -> decodeFolderId(parentId)?.let { folder ->
            snapshot.tracksInFolder(folder).map { browserTrack(it, scheme) }
        }
        else -> null
    }

    fun item(
        mediaId: String,
        snapshot: LocalAudioLibrarySnapshot,
        scheme: AndroidAutoVisualScheme
    ): MediaItem? = when {
        mediaId == ROOT_ID -> rootItem(scheme)
        mediaId == PERMISSION_ID -> permissionItem(scheme)
        mediaId == NO_HEAT_ID -> noHeatItem(scheme)
        mediaId == NO_MEDIA_ID -> noMediaItem(scheme)
        mediaId == HOT_ID || mediaId == ALBUMS_ID || mediaId == MIXES_ID || mediaId == ALL_TRACKS_ID ->
            rootChildren(snapshot, scheme).firstOrNull { it.mediaId == mediaId }
        mediaId.startsWith(FOLDER_PREFIX) -> decodeFolderId(mediaId)?.let { folderItem(it, scheme) }
        else -> snapshot.findByMediaId(mediaId)?.let { browserTrack(it, scheme) }
    }

    fun search(
        query: String,
        snapshot: LocalAudioLibrarySnapshot,
        scheme: AndroidAutoVisualScheme
    ): List<MediaItem> {
        val tracks = if (query.isBlank()) {
            hotTracks(snapshot).ifEmpty {
                snapshot.songs.ifEmpty { snapshot.mixes }
            }
        } else {
            snapshot.search(query)
        }
        return tracks.map { browserTrack(it, scheme) }
    }

    /** Resolve browser IDs into local URIs immediately before playback. */
    fun playbackItem(mediaId: String, snapshot: LocalAudioLibrarySnapshot): MediaItem? =
        snapshot.findByMediaId(mediaId)?.let(::playbackItem)

    fun playbackItem(track: AudioTrack): MediaItem = MediaItem.Builder()
        .setMediaId(track.uri.toString())
        .setUri(track.uri)
        .setMimeType(track.mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.folderName)
                .setArtworkUri(artworkUri(AndroidAutoVisualScheme.read(context)))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    /**
     * A car selection gets a useful next/previous queue while preserving the
     * existing LF-1 rule that songs and long mixes never invade each other.
     */
    fun queueFor(mediaId: String, snapshot: LocalAudioLibrarySnapshot): Pair<List<MediaItem>, Int>? {
        val selected = snapshot.findByMediaId(mediaId) ?: return null
        val pool = if (selected.duration >= LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS) {
            snapshot.mixes
        } else {
            snapshot.songs
        }
        val startIndex = pool.indexOfFirst { it.uri == selected.uri }
        if (startIndex < 0) return null
        return pool.map(::playbackItem) to startIndex
    }

    fun libraryParamsExtras(scheme: AndroidAutoVisualScheme): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        // Classic projected Android Auto currently supports at most four
        // useful top-level destinations without adding driver distraction.
        putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, ROOT_CHILD_COUNT)
        // Media3 bridges this standard legacy extra to projected Android Auto.
        putBoolean(LEGACY_SEARCH_SUPPORTED_KEY, true)
        putString(EXTRA_VISUAL_SCHEME, scheme.storedValue)
    }

    private fun hotTracks(snapshot: LocalAudioLibrarySnapshot): List<AudioTrack> {
        val byId = snapshot.songs.associateBy { it.uri.toString() }
        return PlayStatsStore.hotTrackIds(context, snapshot.songs).mapNotNull(byId::get)
    }

    private fun browserTrack(track: AudioTrack, scheme: AndroidAutoVisualScheme): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.folderName)
                    .setDescription("${track.extension} · ${formatDuration(track.duration)}")
                    .setArtworkUri(artworkUri(scheme))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun folderItem(folder: String, scheme: AndroidAutoVisualScheme): MediaItem = browsableItem(
        id = encodeFolderId(folder),
        title = folder,
        subtitle = "Album folder",
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        scheme = scheme
    )

    private fun permissionItem(scheme: AndroidAutoVisualScheme): MediaItem = MediaItem.Builder()
        .setMediaId(PERMISSION_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Phone setup required")
                .setSubtitle("When safely parked, open LE FLAC on your phone and allow Music and audio")
                .setArtworkUri(artworkUri(scheme))
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun noHeatItem(scheme: AndroidAutoVisualScheme): MediaItem = MediaItem.Builder()
        .setMediaId(NO_HEAT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("No heat yet")
                .setSubtitle("Tracks appear here after you listen")
                .setArtworkUri(artworkUri(scheme))
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun noMediaItem(scheme: AndroidAutoVisualScheme): MediaItem = MediaItem.Builder()
        .setMediaId(NO_MEDIA_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("No local audio found")
                .setSubtitle("When safely parked, add music to this phone")
                .setArtworkUri(artworkUri(scheme))
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun browsableItem(
        id: String,
        title: String,
        subtitle: String,
        mediaType: Int,
        scheme: AndroidAutoVisualScheme,
        childBrowsableStyle: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        childPlayableStyle: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, childBrowsableStyle)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, childPlayableStyle)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUri(scheme))
                    .setMediaType(mediaType)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun artworkUri(scheme: AndroidAutoVisualScheme): Uri {
        val resource = when (scheme) {
            AndroidAutoVisualScheme.POCKET -> R.drawable.car_art_pocket
            AndroidAutoVisualScheme.FIELD -> R.drawable.car_art_field
        }
        return Uri.parse("android.resource://${context.packageName}/$resource")
    }

    companion object {
        const val ROOT_ID = "leflac:root"
        const val HOT_ID = "leflac:hot"
        const val ALBUMS_ID = "leflac:albums"
        const val MIXES_ID = "leflac:mixes"
        const val ALL_TRACKS_ID = "leflac:all-tracks"
        const val PERMISSION_ID = "leflac:permission-required"
        const val NO_HEAT_ID = "leflac:no-heat-yet"
        const val NO_MEDIA_ID = "leflac:no-local-audio"
        const val ROOT_CHILD_COUNT = 4
        const val EXTRA_VISUAL_SCHEME = "app.nogarbo.leflac.AUTO_VISUAL_SCHEME"
        private const val FOLDER_PREFIX = "leflac:folder:"
        private const val LEGACY_SEARCH_SUPPORTED_KEY = "android.media.browse.SEARCH_SUPPORTED"

        fun encodeFolderId(folder: String): String = FOLDER_PREFIX + Uri.encode(folder)

        fun decodeFolderId(mediaId: String): String? =
            mediaId.takeIf { it.startsWith(FOLDER_PREFIX) }
                ?.removePrefix(FOLDER_PREFIX)
                ?.let(Uri::decode)
                ?.takeIf(String::isNotBlank)

        fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T>? {
            if (page < 0 || pageSize <= 0) return null
            val start = page.toLong() * pageSize.toLong()
            if (start >= items.size) return emptyList()
            val end = (start + pageSize).coerceAtMost(items.size.toLong())
            return items.subList(start.toInt(), end.toInt())
        }

        private fun formatDuration(durationMs: Long): String {
            val totalMinutes = durationMs.coerceAtLeast(0) / 60_000L
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
    }
}
