package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class StructurePoints(
    val introSec: Double,
    val buildSec: Double,
    val dropSec: Double,
    val breakSec: Double,
    val outroSec: Double = 0.0,
)

data class TrackRead(
    val bpm: Double,
    val bpmConfidence: Double,
    val gridErrorMs: Double,
    val gridErrorMaxMs: Double,
    val fullBandErrorMs: Double,
    val gridCoverage: Double,
    val keyName: String,
    val keyConfidence: Double,
    val beats: List<AnlzBeat>,
    val structure: StructurePoints,
    val elapsedMs: Long,
    val phrases: List<PhraseSpan> = emptyList(),
    val loudnessLufs: Double = Double.NaN,
    val energy: Double = 0.0,
)

// EDMA publicado (Faraldo e outros). Índice 0 é a tónica. Não é o código da Essentia.
private val EDMA_MAJOR = doubleArrayOf(1.00, 0.29, 0.50, 0.40, 0.60, 0.56, 0.32, 0.80, 0.31, 0.45, 0.42, 0.39)
private val EDMA_MINOR = doubleArrayOf(1.00, 0.31, 0.44, 0.58, 0.33, 0.49, 0.29, 0.78, 0.43, 0.29, 0.53, 0.32)
internal val MAJOR_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
internal val MINOR_NAMES = listOf("Cm", "Dbm", "Dm", "Ebm", "Em", "Fm", "F#m", "Gm", "Abm", "Am", "Bbm", "Bm")
private val PITCH_CLASS = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/** BPM, tom, grade e pontos de estrutura. Heurística deste app, não análise de um programa comercial. */
fun analyzeTrack(samples: FloatArray, rate: Int): TrackRead {
    val started = System.nanoTime()
    if (samples.size < rate / 2 || rate < 8000) {
        return TrackRead(0.0, 0.0, -1.0, -1.0, -1.0, 0.0, "—", 0.0, emptyList(), StructurePoints(0.0, 0.0, 0.0, 0.0), 0)
    }
    val structureEnv = structureEnvelope(samples, rate)
    val kick = kickFlux(samples, rate)
    val full = fullBandFlux(samples, rate)
    val peaks = strongPeaks(kick.values, kick.rate)
    val firstPick = pickBpm(kick.values, kick.rate, peaks, onsetTempo(samples, rate, kick.values, kick.rate))
    val phaseFix = refineGridPhase(samples, rate, firstPick.bpm, firstPick.phase)
    val bpmPick = if (phaseFix == null) firstPick else firstPick.copy(phase = phaseFix.first)
    // Com a fase corrigida, os picos de referência passam a ser os da figura que escolhemos.
    val gridPeaks = phaseFix?.second ?: peaks
    // Fase corrigida pelo ataque: grade regular, sem recolar nos picos do fluxo (que caem alguns ms depois do início).
    val firstBeats = beatGrid(if (phaseFix == null) peaks else DoubleArray(0), bpmPick, samples.size.toDouble() / rate)
    // Última passada: a grade no início do clique do bumbo, medido na faixa inteira (cada caminho acima chegava a uma
    // distância diferente dele, de -16 a +29 ms, e duas faixas sincronizadas embolavam o grave).
    val aligned = alignGridToClick(samples, rate, firstBeats, bpmPick.bpm)
    val beats = aligned?.first ?: firstBeats
    val bpm = aligned?.second ?: bpmPick.bpm
    val kickError = kickGridError(gridPeaks, beats, bpm)
    val fullError = fullBandGridError(full.values, full.rate, beats)
    val key = detectKey(samples, rate)
    val duration = samples.size.toDouble() / rate
    // Frases presas à grade (primeira batida 1); sem grade, o detector antigo por janelas.
    val downbeat = beats.firstOrNull { it.beatInBar == 1 }?.timeMs?.div(1000.0)
    val sections = (if (downbeat != null) phraseSections(samples, rate, bpm, downbeat) else null)
        ?: detectSections(structureEnv.low, structureEnv.high, structureEnv.windowSec, bpm, duration)
    val loudness = integratedLufs(samples, rate)
    val energy = energyLevel(samples)
    val elapsed = (System.nanoTime() - started) / 1_000_000
    return TrackRead(
        bpm,
        bpmPick.confidence,
        kickError.first,
        kickError.second,
        fullError,
        kickError.third,
        key.first,
        key.second,
        beats,
        sections.points,
        elapsed,
        sections.phrases,
        loudness,
        energy,
    )
}

/** Só o BPM (sem tom, grade nem estrutura): o que o corpus de tempo mede. */
internal fun estimateTempo(samples: FloatArray, rate: Int): Double {
    if (samples.size < rate / 2 || rate < 8000) return 0.0
    val kick = kickFlux(samples, rate)
    val peaks = strongPeaks(kick.values, kick.rate)
    return pickBpm(kick.values, kick.rate, peaks, onsetTempo(samples, rate, kick.values, kick.rate)).bpm
}

fun structurePoints(low: DoubleArray, high: DoubleArray, windowSec: Double): StructurePoints {
    if (low.isEmpty() || high.isEmpty() || windowSec <= 0.0) return StructurePoints(0.0, 0.0, 0.0, 0.0)
    val count = min(low.size, high.size)
    var peak = 1e-9
    var dropIndex = 0
    for (index in 0 until count) {
        if (low[index] > peak) {
            peak = low[index]
            dropIndex = index
        }
    }
    val intro = 0
    var build = intro
    var bestRise = 0.0
    for (index in 1..dropIndex) {
        val rise = high[index] - high[index - 1]
        if (rise > bestRise) {
            bestRise = rise
            build = index
        }
    }
    var breakIndex = dropIndex
    var quiet = peak
    val last = max(dropIndex, (count * 0.92).toInt().coerceAtMost(count - 1))
    for (index in dropIndex + 1..last) {
        if (low[index] < quiet) {
            quiet = low[index]
            breakIndex = index
        }
    }
    if (quiet > peak * 0.72) breakIndex = dropIndex
    return StructurePoints(intro * windowSec, build * windowSec, dropIndex * windowSec, breakIndex * windowSec)
}

