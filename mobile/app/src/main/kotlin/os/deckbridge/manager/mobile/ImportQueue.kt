package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.smart.Smart

/** Como vai a importação: quantas no total, quantas já passaram e o que aconteceu com elas. */
data class ImportProgress(val total: Int, val done: Int, val imported: Int, val dups: Int, val failed: Int, val current: String)

/**
 * Importação em segundo plano: a tela continua livre (dá para marcar, montar playlists e ouvir enquanto importa). Uma
 * thread copia os arquivos e entrega cada um para [WORKERS] análises em paralelo; cada música aparece na lista assim que
 * fica pronta, e a fila é gravada aos poucos pela gravação silenciosa (sem reabrir a tela). Um arquivo com problema (ou
 * que estoura a memória) conta como falha e a importação segue. Pedidos novos durante a importação entram na mesma fila.
 */
class ImportQueue(private val app: ManagerApp) {
    var progress by mutableStateOf<ImportProgress?>(null)
        private set

    val running: Boolean get() = progress != null

    private val waiting = LinkedBlockingQueue<() -> List<Pair<Uri, String>>>()
    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Para a importação: o que já entrou fica; o resto não é importado. */
    fun cancel() {
        cancelled = true
    }

    /** Põe na fila os arquivos que [load] encontra (numa thread de fundo: pode varrer uma pasta). */
    fun add(load: () -> List<Pair<Uri, String>>) {
        waiting.put(load)
        // Já aparece como "importando" (a contagem chega quando a lista de arquivos estiver pronta).
        if (progress == null) progress = ImportProgress(0, 0, 0, 0, 0, "")
        synchronized(this) {
            if (worker?.isAlive == true) return
            cancelled = false
            worker = Thread({ run() }, "deckbridge-importar").apply { start() }
        }
    }

    private fun onMain(block: () -> Unit) = main.post(block)

