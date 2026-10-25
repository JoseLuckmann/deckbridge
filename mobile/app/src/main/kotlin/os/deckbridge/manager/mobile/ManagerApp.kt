package os.deckbridge.manager.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import os.deckbridge.manager.Banner
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.organize.OnDemand
import os.deckbridge.manager.organize.Organizer
import os.deckbridge.manager.organize.Suggestion
import os.deckbridge.manager.smart.Smart
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.transfer.Cancelled
import os.deckbridge.manager.transfer.ExportException
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.transfer.Storage
import os.deckbridge.manager.transfer.TargetLibrary
import os.deckbridge.manager.transfer.TransferReport
import os.deckbridge.manager.transfer.UsbExporter
import os.deckbridge.manager.transfer.UsbImporter
import os.deckbridge.manager.volume.Backups

enum class Tab(val title: String) { LIBRARY("Biblioteca"), PLAYLISTS("Playlists"), USB("Pendrive"), MENU("Menu") }

/** Um trabalho longo em andamento (importar, analisar, exportar). A tela mostra o texto e o botão de cancelar. */
class Job(val title: String) {
    var text by mutableStateOf("")
    @Volatile var cancelled = false
}

/** O pendrive escolhido e o que há nele. */
class UsbChoice(val tree: Uri, val label: String, val storage: Storage, val existing: TargetLibrary?, val problem: String?)

/**
 * Estado do app inteiro. A biblioteca mora numa pasta do app, no aparelho, no mesmo formato de um pendrive do rekordbox;
 * o pendrive só é tocado quando o usuário manda exportar (ou trazer). Tudo que é pesado roda fora da thread de interface.
 */
class ManagerApp(val context: Context, val root: File = defaultRoot(context)) {
    val backupsDir = File(root.parentFile, "backups")
    val model = ManagerModel(Backups(backupsDir))

    var tab by mutableStateOf(Tab.LIBRARY)
    var detailId by mutableStateOf<Int?>(null)
    var searchOpen by mutableStateOf(false)

    /** Pedido vindo de fora (atalho do ícone ou "compartilhar"), tratado assim que a biblioteca abre. */
    var pendingIntent by mutableStateOf<android.content.Intent?>(null)
    var detailPage by mutableStateOf(DetailPage.MARK)
    var selecting by mutableStateOf(false)
    var job by mutableStateOf<Job?>(null)
    var usb by mutableStateOf<UsbChoice?>(null)
    var lastTransfer by mutableStateOf<TransferReport?>(null)
    var notice by mutableStateOf<Banner?>(null)

    /** Verdadeiro quando o aviso atual veio de uma mudança que o botão "Desfazer" do aviso desfaz. */
    var noticeUndo by mutableStateOf(false)

    // Triagem: passar pelas faixas uma a uma marcando (nota, cor, playlist alvo).
    var triage by mutableStateOf<List<Int>?>(null)
    var triageAt by mutableStateOf(0)

    // "Arrumar tudo": sugestões por regra.
    var tidy by mutableStateOf<List<Suggestion>?>(null)

    /** Pedido do MENU para abrir a ajuda de busca na Biblioteca. */
    var helpRequested by mutableStateOf(false)

    val preview = Preview(this)

    private val prefs = context.getSharedPreferences("biblioteca", Context.MODE_PRIVATE)
    var guideHidden by mutableStateOf(prefs.getBoolean("guiaOculto", false))
        private set
    var exportedOnce by mutableStateOf(prefs.getBoolean("exportou", false))
        private set

    /** Atalho do ícone ou "compartilhar": faz o que o pedido diz. [pickAudio] abre o seletor de músicas. */
    fun handle(intent: android.content.Intent, pickAudio: () -> Unit) {
        when (intent.action) {
            android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE -> intent.streamUris().takeIf { it.isNotEmpty() }?.let { importAudio(it) }
            ACTION_TRIAGEM -> startTriage()
            ACTION_IMPORTAR -> pickAudio()
            ACTION_EXPORTAR -> tab = Tab.USB
        }
    }

    fun hideGuide() {
        guideHidden = true
        prefs.edit().putBoolean("guiaOculto", true).apply()
    }

    fun showGuide() {
        guideHidden = false
        prefs.edit().putBoolean("guiaOculto", false).apply()
    }

