package app.nogarbo.leflac.util

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The machine's voice: tiny transport clicks and a power-on chirp.
 * Off by default; the VOICE switch lives on the rear panel.
 */
object MachineVoice {
    @Volatile var enabled: Boolean = false

    private val tg: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 35) } catch (e: Exception) { null }
    }

    /** Key click — transport buttons, cue jumps. */
    fun click() = play(ToneGenerator.TONE_PROP_BEEP, 25)

    /** Tape stop — pause. */
    fun thunk() = play(ToneGenerator.TONE_CDMA_PIP, 45)

    /** Power-on chirp. */
    fun boot() = play(ToneGenerator.TONE_PROP_BEEP2, 80)

    private fun play(tone: Int, ms: Int) {
        if (!enabled) return
        try { tg?.startTone(tone, ms) } catch (e: Exception) { /* voice is optional */ }
    }
}
