package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Frases no estilo da análise de frases do rekordbox (humor "high": Intro, Up, Down, Chorus, Outro), feitas por este
 * app a partir do áudio. Não é o algoritmo do rekordbox, que não é publicado; só os nomes e a ideia (frases de 8 a 32
 * compassos presas à grade, com fill-in no fim) vêm da descrição pública.
 *
 * 1. Medidas por compasso, presas à grade da faixa (primeira batida 1): grave, médio e agudo em dB e presença do bumbo.
 * 2. Cortes por programação dinâmica em blocos de 4 compassos: cada frase tem 8, 16, 24 ou 32 compassos (4 só nas
 *    pontas) e o custo é a variação dentro da frase mais uma multa por frase.
 * 3. Rótulos pelo nível de grave e bumbo de cada frase contra o patamar da faixa.
 */
internal data class BarFeatures(
    val originSec: Double,
    val barSec: Double,
    val lowDb: DoubleArray,
    val midDb: DoubleArray,
    val highDb: DoubleArray,
    /** Presença do bumbo, 0..1: ataque grave na batida contra o que vem logo antes. */
    val kick: DoubleArray,
    /** Nível total do compasso em dB (para achar o silêncio da cauda). */
    val totalDb: DoubleArray,
) {
    val bars: Int get() = lowDb.size
}

private const val SILENCE_DB = -55.0

internal fun barFeatures(samples: FloatArray, rate: Int, bpm: Double, originSec: Double): BarFeatures? {
    if (bpm <= 1.0 || rate <= 0 || samples.isEmpty()) return null
    val barSec = 240.0 / bpm
    // Começa no último compasso inteiro antes da primeira batida 1: a anacruse entra no primeiro compasso.
    val origin = originSec - kotlin.math.ceil(originSec / barSec - 1e-9) * barSec
    val duration = samples.size.toDouble() / rate
    val bars = ((duration - origin) / barSec).toInt().coerceAtLeast(1)
    val low = DoubleArray(bars)
    val mid = DoubleArray(bars)
    val high = DoubleArray(bars)
    val total = DoubleArray(bars)
    val counts = IntArray(bars)
    // Grave: dois polos a 150 Hz. Agudo: o que sobra acima de dois polos a 3 kHz. Médio: o meio.
    val aLow = exp(-2.0 * PI * 150.0 / rate).toFloat()
    val aHigh = exp(-2.0 * PI * 3000.0 / rate).toFloat()
    var l1 = 0f
    var l2 = 0f
    var h1 = 0f
    var h2 = 0f
    // Envelope grave em 5 ms, para o bumbo por batida.
    val hop = max(1, rate / 200)
    val env = FloatArray(samples.size / hop + 1)
    var acc = 0f
    for (index in samples.indices) {
        val x = samples[index]
        l1 += (1f - aLow) * (x - l1)
        l2 += (1f - aLow) * (l1 - l2)
        h1 += (1f - aHigh) * (x - h1)
        h2 += (1f - aHigh) * (h1 - h2)
        val lowBand = l2
        val highBand = x - h2
        val midBand = h2 - l2
        acc += lowBand * lowBand
        if (index % hop == hop - 1) {
            env[index / hop] = acc / hop
            acc = 0f
        }
        val t = index.toDouble() / rate - origin
        if (t < 0.0) continue
        val bar = (t / barSec).toInt()
        if (bar >= bars) continue
        low[bar] += (lowBand * lowBand).toDouble()
        mid[bar] += (midBand * midBand).toDouble()
        high[bar] += (highBand * highBand).toDouble()
        total[bar] += (x * x).toDouble()
        counts[bar] += 1
    }
    fun db(sum: Double, n: Int): Double = 10.0 * log10(max(sum / max(1, n), 1e-12))
    // Bumbo sem depender da grade (um BPM 0,1 fora já faz a janela "na batida" escorregar ao longo da faixa): quanto o
    // envelope grave de 5 ms pulsa dentro do compasso, pico (95%) contra a mediana. Grave contínuo sem bumbo pulsa pouco.
    val kick = DoubleArray(bars)
    val envRate = rate.toDouble() / hop
    for (bar in 0 until bars) {
        val from = ((origin + bar * barSec) * envRate).toInt().coerceIn(0, env.size)
        val to = ((origin + (bar + 1) * barSec) * envRate).toInt().coerceIn(from, env.size)
        if (to - from < 8) continue
        val slice = env.copyOfRange(from, to).sorted()
        val median = slice[slice.size / 2].toDouble()
        val peak = slice[(slice.size * 95) / 100].toDouble()
        val pulse = 10.0 * log10(max(peak, 1e-12) / max(median, 1e-12))
        kick[bar] = ((pulse - 4.0) / 8.0).coerceIn(0.0, 1.0)
    }
    return BarFeatures(
        origin,
        barSec,
        DoubleArray(bars) { db(low[it], counts[it]) },
        DoubleArray(bars) { db(mid[it], counts[it]) },
        DoubleArray(bars) { db(high[it], counts[it]) },
        kick,
        DoubleArray(bars) { db(total[it], counts[it]) },
    )
}