/** Peso MIREX: 1 certo, 0,5 quinta, 0,3 relativo, 0,2 paralelo, 0 o resto. */
fun mirexWeight(predicted: String, truth: String): Double {
    val pred = parseKey(predicted) ?: return 0.0
    val known = parseKey(truth) ?: return 0.0
    val dist = Math.floorMod(pred.first - known.first, 12)
    val sameMode = pred.second == known.second
    if (dist == 0 && sameMode) return 1.0
    if (dist == 0) return 0.2
    if (sameMode && (dist == 5 || dist == 7)) return 0.5
    val relative = (!sameMode) && ((pred.second && dist == 9) || (!pred.second && dist == 3))
    if (relative) return 0.3
    return 0.0
}

private fun parseKey(raw: String): Pair<Int, Boolean>? {
    val name = normalizeKeyName(raw)
    if (name.isEmpty() || name == "—") return null
    val minor = name.endsWith("m")
    val root = if (minor) name.dropLast(1) else name
    val index = PITCH_CLASS.indexOf(root)
    if (index < 0) return null
    return index to minor
}

/**
 * O pico grave mais forte pode ser um baixo no contratempo (house): o BPM sai certo e a primeira batida da grade cai
 * 100 a 230 ms fora do bumbo. O bumbo tem ataque (clique) e corpo grave na mesma batida. O fluxo logarítmico (que vê
 * o salto a partir do silêncio) de 1 a 5 kHz, cruzado com o de 40 a 150 Hz, aponta o bumbo nas duas faixas de teste.
 * Só troca a fase se a nova for bem mais forte e também tiver corpo grave. Devolve a fase e os picos da figura.
 */
private fun refineGridPhase(samples: FloatArray, rate: Int, bpm: Double, phase: Double): Pair<Double, DoubleArray>? {
    if (bpm < 70.0 || rate < 16000) return null
    val seconds = min(samples.size.toDouble() / rate, 180.0)
    val head = samples.copyOf((seconds * rate).toInt())
    val lowBand = bandpass(head, rate, 40.0, 150.0)
    val clickBand = bandpass(head, rate, 1000.0, 5000.0)
    val low = logFlux(lowBand, rate)
    val click = logFlux(clickBand, rate)
    val frameRate = low.rate
    val beatFrames = 60.0 / bpm * frameRate
    val steps = beatFrames.roundToInt()
    if (steps < 20 || low.values.size < steps * 16) return null
    fun profile(flux: Flux): DoubleArray {
        val out = DoubleArray(steps)
        for (shift in 0 until steps) {
            var sum = 0.0
            var count = 0
            var at = shift.toDouble()
            while (at < flux.values.size - 1) {
                sum += flux.values[at.roundToInt()]
                count += 1
                at += beatFrames
            }
            out[shift] = if (count == 0) 0.0 else sum / count
        }
        // Suaviza ±3 quadros (6 ms) em círculo.
        val smooth = DoubleArray(steps) { index -> (-3..3).sumOf { out[Math.floorMod(index + it, steps)] } / 7.0 }
        val top = smooth.max().coerceAtLeast(1e-12)
        return DoubleArray(steps) { smooth[it] / top }
    }
    val lowProfile = profile(low)
    val clickProfile = profile(click)
    // Corpo do bumbo (100 a 250 Hz): a energia média por fase tem o máximo logo depois do ataque do bumbo e o
    // mínimo no contratempo. É o que separa o bumbo do baixo e do chapéu no contratempo.
    val bodyBand = bandpass(head, rate, 100.0, 250.0)
    val hop = max(1, rate / 500)
    val bodyFrames = bodyBand.size / hop
    val bodyEnergy = FloatArray(bodyFrames) { index ->
        var energy = 0.0
        for (cursor in index * hop until index * hop + hop) energy += bodyBand[cursor] * bodyBand[cursor]
        (energy / hop).toFloat()
    }
    val body = profile(Flux(bodyEnergy, frameRate))
    val floor = body.min()
    // Sem contraste entre o bumbo e o resto da batida, não há o que corrigir.
    if (body.max() - floor < 0.4) return null
    // O ataque (clique) está no início do bumbo e o corpo (100 a 250 Hz) tem o máximo cerca de 18 ms depois. Soma o
    // clique (60%) com o corpo deslocado (40%): o clique sozinho empata com o chapéu no contratempo, o corpo sozinho
    // vaza o baixo no contratempo. Só vale onde há corpo grave na mesma batida (fluxo de 40 a 150 Hz).
    val lead = (0.018 * frameRate).roundToInt()
    var best = -1
    var bestScore = -1.0
    for (index in 0 until steps) {
        if (lowProfile[index] < 0.5) continue
        val value = 0.6 * clickProfile[index] + 0.4 * body[Math.floorMod(index + lead, steps)]
        if (value > bestScore) {
            bestScore = value
            best = index
        }
    }
    if (best < 0) return null
    val currentShift = ((phase * frameRate).roundToInt()).let { Math.floorMod(it, steps) }
    val gap = Math.floorMod(best - currentShift, steps).let { min(it, steps - it) }
    if (gap < (0.015 * frameRate).roundToInt()) return null
    val newPhase = best / frameRate
    val clickPeaks = strongPeaks(click.values, click.rate)
    // Ajuste fino: o pico de clique mais perto da fase escolhida.
    val snapped = if (clickPeaks.isEmpty()) newPhase else {
        val near = nearestPeak(clickPeaks, newPhase + beatFrames / frameRate * 8)
        val offset = (near - (newPhase + 8 * 60.0 / bpm))
        if (abs(offset) <= 0.012) newPhase + offset else newPhase
    }
    val period = 60.0 / bpm
    var fixed = (snapped + onsetCorrection(clickBand, rate, snapped, period)) % period
    if (fixed < 0.0) fixed += period
    if (fixed > period - 0.012) fixed = 0.0
    return fixed.coerceAtLeast(0.0) to clickPeaks
}

