package os.deckbridge.session

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import os.deckbridge.logic.BEAT_FX_NAMES

/** Um DJ automático: planeja quando a agenda esvazia e diz quando terminou. */
interface DjAgent {
    val finishedAt: Double
    fun step()
}

/**
 * As mãos do DJ na DDJ-400 e na tela: as mensagens MIDI e os toques que os roteiros usam, mais a geometria de
 * compassos de uma faixa e o "ouvido" (desvio medido entre os ataques reais e a grade, tools/onset_offsets.py).
 */
abstract class DjHands(protected val model: SetModel, offsetsDir: File) : DjAgent {
    override var finishedAt = -1.0
        protected set
    protected var fxIndex = 0
    private val offsets: Map<Int, List<Pair<Int, Double>>> = model.all.indices.associateWith { index ->
        val file = File(offsetsDir, "off-${model.all[index].title}.json")
        if (!file.isFile) emptyList() else {
            Regex("\"(\\d+)\": (-?[0-9.eE-]+)").findAll(file.readText())
                .map { it.groupValues[1].toInt() to it.groupValues[2].toDouble() }
                .filter { abs(it.second) < 0.015 }
                .toList()
        }
    }

    /** Desvio (s) do ataque real contra a grade perto de um compasso; 0 se não há medida confiável por perto. */
    protected fun offsetAt(trackIndex: Int, bar: Double): Double {
        val list = offsets[trackIndex].orEmpty()
        val near = list.minByOrNull { abs(it.first - bar) } ?: return 0.0
        return if (abs(near.first - bar) <= 64) near.second else 0.0
    }

    // ---- ações básicas ----
    protected fun m(time: Double, status: Int, data1: Int, data2: Int) = model.at(time) { midi(status, data1, data2) }

    protected fun ramp(t0: Double, t1: Double, status: Int, data1: Int, from: Int, to: Int, step: Double = 0.05) {
        val n = ((t1 - t0) / step).toInt().coerceAtLeast(1)
        for (k in 0..n) m(t0 + k * step, status, data1, (from + (to - from) * k.toDouble() / n).toInt().coerceIn(0, 127))
    }

    protected fun ui(time: Double, text: String, block: SetModel.() -> Unit) = model.at(time) { touch(text, block) }

    protected fun faderFull(pitch: Double, range: Double = 0.10): Int = (0x2000 * (1.0 - (pitch - 1.0) / range)).toInt().coerceIn(0, 0x3FFF)

    protected fun tempoAt(time: Double, deck: Int, pitch: Double) {
        val full = faderFull(pitch)
        m(time, 176 + deck, 0, full shr 7)
        m(time + 0.002, 176 + deck, 32, full and 0x7F)
    }

    protected fun tempoRamp(t0: Double, t1: Double, deck: Int, from: Double, to: Double) {
        val n = ((t1 - t0) / 0.05).toInt().coerceAtLeast(1)
        for (k in 0..n) tempoAt(t0 + k * 0.05, deck, from + (to - from) * k.toDouble() / n)
    }

    protected fun play(time: Double, deck: Int) = m(time, 144 + deck, 11, 127)
    protected fun volume(time: Double, deck: Int, v: Int) = m(time, 176 + deck, 19, v)
    protected fun low(time: Double, deck: Int, v: Int) = m(time, 176 + deck, 15, v)
    protected fun filterKnob(time: Double, deck: Int, v: Int) = m(time, 182, 23 + deck, v)
    protected fun hotCuePad(time: Double, deck: Int, pad: Int) = m(time, 151 + 2 * deck, pad, 127)
    protected fun loadButton(time: Double, deck: Int) = m(time, 150, 70 + deck, 127)

    /** Abre o BROWSE, gira o encoder até a faixa e carrega no deck. Devolve a hora do LOAD. */
    protected fun browseAndLoad(start: Double, trackIndex: Int, deck: Int): Double {
        ui(start, "BROWSE") { openBrowse() }
        val row = model.rowIndexOfTrack(trackIndex).coerceAtLeast(0)
        for (k in 0 until row) m(start + 1.0 + 0.45 * k, 182, 64, 1)
        val loadAt = start + 1.0 + 0.45 * row + 0.9
        loadButton(loadAt, deck)
        return loadAt
    }

    /** Aperta o Beat FX certo: gira até o efeito pedido, escolhe o canal e liga. */
    protected fun beatFxOn(time: Double, effect: Int, deck: Int, level: Int) {
        val count = BEAT_FX_NAMES.size
        val presses = ((effect - fxIndex) % count + count) % count
        for (k in 0 until presses) m(time - 0.3 + 0.08 * k, 148, 99, 127)
        fxIndex = effect
        m(time - 0.2, 148, 16 + deck, 127)
        m(time - 0.1, 180, 2, level)
        m(time, 148, 71, 127)
    }

    protected fun beatFxOff(time: Double) = m(time, 148, 71, 127)

    // ---- geometria de compassos ----
    protected class Geometry(val origin: Double, val bpm: Double, val duration: Double) {
        val barLen = 240.0 / bpm
        val bars = floor((duration - origin) / barLen).toInt()
        fun barTime(bar: Double) = origin + bar * barLen
    }

    protected fun geometry(trackIndex: Int): Geometry {
        val (origin, bpm) = model.gridOfTrack(trackIndex)
        return Geometry(origin, bpm, model.durationOf(trackIndex))
    }

}
