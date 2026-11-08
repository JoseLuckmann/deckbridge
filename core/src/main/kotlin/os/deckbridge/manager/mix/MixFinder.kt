package os.deckbridge.manager.mix

import kotlin.math.abs
import os.deckbridge.logic.Section
import os.deckbridge.manager.pdb.TrackItem

/** Uma forma de fazer a troca, com a nota (0–99) e o porquê em frases curtas. */
data class MixPlan(val transition: Transition, val score: Int, val title: String, val reasons: List<String>)

/**
 * Procura a troca entre duas faixas como um DJ procura: ouvindo onde cada uma tem grave, bateria e melodia, compasso a
 * compasso ([TrackProfile]). Testa saídas e entradas em começos de frase, durações de 8 a 64 compassos, o compasso em
 * que o grave vira e loops (repetir o fim da que sai quando o outro é curto; repetir a intro da que entra para o drop dela
 * cair quando a outra termina de sair, ou para segurar uma batida clara). Pontua cada uma pelo que um DJ evita e procura
 * (ver `docs/ux-mixagem.md`, "Achar a troca"):
 *
 * - nunca dois graves juntos e nunca um buraco sem grave: o grave vira onde um sai (break, outro) ou o outro entra (drop);
 * - quem segura o grave segura a batida (bateria clara de quem está com o grave);
 * - duas melodias juntas só se os tons combinam; com tons longe, junta só onde uma delas é só bateria;
 * - saída e entrada em começo de frase, e o drop da que entra caindo quando a que sai acaba;
 * - não queimar o drop da que entra embaixo da outra, nem sair cedo demais da que toca;
 * - quando nada casa longo (tons longe, andamentos longe, nada limpo), eco, corte ou filtro, curtos e na frase.
 *
 * Regras de DJ como as das entrevistas de Zehren, Alunno e Bientinesi (2020/2022) e de Vande Veire e De Bie (2018);
 * casar seções pelo equilíbrio de espectro e pela harmonia, como no AutoMashUpper (Davies et al. 2014).
 */
object MixFinder {
    private val LENGTHS = listOf(8, 16, 24, 32, 48, 64)
    private val A_LOOPS = listOf(0, 4, 8)
    private val B_LOOPS = listOf(0 to 0, 4 to 2, 8 to 2, 4 to 4)

    /** Uma candidata e o que se sabe dela para pontuar e explicar. */
    private class Cand(
        val style: MixStyle, val out: Int, val inn: Int, val len: Int, val aLoop: Int, val bLoop: Int, val bTimes: Int,
        val swap: Int, val score: Double, val why: List<String>,
    )

    fun find(
        a: TrackItem, pa: TrackProfile, b: TrackItem, pb: TrackProfile,
        ga: TrackGrid? = null, gb: TrackGrid? = null, limit: Int = 4,
    ): List<MixPlan> {
        val fit = SetPlanner.fit(a, b)
        val keyDist = fit.keyDistance
        val tempoFar = abs(fit.bpmDiffPct) > SetTempo.COMFORT_PCT
        val all = ArrayList<Cand>()
        val outs = outBars(pa)
        val ins = inBars(pb)
        val firstDropB = pb.phrases.firstOrNull { it.kind == "Chorus" }?.start
        for (out in outs) for (inn in ins) {
            for (len in LENGTHS) for (aLoop in A_LOOPS) for ((bLoop, bTimes) in B_LOOPS) {
                swapCandidate(pa, pb, out, inn, len, aLoop, bLoop, bTimes, keyDist, tempoFar, firstDropB)?.let { all += it }
            }
            // Para o que não casa longo: eco ou corte na frase, e filtro.
            shortCandidates(pa, pb, out, inn, keyDist, tempoFar, firstDropB).forEach { all += it }
        }
        if (all.isEmpty()) return emptyList()
        // As melhores, com variedade: no máximo uma por estilo e duração parecida.
        val picked = ArrayList<Cand>()
        for (c in all.sortedByDescending { it.score }) {
            if (picked.any { it.style == c.style && abs(it.len - c.len) < 8 && it.aLoop == c.aLoop && it.bLoop == c.bLoop }) continue
            picked += c
            if (picked.size >= limit) break
        }
        return picked.map { c -> plan(a, pa, b, pb, ga, gb, c) }
    }

