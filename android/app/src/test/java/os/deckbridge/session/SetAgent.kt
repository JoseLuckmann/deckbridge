package os.deckbridge.session

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import os.deckbridge.logic.BEAT_FX_NAMES
import os.deckbridge.logic.beatPhase

/** Uma linha de verificação da fase entre os decks, para o relatório. */
class PhaseCheck(val label: String, val time: Double, val milliseconds: Double)

/**
 * O DJ automático de um set completo. Cada fase põe no modelo as ações (mensagens MIDI da DDJ-400 e toques na tela)
 * de um trecho: abertura, uma transição por faixa e o final. Só planeja quando a agenda está vazia, com o estado
 * real do modelo (posição e tempo da faixa que toca), por isso as horas de entrada caem exatamente nas linhas de
 * compasso. O "ouvido" do DJ são os desvios medidos entre os ataques reais e a grade do analisador
 * (tools/onset_offsets.py), usados para acertar a hora do PLAY.
 */
class SetAgent(model: SetModel, private val order: List<Int>, offsetsDir: File) : DjHands(model, offsetsDir) {
    val checks = ArrayList<PhaseCheck>()
    val notes = ArrayList<String>()

    private var phase = 0
    private var readyAt = 0.0
    override fun step() {
        if (finishedAt >= 0.0 || model.pending() > 0 || model.t < readyAt - 1e-9) return
        when {
            phase == 0 -> readyAt = opening()
            phase < order.size -> readyAt = transition(phase)
            phase == order.size -> {
                finishedAt = finale()
                readyAt = finishedAt
            }
        }
        phase += 1
    }

    // ---- fases ----
    private fun opening(): Double {
        val index = order[0]
        val g = geometry(index)
        m(0.1, 176, 19, 127)
        m(0.1, 177, 19, 0)
        m(0.1, 182, 31, 64)
        val loadAt = browseAndLoad(0.8, index, 0)
        hotCuePad(loadAt + 1.0, 0, 0)
        val tPlay = loadAt + 2.0
        play(tPlay, 0)
        val bar = g.barLen
        val t0 = tPlay // a faixa parte da primeira batida (hot cue 1 quantizado)
        // Charme no solo: filtro, eco e corte de grave, tudo em compassos inteiros da faixa.
        ramp(t0 + 4 * bar, t0 + 5 * bar, 182, 23, 64, 8)
        ramp(t0 + 5 * bar, t0 + 6 * bar, 182, 23, 8, 64)
        beatFxOn(t0 + 8 * bar, 1, 0, 100)
        beatFxOff(t0 + 9 * bar)
        ramp(t0 + 12 * bar, t0 + 12 * bar + 0.3, 176, 15, 64, 0)
        ramp(t0 + 13 * bar, t0 + 13 * bar + 0.3, 176, 15, 0, 64)
        notes += "abertura: ${model.all[index].title} a ${"%.1f".format(g.bpm)} BPM"
        return t0 + 14 * bar
    }

