package app.nogarbo.leflac.ui.library

import app.nogarbo.leflac.service.QueuePool

/** Reconciles saveable FIFO selection IDs with a freshly scanned library. */
internal fun reconcileQueueSelectionIds(
    selectedIds: List<String>,
    availablePools: Map<String, QueuePool>,
    blockedIds: Set<String>
): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var selectedPool: QueuePool? = null
    selectedIds.forEach { mediaId ->
        val pool = availablePools[mediaId] ?: return@forEach
        if (mediaId in blockedIds || !seen.add(mediaId)) return@forEach
        if (selectedPool == null) selectedPool = pool
        if (pool == selectedPool) result += mediaId
    }
    return result
}

internal fun compactQueueCount(value: Int): String =
    if (value > 99) "99+" else value.toString().padStart(2, '0')

internal enum class LibraryBackAction {
    CANCEL_SELECTION,
    CLEAR_SEARCH,
    EXIT_ALBUM,
    NONE
}

internal fun libraryBackAction(
    selectionActive: Boolean,
    searchActive: Boolean,
    albumOpen: Boolean
): LibraryBackAction = when {
    selectionActive -> LibraryBackAction.CANCEL_SELECTION
    albumOpen && searchActive -> LibraryBackAction.EXIT_ALBUM
    searchActive -> LibraryBackAction.CLEAR_SEARCH
    albumOpen -> LibraryBackAction.EXIT_ALBUM
    else -> LibraryBackAction.NONE
}