/**
 * A grade no início do clique do bumbo (envelope de 1 ms de 1 a 5 kHz passando de 25% do pico, a mesma regra de
 * [onsetCorrection]), medido em todas as batidas da faixa e não só nas primeiras, e regular (sem recolar cada batida
 * num pico do fluxo, que fica alguns ms depois do ataque e varia com o bumbo).
 *
 * Só mexe quando a medida é firme: pelo menos 32 batidas com bumbo (corpo de 40 a 150 Hz na mesma batida), metade do meio
 * das medidas dentro de 15 ms, e o deslocamento até 40 ms. O período sai da reta pelos ataques se ficar a até 0,05% do
 * escolhido (assim 128,003 vira 128,000); senão fica o escolhido. Faixas sem bumbo claro ficam como estavam.
 * Devolve as batidas (mesmos números de tempo no compasso) e o BPM, ou null para manter a grade.
 */
internal fun alignGridToClick(samples: FloatArray, rate: Int, beats: List<AnlzBeat>, bpm: Double, why: (String) -> Unit = {}): Pair<List<AnlzBeat>, Double>? {
    if (beats.size < 32 || bpm < 40.0 || rate < 16000) return null
    val click = bandpass(samples, rate, 1000.0, 5000.0)
    val low = bandpass(samples, rate, 40.0, 150.0)
    val width = max(1, rate / 1000)
    fun envelope(x: FloatArray, w: Int): FloatArray {
        val out = FloatArray(x.size)
        var acc = 0f
        for (index in x.indices) {
            acc += abs(x[index])
            if (index >= w) acc -= abs(x[index - w])
            out[index] = acc / w
        }
        return out
    }
    val clickEnv = envelope(click, width)
    val lowEnv = envelope(low, 3 * width)
    val before = (0.040 * rate).toInt()
    val after = (0.070 * rate).toInt()
    val attacks = DoubleArray(beats.size) { Double.NaN }
    val bodies = DoubleArray(beats.size)
    for ((k, beat) in beats.withIndex()) {
        val center = (beat.timeMs / 1000.0 * rate).toInt()
        val from = center - before
        val to = center + after
        if (from < 0 || to >= clickEnv.size) continue
        var peakAt = from
        var body = 0f
        for (index in from..to) {
            if (clickEnv[index] > clickEnv[peakAt]) peakAt = index
            if (lowEnv[index] > body) body = lowEnv[index]
        }
        bodies[k] = body.toDouble()
        val peak = clickEnv[peakAt]
        if (peak <= 1e-6f) continue
        var edge = peakAt
        while (edge > from && clickEnv[edge] > 0.25f * peak) edge -= 1
        if (edge > from) attacks[k] = edge.toDouble() / rate
    }
    // Só batidas com bumbo: corpo grave de pelo menos metade da mediana das batidas.
    val bodyGate = bodies.filter { it > 0.0 }.sorted().let { if (it.isEmpty()) return null else it[it.size / 2] * 0.5 }
    val period = 60.0 / bpm
    val usable = beats.indices.filter { !attacks[it].isNaN() && bodies[it] >= bodyGate }
    if (usable.size < 32) return null.also { why("poucas batidas com bumbo: ${usable.size}") }
    // Desvio de cada ataque para a grade regular de partida (fase da primeira batida, período escolhido). Um período um
    // pouco fora (119,994 em vez de 120) faz o desvio crescer ao longo da faixa: tira-se a tendência pelas medianas dos
    // quatro quartos antes de julgar se a medida é firme.
    val origin = beats.first().timeMs / 1000.0
    fun residual(k: Int) = attacks[k] - (origin + k * period)
    val quarters = usable.chunked((usable.size + 3) / 4).filter { it.size >= 8 }.map { part ->
        part.average() to part.map(::residual).sorted()[part.size / 2]
    }
    val trend = if (quarters.size >= 2) {
        val mx = quarters.sumOf { it.first } / quarters.size
        val my = quarters.sumOf { it.second } / quarters.size
        val sxx = quarters.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx > 0) quarters.sumOf { (it.first - mx) * (it.second - my) } / sxx else 0.0
    } else 0.0
    // A tendência só vale se for um ajuste pequeno de período (até 0,05%).
    val drift = if (abs(trend / period) <= 0.0005) trend else 0.0
    val residuals = usable.map { residual(it) - drift * it }.sorted()
    val median = residuals[residuals.size / 2]
    val spread = residuals[residuals.size * 3 / 4] - residuals[residuals.size / 4]
    why("mediana %+.1f ms, meio %.1f ms, deriva %.4f%%, n=%d".format(median * 1000, spread * 1000, drift / period * 100, usable.size))
    if (spread > 0.015 || abs(median) > 0.040) return null
    // Reta pelos ataques que concordam (até 12 ms da linha): fase e, se coerente, período.
    val kept = usable.filter { abs(residual(it) - drift * it - median) <= 0.012 }
    if (kept.size < 32) return null.also { why("poucas concordam: ${kept.size}") }
    val meanK = kept.sumOf { it.toDouble() } / kept.size
    val meanT = kept.sumOf { attacks[it] } / kept.size
    val sxx = kept.sumOf { (it - meanK) * (it - meanK) }
    val slope = if (sxx > 0) kept.sumOf { (it - meanK) * (attacks[it] - meanT) } / sxx else period
    val step = if (abs(slope / period - 1.0) <= 0.0005) slope else period
    val start = if (step == slope) meanT - meanK * step else origin + median
    // Mesmo que já esteja certo, a grade fica regular; o tempo no compasso de cada batida não muda.
    val duration = samples.size.toDouble() / rate
    val out = beats.indices.mapNotNull { k ->
        // Uma batida que cai poucos ms antes do início fica no zero: tirá-la mudaria qual é o primeiro tempo 1, e as frases
        // são contadas a partir dele.
        val time = (start + k * step).let { if (it < 0.0 && it > -0.050) 0.0 else it }
        if (time < 0.0 || time > duration) null else AnlzBeat(beats[k].beatInBar, 60.0 / step, (time * 1000.0).roundToInt())
    }
    return if (out.size < 32) null else out to 60.0 / step
}