    private fun transition(i: Int): Double {
        val aIndex = order[i - 1]
        val bIndex = order[i]
        val dA = (i - 1) % 2
        val dB = i % 2
        val ga = geometry(aIndex)
        val gb = geometry(bIndex)
        val now = model.t
        val posA = model.pos[dA]
        val pitchA = model.pitchOf(dA)
        val effA = ga.bpm * pitchA
        val barReal = 240.0 / effA
        val barNow = (posA - ga.origin) / ga.barLen
        // Sobreposição: 32 compassos em faixa longa, 16 em curta; a entrada cai num múltiplo de 8 compassos da grade.
        val overlap = if (ga.duration < 200.0) 16 else 32
        var entryBar = ((ga.bars - overlap) / 8) * 8
        val earliest = ceil(barNow + 14.0 / barReal).toInt()
        if (entryBar < earliest) entryBar = ((earliest + 7) / 8) * 8
        var span = ga.bars - entryBar
        if (span < 8) {
            entryBar = max(earliest, ga.bars - 8)
            span = ga.bars - entryBar
        }
        val posEntry = ga.barTime(entryBar.toDouble())
        val tEnter = now + (posEntry - posA) / pitchA
        val tEnd = tEnter + span * barReal
        val tSwap = tEnter + (span / 2) * barReal

        // 1. Carrega a próxima faixa e igualar o BPM pelo fader de tempo, ainda com o fader do canal embaixo.
        val loadAt = browseAndLoad(now + 0.5, bIndex, dB)
        hotCuePad(loadAt + 1.0, dB, 0)
        low(loadAt + 1.2, dB, 0)
        filterKnob(loadAt + 1.2, dB, 100)
        volume(loadAt + 1.2, dB, 0)
        val pitchB = effA / gb.bpm
        val t0 = loadAt + 2.0
        tempoRamp(t0, t0 + 1.8, dB, 1.0, pitchB + 0.0045)
        tempoRamp(t0 + 1.8, t0 + 2.8, dB, pitchB + 0.0045, pitchB - 0.0012)
        tempoRamp(t0 + 2.8, t0 + 3.6, dB, pitchB - 0.0012, pitchB)
        notes += "${model.all[bIndex].title}: ${"%.2f".format(gb.bpm)} → ${"%.2f".format(effA)} BPM (pitch ${"%.4f".format(pitchB)}), entra no compasso $entryBar de ${model.all[aIndex].title}, sobreposição de $span compassos"

        // 2. O PLAY cai na linha do compasso do deck que toca, corrigido pelo desvio real dos ataques (o ouvido do DJ).
        val offA = offsetAt(aIndex, entryBar.toDouble())
        val offB = offsetAt(bIndex, 0.0)
        val human = if (i == 1 || i == 4) 0.012 else 0.0
        val tPlay = tEnter + offA / pitchA - offB / pitchB + human
        play(tPlay, dB)
        if (human > 0.0) {
            // Ajuste de grade pelo prato: dois passos de 5 ms empurram o deck que entrou atrasado.
            m(tPlay + 2.4, 176 + dB, 35, 65)
            m(tPlay + 2.6, 176 + dB, 35, 65)
        }
        // Passa o ponto de referência de tempo do RMX e do Beat FX para o deck que entra quando ele assume.
        ui(tEnter - 0.2, "carrega ${model.all[bIndex].title} no deck ${dB + 1}") { }

        // 3. O fader do canal sobe devagar, o filtro passa-altas da faixa que entra abre e o grave fica fechado.
        ramp(tEnter + 2 * barReal, tEnter + 4 * barReal, 176 + dB, 19, 0, 127, 0.1)
        ramp(tEnter + 2 * barReal, tEnter + min(10, span - 2) * barReal, 182, 23 + dB, 100, 64, 0.1)

        // 4. Efeitos do trecho final, um receituário diferente por transição.
        val recipe = (i - 1) % 5
        when (recipe) {
            0 -> { // filtro passa-baixas fechando na saída + eco na troca de grave
                ramp(tEnd - 8 * barReal, tEnd - 0.3, 182, 23 + dA, 64, 10, 0.1)
                beatFxOn(tSwap, 1, dA, 100)
                beatFxOff(tSwap + 2.0 * barReal / 4)
            }
            1 -> { // roll de loop com slip na saída: 8, 4, 2, 1, 1/2, 1/4 de batida
                val beat = barReal / 4
                ui(tEnd - 17 * beat, "SLIP ligado (menu ···)") { toggleSlip(dA) }
                val pad = 151 + 2 * dA
                m(tEnd - 16 * beat, pad, 101, 127)
                m(tEnd - 8 * beat, pad, 100, 127)
                m(tEnd - 4 * beat, pad, 99, 127)
                m(tEnd - 2 * beat, pad, 98, 127)
                m(tEnd - 1 * beat, 144 + dA, 81, 127)
                m(tEnd - 0.5 * beat, 144 + dA, 81, 127)
                m(tEnd, 144 + dA, 77, 127)
                ui(tEnd + 0.5, "SLIP desligado") { toggleSlip(dA) }
            }
            2 -> { // RMX: Noise UP subindo até a troca de grave, com o roll de bumbo e palma no último compasso
                ui(tSwap - 9 * barReal, "abre o painel RMX") { openRmx() }
                m(tSwap - 8.7 * barReal, 148, 16 + dA, 127)
                m(tSwap - 8.6 * barReal, 148, 75, 127)
                m(tSwap - barReal - 0.2, 151, 54, 127)
                m(tSwap - 8 * barReal, 151, 49, 127)
                m(tSwap, 151, 49, 0)
                m(tSwap + 0.9, 151, 54, 127)
                ui(tSwap + 2.0, "fecha o painel RMX") { closeRmx() }
            }
            3 -> { // eco de saída: o fader do canal que sai desce nos dois últimos compassos
                beatFxOn(tEnd - 2 * barReal, 4, dA, 90)
                ramp(tEnd - 2 * barReal, tEnd - 0.2, 176 + dA, 19, 127, 0, 0.1)
                beatFxOff(tEnd)
            }
            else -> { // Color FX: DUB ECHO no canal que sai, do centro para a direita
                ui(tSwap - 0.5, "Color FX: DUB ECHO") { setColorFx(dA, 1) }
                ramp(tSwap, tSwap + 6 * barReal, 182, 23 + dA, 64, 120, 0.1)
                ramp(tEnd - 2 * barReal, tEnd - 0.1, 182, 23 + dA, 120, 64, 0.1)
                ui(tEnd + 0.2, "Color FX: FILTER") { setColorFx(dA, 5) }
            }
        }

        // 5. Troca de grave no meio da sobreposição: o deck que sai perde o LOW e o que entra ganha.
        ramp(tSwap, tSwap + 0.35, 176 + dA, 15, 64, 0, 0.05)
        ramp(tSwap, tSwap + 0.35, 176 + dB, 15, 0, 64, 0.05)
        m(tSwap + 0.5, 144 + dB, 92, 127) // o deck que entra vira o master

        // 6. O deck que sai termina a faixa, para, e a mesa volta ao centro.
        val tail = max(0.0, ga.duration - ga.barTime(ga.bars.toDouble())) / pitchA
        val tStop = tEnd + tail + 0.6
        play(tStop, dA)
        volume(tStop + 0.5, dA, 0)
        low(tStop + 0.5, dA, 64)
        filterKnob(tStop + 0.5, dA, 64)
        // A faixa que ficou volta devagar ao tempo original em 16 compassos (o DJ solta o fader de tempo).
        val rampStart = tStop + 1.0
        val barB = 240.0 / (gb.bpm * pitchB)
        if (abs(pitchB - 1.0) > 0.002) tempoRamp(rampStart, rampStart + 16 * barB, dB, pitchB, 1.0)
        // Verificação: fase entre os decks (grade e desvio medido) seis compassos depois da entrada.
        val checkAt = tEnter + 6 * barReal
        model.at(checkAt) {
            val a = beatPhase(pos[dA], ga.origin + offA, ga.bpm)
            val b = beatPhase(pos[dB], gb.origin + offB, gb.bpm)
            var d = b - a
            if (d > 0.5) d -= 1.0
            if (d < -0.5) d += 1.0
            checks += PhaseCheck("${all[aIndex].title}→${all[bIndex].title}", t, d * barReal / 4 * 1000.0)
        }
        return rampStart + (if (abs(pitchB - 1.0) > 0.002) 16 * barB else 0.0) + 1.0
    }

