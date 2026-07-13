package app.nogarbo.leflac.service

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.nogarbo.leflac.data.AudioTrack
import app.nogarbo.leflac.data.LocalAudioLibrarySnapshot
import java.util.UUID

enum class QueuePool {
    SONG,
    MIX
}

data class UpNextEntry(
    val entryId: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val durationMs: Long
)

enum class ScheduleUpNextResult {
    ADDED,
    ARMED,
    ALREADY_QUEUED,
    WRONG_POOL,
    UNAVAILABLE
}

/** Result plus the number of tracks that actually changed the physical queue. */
data class ScheduleUpNextOutcome(
    val result: ScheduleUpNextResult,
    val changedCount: Int = 0
) {
    val shouldClearSelection: Boolean
        get() = result != ScheduleUpNextResult.WRONG_POOL &&
            result != ScheduleUpNextResult.UNAVAILABLE
}

fun AudioTrack.queuePool(): QueuePool =
    if (duration >= LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS) {
        QueuePool.MIX
    } else {
        QueuePool.SONG
    }

fun playbackPool(allTracks: List<AudioTrack>, selected: AudioTrack): List<AudioTrack> {
    val pool = selected.queuePool()
    return allTracks.filter { it.queuePool() == pool }
}

fun AudioTrack.toPlaybackMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setMimeType(mimeType)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(folderName)
            .setDurationMs(duration)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()

object UpNextQueue {
    private const val EXTRA_QUEUE_ORIGIN = "app.nogarbo.leflac.queue_origin"
    private const val EXTRA_QUEUE_TOKEN = "app.nogarbo.leflac.queue_token"
    private const val ORIGIN_UP_NEXT = "up_next"

    fun mark(mediaItem: MediaItem, entryId: String = UUID.randomUUID().toString()): MediaItem {
        val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(EXTRA_QUEUE_ORIGIN, ORIGIN_UP_NEXT)
            putString(EXTRA_QUEUE_TOKEN, entryId)
        }
        val metadata = mediaItem.mediaMetadata.buildUpon()
            .setExtras(extras)
            .build()
        return mediaItem.buildUpon().setMediaMetadata(metadata).build()
    }

    fun isMarked(mediaItem: MediaItem): Boolean =
        mediaItem.mediaMetadata.extras?.getString(EXTRA_QUEUE_ORIGIN) == ORIGIN_UP_NEXT

    fun entryId(mediaItem: MediaItem): String? =
        mediaItem.mediaMetadata.extras?.getString(EXTRA_QUEUE_TOKEN)

    fun pendingIndices(player: Player): List<Int> {
        val flags = (0 until player.mediaItemCount).map { index ->
            isMarked(player.getMediaItemAt(index))
        }
        return upNextPendingIndices(player.currentMediaItemIndex, flags)
    }

    fun pendingItems(player: Player): List<MediaItem> =
        pendingIndices(player).map(player::getMediaItemAt)

    fun pendingEntries(player: Player): List<UpNextEntry> =
        pendingIndices(player).map { index ->
            val item = player.getMediaItemAt(index)
            UpNextEntry(
                entryId = entryId(item) ?: "queue-$index-${item.mediaId}",
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown track" },
                artist = item.mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
                durationMs = item.mediaMetadata.durationMs ?: C.TIME_UNSET
            )
        }
}

internal fun upNextInsertionIndex(currentIndex: Int, marked: List<Boolean>): Int {
    if (marked.isEmpty()) return 0
    var index = if (currentIndex == C.INDEX_UNSET) 0 else (currentIndex + 1).coerceIn(0, marked.size)
    while (index < marked.size && marked[index]) index++
    return index
}

internal fun upNextPendingIndices(currentIndex: Int, marked: List<Boolean>): List<Int> {
    val start = if (currentIndex == C.INDEX_UNSET) 0 else (currentIndex + 1).coerceIn(0, marked.size)
    return (start until marked.size).filter { marked[it] }
}

internal data class PlannedUpNextItem(
    val mediaId: String,
    val sourceIndex: Int?
)

internal data class UpNextSchedulePlan(
    val insertionIndex: Int,
    val items: List<PlannedUpNextItem>
)

internal fun planUpNextSchedule(
    currentIndex: Int,
    timelineIds: List<String>,
    marked: List<Boolean>,
    requestedIds: List<String>
): UpNextSchedulePlan {
    require(timelineIds.size == marked.size)
    val insertionIndex = upNextInsertionIndex(currentIndex, marked)
    val currentId = timelineIds.getOrNull(currentIndex)
    val pendingIds = upNextPendingIndices(currentIndex, marked)
        .map(timelineIds::get)
        .toSet()
    val freshIds = requestedIds.distinct().filter { it != currentId && it !in pendingIds }
    val claimed = mutableSetOf<Int>()
    val items = freshIds.map { requestedId ->
        val sourceIndex = (insertionIndex until timelineIds.size).firstOrNull { index ->
            index !in claimed && !marked[index] && timelineIds[index] == requestedId
        }
        if (sourceIndex != null) claimed += sourceIndex
        PlannedUpNextItem(requestedId, sourceIndex)
    }
    return UpNextSchedulePlan(insertionIndex, items)
}

internal fun firstUnmarkedFutureIndex(
    currentIndex: Int,
    timelineIds: List<String>,
    marked: List<Boolean>,
    targetId: String,
    excludedIndices: Set<Int> = emptySet()
): Int? {
    require(timelineIds.size == marked.size)
    val start = (currentIndex + 1).coerceIn(0, timelineIds.size)
    return (start until timelineIds.size).firstOrNull { index ->
        index !in excludedIndices && !marked[index] && timelineIds[index] == targetId
    }
}

/** Inserts explicit priority items without mutating the underlying rail. */
internal fun <T> insertPriorityAfterCurrent(
    base: List<T>,
    currentIndex: Int,
    priority: List<T>
): List<T> {
    if (priority.isEmpty()) return base
    val split = (currentIndex + 1).coerceIn(0, base.size)
    return base.take(split) + priority + base.drop(split)
}
