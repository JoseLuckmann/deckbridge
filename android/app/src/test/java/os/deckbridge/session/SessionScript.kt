package os.deckbridge.session

/** Um instante do roteiro: o que a mão faz na DDJ-400 ou na tela. */
class Ev(val t: Double, val run: SessionModel.() -> Unit)

/**
 * Mix de duas faixas (130 BPM e 129 BPM), tudo por mensagens MIDI da DDJ-400 (status, nota, valor)
 * e alguns toques na tela. Compassos da primeira faixa: BL = 240/130 s cada.
 */
object SessionScript {
    const val BL = 240.0 / 130.0
    const val END = 96.0

    fun events(): List<Ev> {
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
        val tB0 = 1.0 + 4 * BL            // compasso 20 da faixa 1: o deck 2 entra na linha do compasso
        val tCue = tB0 + 4 * BL           // deck 2 no compasso 4
        val tB12 = tB0 + 12 * BL          // deck 2 no compasso 12
        val tBuild = tB12 + 0.0           // deck 2 chega ao compasso 44 exatamente aqui
        val tDrop = tBuild + 8 * BL       // compasso 52 do deck 2
        val tA32 = tBuild + 20 * BL       // deck 2 no compasso 64

        // Estado inicial da mesa.
        m(0.10, 176, 19, 127)             // fader do canal 1 todo aberto
        m(0.10, 177, 19, 0)               // canal 2 fechado
        m(0.10, 182, 31, 64)              // crossfader no centro
        m(0.50, 151, 1, 127)              // HOT CUE 2 do deck 1: compasso 16, o groove
        m(1.00, 144, 11, 127)             // PLAY deck 1

        // Color FX FILTER no canal 1 e o EQ de canal.
        ramp(2.60, 3.80, 182, 23, 64, 8)
        ramp(3.80, 4.90, 182, 23, 8, 64)
        ramp(5.20, 5.60, 176, 15, 64, 0)  // EQ LOW do canal 1 em kill
        ramp(6.90, 7.20, 176, 15, 0, 64)
        m(7.20, 177, 15, 0)               // canal 2 com o LOW em kill antes de entrar

        // O deck 2 entra na linha do compasso, sincroniza e sobe o fader.
        m(tB0, 145, 11, 127)              // PLAY deck 2
        m(tB0 + 0.30, 145, 88, 127)       // SYNC deck 2 (tempo e fase pela grade)
        ramp(tB0 + 0.60, tB0 + 2.40, 177, 19, 0, 127)

        // O deck 1 salta ao breakdown (hot cue 3, compasso 32) e o grave do deck 2 entra.
        m(tCue, 151, 2, 127)
        ramp(tCue + 0.05, tCue + 1.25, 177, 15, 0, 64)

        // Troca de grave: o deck 1 perde o LOW antes do drop, que volta sem grave.
        ramp(tCue + 6.40, tCue + 7.00, 176, 15, 64, 0)

        // Beat FX Echo no deck 1 enquanto o crossfader leva a mistura para o deck 2.
        m(26.40, 148, 99, 127)            // BEAT FX SELECT: próximo efeito
        m(26.50, 148, 16, 127)            // CH SELECT: deck 1
        m(26.60, 180, 2, 100)             // LEVEL/DEPTH
        m(28.69, 148, 71, 127)            // BEAT FX ON
        ramp(28.69, 30.50, 182, 31, 64, 127)
        m(29.55, 148, 71, 127)            // BEAT FX OFF (a cauda continua)
        m(30.30, 144, 11, 127)            // PAUSE deck 1

        // RMX no deck 2: bars, canal, salto de 128 batidas e o roll que cai no drop.
        ui(29.70, "abre o painel RMX") { openRmx() }
        m(29.90, 148, 17, 127)            // CH SELECT: deck 2
        m(29.95, 148, 75, 127)            // BEAT RIGHT: cena de 8 compassos
        m(29.97, 154, 39, 127)            // SHIFT+PAD 8: salto maior
        m(tBuild - 0.4615, 153, 39, 127)  // PAD 8 do deck 2: +128 batidas, cai no compasso 43,75
        m(tBuild - 0.20, 151, 54, 127)    // PAD do modo Drum: o roll quantiza para o compasso 44
        m(tBuild, 151, 49, 127)           // PAD Noise UP segurado durante 8 compassos
        m(tDrop, 151, 49, 0)              // solta no drop
        m(tDrop + 0.90, 151, 54, 127)     // desliga o Drum
        ui(tDrop + 2.0, "fecha o painel RMX") { closeRmx() }

        // Volta para a primeira faixa: ela entra pelo breakdown, na linha do compasso do deck 2.
        m(tA32 - 9.0, 145, 92, 127)       // SYNC longo: deck 2 é o master
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

        // Final: eco no deck 1 e fader descendo.
        m(tA32 + 21.0, 148, 99, 127)      // próximo efeito
        m(tA32 + 21.1, 148, 16, 127)
        m(tA32 + 22.2, 148, 71, 127)
        ramp(tA32 + 23.0, tA32 + 25.5, 176, 19, 127, 0)
        m(tA32 + 24.2, 148, 71, 127)
        return out.sortedBy { it.t }
    }
}