/** Cortes em compassos (o primeiro é 0, o último é o total), por programação dinâmica em blocos de 4 compassos. */
internal fun phraseCuts(f: BarFeatures): List<Int> {
    val block = 4
    val blocks = (f.bars + block - 1) / block
    if (blocks <= 2) return listOf(0, f.bars)
    // Vetor do bloco: grave, médio, agudo e bumbo, cada um em escala de desvio da faixa.
    fun column(values: DoubleArray, b: Int): Double {
        val from = b * block
        val to = min(f.bars, from + block)
        var s = 0.0
        for (i in from until to) s += values[i]
        return s / (to - from)
    }
    val raw = Array(blocks) { b ->
        doubleArrayOf(column(f.lowDb, b) * 2.0, column(f.midDb, b), column(f.highDb, b), column(f.kick, b) * 6.0)
    }
    val dims = raw[0].size
    val scaled = Array(blocks) { DoubleArray(dims) }
    for (d in 0 until dims) {
        val mean = raw.sumOf { it[d] } / blocks
        val sd = sqrt(raw.sumOf { (it[d] - mean) * (it[d] - mean) } / blocks).coerceAtLeast(1.5)
        for (b in 0 until blocks) scaled[b][d] = (raw[b][d] - mean) / sd
    }
    fun cost(from: Int, to: Int): Double {
        val n = to - from
        val mean = DoubleArray(dims)
        for (b in from until to) for (d in 0 until dims) mean[d] += scaled[b][d] / n
        var c = 0.0
        for (b in from until to) for (d in 0 until dims) c += (scaled[b][d] - mean[d]) * (scaled[b][d] - mean[d])
        return c
    }
    // Multa por frase: um corte só vale se separa blocos diferentes de verdade (cerca de 1,2 desvio por dimensão).
    val penalty = 0.5 * dims
    val lengths = intArrayOf(2, 4, 6, 8)
    val best = DoubleArray(blocks + 1) { Double.MAX_VALUE }
    val from = IntArray(blocks + 1) { -1 }
    best[0] = 0.0
    for (end in 1..blocks) {
        val options = lengths.toMutableList()
        // Frase de 4 compassos só na ponta: a abertura curta ou a cauda que sobra da grade.
        if (end == 1 || end == blocks) options += 1
        for (n in options) {
            val start = end - n
            if (start < 0 || best[start] == Double.MAX_VALUE) continue
            // A última frase pode ser mais curta que o tamanho pedido (a faixa acaba).
            val total = best[start] + cost(start, end) + penalty + if (n == 1) penalty * 0.5 else 0.0
            if (total < best[end]) {
                best[end] = total
                from[end] = start
            }
        }
        if (end == blocks && best[end] == Double.MAX_VALUE) {
            // Sobra que nenhum tamanho cobre: junta à frase anterior.
            for (start in end - 1 downTo 0) {
                if (best[start] == Double.MAX_VALUE) continue
                best[end] = best[start] + cost(start, end) + penalty
                from[end] = start
                break
            }
        }
    }
    val cuts = ArrayList<Int>()
    var at = blocks
    while (at > 0) {
        cuts += min(f.bars, at * block)
        at = from[at].coerceAtLeast(0)
        if (at == 0) break
    }
    cuts += 0
    return cuts.distinct().sorted()
}

