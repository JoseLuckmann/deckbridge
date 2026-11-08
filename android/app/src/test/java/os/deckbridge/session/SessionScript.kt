package os.deckbridge.session

/** Um instante do roteiro: o que a mão faz na DDJ-400 ou na tela. */
class Ev(val t: Double, val run: SessionModel.() -> Unit)

/**
 * Performance completa de duas faixas (130 e 129 BPM), sem SYNC: o tempo é igualado pelo fader de tempo
 * (mensagens de 14 bits da DDJ-400) e a entrada do deck 2 é apertada na linha do compasso. Tudo por mensagens
 * MIDI (status, nota, valor) e alguns toques na tela. Começa com os dois decks vazios.
 */
object SessionScript {
    /** Atraso antes do PLAY do deck 1: tela vazia, BROWSE, LOAD, hot cue. */
    const val INTRO = 4.8
    const val SOLO_BARS = 8
    const val END = 112.0
    const val DRIFT = 0.0004
    const val LATE = 0.020

    private fun bar(bpm: Double) = 240.0 / bpm

    /** Do tempo de uma faixa (BPM do mapa) ao multiplicador que o fader deve mostrar. */
    private fun faderFull(pitch: Double, range: Double = 0.10): Int =
        (0x2000 * (1.0 - (pitch - 1.0) / range)).toInt().coerceIn(0, 0x3FFF)

