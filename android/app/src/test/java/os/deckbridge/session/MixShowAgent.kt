package os.deckbridge.session

import java.io.File
import kotlin.math.max
import os.deckbridge.logic.beatPhase

/**
 * Mixagem de duas faixas com os truques: loop roll com slip, scratch com cortes no crossfader, backspin manual pelo prato, SYNC, troca de grave,
 * buildup com RMX, saída da primeira faixa com o BACKSPIN do RMX e final com backspin pelo prato. Tudo em compassos da faixa A (que toca do começo) e da B.
 */
class MixShowAgent(model: SetModel, private val a: Int, private val b: Int, offsetsDir: File) : DjHands(model, offsetsDir), Reporting {
    override val notes = ArrayList<String>()
    override val checks = ArrayList<PhaseCheck>()
    private var phase = 0

    override fun step() {
        if (finishedAt >= 0.0 || model.pending() > 0) return
        if (phase == 0) {
            finishedAt = plan()
        }
        phase += 1
    }

    private fun plan(): Double {
        val ga = geometry(a)
        val gb = geometry(b)
        // Mesa: canal 1 aberto, canal 2 fechado, crossfader no centro.
        m(0.1, 176, 19, 127)
        m(0.1, 177, 19, 0)
        m(0.1, 182, 31, 64)
        val loadA = browseAndLoad(0.8, a, 0)
        hotCuePad(loadA + 1.0, 0, 0)
        val start = loadA + 2.0
        play(start, 0)
        val bar = ga.barLen
        val beat = bar / 4
        fun barA(k: Double) = start + k * bar

        // Abertura: filtro passa-baixas e um eco curto.
        ramp(barA(4.0), barA(5.0), 182, 23, 64, 10)
        ramp(barA(5.0), barA(6.0), 182, 23, 10, 64)
        beatFxOn(barA(8.0), 1, 0, 100)
        beatFxOff(barA(9.0))

        // Loop roll com SLIP (8, 4, 2, 1, 1/2, 1/4 de batida): a faixa volta onde estaria no compasso 16.
        ui(barA(12.0) - beat, "SLIP ligado (menu ···)") { toggleSlip(0) }
        m(barA(12.0), 151, 101, 127)
        m(barA(14.0), 151, 100, 127)
        m(barA(15.0), 151, 99, 127)
        m(barA(15.5), 151, 98, 127)
        m(barA(15.75), 144, 81, 127)
        m(barA(15.875), 144, 81, 127)
        m(barA(16.0), 144, 77, 127)
        ui(barA(16.0) + 0.4, "SLIP desligado") { toggleSlip(0) }

        // A faixa B entra no deck 2, parada na primeira batida, com o fader do canal aberto e o crossfader todo no A.
        val loadB = browseAndLoad(barA(16.5), b, 1)
        hotCuePad(loadB + 1.0, 1, 0)
        m(barA(19.8), 182, 31, 0)
        m(barA(19.9), 177, 19, 127)

        // Scratch no deck B por cima do A: toca no prato (nota 54), gira para a frente na primeira metade de cada batida
        // e para trás na segunda (baby scratch). Dois compassos com o crossfader aberto no centro, dois com cortes:
        // aberto na ida, fechado na volta.
        m(barA(20.0) - 0.05, 145, 54, 127)
        for (k in 0 until 16) {
            val beatStart = barA(20.0) + k * beat
            val cuts = k >= 8
            if (!cuts && k == 0) m(beatStart - 0.02, 182, 31, 64)
            if (cuts) m(beatStart - 0.01, 182, 31, 64)
            val stroke = beat / 2
            val messages = 8
            for (n in 0 until messages) m(beatStart + n * stroke / messages, 177, 34, 67)
            if (cuts) m(beatStart + stroke, 182, 31, 0)
            for (n in 0 until messages) m(beatStart + stroke + n * stroke / messages, 177, 34, 61)
        }
        m(barA(24.0), 145, 54, 0)
        ui(barA(24.0) + 0.1, "solta o prato") { }

        // Volta o B à primeira batida, crossfader no centro, canal B fechado, sem grave e com passa-altas.
        m(barA(24.2), 182, 31, 64)
        m(barA(24.3), 177, 19, 0)
        m(barA(24.4), 177, 15, 0)
        m(barA(24.5), 182, 24, 100)
        hotCuePad(barA(25.0), 1, 0)

        // SYNC no B (parado: iguala o tempo ao do A) e PLAY na linha do compasso 32 do A.
        m(barA(28.0), 145, 88, 127)
        val entryBar = 32.0
        val offA = offsetAt(a, entryBar)
        val offB = offsetAt(b, 0.0)
        val pitchB = ga.bpm / gb.bpm
        val tEnter = barA(entryBar)
        play(tEnter + offA - offB / pitchB, 1)
        val barReal = bar
        fun barB(k: Double) = tEnter + k * barReal
        notes += "${model.all[b].title}: SYNC iguala ${"%.2f".format(gb.bpm)} a ${"%.2f".format(ga.bpm)} BPM; entra no compasso 32 de ${model.all[a].title}"

        // O fader do B sobe devagar, o passa-altas abre e a troca de grave vem no compasso 16 do B.
        ramp(barB(2.0), barB(4.0), 177, 19, 0, 127, 0.1)
        ramp(barB(2.0), barB(10.0), 182, 24, 100, 64, 0.1)
        ramp(barB(16.0), barB(16.0) + 0.35, 176, 15, 64, 0, 0.05)
        ramp(barB(16.0), barB(16.0) + 0.35, 177, 15, 0, 64, 0.05)
        m(barB(16.5), 145, 92, 127)

        // Buildup com RMX no A: Noise UP por 8 compassos, roll de bumbo e palma no último, e o A sai com BACKSPIN no
        // compasso 32 do B, junto com o drop.
        ui(barB(23.0), "abre o painel RMX") { openRmx() }
        m(barB(23.2), 148, 16, 127)
        m(barB(23.3), 148, 75, 127)
        m(barB(24.0), 151, 49, 127)
        m(barB(31.0) - 0.2, 151, 54, 127)
        m(barB(32.0), 151, 49, 0)
        m(barB(32.0) + 0.05, 151, 54, 127)
        ui(barB(32.0) - beat * 0.5, "BACKSPIN no deck 1") { setRelease(2) }
        play(barB(33.0), 0)
        m(barB(33.0) + 0.1, 176, 19, 0)
        ui(barB(34.0), "fecha o painel RMX") { setRelease(-1); closeRmx() }

        // O backspin cai no breakdown do B; o loop roll com SLIP fecha a subida e solta no drop (compasso 52).
        // Depois 16 compassos de drop e saída com eco e fader.
        ui(barB(48.0) - beat, "SLIP ligado (menu ···)") { toggleSlip(1) }
        m(barB(48.0), 153, 101, 127)
        m(barB(50.0), 153, 100, 127)
        m(barB(51.0), 153, 99, 127)
        m(barB(51.5), 153, 98, 127)
        m(barB(51.75), 145, 81, 127)
        m(barB(51.875), 145, 81, 127)
        m(barB(52.0), 145, 77, 127)
        ui(barB(52.0) + 0.4, "SLIP desligado") { toggleSlip(1) }
        beatFxOn(barB(68.0), 4, 1, 100)

        // Final com BACKSPIN manual pelo prato: no compasso 72 a mão toca o topo, gira para trás com força
        // (0,15 s de giro, ~9x em reverso) e solta. A faixa segue para trás pela inércia e freia; PLAY para o deck
        // antes de a faixa voltar a andar, e o eco segura a cauda.
        val spin = barB(72.0)
        m(spin - 0.02, 145, 54, 127)
        for (n in 0 until 30) m(spin + n * 0.005, 177, 34, 20)
        m(spin + 0.15, 145, 54, 0)
        play(spin + 1.5, 1)
        ramp(spin + 2.2, spin + 2.6, 177, 19, 127, 0, 0.05)
        beatFxOff(spin + 4.5)

        model.at(barB(6.0)) {
            val pa = beatPhase(pos[0], ga.origin + offA, ga.bpm)
            val pb = beatPhase(pos[1], gb.origin + offB, gb.bpm)
            var d = pb - pa
            if (d > 0.5) d -= 1.0
            if (d < -0.5) d += 1.0
            checks += PhaseCheck("${all[a].title}→${all[b].title}", t, d * barReal / 4 * 1000.0)
        }
        return max(barB(72.0) + 5.0, start + 1.0)
    }
}