internal data class PhrasePlan(val cuts: List<Int>, val kinds: List<String>, val fillBars: List<Int?>)

/** Grave suavizado em 2 compassos (o degrau de um compasso de virada não conta). */
private fun smoothedLow(f: BarFeatures): DoubleArray = DoubleArray(f.bars) { bar ->
    val from = max(0, bar - 1)
    val to = min(f.bars - 1, bar)
    (from..to).sumOf { f.lowDb[it] } / (to - from + 1)
}

/**
 * Cada corte da programação dinâmica anda até 2 compassos para o compasso de maior degrau do grave (subida ou
 * descida), se esse degrau for bem maior que o do corte original. A frase continua presa à grade de compassos.
 */
internal fun refineCuts(f: BarFeatures, cuts: List<Int>): List<Int> {
    fun step(bar: Int): Double {
        if (bar <= 0 || bar >= f.bars) return 0.0
        val before = (max(0, bar - 2) until bar).map { f.lowDb[it] }.average()
        val after = (bar until min(f.bars, bar + 2)).map { f.lowDb[it] }.average()
        return kotlin.math.abs(after - before)
    }
    val out = cuts.toMutableList()
    for (k in 1 until out.size - 1) {
        val cut = out[k]
        var best = cut
        for (bar in cut - 2..cut + 2) {
            if (bar <= out[k - 1] + 3 || bar >= out[k + 1] - 3) continue
            if (step(bar) > step(best) + 1.5) best = bar
        }
        out[k] = best
    }
    return out
}

/**
 * Rótulos do humor "high" pelo grave de cada frase contra o patamar (o grave das frases mais fortes):
 * Chorus no patamar, Down bem abaixo dele, Up no meio; antes do primeiro Chorus é Intro (a última frase antes dele,
 * se já subiu, é Up); depois do último Chorus é Outro; silêncio de arquivo no fim entra no Outro.
 */