    /** Roda [block] na thread da tela e espera o resultado (a thread de fundo nunca mexe no modelo direto). */
    private fun <T> onMainSync(block: () -> T): T {
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            out = runCatching(block)
            done.countDown()
        }
        done.await()
        return out!!.getOrThrow()
    }

    private fun run() {
        val total = AtomicInteger()
        val finished = AtomicInteger()
        val imported = AtomicInteger()
        val dups = AtomicInteger()
        val failed = AtomicInteger()
        var firstError = ""
        var current = ""
        fun publish() {
            val p = ImportProgress(total.get(), finished.get(), imported.get(), dups.get(), failed.get(), current)
            onMain { progress = p }
        }
        val cache = File(app.context.cacheDir, "importar").apply { deleteRecursively(); mkdirs() }
        val pool = Executors.newFixedThreadPool(WORKERS)
        // No máximo uma cópia esperando por análise além das que estão sendo analisadas (o cache não cresce à toa).
        val slots = Semaphore(WORKERS + 1)
        // O que foi para a fila do modelo e de qual arquivo do cache veio: depois de gravado, o arquivo some.
        val submitted = java.util.Collections.synchronizedList(ArrayList<Pair<Edit, File>>())
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // As impressões digitais das que entraram nesta importação (duração, andamento e impressão).
        val prints = ArrayList<Triple<Int, Double, os.deckbridge.logic.AudioPrint>>()
        var sinceSave = 0
        var lastSave = System.currentTimeMillis()

        /** Grava o que está na fila (silenciosa) e apaga do cache o que já foi para a biblioteca. */
        fun save() {
            val queued = onMainSync { app.model.beginQuiet() } ?: return
            val done = app.model.writeQuiet(queued)
            onMainSync {
                app.model.finishQuiet(done)
                if (done != null) app.pruneBackups()
                val pending = app.model.pending.map { it.edit }
                synchronized(submitted) {
                    val gone = submitted.filter { (e, _) -> pending.none { it === e } }
                    gone.forEach { (_, f) -> f.delete() }
                    submitted.removeAll(gone)
                }
            }
            sinceSave = 0
            lastSave = System.currentTimeMillis()
        }

        try {
            var n = 0
            while (!cancelled) {
                val load = waiting.poll() ?: break
                val items = runCatching(load).getOrElse { emptyList() }
                if (items.isEmpty()) {
                    onMain { app.notice = os.deckbridge.manager.Banner("Nenhuma música encontrada", true) }
                    continue
                }
                total.addAndGet(items.size)
                publish()
                for ((uri, name) in items) {
                    if (cancelled) break
                    slots.acquire()
                    current = name
                    publish()
                    // Cada cópia numa pasta numerada, com o nome original: o título lido do nome do arquivo não ganha o
                    // número da fila na frente (até a 0.24.0 ganhava: "0003-Artista_-_Título").
                    val copy = File(File(cache, "%04d".format(n++)).apply { mkdirs() }, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                    try {
                        app.context.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        if (firstError.isEmpty()) firstError = "$name: ${e.message}"
                        finished.incrementAndGet()
                        slots.release()
                        publish()
                        continue
                    }
                    pool.execute {
                        try {
                            if (cancelled) return@execute
                            val info = os.deckbridge.manager.importer.AudioIo.probe(copy)
                            val key = os.deckbridge.manager.organize.TrackNames.key(os.deckbridge.manager.organize.TrackNames.Names(info.title, info.artist)) + "|" + info.durationSec / 3
                            val v = app.model.view
                            if ((v != null && Smart.findDuplicate(v, info.title, info.artist, info.durationSec) != null) || !seen.add(key)) {
                                dups.incrementAndGet()
                                copy.delete()
                                return@execute
                            }
                            val p = ImportPrep.prepare(copy)
                            // A mesma música com outro nome: o áudio bate com uma da biblioteca ou desta importação. Pular é
                            // sozinho, então só com tudo batendo: áudio, duração (até 3 s) e andamento (até 0,5 BPM).
                            val print = p.print
                            if (print != null) {
                                val bpm = p.read.bpm
                                val inBatch = synchronized(prints) {
                                    prints.any { (d, b, other) -> kotlin.math.abs(d - p.info.durationSec) <= 3 && kotlin.math.abs(b - bpm) <= 0.5 && os.deckbridge.logic.AudioPrint.distance(print, other) < os.deckbridge.logic.AudioPrint.SAME }
                                }
                                val near = v?.tracks.orEmpty().filter { kotlin.math.abs(it.bpm - bpm) <= 0.5 }
                                if (inBatch || app.prints.sameAs(print, p.info.durationSec, near, toleranceSec = 3) != null) {
                                    dups.incrementAndGet()
                                    copy.delete()
                                    return@execute
                                }
                                synchronized(prints) { prints += Triple(p.info.durationSec, bpm, print) }
                            }
                            val edit = Edit.ImportTrack(p, p.info.title, p.info.artist, p.info.album, p.genre, p.read.keyName, p.info.year, p.info.trackNumber, p.suggestedCues, p.comment, p.myTags)
                            // A música entra na lista agora (na fila do modelo); a gravação vem depois, em lote.
                            val ok = onMainSync { app.model.submit(edit, "Importar \"${p.info.title}\"") }
                            if (ok) {
                                submitted += edit to copy
                                imported.incrementAndGet()
                            } else {
                                failed.incrementAndGet()
                                copy.delete()
                            }
                        } catch (e: Throwable) {
                            // Inclui falta de memória numa faixa: ela falha, as outras seguem.
                            failed.incrementAndGet()
                            if (firstError.isEmpty()) firstError = "${copy.name.substringAfter('-')}: ${e.message ?: e.javaClass.simpleName}"
                            copy.delete()
                        } finally {
                            finished.incrementAndGet()
                            slots.release()
                            publish()
                        }
                    }
                    sinceSave++
                    if (sinceSave >= SAVE_EVERY || System.currentTimeMillis() - lastSave > SAVE_MS) save()
                }
            }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.HOURS)
            save()
            // Uma gravação automática pode ter começado antes: espera e grava o que sobrou.
            while (onMainSync { app.model.saving }) Thread.sleep(100)
            save()
        } catch (e: Throwable) {
            if (firstError.isEmpty()) firstError = e.message ?: e.javaClass.simpleName
        } finally {
            pool.shutdownNow()
            cache.deleteRecursively()
            waiting.clear()
            val parts = buildList {
                if (imported.get() > 0) add("${imported.get()} importadas")
                if (dups.get() > 0) add("${dups.get()} já estavam")
                if (failed.get() > 0) add("${failed.get()} falharam")
                if (cancelled) add("parou")
            }
            val error = imported.get() == 0 && dups.get() == 0 && !cancelled
            onMain {
                progress = null
                app.notice = os.deckbridge.manager.Banner(if (error) "Não importou: $firstError" else parts.joinToString(" · "), error)
            }
        }
    }

    private companion object {
        /**
         * Análises ao mesmo tempo (decodificar e analisar é o que pesa: ~150 MB por faixa longa). Duas só quando a memória
         * que o Android dá ao app tem folga para elas e para o resto (a prévia do MIX tocando junto); senão, uma.
         */
        val WORKERS = if (Runtime.getRuntime().maxMemory() >= 400L * 1024 * 1024) 2 else 1

        /** Grava a cada tantas músicas ou tanto tempo (gravar a biblioteca inteira a cada uma seria lento). */
        const val SAVE_EVERY = 4
        const val SAVE_MS = 15_000L
    }
}
