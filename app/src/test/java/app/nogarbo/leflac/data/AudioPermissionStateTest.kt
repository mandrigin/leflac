package app.nogarbo.leflac.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPermissionStateTest {

    @Test
    fun resumeAfterSettingsRecognizesNewGrant() {
        assertEquals(
            AudioPermissionTransition(granted = true, changed = true),
            audioPermissionTransition(previousGranted = false, actualGranted = true)
        )
    }

    @Test
    fun unchangedOrRevokedPermissionReportsTruthfully() {
        assertEquals(
            AudioPermissionTransition(granted = false, changed = false),
            audioPermissionTransition(previousGranted = false, actualGranted = false)
        )
        assertEquals(
            AudioPermissionTransition(granted = false, changed = true),
            audioPermissionTransition(previousGranted = true, actualGranted = false)
        )
    }
}
