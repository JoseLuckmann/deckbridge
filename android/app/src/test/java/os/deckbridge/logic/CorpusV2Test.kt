package os.deckbridge.logic

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Mede tempo e tom contra o corpus GiantSteps completo (tools/fetch_corpus_v2.py). Sem o corpus, o teste é ignorado.
 * A divisão treino/holdout é fixa (sha1 do id módulo 5): só o treino entra na escolha de parâmetros.
 */
class CorpusV2Test {
    private data class Item(val id: String, val family: String, val bpm: Double?, val key: String?, val holdout: Boolean)
    private data class Result(val item: Item, val bpm: Double, val key: String)

    private val root = File("/opt/cursor/artifacts/ground-truth-v2")

    private fun items(): List<Item> {
        val manifest = File(root, "manifest.tsv")
        if (!manifest.isFile) return emptyList()
        return manifest.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@mapNotNull null
            val wav = File(root, "mono/${parts[0]}.wav")
            if (!wav.isFile) return@mapNotNull null
            Item(parts[0], parts[1], parts[2].toDoubleOrNull(), parts[3].takeIf { it.isNotBlank() }, parts[4] == "holdout")
        }
    }

    private fun run(all: List<Item>, wantTempo: Boolean, wantKey: Boolean): List<Result> {
        val pool = Executors.newFixedThreadPool(4)
        val futures = all.map { item ->
            pool.submit<Result> {
                val pcm = decodeWavMono(File(root, "mono/${item.id}.wav").readBytes()) ?: error(item.id)
                val bpm = if (wantTempo && item.bpm != null) estimateTempo(pcm.samples, pcm.rate) else 0.0
                val key = if (wantKey && item.key != null) detectKey(pcm.samples, pcm.rate).first else ""
                Result(item, bpm, key)
            }
        }
        val out = futures.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        return out
    }

    private fun acc1(truth: Double, pred: Double) = truth > 0 && abs(pred - truth) / truth <= 0.04
    private fun acc2(truth: Double, pred: Double): Boolean {
        if (acc1(truth, pred)) return true
        return listOf(2.0, 0.5, 3.0, 1.0 / 3.0).any { acc1(truth, pred * it) }
    }

    private fun fmt(x: Double) = "%.3f".format(x)

    @Test
    fun tempoAndKeyMetrics() {
        // Pesado (uns 12 min com o tom): só roda com CORPUS=1 e com o corpus baixado.
        assumeTrue(System.getenv("CORPUS") == "1")
        val all = items()
        assumeTrue(all.size >= 200)
        // CORPUS_KEY=0 pula o tom (a parte lenta) quando só o andamento mudou.
        val results = run(all, wantTempo = true, wantKey = System.getenv("CORPUS_KEY") != "0")
        val text = buildString {
            for (scope in listOf("train", "holdout", "all")) {
                val subset = results.filter { scope == "all" || (scope == "holdout") == it.item.holdout }
                val tempo = subset.filter { it.item.bpm != null }
                val keys = subset.filter { it.item.key != null }
                appendLine("$scope tempo n=${tempo.size} tight1pct=${fmt(tempo.count { abs(it.bpm - it.item.bpm!!) / it.item.bpm!! <= 0.01 }.toDouble() / tempo.size)} acc1=${fmt(tempo.count { acc1(it.item.bpm!!, it.bpm) }.toDouble() / tempo.size)} acc2=${fmt(tempo.count { acc2(it.item.bpm!!, it.bpm) }.toDouble() / tempo.size)}")
                val weights = keys.filter { it.key.isNotEmpty() }.map { mirexWeight(it.key, normalizeKeyName(it.item.key!!)) }
                if (weights.isNotEmpty()) appendLine("$scope key n=${weights.size} mirex=${fmt(weights.average())} exact=${fmt(weights.count { it == 1.0 }.toDouble() / weights.size)}")
                tempo.groupBy { it.item.family }.toSortedMap().forEach { (name, group) ->
                    appendLine("  $scope fam $name n=${group.size} acc1=${fmt(group.count { acc1(it.item.bpm!!, it.bpm) }.toDouble() / group.size)} acc2=${fmt(group.count { acc2(it.item.bpm!!, it.bpm) }.toDouble() / group.size)}")
                }
            }
            results.filter { it.item.bpm != null && !acc2(it.item.bpm!!, it.bpm) }.forEach {
                appendLine("miss ${it.item.id} ${it.item.family} ficha ${it.item.bpm} app ${"%.1f".format(it.bpm)} ${if (it.item.holdout) "holdout" else "train"}")
            }
        }
        File(root, "metrics-v2.txt").writeText(text)
        println(text)
        val rows = results.filter { it.item.bpm != null }
        val acc1 = rows.count { acc1(it.item.bpm!!, it.bpm) }.toDouble() / rows.size
        val acc2 = rows.count { acc2(it.item.bpm!!, it.bpm) }.toDouble() / rows.size
        // Pisos abaixo do medido (todas as faixas: 0,868 e 0,955; holdout 0,894 e 0,947).
        assertTrue("acc1 $acc1", acc1 >= 0.82)
        assertTrue("acc2 $acc2", acc2 >= 0.92)
        val keyed = results.filter { it.key.isNotEmpty() }
        if (keyed.isNotEmpty()) {
            val mirex = keyed.map { mirexWeight(it.key, normalizeKeyName(it.item.key!!)) }.average()
            assertTrue("mirex $mirex", mirex >= 0.58)
        }
    }
}
