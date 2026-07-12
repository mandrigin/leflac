package app.nogarbo.leflac.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun missingPreferenceDefaultsToPocket() {
        assertEquals(AndroidAutoVisualScheme.POCKET, AndroidAutoVisualScheme.fromStored(null))
    }

    @Test
    fun invalidPreferenceDefaultsToPocket() {
        assertEquals(AndroidAutoVisualScheme.POCKET, AndroidAutoVisualScheme.fromStored("unknown"))
    }

    @Test
    fun storedValuesRoundTrip() {
        AndroidAutoVisualScheme.entries.forEach { scheme ->
            assertEquals(scheme, AndroidAutoVisualScheme.fromStored(scheme.storedValue))
        }
    }
}