/**
 * O pico do fluxo fica alguns ms depois do início do bumbo. No envelope de 1 ms da faixa de clique, procura o instante em
 * que o ataque passa de 25% do pico de cada batida e devolve a mediana do desvio (até ±20 ms), para a grade cair no início.
 */
private fun onsetCorrection(click: FloatArray, rate: Int, phase: Double, period: Double): Double {
    val width = max(1, rate / 1000)
    val env = FloatArray(click.size)
    var acc = 0f
    for (index in click.indices) {
        acc += abs(click[index])
        if (index >= width) acc -= abs(click[index - width])
        env[index] = acc / width
    }
    val offsets = ArrayList<Double>()
    val reach = (0.025 * rate).toInt()
    for (beat in 2 until 160) {
        val center = ((phase + beat * period) * rate).toInt()
        val from = center - reach
        val to = center + reach
        if (from < 0 || to >= env.size) break
        var peak = 0f
        for (index in from..to) if (env[index] > peak) peak = env[index]
        if (peak <= 1e-6f) continue
        var edge = from
        while (edge < to && env[edge] < 0.25f * peak) edge += 1
        offsets += (edge - center).toDouble() / rate
    }
    if (offsets.size < 16) return 0.0
    offsets.sort()
    return offsets[offsets.size / 2].coerceIn(-0.020, 0.020)
}

private fun bandpass(samples: FloatArray, rate: Int, lowHz: Double, highHz: Double): FloatArray {
    val upper = zeroPhaseLowpass(samples, rate, min(highHz, rate * 0.45))
    val lower = zeroPhaseLowpass(samples, rate, lowHz)
    return FloatArray(samples.size) { upper[it] - lower[it] }
}

private fun logFlux(band: FloatArray, rate: Int): Flux {
    val hop = max(1, rate / 500)
    val frames = band.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    for (index in 0 until frames) {
        var energy = 0.0
        val start = index * hop
        for (cursor in start until start + hop) energy += band[cursor] * band[cursor]
        val level = ln1p(energy / hop * 1e6)
        env[index] = (level - previous).coerceAtLeast(0.0).toFloat()
        previous = level
    }
    return Flux(env, rate.toDouble() / hop)
}

private data class BpmPick(val bpm: Double, val confidence: Double, val phase: Double)

private data class Flux(val values: FloatArray, val rate: Double)

private data class StructEnv(val low: DoubleArray, val high: DoubleArray, val windowSec: Double)

private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val nb0 = (b0 / a0).toFloat()
    private val nb1 = (b1 / a0).toFloat()
    private val nb2 = (b2 / a0).toFloat()
    private val na1 = (a1 / a0).toFloat()
    private val na2 = (a2 / a0).toFloat()
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = nb0 * x + z1
        z1 = nb1 * x - na1 * y + z2
        z2 = nb2 * x - na2 * y
        return y
    }
}

private fun lowpass(rate: Int, freq: Double): Biquad {
    val w0 = 2.0 * PI * freq / rate
    val cosine = cos(w0)
    val sine = sin(w0)
    val alpha = sine / (2.0 * 0.7071067811865476)
    val b0 = (1.0 - cosine) / 2.0
    val b1 = 1.0 - cosine
    val b2 = (1.0 - cosine) / 2.0
    val a0 = 1.0 + alpha
    val a1 = -2.0 * cosine
    val a2 = 1.0 - alpha
    return Biquad(b0, b1, b2, a0, a1, a2)
}

private fun zeroPhaseLowpass(samples: FloatArray, rate: Int, freq: Double): FloatArray {
    var current = samples
    repeat(2) {
        val forward = FloatArray(current.size)
        val forth = lowpass(rate, freq)
        for (index in current.indices) forward[index] = forth.process(current[index])
        val back = FloatArray(current.size)
        val reverse = lowpass(rate, freq)
        for (index in current.indices.reversed()) back[index] = reverse.process(forward[index])
        current = back
    }
    return current
}

private fun kickFlux(samples: FloatArray, rate: Int): Flux {
    val low = zeroPhaseLowpass(samples, rate, 180.0)
    val rumble = zeroPhaseLowpass(samples, rate, 35.0)
    val band = FloatArray(samples.size) { index -> low[index] - rumble[index] }
    return fluxOf(band, rate)
}