    fun events(model: SessionModel): List<Ev> {
        val out = ArrayList<Ev>()
        fun m(t: Double, status: Int, data1: Int, data2: Int) {
            out += Ev(t) { midi(status, data1, data2) }
        }
        fun ramp(t0: Double, t1: Double, status: Int, data1: Int, from: Int, to: Int, step: Double = 0.05) {
            val n = ((t1 - t0) / step).toInt().coerceAtLeast(1)
            for (k in 0..n) m(t0 + k * step, status, data1, (from + (to - from) * k.toDouble() / n).toInt().coerceIn(0, 127))
        }
        fun ui(t: Double, text: String, block: SessionModel.() -> Unit) {
            out += Ev(t) { touch(text, block) }
        }
        /** Fader de tempo do deck: MSB e LSB como a DDJ-400 manda (status 176+deck, notas 0 e 32). */
        fun tempoFader(t: Double, deck: Int, pitch: Double) {
            val full = faderFull(pitch)
            m(t, 176 + deck, 0, full shr 7)
            m(t + 0.002, 176 + deck, 32, full and 0x7F)
        }
        fun tempoRamp(t0: Double, t1: Double, deck: Int, from: Double, to: Double) {
            val n = ((t1 - t0) / 0.05).toInt().coerceAtLeast(1)
            for (k in 0..n) tempoFader(t0 + k * 0.05, deck, from + (to - from) * k.toDouble() / n)
        }

        val bpmA = model.tracks[0].bpm
        val bpmB = model.tracks[1].bpm
        val BL = bar(bpmA)
        val o = INTRO
        val pA = o + 1.0                                    // PLAY do deck 1
        val tB0 = pA + SOLO_BARS * BL                       // o deck 2 entra na linha do compasso
        // Pitch que iguala o deck 2 ao deck 1 (o DJ lê o BPM na tela e ajusta o fader).
        val pitchB = bpmA / bpmB
        // Tempos antigos medidos a partir da entrada do deck 2 (tB0 antigo = 8,3846).
        fun rel(old: Double) = tB0 + (old - 8.3846)
        val tCue = tB0 + 4 * BL
        val tB12 = tB0 + 12 * BL
        val tBuild = tB12
        val tDrop = tBuild + 8 * BL
        val tA32 = tBuild + 20 * BL

        // Estado inicial da mesa (invisível: nenhum deck carregado ainda).
        m(0.10, 176, 19, 127)
        m(0.10, 177, 19, 0)
        m(0.10, 182, 31, 64)

        // Deck 1: BROWSE, LOAD, hot cue (compasso 16) e PLAY.
        ui(0.8, "BROWSE") { openBrowse() }
        m(1.9, 182, 64, 1)                // gira: segunda faixa
        m(2.4, 182, 64, 127)              // volta uma: primeira faixa
        m(3.3, 150, 70, 127)              // LOAD deck 1
        m(o + 0.5, 151, 1, 127)           // HOT CUE 2 do deck 1
        m(pA, 144, 11, 127)               // PLAY deck 1

        // Charme no solo do deck 1: filtro, kill de grave e eco.
        ramp(o + 2.60, o + 3.80, 182, 23, 64, 8)
        ramp(o + 3.80, o + 4.90, 182, 23, 8, 64)
        ramp(o + 5.20, o + 5.60, 176, 15, 64, 0)
        ramp(o + 6.90, o + 7.20, 176, 15, 0, 64)
        m(o + 7.20, 177, 15, 0)           // canal 2 com o LOW em kill antes de entrar
        m(o + 7.60, 148, 99, 127)         // BEAT FX SELECT: próximo efeito
        m(o + 7.70, 148, 16, 127)         // CH SELECT: deck 1
        m(o + 7.80, 180, 2, 100)          // LEVEL/DEPTH
        m(o + 8.10, 148, 71, 127)         // BEAT FX ON
        m(o + 8.95, 148, 71, 127)         // BEAT FX OFF

        // Deck 2: BROWSE, LOAD, o BPM é igualado pelo fader (passa do ponto e volta).
        ui(tB0 - 7.4, "BROWSE") { openBrowse() }
        m(tB0 - 6.4, 182, 64, 1)
        m(tB0 - 4.9, 150, 71, 127)        // LOAD deck 2
        tempoRamp(tB0 - 4.3, tB0 - 2.6, 1, 1.0, pitchB + 0.0045)
        tempoRamp(tB0 - 2.6, tB0 - 1.7, 1, pitchB + 0.0045, pitchB - 0.0012)
        // O fader para um pouco acima do ponto (0,04%): o deck 2 vai se afastar devagar e o DJ corrige no prato.
        tempoRamp(tB0 - 1.7, tB0 - 1.0, 1, pitchB - 0.0012, pitchB + DRIFT)
        // Filtro passa-alta subindo no deck 1 até a entrada do deck 2 (build de transição).
        ramp(tB0 - 3.2, tB0 - 0.15, 182, 23, 64, 110)
        m(tB0 - 0.05, 182, 23, 64)

        // Roll de loop no deck 1 (pads BEAT LOOP e CUE/LOOP CALL): 8, 4, 2, 1, 1/2 e 1/4 de batida, cada um por
        // o seu tamanho, tudo em batidas inteiras do deck 1. Sai no instante em que o deck 2 entra.
        val beat = 60.0 / bpmA
        ui(tB0 - 17 * beat, "SLIP ligado (menu ···)") { toggleSlip(0) }
        m(tB0 - 16 * beat, 151, 101, 127)        // pad 6: loop de 8 batidas
        m(tB0 - 8 * beat, 151, 100, 127)         // pad 5: 4 batidas
        m(tB0 - 4 * beat, 151, 99, 127)          // pad 4: 2 batidas
        m(tB0 - 2 * beat, 151, 98, 127)          // pad 3: 1 batida
        m(tB0 - 1 * beat, 144, 81, 127)          // CUE/LOOP CALL esquerda: 1/2
        m(tB0 - 0.5 * beat, 144, 81, 127)        // de novo: 1/4
        m(tB0, 144, 77, 127)                     // RELOOP/EXIT: sai do loop e a faixa volta onde estaria (SLIP)
        ui(tB0 + 1.0, "SLIP desligado") { toggleSlip(0) }

        // O deck 2 entra na linha do compasso, sem SYNC, e o fader sobe.
        m(tB0 + LATE, 145, 11, 127)       // PLAY deck 2, 20 ms atrasado (a mão não é perfeita)
        ramp(tB0 + 0.60, tB0 + 2.40, 177, 19, 0, 127)
        // Ajuste de grade pelo prato: cada passo vale 5 ms (valor 65 = +1). O DJ ouve o deck 2 atrasado e empurra.
        for (at in doubleArrayOf(2.60, 2.75, 4.40, 4.55)) m(tB0 + at, 177, 35, 65)
        // Depois acerta o fader de tempo, que estava 0,04% acima.
        tempoRamp(tB0 + 8.0, tB0 + 8.6, 1, pitchB + DRIFT, pitchB)

        // O deck 1 salta ao breakdown (hot cue 3, compasso 32) e o grave do deck 2 entra.
        m(tCue, 151, 2, 127)
        ramp(tCue + 0.05, tCue + 1.25, 177, 15, 0, 64)

        // Troca de grave: o deck 1 perde o LOW antes do drop, que volta sem grave.
        ramp(tCue + 6.40, tCue + 7.00, 176, 15, 64, 0)

        // Beat FX Echo no deck 1 enquanto o crossfader leva a mistura para o deck 2.
        m(rel(26.40), 148, 99, 127)
        m(rel(26.50), 148, 16, 127)
        m(rel(26.60), 180, 2, 100)
        m(rel(28.69), 148, 71, 127)
        ramp(rel(28.69), rel(30.50), 182, 31, 64, 127)
        m(rel(29.55), 148, 71, 127)
        m(rel(30.30), 144, 11, 127)       // PAUSE deck 1

        // RMX no deck 2: bars, canal, salto de 128 batidas e o roll que cai no drop.
        ui(rel(29.70), "abre o painel RMX") { openRmx() }
        m(rel(29.90), 148, 17, 127)
        m(rel(29.95), 148, 75, 127)
        m(rel(29.97), 154, 39, 127)
        m(tBuild - 0.4615, 153, 39, 127)
        m(tBuild - 0.20, 151, 54, 127)
        m(tBuild, 151, 49, 127)
        m(tDrop, 151, 49, 0)
        m(tDrop + 0.90, 151, 54, 127)
        ui(tDrop + 2.0, "fecha o painel RMX") { closeRmx() }

        // Volta para a primeira faixa: entra pelo breakdown, na linha do compasso do deck 2 (também sem SYNC).
        m(tA32 - 7.5, 176, 15, 0)         // LOW do deck 1 em kill
        m(tA32 - 7.0, 151, 2, 127)        // HOT CUE 3 do deck 1: compasso 32
        m(tA32 - 1.5, 182, 31, 64)        // crossfader de volta ao centro
        m(tA32, 144, 11, 127)             // PLAY deck 1 na linha do compasso 64 do deck 2
        ramp(tA32 + 7.2, tA32 + 7.8, 177, 15, 64, 0)    // deck 2 perde o grave
        ramp(tA32 + 9.1, tA32 + 9.7, 176, 15, 0, 64)    // o grave do deck 1 volta no drop

        // Saída do deck 2: filtro fechando e crossfader.
        ramp(tA32 + 12.8, tA32 + 16.5, 182, 24, 64, 6)
        ramp(tA32 + 12.8, tA32 + 16.5, 182, 31, 64, 0)
        m(tA32 + 17.6, 145, 11, 127)      // PAUSE deck 2

        // Final: o deck 1 trava em loop de 4 batidas e vai encolhendo até o eco e o fader fecharem.
        ui(tA32 + 39 * beat, "SLIP ligado (menu ···)") { toggleSlip(0) }
        m(tA32 + 40 * beat, 151, 100, 127)       // pad 5: 4 batidas
        m(tA32 + 44 * beat, 151, 99, 127)        // 2 batidas
        m(tA32 + 46 * beat, 151, 98, 127)        // 1 batida
        m(tA32 + 47 * beat, 144, 81, 127)        // 1/2
        m(tA32 + 47.5 * beat, 144, 81, 127)      // 1/4
        // Final: eco no deck 1 e fader descendo.
        m(tA32 + 21.0, 148, 99, 127)
        m(tA32 + 21.1, 148, 16, 127)
        m(tA32 + 22.2, 148, 71, 127)
        ramp(tA32 + 23.0, tA32 + 25.5, 176, 19, 127, 0)
        m(tA32 + 24.2, 148, 71, 127)
        return out.sortedBy { it.t }
    }
}