    init {
        AudioIo.platform = MediaDecoder()
    }

    fun start() {
        LocalLibrary.ensure(root)
        model.open(root)
    }

    private fun say(text: String, error: Boolean = false, undo: Boolean = false) {
        notice = Banner(text, error)
        noticeUndo = undo
    }

    /** Avisa o que uma ação rápida fez, com "Desfazer" no aviso. */
    fun did(text: String?) {
        if (text != null) say(text, undo = true)
    }

    fun undoFromNotice() {
        model.undo()
        notice = null
        noticeUndo = false
    }

    // --- Triagem -------------------------------------------------------------------------------

    private val waves = HashMap<String, ByteArray>()

    /** Prévia da onda de uma faixa (do ANLZ), com cache. Vazio se a faixa não tem análise legível. */
    fun waveFor(t: TrackItem): ByteArray = synchronized(waves) {
        waves.getOrPut(t.analyzePath + "|" + model.hasAnalysis(t)) { runCatching { model.trackFiles(t).wave }.getOrDefault(ByteArray(0)) }
    }

    fun startTriage() {
        val v = model.view ?: return
        val ids = Smart.triageQueue(v)
        if (ids.isEmpty()) return
        triage = ids
        triageAt = 0
        detailId = null
    }

    fun endTriage() {
        triage = null
        preview.stop()
    }

    // --- Arrumar tudo --------------------------------------------------------------------------

    fun openTidy() {
        val v = model.view ?: return
        tidy = Organizer.suggest(v) { model.hasAnalysis(it) }.filter { it.edit != null }
    }

    fun applyTidy(chosen: List<Suggestion>) {
        tidy = null
        val n = model.submitAll(chosen.mapNotNull { s -> s.edit?.let { it to "${s.rule.title}: ${s.title}" } })
        if (n > 0) say("$n ajustes", undo = true)
    }

    // --- Analisar todas ------------------------------------------------------------------------

    fun analyzeAll() {
        val v = model.view ?: return
        val todo = v.tracks.filter { !model.hasAnalysis(it) }
        if (todo.isEmpty()) {
            say("Tudo analisado")
            return
        }
        background("Analisando ${todo.size} faixa(s)") { j ->
            var done = 0
            var failed = 0
            val edits = ArrayList<Pair<Edit, String>>()
            for ((n, t) in todo.withIndex()) {
                if (j.cancelled) break
                j.text = "${n + 1}/${todo.size}: ${t.title}"
                val out = OnDemand.analyze(root, t)
                val sug = OnDemand.suggestion(out, hasAnalysis = false, force = false)
                if (sug?.edit != null) {
                    edits += sug.edit!! to "Analisar \"${t.title}\""
                    done++
                } else failed++
                // Em lotes: segura poucas análises na memória e já guarda no aparelho.
                if (edits.size >= 6) {
                    model.submitAll(edits)
                    edits.clear()
                    model.apply(null) { j.text = it }
                }
            }
            if (edits.isNotEmpty()) {
                model.submitAll(edits)
                model.apply(null) { j.text = it }
            }
            pruneBackups()
            say("$done analisadas" + (if (failed > 0) " · $failed falharam" else ""), error = done == 0 && failed > 0)
        }
    }

    /** Roda [block] numa thread própria, com [Job] visível. Erros viram aviso; nada derruba o app. */
    fun background(title: String, block: (Job) -> Unit) {
        if (job != null) return
        val j = Job(title)
        job = j
        Thread({
            try {
                block(j)
            } catch (_: Cancelled) {
                say("Cancelado")
            } catch (e: ExportException) {
                say(e.message ?: e.code, error = true)
            } catch (e: Exception) {
                say("Falhou: ${e.message ?: e.javaClass.simpleName}", error = true)
            } finally {
                job = null
            }
        }, "deckbridge-job").start()
    }

    /** Grava as mudanças da fila na biblioteca local (com cópia de segurança). */
    fun save(thenRun: (() -> Unit)? = null) {
        if (!model.dirty) {
            thenRun?.invoke()
            return
        }
        background("Salvando") { j ->
            val report = model.apply(null) { j.text = it }
            if (report != null) {
                pruneBackups()
                say("Salvo ✓")
            } else {
                say(model.banner?.text ?: "Não consegui salvar.", error = true)
            }
        }
    }