private fun fullBandFlux(samples: FloatArray, rate: Int): Flux {
    val hop = max(1, rate / 200)
    val frames = samples.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    for (index in 0 until frames) {
        val start = index * hop
        val end = min(samples.size, start + hop)
        var energy = 0.0
        var cursor = start
        while (cursor < end) {
            energy += abs(samples[cursor])
            cursor += 1
        }
        energy /= (end - start).coerceAtLeast(1)
        env[index] = (energy - previous).coerceAtLeast(0.0).toFloat()
        previous = energy
    }
    return Flux(env, rate.toDouble() / hop)
}

private fun fluxOf(band: FloatArray, rate: Int): Flux {
    val hop = max(1, rate / 200)
    val frames = band.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    for (index in 0 until frames) {
        val start = index * hop
        var energy = 0.0
        var cursor = start
        val end = start + hop
        while (cursor < end && cursor < band.size) {
            val sample = band[cursor]
            energy += sample * sample
            cursor += 1
        }
        energy /= hop
        env[index] = (energy - previous).coerceAtLeast(0.0).toFloat()
        previous = energy
    }
    return Flux(env, rate.toDouble() / hop)
}

private fun structureEnvelope(samples: FloatArray, rate: Int): StructEnv {
    val hop = max(1, rate / 200)
    val envRate = rate.toDouble() / hop
    val frames = samples.size / hop
    val window = 2.0
    val winFrames = max(1, (envRate * window).roundToInt())
    val windows = max(1, frames / winFrames)
    val low = DoubleArray(windows)
    val high = DoubleArray(windows)
    val lowAcc = DoubleArray(windows)
    val highAcc = DoubleArray(windows)
    val count = IntArray(windows)
    val coef = kotlin.math.exp(-2.0 * PI * 180.0 / rate).toFloat()
    val mix = 1f - coef
    var state = 0f
    for (index in 0 until frames) {
        val start = index * hop
        val end = min(samples.size, start + hop)
        var bass = 0.0
        var rest = 0.0
        var cursor = start
        while (cursor < end) {
            val sample = samples[cursor]
            state = coef * state + mix * sample
            val highBand = sample - state
            bass += state * state
            rest += highBand * highBand
            cursor += 1
        }
        val span = (end - start).coerceAtLeast(1)
        val bin = min(windows - 1, index / winFrames)
        lowAcc[bin] += bass / span
        highAcc[bin] += rest / span
        count[bin] += 1
    }
    for (index in 0 until windows) {
        val n = count[index].coerceAtLeast(1)
        low[index] = sqrt(lowAcc[index] / n)
        high[index] = sqrt(highAcc[index] / n)
    }
    return StructEnv(low, high, window)
}

private fun pickBpm(env: FloatArray, envRate: Double, peaks: DoubleArray, seed: TempoSeed? = null): BpmPick {
    val forced = seed?.bpm ?: 0.0
    if (env.size < 8) return BpmPick(0.0, 0.0, 0.0)
    val minLag = max(1, (envRate * 60.0 / 190.0).roundToInt())
    val maxLag = min(env.size / 2, (envRate * 60.0 / 80.0).roundToInt())
    if (maxLag <= minLag) return BpmPick(0.0, 0.0, 0.0)
    val scores = DoubleArray(maxLag + 1)
    var mean = 0.0
    var counted = 0
    for (lag in minLag..maxLag) {
        var acc = 0.0
        val limit = env.size - lag
        var index = 0
        while (index < limit) {
            acc += env[index] * env[index + lag]
            index += 1
        }
        val score = acc / limit.coerceAtLeast(1)
        scores[lag] = score
        mean += score
        counted += 1
    }
    mean /= counted.coerceAtLeast(1)
    val lags = ArrayList<Int>()
    for (lag in (minLag + 1) until maxLag) {
        if (scores[lag] >= scores[lag - 1] && scores[lag] > scores[lag + 1]) lags += lag
    }
    lags.sortByDescending { scores[it] }
    if (lags.isEmpty()) lags += minLag
    val duration = env.size / envRate
    // A cobertura dos picos sozinha prefere 3/4 e 3/2 quando há picos entre as batidas.
    // O pente no envelope e a autocorrelação têm de concordar. O prior é largo (cerca de uma oitava)
    // à volta de 124, para house e drum and bass caberem os dois.
    // Com andamento vindo do envelope multibanda (onsetTempo), a busca antiga pelo pente do kick não decide o BPM:
    // o kick só refina a fase e o período (refineTempo).
    val local = if (forced > 0.0) forced else {
        var chosen = 120.0
        var best = -1.0
        var bpm = 70.0
        while (bpm <= 180.0) {
            val comb = combScore(env, envRate, bpm)
            val periodic = interpolatedScore(scores, envRate, bpm).coerceAtLeast(0.0)
            val score = comb * (periodic + 1e-9) * meterPrior(bpm)
            if (score > best) {
                best = score
                chosen = bpm
            }
            bpm += 1.0
        }
        var found = chosen
        var localBest = best
        var delta = -0.8
        while (delta <= 0.8) {
            val candidate = chosen + delta
            if (candidate in 70.0..180.0) {
                val score = combScore(env, envRate, candidate) * (interpolatedScore(scores, envRate, candidate).coerceAtLeast(0.0) + 1e-9) * meterPrior(candidate)
                if (score > localBest) {
                    localBest = score
                    found = candidate
                }
            }
            delta += 0.2
        }
        found
    }
    val (phase, _) = coverage(peaks, local, duration, 72)
    // O período vindo do envelope já foi refinado na faixa inteira (refineByGrid); a inclinação dos picos do bumbo só
    // refina o pente antigo.
    val refined = if (seed == null) {
        refineTempo(peaks, local, phase, duration)
    } else {
        // Dois candidatos: o do envelope e o refinado pela inclinação dos picos do bumbo; vale o que mantém mais
        // ataques na grade em toda a faixa. Depois um polimento fino (±0,15 BPM, passo de 0,005).
        val fromKick = refineTempo(peaks, local, phase, duration).first
        val pick = if (seed.scorer.score(fromKick) > seed.scorer.score(local)) fromKick else local
        val polished = seed.scorer.polish(pick, 0.15, 0.005)
        polished to coverage(peaks, polished, duration, 120).first
    }
    val confidence = if (mean <= 1e-12) 0.0 else (scores[lags.first()] / mean).coerceIn(0.0, 99.0)
    return BpmPick(refined.first, confidence, refined.second)
}

