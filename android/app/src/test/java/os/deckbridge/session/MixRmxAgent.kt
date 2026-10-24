package os.deckbridge.session

import java.io.File
import kotlin.math.max
import os.deckbridge.logic.beatPhase

/**
 * Mixagem de duas faixas que percorre o RMX do app: as oito cenas (Echo UP, Noise UP, Spiral UP e Reverb UP pelos
 * pads do SAMPLER; Reverb DOWN, Echo DOWN, Tape STOP e Dub ECHO pela tela), o X-PAD (Roll descendo de 1/4 a 1/16,
 * Trans, Hi-pass e Drum com voz, compassos e LEVEL), o GATE, o isolador do RMX, a troca de canal pelo CH SELECT e os
 * releases ECHO OUT (saída da faixa A) e BRAKE (final). A entra sozinha; B entra com SYNC no compasso 48 de A.
 */
class MixRmxAgent(model: SetModel, private val a: Int, private val b: Int, offsetsDir: File) : DjHands(model, offsetsDir), Reporting {
    override val notes = ArrayList<String>()
    override val checks = ArrayList<PhaseCheck>()
    private var phase = 0

    override fun step() {
        if (finishedAt >= 0.0 || model.pending() > 0) return
        if (phase == 0) finishedAt = plan()
        phase += 1
    }

    // Pads do SAMPLER: deck 1 (0x97) cenas 1–4 e X-PAD 5–8; deck 2 (0x99) voz, compassos e GAP da bateria.
    private fun padA(time: Double, pad: Int, down: Boolean = true) = m(time, 151, 48 + pad, if (down) 127 else 0)
    private fun padB(time: Double, pad: Int) = m(time, 153, 48 + pad, 127)
    private fun level(time: Double, value: Int) = m(time, 180, 2, value)