    /** Saídas: começos de frase e de bloco de 8 na segunda metade da que sai. */
    private fun outBars(p: TrackProfile): List<Int> {
        val from = maxOf(8, (p.bars * 0.45).toInt())
        val to = p.bars - 4
        val phraseStarts = p.phrases.map { it.start }.filter { it in from..to }
        val grid = (0..to step 8).filter { it >= from }
        return (phraseStarts + grid).distinct().filter { p.msOf(it) >= 0 }.sorted()
    }

    /** Entradas: o começo e os começos de frase e de bloco de 8 na primeira metade da que entra. */
    private fun inBars(p: TrackProfile): List<Int> {
        val to = minOf((p.bars * 0.5).toInt(), 64)
        val phraseStarts = p.phrases.map { it.start }.filter { it in 0..to }
        val grid = (0..to step 8).toList()
        return (listOf(0) + phraseStarts + grid).distinct().filter { p.msOf(it) >= 0 }.sorted()
    }

    private fun keyWeight(d: Int?) = when {
        d == null -> 0.6
        d == 0 -> 0.1
        d == 1 -> 0.25
        else -> 1.2
    }

    private fun swapCandidate(
        pa: TrackProfile, pb: TrackProfile, out: Int, inn: Int, len: Int, aLoop: Int, bLoop: Int, bTimes: Int,
        keyDist: Int?, tempoFar: Boolean, firstDropB: Int?,
    ): Cand? {
        // Loops só onde fazem sentido e cabem.
        // Loop na que sai só quando o fim dela não dá a troca inteira.
        if (aLoop > 0 && (aLoop >= len || out + aLoop > pa.bars || out + len <= pa.bars)) return null
        if (aLoop == 0 && out + len > pa.bars) return null
        if (bLoop > 0 && bLoop * bTimes > len) return null
        val extra = if (bLoop > 0) bLoop * (bTimes - 1) else 0
        if (inn + len - extra + 8 > pb.bars) return null
        if (aLoop > 0 && (out until out + aLoop).any { !pa.drums(it) }) return null
        if (bLoop > 0 && (inn until inn + bLoop).any { !pb.drums(it) }) return null
        fun aBar(j: Int) = if (aLoop > 0) out + j % aLoop else out + j
        fun bBar(j: Int) = if (bLoop > 0 && j < bLoop * bTimes) inn + j % bLoop else inn + j - extra
        val aB = BooleanArray(len) { pa.bass(aBar(it)) }
        val aD = BooleanArray(len) { pa.drums(aBar(it)) }
        val aM = BooleanArray(len) { pa.melodic(aBar(it)) }
        val bB = BooleanArray(len) { pb.bass(bBar(it)) }
        val bD = BooleanArray(len) { pb.drums(bBar(it)) }
        val bM = BooleanArray(len) { pb.melodic(bBar(it)) }
        val landing = bBar(len)
        // Loop na que entra só para o drop dela cair quando a outra acaba (sem loop ele cairia fora da hora).
        if (bLoop > 0 && (firstDropB == null || landing != firstDropB)) return null
        // O compasso do grave: o melhor de 4 em 4 (e o fim).
        var bestSwap = len
        var bestBass = Double.NEGATIVE_INFINITY
        val reasonsBass = HashMap<Int, String>()
        for (s in (4..len step 4)) {
            var holes = 0
            for (j in 0 until s) if (!aB[j] && bB[j]) holes++
            for (j in s until len) if (!bB[j] && aB[j]) holes++
            var natural = 0.0
            var why = ""
            if (s < len && bB[s] && !bB[s - 1]) { natural += 3.0; why = "o grave vira no comp. ${s + 1} da troca, onde o grave da que entra começa" }
            if (s < len && aB[s - 1] && !aB[s]) { natural += 3.0; why = "o grave vira no comp. ${s + 1} da troca, onde a que sai tira o grave (break)" }
            if (s == len && pb.bass(landing) && !bB[len - 1]) { natural += 2.0; why = "o grave vira no fim, junto com o grave da que entra" }
            // Grave das duas onde ninguém tem: tanto faz; o ponto no meio-fim é o clássico.
            val center = -abs(s - len * 0.6) / len
            val phrase = if (pb.phrases.any { it.start == bBar(s) }) 0.5 else 0.0
            val v = natural - holes * 0.4 + center + phrase
            if (v > bestBass) { bestBass = v; bestSwap = s; if (why.isNotEmpty()) reasonsBass[s] = why }
        }
        val s = bestSwap
        var score = bestBass
        val why = ArrayList<String>()
        reasonsBass[s]?.let { why += it }
        // Quem tem o grave segura a batida.
        var lostGrip = 0
        for (j in s until len) if (!bD[j]) lostGrip++
        var aGrip = 0
        for (j in 0 until s) if (!aD[j]) aGrip++
        score -= lostGrip * 0.6 + aGrip * 0.3
        var both = 0
        for (j in 0 until len) if (aD[j] && bD[j]) both++
        val clarity = both.toDouble() / len
        score += both * 0.15
        // Melodias juntas: o peso depende de os tons combinarem.
        var clash = 0
        for (j in 0 until len) if (aM[j] && bM[j]) clash++
        score -= clash * keyWeight(keyDist)
        if (clash == 0 && (keyDist ?: 2) >= 2) why += "tons longe: as duas só se encontram onde uma é só bateria"
        // Frases: saída, entrada e o drop da que entra quando a outra acaba.
        val aPhrase = pa.phrases.firstOrNull { it.start == out }
        if (aPhrase != null) score += 3.0 else if ((out - (pa.phrases.firstOrNull()?.start ?: 0)) % 8 == 0) score += 1.0
        when (pa.phraseAt(out)?.kind) {
            Section.OUTRO -> { score += 2.0; why += "sai no Outro da que toca (comp. ${out + 1})" }
            "Down" -> { score += 1.5; why += "sai no break da que toca (comp. ${out + 1})" }
            "Chorus" -> score -= 0.5
        }
        if (pb.phrases.any { it.start == inn }) score += 2.0
        if (inn == 0) score += 1.0
        val landPhrase = pb.phrases.firstOrNull { it.start == landing }
        if (landPhrase?.kind == "Chorus") { score += 5.0; why += "o drop da que entra cai quando a outra termina de sair" }
        else if (landPhrase != null) score += 2.0
        if (firstDropB != null && firstDropB > inn && firstDropB < landing - 4) score -= 2.0
        if (firstDropB != null && inn >= firstDropB) score -= 4.0
        // Não sair cedo demais da que toca.
        if (out < pa.bars * 0.6) score -= (pa.bars * 0.6 - out) / 8.0
        val lost = pa.bars - (out + if (aLoop > 0) aLoop else len)
        if (lost > 16) score -= (lost - 16) / 16.0
        // Longo é bom quando dá para ouvir as duas batidas; com tons longe ou andamentos longe, curto.
        val keyOk = (keyDist ?: 2) <= 1
        score += len / 16.0 * clarity * (if (keyOk) 1.0 else 0.3)
        if (tempoFar) score -= len / 8.0
        // Depois do grave virar, a que sai só acompanha: mais de 16 compassos assim é arrastar a troca.
        if (len - s > 16) score -= (len - s - 16) / 4.0
        // Loop custa um pouco (é mais trabalho na hora e cansa se repete muito), mas abre o que não caberia.
        if (aLoop > 0) { score -= 1.5 + 0.25 * (out + len - pa.bars) / aLoop; why += "loop de $aLoop compassos na que sai: o fim dela é curto para a troca" }
        if (bLoop > 0) { score -= 1.5; why += "loop de $bLoop compassos da intro da que entra ($bTimes×): segura a batida até o drop cair na hora" }
        val beatsB = (0 until len).count { pb.beatsOnly(bBar(it)) }
        if (beatsB >= len * 0.6 && len >= 16) why += "a intro da que entra é só bateria: dá para mixar longo"
        return Cand(MixStyle.BASS_SWAP, out, inn, len, aLoop, bLoop, bTimes, s, score, why)
    }