internal fun labelPhrases(f: BarFeatures, cuts: List<Int>): PhrasePlan {
    val n = cuts.size - 1
    if (n <= 0) return PhrasePlan(cuts, emptyList(), emptyList())
    val smooth = smoothedLow(f)
    fun mean(values: DoubleArray, i: Int): Double {
        val a = cuts[i]
        val b = min(f.bars, max(a + 1, cuts[i + 1]))
        return (a until b).sumOf { values[it] } / (b - a).coerceAtLeast(1)
    }
    val low = DoubleArray(n) { mean(smooth, it) }
    val total = DoubleArray(n) { mean(f.totalDb, it) }
    val kick = DoubleArray(n) { mean(f.kick, it) }
    val audible = (0 until n).filter { total[it] > SILENCE_DB }
    if (audible.isEmpty()) return PhrasePlan(cuts, List(n) { Section.INTRO }, List(n) { null })
    val loudest = audible.maxOf { low[it] }
    val plateau = audible.map { low[it] }.filter { it >= loudest - 2.0 }.average()
    // Agudo do patamar: o das frases que estão no patamar de grave.
    val high = DoubleArray(n) { mean(f.highDb, it) }
    val plateauHigh = audible.filter { low[it] >= plateau - 2.0 }.map { high[it] }.sorted().let { it[it.size / 2] }
    val kinds = Array(n) { i ->
        when {
            total[i] <= SILENCE_DB -> "Silence"
            // Metade da potência do patamar (−3 dB) já é descida: a regra das marcas do corpus ("cerca de metade").
            low[i] <= plateau - 3.5 -> "Down"
            low[i] >= plateau - 2.0 -> "Chorus"
            else -> "Up"
        }
    }
    val firstChorus = kinds.indexOfFirst { it == "Chorus" }
    val lastChorus = kinds.indexOfLast { it == "Chorus" }
    // Breakdown que guarda o grave mas tira o agudo (pratos, hats), entre o primeiro e o último Chorus: também é Down.
    // Na abertura isso é só a Intro sem pratos.
    for (i in firstChorus + 1 until lastChorus) if (kinds[i] == "Chorus" && high[i] <= plateauHigh - 6.0) kinds[i] = "Down"
    if (firstChorus > 0) {
        for (i in 0 until firstChorus) kinds[i] = Section.INTRO
        // O build: a frase antes do primeiro Chorus que já saiu do grave da abertura.
        val pre = firstChorus - 1
        if (pre >= 1 && low[pre] > low[0] + 2.0) {
            var start = pre
            while (start - 1 >= 1 && low[start - 1] > low[0] + 2.0) start -= 1
            for (i in start..pre) kinds[i] = "Up"
        }
    }
    if (lastChorus >= 0) for (i in lastChorus + 1 until n) if (kinds[i] != "Silence") kinds[i] = Section.OUTRO
    // A faixa fecha com Outro, como no rekordbox: se a última frase de som ainda é Chorus e curta (até 16 compassos),
    // ela vira o Outro.
    val lastSound = (0 until n).lastOrNull { kinds[it] != "Silence" }
    if (lastSound != null && lastSound > 0 && kinds[lastSound] == "Chorus" && cuts[lastSound + 1] - cuts[lastSound] <= 16) {
        kinds[lastSound] = Section.OUTRO
    }
    for (i in 0 until n) if (kinds[i] == "Silence") kinds[i] = if (i == 0) Section.INTRO else if (i > lastChorus) Section.OUTRO else "Down"
    // Fill-in: o último compasso da frase pulsa bem menos que a frase (o bumbo sai antes da frase seguinte).
    val fills = (0 until n).map { i ->
        val last = cuts[i + 1] - 1
        if (last <= cuts[i] || last >= f.bars) null
        else if (kick[i] >= 0.35 && f.kick[last] < kick[i] * 0.4) last else null
    }
    return PhrasePlan(cuts, kinds.toList(), fills)
}

/** Frases em ms e os pontos de estrutura (Up antes do primeiro Chorus, primeiro Chorus, primeiro Down depois, Outro). */
internal fun phraseSections(samples: FloatArray, rate: Int, bpm: Double, originSec: Double): SectionRead? {
    val f = barFeatures(samples, rate, bpm, originSec) ?: return null
    if (f.bars < 8) return null
    val plan = labelPhrases(f, refineCuts(f, phraseCuts(f)))
    val duration = samples.size.toDouble() / rate
    fun sec(bar: Int): Double = (f.originSec + bar * f.barSec).coerceIn(0.0, duration)
    val spans = ArrayList<PhraseSpan>()
    for (i in 0 until plan.kinds.size) {
        val start = if (i == 0) 0.0 else sec(plan.cuts[i])
        val end = if (i == plan.kinds.size - 1) duration else sec(plan.cuts[i + 1])
        if (end <= start) continue
        val kind = plan.kinds[i]
        val fill = plan.fillBars[i]?.let { (sec(it) * 1000).toInt() }
        spans += PhraseSpan((start * 1000).toInt(), (end * 1000).toInt(), kind, kind, "high", fill)
    }
    val firstChorus = spans.firstOrNull { it.kind == "Chorus" }
    val dropSec = (firstChorus?.startMs ?: 0) / 1000.0
    val upBefore = spans.lastOrNull { it.kind == "Up" && it.startMs < (firstChorus?.startMs ?: Int.MAX_VALUE) }
    val buildSec = upBefore?.startMs?.div(1000.0) ?: max(0.0, dropSec - 8 * f.barSec)
    val breakSec = spans.firstOrNull { it.kind == "Down" && it.startMs > (firstChorus?.startMs ?: -1) }?.startMs?.div(1000.0) ?: dropSec
    val outroSec = spans.firstOrNull { it.kind == Section.OUTRO }?.startMs?.div(1000.0) ?: duration
    return SectionRead(StructurePoints(0.0, buildSec, dropSec, breakSec, outroSec), spans)
}