    private fun plan(): Double {
        val ga = geometry(a)
        val gb = geometry(b)
        m(0.1, 176, 19, 127)
        m(0.1, 177, 19, 0)
        m(0.1, 182, 31, 64)
        level(0.15, 0)
        val loadA = browseAndLoad(0.8, a, 0)
        hotCuePad(loadA + 1.0, 0, 0)
        val start = loadA + 2.0
        play(start, 0)
        val bar = ga.barLen
        val beat = bar / 4
        fun barA(k: Double) = start + k * bar

        // Painel RMX acoplado embaixo das ondas; CH SELECT 1 (o RMX age no deck 1).
        ui(barA(2.0), "abre o painel RMX") { openRmx() }
        m(barA(2.5), 148, 16, 127)

        // 1. Echo UP segurado por 4 compassos (pad 1): o eco sobe até o pico e some ao soltar.
        padA(barA(8.0), 0)
        padA(barA(12.0), 0, down = false)

        // 2. X-PAD Roll descendo: 1/4, 1/8 e 1/16 num compasso.
        ui(barA(13.0) - 0.3, "X-PAD BEAT 1/4") { rmxTouch { it.copy(xBeat = 2) } }
        padA(barA(13.0), 4)
        ui(barA(13.5), "X-PAD BEAT 1/8") { rmxTouch { it.copy(xBeat = 1) } }
        ui(barA(13.75), "X-PAD BEAT 1/16") { rmxTouch { it.copy(xBeat = 0) } }
        padA(barA(14.0), 4)

        // 3. X-PAD Trans em 1/8 por 2 compassos.
        ui(barA(16.0) - 0.3, "X-PAD BEAT 1/8") { rmxTouch { it.copy(xBeat = 1) } }
        padA(barA(16.0), 5)
        padA(barA(18.0), 5)

        // 4. X-PAD Hi-pass em 1/4 por 2 compassos.
        ui(barA(20.0) - 0.3, "X-PAD BEAT 1/4") { rmxTouch { it.copy(xBeat = 2) } }
        padA(barA(20.0), 7)
        padA(barA(22.0), 7)

        // 5. Spiral UP de 8 compassos (BEAT ► sobe o tamanho da cena de 4 para 8), segurado até o pico.
        m(barA(23.5), 148, 75, 127)
        padA(barA(24.0), 2)
        padA(barA(32.0), 2, down = false)
        m(barA(32.2), 148, 74, 127)

        // 6. Reverb DOWN pela tela (cena 5) por 4 compassos: o reverb cresce e o som original desce.
        ui(barA(33.0), "cena Reverb DOWN (segura)") { rmxTouch { it.copy(scene = 4, holding = true, panel = true) } }
        ui(barA(37.0), "solta Reverb DOWN") { rmxTouch { it.copy(holding = false, intensity = 0f) } }

        // 7. Isolador do RMX no deck 1: grave fora por 3 compassos e de volta na cabeça do compasso 40.
        ui(barA(37.0) + 0.3, "isolador do RMX: LOW do deck 1 fechado") { rmxTouch { it.copy(low = listOf(0f, it.low.getOrElse(1) { 1f })) } }
        ui(barA(40.0), "isolador: LOW de volta") { rmxTouch { it.copy(low = listOf(1f, it.low.getOrElse(1) { 1f })) } }

        // B no deck 2: LOAD, cue, SYNC; canal fechado, sem grave e com passa-altas.
        val loadB = browseAndLoad(barA(38.0), b, 1)
        hotCuePad(loadB + 1.0, 1, 0)
        m(loadB + 1.5, 177, 15, 0)
        m(loadB + 1.6, 182, 24, 100)
        m(barA(44.0), 145, 88, 127)

        // 8. Noise UP de 4 compassos no A como build para a entrada do B (pad 2), solto na cabeça do compasso 48.
        padA(barA(44.0), 1)
        padA(barA(48.0), 1, down = false)

        val entryBar = 48.0
        val offA = offsetAt(a, entryBar)
        val offB = offsetAt(b, 0.0)
        val pitchB = ga.bpm / gb.bpm
        val tEnter = barA(entryBar)
        play(tEnter + offA - offB / pitchB, 1)
        fun barB(k: Double) = tEnter + k * bar
        notes += "${model.all[b].title}: SYNC iguala ${"%.2f".format(gb.bpm)} a ${"%.2f".format(ga.bpm)} BPM; entra no compasso 48 de ${model.all[a].title}"

        // B sobe em 2 compassos, o passa-altas abre em 8, troca de grave no compasso 8 do B.
        ramp(barB(0.0), barB(2.0), 177, 19, 0, 127, 0.1)
        ramp(barB(0.0), barB(8.0), 182, 24, 100, 64, 0.1)
        ramp(barB(8.0), barB(8.0) + 0.35, 176, 15, 64, 0, 0.05)
        ramp(barB(8.0), barB(8.0) + 0.35, 177, 15, 0, 64, 0.05)
        m(barB(8.5), 145, 92, 127)

        // 9. Saída do A: Echo DOWN pela tela (cena 6) por 4 compassos e ECHO OUT (release) no fim; A para e fecha.
        ui(barB(12.0), "cena Echo DOWN no deck 1 (segura)") { rmxTouch { it.copy(scene = 5, holding = true, panel = true) } }
        ui(barB(16.0) - beat * 4, "release ECHO OUT") { rmxTouch { it.copy(holding = false, intensity = 0f) }; setRelease(1) }
        play(barB(16.0), 0)
        m(barB(16.0) + 0.05, 176, 19, 0)
        ui(barB(16.5), "release desarmado") { setRelease(-1) }

        // 10. CH SELECT 2: daqui em diante o RMX age no deck 2.
        m(barB(17.0), 148, 17, 127)

        // 11. Tape STOP pela tela (cena 7) de 1 compasso, solto antes de parar: o tape para e volta na cabeça do 20.
        ui(barB(19.0), "cena Tape STOP (1 compasso)") { rmxTouch { it.copy(scene = 6, holding = true, panel = true, bars = 1) } }
        ui(barB(20.0) - 0.05, "solta Tape STOP") { rmxTouch { it.copy(holding = false, intensity = 0f, bars = 4) } }

        // 12. GATE do isolador do RMX por 2 compassos.
        ui(barB(22.0), "GATE do isolador") { rmxTouch { it.copy(gate = true) } }
        ui(barB(24.0), "GATE desligado") { rmxTouch { it.copy(gate = false) } }

        // 13. Dub ECHO pela tela (cena 8) por 4 compassos, perto do fim do primeiro trecho do B.
        ui(barB(26.0), "cena Dub ECHO (segura)") { rmxTouch { it.copy(scene = 7, holding = true, panel = true) } }
        ui(barB(30.0), "solta Dub ECHO") { rmxTouch { it.copy(holding = false, intensity = 0f) } }

        // 14. No breakdown do B (compasso 32): Reverb UP pelo pad 4, 4 compassos de ambiente.
        padA(barB(34.0), 3)
        padA(barB(38.0), 3, down = false)

        // 15. Build para o drop do B (compasso 52): bateria do X-PAD com KICK+CLAP (pad 4 do deck 2), 8 compassos
        // (pad 6 do deck 2), o LEVEL sobe a intensidade, e Noise UP de 8 compassos por cima.
        padB(barB(43.5), 3)
        padB(barB(43.7), 5)
        level(barB(43.9), 10)
        padA(barB(44.0), 6)
        ramp(barB(44.0), barB(51.5), 180, 2, 10, 127, 0.1)
        m(barB(43.8), 148, 75, 127)
        padA(barB(44.0) + 0.02, 1)
        padA(barB(52.0), 1, down = false)
        padA(barB(52.0) + 0.02, 6)
        level(barB(52.2), 0)
        m(barB(52.3), 148, 74, 127)

        // 16. No drop, isolador do RMX: agudo fora por 2 compassos, médio fora por 2, tudo de volta no 60.
        ui(barB(56.0), "isolador: HI do deck 2 fechado") { rmxTouch { it.copy(high = listOf(it.high.getOrElse(0) { 1f }, 0f)) } }
        ui(barB(58.0), "isolador: HI de volta, MID fechado") {
            rmxTouch { it.copy(high = listOf(it.high.getOrElse(0) { 1f }, 1f), mid = listOf(it.mid.getOrElse(0) { 1f }, 0f)) }
        }
        ui(barB(60.0), "isolador: tudo de volta") { rmxTouch { it.copy(mid = listOf(it.mid.getOrElse(0) { 1f }, 1f)) } }

        // 17. Roll descendo no fim de frase: 1/2, 1/4, 1/8, 1/16 nos dois últimos compassos antes do 64.
        ui(barB(62.0) - 0.3, "X-PAD BEAT 1/2") { rmxTouch { it.copy(xBeat = 3) } }
        padA(barB(62.0), 4)
        ui(barB(63.0), "X-PAD BEAT 1/4") { rmxTouch { it.copy(xBeat = 2) } }
        ui(barB(63.5), "X-PAD BEAT 1/8") { rmxTouch { it.copy(xBeat = 1) } }
        ui(barB(63.75), "X-PAD BEAT 1/16") { rmxTouch { it.copy(xBeat = 0) } }
        padA(barB(64.0), 4)

        // 18. Final: Echo UP de 4 compassos e BRAKE (release) na cabeça do 72; o deck para e o fader fecha.
        padA(barB(68.0), 0)
        padA(barB(72.0), 0, down = false)
        ui(barB(72.0), "release BRAKE") { setRelease(0) }
        play(barB(74.0), 1)
        m(barB(74.0) + 0.05, 177, 19, 0)
        ui(barB(74.2), "release desarmado, fecha o RMX") { setRelease(-1); closeRmx() }

        model.at(barB(6.0)) {
            val pa = beatPhase(pos[0], ga.origin + offA, ga.bpm)
            val pb = beatPhase(pos[1], gb.origin + offB, gb.bpm)
            var d = pb - pa
            if (d > 0.5) d -= 1.0
            if (d < -0.5) d += 1.0
            checks += PhaseCheck("${all[a].title}→${all[b].title}", t, d * bar / 4 * 1000.0)
        }
        return max(barB(74.0) + 3.0, start + 1.0)
    }
}