    /** Eco na frase, corte seco no drop e filtro: para quando os tons, os andamentos ou as partes não casam longo. */
    private fun shortCandidates(pa: TrackProfile, pb: TrackProfile, out: Int, inn: Int, keyDist: Int?, tempoFar: Boolean, firstDropB: Int?): List<Cand> {
        val out2 = ArrayList<Cand>()
        val atPhrase = pa.phrases.any { it.start == out }
        if (!atPhrase || out + 8 > pa.bars) return out2
        val clashy = (keyDist ?: 2) >= 2 || tempoFar
        // Eco: a que sai para num eco no começo da frase e a que entra sobe; melhor ainda se ela entra no drop.
        run {
            val len = 8
            if (inn + len + 8 > pb.bars) return@run
            var score = 4.0 + (if (clashy) 4.0 else 0.0)
            val why = ArrayList<String>()
            why += "eco na que sai no começo da frase (comp. ${out + 1})"
            if (inn == firstDropB) { score += 4.0; why += "a que entra já entra no drop" }
            if (pb.drums(inn)) score += 1.0
            if (clashy) why += if (tempoFar) "andamentos longe: troca curta" else "tons longe: troca curta, sem as duas melodias juntas"
            if (out < pa.bars * 0.6) score -= (pa.bars * 0.6 - out) / 8.0
            out2 += Cand(MixStyle.ECHO, out, inn, len, 0, 0, 0, len, score, why)
        }
        // Corte seco: no 1 do compasso, a outra entra no drop dela.
        if (firstDropB != null && inn == firstDropB && pb.bars > inn + 16) {
            var score = 3.0 + (if (clashy) 4.5 else 0.0)
            if (pa.phraseAt(out - 1)?.end == out) score += 1.0
            if (out < pa.bars * 0.6) score -= (pa.bars * 0.6 - out) / 8.0
            out2 += Cand(MixStyle.CUT, out, inn, 4, 0, 0, 0, 4, score, listOf("corte seco no 1 do compasso ${out + 1}, direto no drop da que entra"))
        }
        // Filtro: a que sai some num passa-alta em 16 compassos enquanto a outra sobe.
        run {
            val len = 16
            if (out + len > pa.bars || inn + len + 8 > pb.bars) return@run
            var clash = 0
            for (j in 0 until len) if (pa.melodic(out + j) && pb.melodic(inn + j)) clash++
            var score = 2.0 - clash * keyWeight(keyDist) * 0.5 + (if (pb.drums(inn)) 1.0 else 0.0)
            if (pb.phrases.firstOrNull { it.start == inn + len }?.kind == "Chorus") score += 3.0
            if (out < pa.bars * 0.6) score -= (pa.bars * 0.6 - out) / 8.0
            out2 += Cand(MixStyle.FILTER, out, inn, len, 0, 0, 0, len * 3 / 4, score, listOf("a que sai some num filtro passa-alta enquanto a outra sobe"))
        }
        return out2
    }

