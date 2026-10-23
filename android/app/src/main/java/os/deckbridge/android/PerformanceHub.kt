package os.deckbridge.android

import android.media.midi.MidiInputPort
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.TempoAssembler

object PerformanceHub {
    var onAction: ((PerformanceAction) -> Unit)? = null
    var tempo: TempoAssembler? = null

    /** Porta de entrada da controladora (app para controladora): os LEDs saem por aqui. */
    @Volatile
    var ledPort: MidiInputPort? = null

    /** Sobe a cada nova conexão da controladora; o app reenvia todos os LEDs quando muda. */
    @Volatile
    var ledEpoch = 0

    /** Há uma controladora MIDI aberta. */
    @Volatile
    var controllerConnected = false

    fun sendMidi(bytes: ByteArray) {
        val port = ledPort ?: return
        runCatching { port.send(bytes, 0, bytes.size) }
    }
}