    private fun finale(): Double {
        val index = order.last()
        val d = (order.size - 1) % 2
        val g = geometry(index)
        val now = model.t
        val pos = model.pos[d]
        val pitch = model.pitchOf(d)
        val barReal = g.barLen / pitch
        val endPos = g.barTime(g.bars.toDouble())
        val tEnd = now + (endPos - pos) / pitch
        val beat = barReal / 4
        // Últimos compassos: loop roll com slip (4, 2, 1, 1/2, 1/4), eco e fader fechando.
        val rollStart = max(now + 2.0, tEnd - 8 * beat)
        ui(rollStart - beat, "SLIP ligado (menu ···)") { toggleSlip(d) }
        val pad = 151 + 2 * d
        m(rollStart, pad, 100, 127)
        m(rollStart + 4 * beat, pad, 99, 127)
        m(rollStart + 6 * beat, pad, 98, 127)
        m(rollStart + 7 * beat, 144 + d, 81, 127)
        m(rollStart + 7.5 * beat, 144 + d, 81, 127)
        beatFxOn(max(now + 1.0, tEnd - 4 * barReal), 3, d, 100)
        ramp(max(now + 1.5, tEnd - 4 * barReal), tEnd + 4.0, 176 + d, 19, 127, 0, 0.1)
        beatFxOff(tEnd + 3.0)
        val tail = max(0.0, g.duration - endPos) / pitch
        play(tEnd + tail + 1.0, d)
        return tEnd + tail + 5.0
    }
}