    private fun plan(a: TrackItem, pa: TrackProfile, b: TrackItem, pb: TrackProfile, ga: TrackGrid?, gb: TrackGrid?, c: Cand): MixPlan {
        val outMs = SetPlanner.snap(ga, pa.msOf(c.out).coerceAtLeast(0))
        val inMs = SetPlanner.snap(gb, pb.msOf(c.inn).coerceAtLeast(0))
        val pace = when {
            c.len <= 8 -> Transition.Pace.FAST
            c.len >= 48 -> Transition.Pace.SLOW
            else -> Transition.Pace.NORMAL
        }
        val swapAt = (c.swap.toDouble() / c.len).coerceIn(0.0, 1.0)
        val why = c.why.distinct().take(5)
        val t = Transition(
            a.id, b.id, outMs, inMs, c.len, c.style, pace = pace, swapAt = swapAt,
            aLoopBars = c.aLoop, bLoopBars = c.bLoop, bLoopTimes = c.bTimes, why = why,
        )
        val title = buildString {
            append(c.style.title)
            append(" · ${c.len} comp.")
            if (c.aLoop > 0) append(" · loop ${c.aLoop} na que sai")
            if (c.bLoop > 0) append(" · loop ${c.bLoop}×${c.bTimes} na que entra")
        }
        val score = (25 + c.score * 3).toInt().coerceIn(1, 99)
        return MixPlan(t, score, title, why)
    }
}
