package app.nogarbo.leflac.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** A single MediaStore snapshot shared by the phone ledger and car browser. */
data class LocalAudioLibrarySnapshot(
    val hasPermission: Boolean,
    val allTracks: List<AudioTrack>
) {
    val mixes: List<AudioTrack> = allTracks.filter { it.duration >= MIX_DURATION_THRESHOLD_MS }
    val songs: List<AudioTrack> = allTracks.filter { it.duration < MIX_DURATION_THRESHOLD_MS }
    val folders: List<String> = songs.map(AudioTrack::folderName).distinct().sorted()

    fun tracksInFolder(folderName: String): List<AudioTrack> =
        songs.filter { it.folderName == folderName }

    fun findByMediaId(mediaId: String): AudioTrack? =
        allTracks.firstOrNull { it.uri.toString() == mediaId }

    fun search(query: String): List<AudioTrack> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return allTracks.filter { track ->
            val haystack = "${track.title} ${track.artist} ${track.folderName}"
            words.all { word -> haystack.contains(word, ignoreCase = true) }
        }
    }

    companion object {
        const val MIX_DURATION_THRESHOLD_MS = 20 * 60 * 1_000L
    }
}

/**
 * Reads local audio without retaining a Cursor or depending on an Activity.
 * Android Auto can therefore browse the same library while the phone UI is
 * not running.
 */
class LocalAudioLibrary(
    context: Context,
    observeChanges: Boolean = false,
    private val onInvalidated: () -> Unit = {}
) : AutoCloseable {
    private val context = context.applicationContext
    private val cacheLock = Any()
    @Volatile private var cachedSnapshot: LocalAudioLibrarySnapshot? = null
    private val contentObserver = if (observeChanges) {
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = invalidate()
        }.also {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                it
            )
        }
    } else {
        null
    }

    fun load(): LocalAudioLibrarySnapshot {
        if (!hasAudioPermission()) {
            cachedSnapshot = null
            return LocalAudioLibrarySnapshot(false, emptyList())
        }
        cachedSnapshot?.let { return it }

        return synchronized(cacheLock) {
            cachedSnapshot?.let { return@synchronized it }
            val tracks = try {
                queryTracks()
            } catch (_: SecurityException) {
                return@synchronized LocalAudioLibrarySnapshot(false, emptyList())
            }
            LocalAudioLibrarySnapshot(true, tracks).also { cachedSnapshot = it }
        }
    }

    fun invalidate() {
        synchronized(cacheLock) {
            cachedSnapshot = null
        }
        onInvalidated()
    }

    override fun close() {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: IllegalStateException) {
                // The resolver was already torn down with the process.
            }
        }
        cachedSnapshot = null
    }

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryTracks(): List<AudioTrack> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val useBuckets = Build.VERSION.SDK_INT >= 29
        val projection = if (useBuckets) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE
            )
        }

        // Preserve the LF-1 rule: skip tiny non-FLAC clips while always
        // retaining FLAC files even when their duration metadata is absent.
        @Suppress("DEPRECATION")
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "(${MediaStore.Audio.Media.DURATION} >= 60000 OR " +
                "${MediaStore.Audio.Media.MIME_TYPE} LIKE '%flac%' OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%.flac')"
        val sortOrder = if (useBuckets) {
            "${MediaStore.Audio.Media.BUCKET_DISPLAY_NAME} ASC"
        } else {
            null
        }

        val tracks = mutableListOf<AudioTrack>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            @Suppress("DEPRECATION")
            val folderColumn = cursor.getColumnIndexOrThrow(
                if (useBuckets) MediaStore.Audio.Media.BUCKET_DISPLAY_NAME else MediaStore.Audio.Media.DATA
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val rawFolder = cursor.getString(folderColumn)
                val folder = if (useBuckets) {
                    rawFolder ?: "Unknown"
                } else {
                    rawFolder?.let { java.io.File(it).parentFile?.name } ?: "Root"
                }
                tracks += AudioTrack(
                    id = id,
                    title = cursor.getString(titleColumn) ?: "Unknown Track",
                    artist = cursor.getString(artistColumn) ?: "Unknown Artist",
                    duration = cursor.getLong(durationColumn),
                    uri = ContentUris.withAppendedId(collection, id),
                    folderName = folder,
                    size = cursor.getLong(sizeColumn),
                    mimeType = cursor.getString(mimeColumn) ?: "audio/*"
                )
            }
        }
        return tracks
    }
}
