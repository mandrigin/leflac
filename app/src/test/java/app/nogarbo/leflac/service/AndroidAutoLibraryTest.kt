package app.nogarbo.leflac.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoLibraryTest {

    private val items = (0 until 10).toList()

    @Test
    fun pageReturnsRequestedWindow() {
        assertEquals(listOf(3, 4, 5), AndroidAutoLibrary.page(items, page = 1, pageSize = 3))
    }

    @Test
    fun finalPageIsSafelyTruncated() {
        assertEquals(listOf(8, 9), AndroidAutoLibrary.page(items, page = 2, pageSize = 4))
    }

    @Test
    fun pagePastEndIsEmpty() {
        assertEquals(emptyList<Int>(), AndroidAutoLibrary.page(items, page = 3, pageSize = 4))
    }

    @Test
    fun invalidPaginationIsRejected() {
        assertNull(AndroidAutoLibrary.page(items, page = -1, pageSize = 4))
        assertNull(AndroidAutoLibrary.page(items, page = 0, pageSize = 0))
    }

    @Test
    fun paginationArithmeticCannotOverflow() {
        assertEquals(
            emptyList<Int>(),
            AndroidAutoLibrary.page(items, page = Int.MAX_VALUE, pageSize = Int.MAX_VALUE)
        )
    }
}