    /** Salva sem abrir tarefa (ao sair do app). Bloqueante. */
    fun saveNow() {
        if (model.dirty && job == null) model.apply(null)
    }

    private fun pruneBackups(keep: Int = 15) {
        val list = model.backupList().sortedByDescending { it.created }
        list.drop(keep).forEach { runCatching { it.dir.deleteRecursively() } }
    }

    // --- Importar áudio ------------------------------------------------------------------------

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        background("Importando") { j -> importInto(j, uris.mapIndexed { n, u -> u to nameOf(u, n) }) }
    }

    /** Importa todos os áudios de uma pasta (e subpastas) escolhida no seletor. */
    fun importFolder(tree: Uri) {
        background("Importando pasta") { j ->
            j.text = "Procurando músicas"
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree) ?: throw ExportException("E_SAF_ROOT", "Pasta ilegível")
            val found = ArrayList<Pair<Uri, String>>()
            fun walk(dir: androidx.documentfile.provider.DocumentFile) {
                for (f in dir.listFiles()) {
                    if (j.cancelled) throw Cancelled()
                    val name = f.name ?: continue
                    if (f.isDirectory) walk(f) else if (os.deckbridge.manager.volume.isAudio(name)) found += f.uri to name
                }
            }
            walk(root)
            if (found.isEmpty()) say("Nenhuma música na pasta", error = true) else importInto(j, found.sortedBy { it.second.lowercase() })
        }
    }

    /**
     * Copia, confere se já existe, analisa e grava em lotes de 6 (grava no aparelho e solta as análises da memória antes
     * de seguir). Música que já está na biblioteca (mesmo título, artista e duração) é pulada.
     */
    private fun importInto(j: Job, items: List<Pair<Uri, String>>) {
        val cache = File(context.cacheDir, "importar").apply { deleteRecursively(); mkdirs() }
        val edits = ArrayList<Pair<Edit, String>>()
        val seen = HashSet<String>()
        var failed = 0
        var imported = 0
        var dups = 0
        val firstError = StringBuilder()
        fun flush() {
            if (edits.isEmpty()) return
            val ok = model.submitAll(edits)
            imported += ok
            if (ok < edits.size) {
                failed += edits.size - ok
                if (firstError.isEmpty()) firstError.append(model.banner?.text.orEmpty())
            }
            edits.clear()
            model.apply(null) { j.text = it }
        }
        try {
            items.forEachIndexed { n, (uri, name) ->
                if (j.cancelled) throw Cancelled()
                j.text = "${n + 1}/${items.size} · $name"
                val copy = File(cache, "%03d-%s".format(n, name.replace(Regex("[\\\\/:*?\"<>|]"), "_")))
                try {
                    context.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
                    val info = os.deckbridge.manager.importer.AudioIo.probe(copy)
                    val key = "${info.title.lowercase().trim()}|${info.artist.lowercase().trim()}"
                    val v = model.view
                    if ((v != null && Smart.findDuplicate(v, info.title, info.artist, info.durationSec) != null) || !seen.add(key)) {
                        dups++
                        copy.delete()
                        return@forEachIndexed
                    }
                    val p = ImportPrep.prepare(copy)
                    edits += Edit.ImportTrack(p, p.info.title, p.info.artist, p.info.album, p.info.genre, p.read.keyName, p.info.year, p.info.trackNumber, p.suggestedCues) to "Importar \"${p.info.title}\""
                    if (edits.size >= 6) {
                        flush()
                        copy.parentFile?.listFiles()?.forEach { f -> if (f != copy) f.delete() }
                    }
                } catch (e: Cancelled) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    if (firstError.isEmpty()) firstError.append("$name: ${e.message}")
                }
            }
            flush()
            pruneBackups()
            val parts = buildList {
                if (imported > 0) add("$imported importadas")
                if (dups > 0) add("$dups já estavam")
                if (failed > 0) add("$failed falharam")
            }
            if (imported == 0 && dups == 0) say("Não importou: $firstError", error = true) else say(parts.joinToString(" · "))
        } finally {
            cache.deleteRecursively()
        }
    }

    /**
     * Nome do arquivo de um endereço (seletor ou "compartilhar"). Sem nome, usa o fim do caminho; sem extensão, deduz
     * pelo tipo do arquivo, porque a importação precisa saber o formato.
     */
    private fun nameOf(uri: Uri, n: Int): String {
        val base = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "faixa-$n"
        if (base.substringAfterLast('.', "").length in 2..4) return base
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val ext = type?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "mp3"
        return "$base.$ext"
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    // --- Análise -------------------------------------------------------------------------------

    fun analyze(track: TrackItem, force: Boolean) {
        background("Analisando \"${track.title}\"") { j ->
            j.text = "Lendo o áudio e achando batidas, tom e estrutura"
            val outcome = OnDemand.analyze(root, track)
            if (outcome.error != null) {
                say(outcome.error!!, error = true)
                return@background
            }
            val sug = OnDemand.suggestion(outcome, model.hasAnalysis(track), force)
            if (sug?.edit == null) {
                say("Já analisada")
            } else if (model.submit(sug.edit!!, "Analisar \"${track.title}\"")) {
                say("${"%.1f".format(outcome.prepared!!.read.bpm)} BPM · ${outcome.prepared!!.read.keyName}", undo = true)
            }
        }
    }

    // --- Pendrive ------------------------------------------------------------------------------

    fun chooseUsb(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        background("Lendo o pendrive") { _ ->
            val label = SafStorage.labelOf(tree)
            val storage = SafStorage(context, tree, label)
            usb = try {
                UsbChoice(tree, label, storage, UsbExporter(backupsDir).inspect(storage), null)
            } catch (e: ExportException) {
                UsbChoice(tree, label, storage, null, e.message)
            }
        }
    }

    /** Exporta; se houver mudanças na fila, grava antes no aparelho, na mesma tarefa. */
    fun export() {
        val choice = usb ?: return
        background("Exportando") { j ->
            if (model.dirty) {
                j.text = "Salvando"
                if (model.apply(null) { j.text = it } == null) throw ExportException("E_SAVE", model.banner?.text ?: "Não consegui salvar.")
                pruneBackups()
            }
            val report = UsbExporter(backupsDir).export(root, choice.storage, replaceExisting = true, progress = { j.text = it }, cancelled = { j.cancelled })
            lastTransfer = report
            exportedOnce = true
            prefs.edit().putBoolean("exportou", true).apply()
            usb = UsbChoice(choice.tree, choice.label, choice.storage, UsbExporter(backupsDir).inspect(choice.storage), null)
            say("Exportado ✓")
        }
    }

    /** Junta as faixas do pendrive às do aparelho, sem apagar nada; repetidas são puladas. */
    fun mergeFromUsb() {
        val choice = usb ?: return
        if (model.dirty) {
            say("Salve antes", error = true)
            return
        }
        background("Juntando") { j ->
            val r = os.deckbridge.manager.transfer.UsbMerger(model.backups, context.cacheDir).merge(choice.storage, root, progress = { j.text = it }, cancelled = { j.cancelled })
            model.open(root)
            pruneBackups()
            say(listOfNotNull("${r.added} trazidas", r.alreadyThere.takeIf { it > 0 }?.let { "$it já estavam" }, r.failed.takeIf { it > 0 }?.let { "$it falharam" }).joinToString(" · "))
        }
    }

    fun importFromUsb(includeAudio: Boolean) {
        val choice = usb ?: return
        if (model.dirty) {
            say("Salve antes", error = true)
            return
        }
        background("Trazendo do pendrive") { j ->
            val report = UsbImporter(backupsDir).import(choice.storage, root, includeAudio, progress = { j.text = it }, cancelled = { j.cancelled })
            lastTransfer = report
            model.open(root)
            say("${report.tracks} faixas trazidas ✓")
        }
    }

    companion object {
        @Volatile private var instance: ManagerApp? = null

        /**
         * Um só estado por processo. Se a tela é destruída e recriada com o processo vivo, ela reencontra o mesmo
         * [ManagerApp] (e o mesmo player), em vez de criar outro e deixar o antigo tocando sem controle.
         */
        /** Só para testes: cada teste do Robolectric tem um processo "novo", e o estado não deve passar de um para outro. */
        internal fun resetForTests() {
            instance?.preview?.stop()
            instance = null
        }

        fun get(context: Context): ManagerApp = instance ?: synchronized(this) {
            instance ?: ManagerApp(context.applicationContext).also { instance = it }
        }

        fun defaultRoot(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "biblioteca/dados")
    }
}