private fun interpolatedScore(scores: DoubleArray, envRate: Double, bpm: Double): Double {
    if (bpm < 40.0 || scores.size < 3) return 0.0
    val lag = envRate * 60.0 / bpm
    val at = lag.toInt()
    if (at < 1 || at >= scores.lastIndex) return 0.0
    val frac = lag - at
    return scores[at] * (1.0 - frac) + scores[at + 1] * frac
}

private fun meterPrior(bpm: Double): Double {
    val octaves = kotlin.math.ln(bpm / 124.0) / kotlin.math.ln(2.0)
    return kotlin.math.exp(-0.5 * octaves * octaves / (1.2 * 1.2))
}

/** Corrige o período pela inclinação dos kicks que já caem perto da grade. Um BPM 0,3 acima desloca o fim da faixa em dezenas de ms. */
private fun refineTempo(peaks: DoubleArray, bpm: Double, phase: Double, duration: Double): Pair<Double, Double> {
    val beat = 60.0 / bpm
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    var count = 0
    var time = phase
    var index = 0.0
    while (time < duration && index < 4000) {
        if (peaks.isNotEmpty()) {
            val delta = nearestPeak(peaks, time) - time
            if (abs(delta) <= 0.025) {
                sumX += index
                sumY += delta
                sumXX += index * index
                sumXY += index * delta
                count += 1
            }
        }
        time += beat
        index += 1.0
    }
    if (count < 12) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val denom = count * sumXX - sumX * sumX
    if (abs(denom) < 1e-9) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val slope = (count * sumXY - sumX * sumY) / denom
    val newBeat = beat + slope
    val newBpm = 60.0 / newBeat
    if (newBpm < 70.0 || newBpm > 185.0 || abs(newBpm - bpm) > 3.0) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val (newPhase, _) = coverage(peaks, newBpm, duration, 120)
    return newBpm to newPhase
}

/** Fração das batidas do metrônomo que caem a até 18 ms de um pico grave, e a fase que maximiza isso. */
private fun coverage(peaks: DoubleArray, bpm: Double, duration: Double, steps: Int): Pair<Double, Double> {
    if (bpm < 40.0 || duration <= 0.0) return 0.0 to 0.0
    val beat = 60.0 / bpm
    var bestPhase = 0.0
    var bestHits = -1
    var bestTotal = 1
    for (step in 0 until steps) {
        val phase = beat * step / steps
        var hits = 0
        var total = 0
        var time = phase
        while (time < duration && total < 4000) {
            if (peaks.isNotEmpty() && abs(nearestPeak(peaks, time) - time) <= 0.018) hits += 1
            total += 1
            time += beat
        }
        if (hits > bestHits) {
            bestHits = hits
            bestTotal = total.coerceAtLeast(1)
            bestPhase = phase
        }
    }
    return bestPhase to bestHits.toDouble() / bestTotal
}

private fun parabolicLag(scores: DoubleArray, lag: Int): Double {
    if (lag <= 0 || lag >= scores.lastIndex) return lag.toDouble()
    val left = scores[lag - 1]
    val mid = scores[lag]
    val right = scores[lag + 1]
    val denom = left - 2.0 * mid + right
    if (abs(denom) < 1e-12) return lag.toDouble()
    val delta = (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
    return lag + delta
}

private fun combScore(env: FloatArray, envRate: Double, bpm: Double): Double {
    if (bpm < 40.0) return 0.0
    val beat = 60.0 / bpm
    var best = 0.0
    for (step in 0 until 36) {
        val phase = beat * step / 36.0
        val score = phaseSum(env, envRate, phase, beat)
        if (score > best) best = score
    }
    return best
}

private fun phaseSum(env: FloatArray, envRate: Double, phase: Double, beat: Double): Double {
    var cursor = phase
    var sum = 0.0
    var hits = 0
    while (cursor < (env.size - 1) / envRate) {
        val index = cursor * envRate
        val at = index.toInt().coerceIn(0, env.lastIndex - 1)
        val frac = index - at
        sum += env[at] * (1.0 - frac) + env[at + 1] * frac
        hits += 1
        cursor += beat
    }
    return if (hits == 0) 0.0 else sum / hits
}

private fun strongPeaks(env: FloatArray, envRate: Double): DoubleArray {
    if (env.size < 4) return DoubleArray(0)
    val gate = percentile(env, 0.84)
    val found = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        val left = env[index - 1].toDouble()
        val mid = value.toDouble()
        val right = env[index + 1].toDouble()
        val denom = left - 2.0 * mid + right
        val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
        found += (index + delta + 0.5) / envRate
    }
    return found.toDoubleArray()
}

private fun nearestPeak(peaks: DoubleArray, time: Double): Double {
    if (peaks.isEmpty()) return time
    var low = 0
    var high = peaks.size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (peaks[mid] < time) low = mid + 1 else high = mid
    }
    var best = peaks[low]
    if (low > 0 && abs(peaks[low - 1] - time) < abs(best - time)) best = peaks[low - 1]
    return best
}

