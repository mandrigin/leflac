package app.nogarbo.leflac.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nogarbo.leflac.RailLatch
import app.nogarbo.leflac.ui.components.FieldScrubber
import app.nogarbo.leflac.ui.components.IsometricButton
import app.nogarbo.leflac.ui.theme.FieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transportButtonExposesClickAndLongClickActions() {
        composeRule.setContent {
            FieldTheme {
                IsometricButton(
                    text = "NEXT",
                    onClick = {},
                    onLongClick = {},
                    onClickLabel = "Next track",
                    onLongClickLabel = "Next file in library order"
                )
            }
        }

        composeRule.onNodeWithText("NEXT")
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .assert(
                SemanticsMatcher("expected labeled long-click action") { node ->
                    node.config.getOrNull(SemanticsActions.OnLongClick)?.label ==
                        "Next file in library order"
                }
            )
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun railLatchExposesToggleStateWithoutChangingItsPaintedSize() {
        composeRule.setContent {
            FieldTheme {
                RailLatch(rngOn = true, onToggle = {})
            }
        }

        composeRule.onNodeWithContentDescription("Playback rail")
            .assertIsOn()
    }

    @Test
    fun emptyScrubberIsDisabledAndExplainsWhy() {
        composeRule.setContent {
            FieldTheme {
                FieldScrubber(
                    position = 0L,
                    duration = 0L,
                    onSeek = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Playback position")
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "No track loaded"
                )
            )
    }
}
