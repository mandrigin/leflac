package app.nogarbo.leflac.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckCommandSuggestionsTest {

    @Test
    fun dotListsEveryCanonicalCommand() {
        assertEquals(
            listOf(
                ".hot",
                ".gym",
                ".cardio",
                ".weights",
                ".grit",
                ".static",
                ".mix",
                ".rng"
            ),
            matchingDeckCommandSuggestions(".").map(DeckCommandSuggestion::command)
        )
    }

    @Test
    fun partialPrefixesFilterCanonicalCommandsAndAliases() {
        assertEquals(
            listOf(".gym", ".grit"),
            matchingDeckCommandSuggestions(".g").map(DeckCommandSuggestion::command)
        )
        assertEquals(
            listOf(".grit"),
            matchingDeckCommandSuggestions(".grif").map(DeckCommandSuggestion::command)
        )
        assertEquals(
            listOf(".mix"),
            matchingDeckCommandSuggestions(">MI").map(DeckCommandSuggestion::command)
        )
    }

    @Test
    fun resolvedCommandsHideSuggestionsAndPlainSearchDoesNotEnterCommandMode() {
        assertTrue(resolvesDeckCommand(".gym"))
        assertTrue(resolvesDeckCommand(".grift"))
        assertTrue(resolvesDeckCommand(">weight"))
        assertTrue(matchingDeckCommandSuggestions(".gym").isEmpty())
        assertTrue(matchingDeckCommandSuggestions(".grift").isEmpty())
        assertFalse(resolvesDeckCommand("gym"))
        assertTrue(matchingDeckCommandSuggestions("gym").isEmpty())
    }
}
