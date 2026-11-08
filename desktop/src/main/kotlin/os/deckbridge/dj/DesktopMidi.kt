package os.deckbridge.dj

import os.deckbridge.android.PerformanceHub
import os.deckbridge.logic.MidiAssembler
import os.deckbridge.logic.MidiMap
import os.deckbridge.logic.interpretPerformance
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver

/**
 * A DDJ-400 no computador pelo `javax.sound.midi` (no Linux, o ALSA por baixo; a controladora é USB MIDI de classe, sem
 * driver). As mensagens seguem o mesmo caminho do app Android: montador e regra em C++ (`controller.h`), mapa do Mixxx
 * pinado, `interpretPerformance` e `PerformanceHub.onAction`. Os LEDs voltam por `PerformanceHub.sendMidi`.
 */
class DesktopMidi(private val post: (() -> Unit) -> Unit) : AutoCloseable {
    private val map = MidiMap.loadResource()
    private val assembler = MidiAssembler()
    private val tempo = os.deckbridge.logic.TempoAssembler()
    private val opened = mutableListOf<MidiDevice>()

    /** Nome das portas abertas, para a tela dizer o que achou. Vazio: nenhuma controladora. */
    var portNames: List<String> = emptyList()
        private set

    init {
        PerformanceHub.tempo = tempo
    }

    /** Abre a entrada e a saída cujo nome contém [match] ("DDJ" acha a DDJ-400). Devolve quantas portas abriu. */
    fun open(match: String = "DDJ"): Int {
        close()
        val infos = runCatching { MidiSystem.getMidiDeviceInfo().toList() }.getOrDefault(emptyList())
        val names = mutableListOf<String>()
        for (info in infos) {
            if (!info.name.contains(match, ignoreCase = true) && !info.description.contains(match, ignoreCase = true)) continue
            val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            if (device is javax.sound.midi.Sequencer || device is javax.sound.midi.Synthesizer) continue
            runCatching {
                if (device.maxTransmitters != 0) {
                    device.open()
                    device.transmitter.receiver = Input()
                    opened += device
                    names += "entrada ${info.name}"
                } else if (device.maxReceivers != 0) {
                    device.open()
                    val out = device.receiver
                    PerformanceHub.ledPort = { bytes -> sendTo(out, bytes) }
                    PerformanceHub.ledEpoch += 1
                    opened += device
                    names += "saída ${info.name}"
                }
            }
        }
        portNames = names
        PerformanceHub.controllerConnected = names.any { it.startsWith("entrada") }
        return names.size
    }

    /** Uma mensagem como se viesse da controladora (testes e "simular"). */
    fun inject(bytes: ByteArray) = handle(bytes)

    private fun handle(bytes: ByteArray) {
        for (message in assembler.push(bytes)) {
            val hit = map.lookup(message.status, message.data1)
            val action = interpretPerformance(hit, message.data2, tempo) ?: continue
            post { PerformanceHub.onAction?.invoke(action) }
        }
    }

    private inner class Input : Receiver {
        override fun send(message: MidiMessage, timeStamp: Long) = handle(message.message.copyOf(message.length))
        override fun close() {}
    }

    override fun close() {
        PerformanceHub.ledPort = null
        PerformanceHub.controllerConnected = false
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        portNames = emptyList()
    }

    private companion object {
        fun sendTo(out: Receiver, bytes: ByteArray) {
            // A saída aceita uma mensagem curta por vez; os LEDs da DDJ-400 são notas e CCs de 3 bytes.
            var at = 0
            while (at + 2 < bytes.size) {
                runCatching { out.send(javax.sound.midi.ShortMessage(bytes[at].toInt() and 0xFF, bytes[at + 1].toInt(), bytes[at + 2].toInt()), -1) }
                at += 3
            }
        }
    }
}
