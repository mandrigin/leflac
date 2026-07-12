package app.nogarbo.leflac.service

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

object AudioCommandBus {
    const val ACTION_PLAY_LIST = "app.nogarbo.leflac.action.PLAY_LIST"
    private const val EXTRA_COMMAND_TOKEN = "app.nogarbo.leflac.extra.COMMAND_TOKEN"
    private val commandToken = UUID.randomUUID().toString()

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekEvents = _seekEvents.asSharedFlow()

    /**
     * The media library service must be exported for Android Auto discovery,
     * so phone-only start commands carry a per-process token. Browser/session
     * controllers never need or receive it.
     */
    fun authorize(intent: Intent): Intent = intent.putExtra(EXTRA_COMMAND_TOKEN, commandToken)

    fun isAuthorized(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_COMMAND_TOKEN) == commandToken

    fun triggerSeek(positionMs: Long) {
        _seekEvents.tryEmit(positionMs)
    }
}
