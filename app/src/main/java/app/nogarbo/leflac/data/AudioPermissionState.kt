package app.nogarbo.leflac.data

/** Pure permission transition used by Activity resume and permission results. */
data class AudioPermissionTransition(
    val granted: Boolean,
    val changed: Boolean
)

fun audioPermissionTransition(
    previousGranted: Boolean,
    actualGranted: Boolean
): AudioPermissionTransition = AudioPermissionTransition(
    granted = actualGranted,
    changed = previousGranted != actualGranted
)
