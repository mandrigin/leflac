package app.nogarbo.leflac.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AudioCommandBus {
    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekEvents = _seekEvents.asSharedFlow()

    fun triggerSeek(positionMs: Long) {
        _seekEvents.tryEmit(positionMs)
    }
}