private fun beatGrid(peaks: DoubleArray, pick: BpmPick, durationSec: Double): List<AnlzBeat> {
    if (pick.bpm < 40.0) return emptyList()
    // O período fica o do autocorr. Seguir o snap (mesmo um de 16 ms) esticava o
    // período e a fase atravessava a batida: a mediana contra o kick ia para ~1/4.
    val period = 60.0 / pick.bpm
    val times = ArrayList<Double>()
    var cursor = pick.phase
    var guard = 0
    while (cursor <= durationSec + 0.001 && guard < 8000) {
        val near = if (peaks.isEmpty()) cursor else nearestPeak(peaks, cursor)
        val used = if (abs(near - cursor) <= 0.020) near else cursor
        if (used >= -0.001) times += used.coerceAtLeast(0.0)
        cursor += period
        guard += 1
    }
    return times.mapIndexed { index, time ->
        AnlzBeat((index % 4) + 1, pick.bpm, (time * 1000.0).roundToInt().coerceAtLeast(0))
    }
}

private fun snapFlux(env: FloatArray, envRate: Double, time: Double, windowSec: Double, gate: Double): Double? {
    val center = time * envRate
    val radius = (windowSec * envRate).roundToInt().coerceAtLeast(1)
    val start = (center.toInt() - radius).coerceAtLeast(1)
    val end = (center.toInt() + radius).coerceAtMost(env.lastIndex - 1)
    if (end <= start) return null
    var best = start
    var bestVal = env[start]
    for (index in start..end) {
        if (env[index] > bestVal) {
            bestVal = env[index]
            best = index
        }
    }
    if (bestVal < gate) return null
    val left = env[best - 1].toDouble()
    val mid = env[best].toDouble()
    val right = env[best + 1].toDouble()
    val denom = left - 2.0 * mid + right
    val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
    return (best + delta + 0.5) / envRate
}

private fun kickGridError(peaks: DoubleArray, beats: List<AnlzBeat>, bpm: Double): Triple<Double, Double, Double> {
    if (beats.size < 4 || peaks.isEmpty() || bpm < 40.0) return Triple(-1.0, -1.0, 0.0)
    val errors = ArrayList<Double>()
    var covered = 0
    for (beat in beats) {
        val time = beat.timeMs / 1000.0
        val distance = abs(nearestPeak(peaks, time) - time)
        // Fora de 20 ms o pico mais perto é outra figura (contratempo, fill).
        // Não entra na mediana: a cobertura conta essas batidas à parte.
        if (distance <= 0.020) {
            covered += 1
            errors += distance * 1000.0
        }
    }
    if (errors.size < 4) return Triple(-1.0, -1.0, covered.toDouble() / beats.size)
    errors.sort()
    return Triple(errors[errors.size / 2], errors.last(), covered.toDouble() / beats.size)
}

private fun fullBandGridError(env: FloatArray, envRate: Double, beats: List<AnlzBeat>): Double {
    if (beats.size < 4 || env.size < 8) return -1.0
    var mean = 0.0
    for (value in env) mean += value
    mean /= env.size
    val gate = mean * 1.8
    val errors = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        val seconds = (index + 0.5) / envRate
        var nearest = beats[0].timeMs / 1000.0
        var best = abs(nearest - seconds)
        for (beat in beats) {
            val time = beat.timeMs / 1000.0
            val distance = abs(time - seconds)
            if (distance < best) {
                best = distance
                nearest = time
            }
        }
        errors += abs(nearest - seconds) * 1000.0
    }
    if (errors.size < 4) return -1.0
    errors.sort()
    return errors[errors.size / 2]
}

private fun percentile(values: FloatArray, fraction: Double): Double {
    if (values.isEmpty()) return 0.0
    val copy = values.copyOf()
    copy.sort()
    val index = ((copy.size - 1) * fraction).roundToInt().coerceIn(0, copy.lastIndex)
    return copy[index].toDouble()
}

private class CqtBin(val real: FloatArray, val imag: FloatArray, val freq: Double, val pc: Int) {
    val half = real.size / 2
}

// Núcleos fixos a 22050 Hz. O custo de os construir fica fora de cada faixa.
private val cqtBins: List<CqtBin> by lazy { buildCqtBins() }

internal fun detectKey(samples: FloatArray, rate: Int): Pair<String, Double> {
    if (samples.size < 4096 * 2 || rate < 8000) return "—" to 0.0
    if (rate == 22050) return detectKeyAt22050(samples)
    val outCount = (samples.size.toDouble() * 22050.0 / rate).toInt()
    if (outCount < 4096 * 2) return "—" to 0.0
    val scaled = FloatArray(outCount)
    val step = rate / 22050.0
    for (index in 0 until outCount) {
        val source = index * step
        val left = source.toInt().coerceIn(0, samples.lastIndex)
        val right = (left + 1).coerceAtMost(samples.lastIndex)
        val frac = (source - left).toFloat()
        val a = samples[left]
        val b = samples[right]
        scaled[index] = a + (b - a) * frac
    }
    return detectKeyAt22050(scaled)
}

private fun detectKeyAt22050(samples: FloatArray): Pair<String, Double> = detectKeyPeaks(samples, 22050)

