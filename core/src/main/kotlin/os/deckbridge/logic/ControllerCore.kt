package os.deckbridge.logic

import java.io.File

/**
 * Ponte para a lógica de controle e os LEDs em C++ (`cpp/controller.h`, `cpp/leds.h`), a única implementação do
 * projeto: o app Android, os
 * testes JVM e o Pi rodam o mesmo código. No aparelho vem no libdeckbridge-engine.so; nos testes JVM, numa biblioteca
 * de host que o Gradle compila (propriedade `deckbridge.controlLib`).
 */
object ControllerCore {
    init {
        val host = System.getProperty("deckbridge.controlLib")
        if (host.isNullOrBlank()) {
            System.loadLibrary("deckbridge-engine")
        } else {
            try {
                System.load(host)
            } catch (error: UnsatisfiedLinkError) {
                // O Robolectric usa outro classloader, e a JVM não deixa a mesma .so em dois: carrega uma cópia.
                val copy = File.createTempFile("deckbridge-control", ".so").apply { deleteOnExit() }
                File(host).copyTo(copy, overwrite = true)
                System.load(copy.absolutePath)
            }
        }
    }

    external fun newAssembler(): Long

    external fun assemblerPush(handle: Long, bytes: ByteArray, out: IntArray): Int

    external fun newTempo(): Long

    external fun tempoRange(handle: Long, deck: Int): Double

    external fun tempoSetRange(handle: Long, deck: Int, value: Double)

    external fun interpret(
        tempoHandle: Long,
        mode: Int,
        status: Int,
        midino: Int,
        group: String,
        key: String,
        description: String,
        mapped: Boolean,
        value: Int,
        out: DoubleArray,
    ): Int

    external fun pitchFromTempo14(msb: Int, lsb: Int, range: Double): Double

    external fun nextTempoRange(current: Double): Double

    external fun ledTargets(packed: IntArray): IntArray

    external fun newLedDiff(): Long

    external fun ledResend(handle: Long)

    external fun ledMessages(handle: Long, packed: IntArray): ByteArray

    external fun beatLoopIndex(beats: Double): Int

    /** LedState nos 16 inteiros que o C++ lê (ver controller_jni.cpp). */
    internal fun pack(state: LedState): IntArray {
        val out = IntArray(16)
        for (deck in 0..1) {
            val d = state.decks.getOrElse(deck) { DeckLed() }
            val at = deck * 7
            out[at] = if (d.loaded) 1 else 0
            out[at + 1] = if (d.playing) 1 else 0
            out[at + 2] = if (d.sync) 1 else 0
            out[at + 3] = if (d.pfl) 1 else 0
            out[at + 4] = d.hotCues.filter { it in 0..7 }.fold(0) { acc, i -> acc or (1 shl i) }
            out[at + 5] = d.beatLoop
            out[at + 6] = java.lang.Float.floatToRawIntBits(d.peak)
        }
        out[14] = if (state.beatFxOn) 1 else 0
        out[15] = state.samplerPads.filter { it in 0..7 }.fold(0) { acc, i -> acc or (1 shl i) }
        return out
    }

    private val kinds = PerformanceAction.Kind.entries

    /** Chama o C++ e monta a PerformanceAction (o ordinal do Kind é o mesmo nas duas linguagens). */
    internal fun action(tempoHandle: Long, mode: Int, hit: ControlHit, value: Int): PerformanceAction? {
        val out = DoubleArray(4)
        val kind = interpret(tempoHandle, mode, hit.status, hit.midino, hit.group, hit.key, hit.description, hit.mapped, value, out)
        if (kind < 0) return null
        return PerformanceAction(out[0].toInt(), kinds[kind], out[1].toInt(), if (out[2] != 0.0) out[3] else null)
    }
}
