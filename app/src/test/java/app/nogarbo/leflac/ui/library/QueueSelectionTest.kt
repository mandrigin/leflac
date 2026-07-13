package app.nogarbo.leflac.ui.library

import app.nogarbo.leflac.service.QueuePool
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSelectionTest {

    @Test
    fun reconciliationPreservesOrderAndDropsStaleBlockedDuplicateAndWrongPoolIds() {
        assertEquals(
            listOf("song-b", "song-a"),
            reconcileQueueSelectionIds(
                selectedIds = listOf(
                    "missing",
                    "blocked",
                    "song-b",
                    "mix-a",
                    "song-a",
                    "song-b"
                ),
                availablePools = mapOf(
                    "blocked" to QueuePool.SONG,
                    "song-a" to QueuePool.SONG,
                    "song-b" to QueuePool.SONG,
                    "mix-a" to QueuePool.MIX
                ),
                blockedIds = setOf("blocked")
            )
        )
    }

    @Test
    fun compactCountShowsOverflowWithoutLyingInSemantics() {
        assertEquals("00", compactQueueCount(0))
        assertEquals("99", compactQueueCount(99))
        assertEquals("99+", compactQueueCount(100))
    }

    @Test
    fun backPrecedenceUnwindsSelectionThenSearchThenAlbum() {
        assertEquals(
            LibraryBackAction.CANCEL_SELECTION,
            libraryBackAction(selectionActive = true, searchActive = true, albumOpen = true)
        )
        assertEquals(
            LibraryBackAction.EXIT_ALBUM,
            libraryBackAction(selectionActive = false, searchActive = true, albumOpen = true)
        )
        assertEquals(
            LibraryBackAction.CLEAR_SEARCH,
            libraryBackAction(selectionActive = false, searchActive = true, albumOpen = false)
        )
        assertEquals(
            LibraryBackAction.EXIT_ALBUM,
            libraryBackAction(selectionActive = false, searchActive = false, albumOpen = true)
        )
        assertEquals(
            LibraryBackAction.NONE,
            libraryBackAction(selectionActive = false, searchActive = false, albumOpen = false)
        )
    }
}