@Suppress("unused")
private fun detectKeyCqtLegacy(samples: FloatArray): Pair<String, Double> {
    val hop = 4096
    val count = (samples.size - hop) / hop
    if (count < 4) return "—" to 0.0
    val rms = DoubleArray(count)
    for (index in 0 until count) {
        val start = index * hop
        var energy = 0.0
        for (cursor in start until start + hop) {
            val value = samples[cursor].toDouble()
            energy += value * value
        }
        rms[index] = energy / hop
    }
    val sorted = rms.copyOf()
    sorted.sort()
    val gate = sorted[sorted.size / 2]
    val loud = ArrayList<Int>(count / 2)
    for (index in 0 until count) {
        if (rms[index] >= gate) loud += index
    }
    if (loud.size < 4) return "—" to 0.0
    val chroma = DoubleArray(12)
    val bins = cqtBins
    for (bin in bins) {
        val length = bin.real.size
        var acc = 0.0
        for (index in loud) {
            val start = index * hop + hop / 2 - bin.half
            if (start < 0 || start + length > samples.size) continue
            var re = 0f
            var im = 0f
            var cursor = 0
            val real = bin.real
            val imag = bin.imag
            while (cursor + 3 < length) {
                val x0 = samples[start + cursor]
                val x1 = samples[start + cursor + 1]
                val x2 = samples[start + cursor + 2]
                val x3 = samples[start + cursor + 3]
                re += x0 * real[cursor] + x1 * real[cursor + 1] + x2 * real[cursor + 2] + x3 * real[cursor + 3]
                im += x0 * imag[cursor] + x1 * imag[cursor + 1] + x2 * imag[cursor + 2] + x3 * imag[cursor + 3]
                cursor += 4
            }
            while (cursor < length) {
                val value = samples[start + cursor]
                re += value * real[cursor]
                im += value * imag[cursor]
                cursor += 1
            }
            val re64 = re.toDouble()
            val im64 = im.toDouble()
            acc += ln1p(sqrt(re64 * re64 + im64 * im64))
        }
        val weight = if (bin.freq < 180.0) 1.7 else 1.0
        chroma[bin.pc] += acc * weight
    }
    val energy = chroma.sum()
    if (energy <= 1e-6) return "—" to 0.0
    val peak = chroma.max()
    // Um seno puro não tem terça. Sem terça, o nome é o da nota mais forte.
    if (peak / energy >= 0.5) {
        val name = MAJOR_NAMES[chroma.indices.maxBy { chroma[it] }]
        return name to (peak / energy).coerceIn(0.0, 1.0)
    }
    return matchEdma(chroma)
}

private fun buildCqtBins(): List<CqtBin> {
    val rate = 22050.0
    val q = 4.0 / (2.0.pow(1.0 / 12.0) - 1.0)
    var midi = ceil(69.0 + 12.0 * log2(32.7 / 440.0))
    val bins = ArrayList<CqtBin>(96)
    while (bins.size < 500) {
        val freq = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
        if (freq > 5000.0) break
        if (freq >= 32.7) {
            var length = max(32, round(q * rate / freq).toInt())
            if (length % 2 == 1) length += 1
            val real = FloatArray(length)
            val imag = FloatArray(length)
            var windowSum = 0.0
            for (index in 0 until length) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * index / (length - 1))
                val time = index - length / 2.0
                val theta = 2.0 * PI * freq * time / rate
                real[index] = (window * cos(theta)).toFloat()
                imag[index] = (window * -sin(theta)).toFloat()
                windowSum += window
            }
            val inv = (1.0 / windowSum).toFloat()
            for (index in 0 until length) {
                real[index] *= inv
                imag[index] *= inv
            }
            bins += CqtBin(real, imag, freq, (round(midi).toInt() % 12 + 12) % 12)
        }
        midi += 1.0
    }
    return bins
}

private fun matchEdma(chroma: DoubleArray): Pair<String, Double> {
    var bestName = "C"
    var best = -2.0
    var second = -2.0
    for (shift in 0 until 12) {
        val pairs = listOf(
            pearson(chroma, EDMA_MAJOR, shift) to MAJOR_NAMES[shift],
            pearson(chroma, EDMA_MINOR, shift) to MINOR_NAMES[shift],
        )
        for ((score, name) in pairs) {
            if (score > best) {
                second = best
                best = score
                bestName = name
            } else if (score > second) {
                second = score
            }
        }
    }
    return bestName to best.coerceIn(0.0, 1.0)
}

private fun pearson(chroma: DoubleArray, profile: DoubleArray, shift: Int): Double {
    var meanLeft = 0.0
    var meanRight = 0.0
    for (index in 0 until 12) {
        meanLeft += chroma[index]
        meanRight += profile[index]
    }
    meanLeft /= 12.0
    meanRight /= 12.0
    var dot = 0.0
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    for (index in 0 until 12) {
        val left = chroma[(index + shift) % 12] - meanLeft
        val right = profile[index] - meanRight
        dot += left * right
        leftEnergy += left * left
        rightEnergy += right * right
    }
    if (leftEnergy <= 1e-12 || rightEnergy <= 1e-12) return 0.0
    return dot / sqrt(leftEnergy * rightEnergy)
}

fun normalizeKeyName(raw: String): String {
    var text = raw.trim()
    text = text.replace(Regex("(?i)\\s+minor$"), "m")
    text = text.replace(Regex("(?i)\\s+major$"), "")
    text = text.replace(" ", "")
    text = text.replace(Regex("(?i)minor$"), "m")
    text = text.replace(Regex("(?i)major$"), "")
    val trimmed = text.replace("maj", "").replace("min", "m")
    if (trimmed.isEmpty()) return ""
    return trimmed
        .replace("D#m", "Ebm")
        .replace("D#", "Eb")
        .replace("A#m", "Bbm")
        .replace("A#", "Bb")
        .replace("G#m", "Abm")
        .replace("G#", "Ab")
        .replace("C#m", "Dbm")
        .replace("C#", "Db")
        .replace("Gbm", "F#m")
        .replace("Gb", "F#")
        .replace("Cbm", "Bm")
        .replace("Cb", "B")
}
