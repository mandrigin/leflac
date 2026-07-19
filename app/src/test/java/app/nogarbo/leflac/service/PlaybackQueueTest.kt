package app.nogarbo.leflac.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTest {

    @Test
    fun fifoInsertionFollowsExistingPrioritySegment() {
        assertEquals(3, upNextInsertionIndex(0, listOf(false, true, true, false)))
    }

    @Test
    fun emptyTimelineInsertsAtZero() {
        assertEquals(0, upNextInsertionIndex(C.INDEX_UNSET, emptyList()))
    }

    @Test
    fun pendingIndicesExcludeHistoryAndCurrent() {
        assertEquals(
            listOf(2, 4),
            upNextPendingIndices(1, listOf(true, true, true, false, true))
        )
    }

    @Test
    fun effectiveFutureIncludesManualAndGeneratedButHidesPromotedOccurrence() {
        assertEquals(
            listOf(2, 3, 5),
            effectiveFutureIndices(
                currentIndex = 1,
                timelineIds = listOf("history", "current", "manual-a", "rail-x", "manual-a", "rail-y"),
                marked = listOf(false, false, true, false, false, false)
            )
        )
    }

    @Test
    fun effectiveFutureHidesExactlyOneNaturalOccurrencePerPromotion() {
        assertEquals(
            listOf(1, 2, 5),
            effectiveFutureIndices(
                currentIndex = 0,
                timelineIds = listOf("current", "repeat", "repeat", "repeat", "repeat", "repeat"),
                marked = listOf(false, true, true, false, false, false)
            )
        )
    }

    @Test
    fun effectiveFutureDoesNotLetHistoryOrCurrentPromotionsHideFutureRepeats() {
        assertEquals(
            listOf(2, 3, 4),
            effectiveFutureIndices(
                currentIndex = 1,
                timelineIds = listOf("repeat", "repeat", "repeat", "rail", "repeat"),
                marked = listOf(true, true, false, false, false)
            )
        )
    }

    @Test
    fun effectiveFutureWithoutCurrentProjectsWholeTimeline() {
        assertEquals(
            listOf(0, 2),
            effectiveFutureIndices(
                currentIndex = C.INDEX_UNSET,
                timelineIds = listOf("manual-a", "manual-a", "rail"),
                marked = listOf(true, false, false)
            )
        )
    }

    @Test
    fun priorityItemsPrecedeRailWithoutDestroyingNaturalOccurrences() {
        val merged = insertPriorityAfterCurrent(
            base = listOf("history", "current", "a", "rail", "b", "a"),
            currentIndex = 1,
            priority = listOf("a", "b")
        )

        assertEquals(
            listOf("history", "current", "a", "b", "a", "rail", "b", "a"),
            merged
        )
    }

    @Test
    fun plannerPromotesFutureItemsInRequestedOrder() {
        val plan = planUpNextSchedule(
            currentIndex = 0,
            timelineIds = listOf("current", "rail", "a", "b"),
            marked = listOf(false, false, false, false),
            requestedIds = listOf("a", "b")
        )

        assertEquals(1, plan.insertionIndex)
        assertEquals(
            listOf(PlannedUpNextItem("a", 2), PlannedUpNextItem("b", 3)),
            plan.items
        )
    }

    @Test
    fun plannerIsIdempotentForPendingAndCurrentItems() {
        val plan = planUpNextSchedule(
            currentIndex = 0,
            timelineIds = listOf("current", "a", "rail"),
            marked = listOf(false, true, false),
            requestedIds = listOf("current", "a", "a")
        )

        assertEquals(2, plan.insertionIndex)
        assertEquals(emptyList<PlannedUpNextItem>(), plan.items)
    }

    @Test
    fun consumedPriorityFindsOnlyFirstUnmarkedNaturalDuplicate() {
        assertEquals(
            3,
            firstUnmarkedFutureIndex(
                currentIndex = 1,
                timelineIds = listOf("history", "a", "a", "a", "a"),
                marked = listOf(true, true, true, false, false),
                targetId = "a"
            )
        )
        assertEquals(
            4,
            firstUnmarkedFutureIndex(
                currentIndex = 1,
                timelineIds = listOf("history", "a", "a", "a", "a"),
                marked = listOf(true, true, true, false, false),
                targetId = "a",
                excludedIndices = setOf(3)
            )
        )
    }

    @Test
    fun queueOutcomeOnlyRetainsSelectionForRetryableFailures() {
        assertEquals(
            true,
            ScheduleUpNextOutcome(ScheduleUpNextResult.ADDED, changedCount = 2)
                .shouldClearSelection
        )
        assertEquals(
            false,
            ScheduleUpNextOutcome(ScheduleUpNextResult.WRONG_POOL)
                .shouldClearSelection
        )
        assertEquals(
            false,
            ScheduleUpNextOutcome(ScheduleUpNextResult.UNAVAILABLE)
                .shouldClearSelection
        )
    }
}
