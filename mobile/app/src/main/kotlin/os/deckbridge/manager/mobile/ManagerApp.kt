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

enum class Tab(val title: String) { LIBRARY("Biblioteca"), PLAYLISTS("Playlists"), USB("Pendrive") }

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
    var selecting by mutableStateOf(false)
    var job by mutableStateOf<Job?>(null)
    var usb by mutableStateOf<UsbChoice?>(null)
    var lastTransfer by mutableStateOf<TransferReport?>(null)
    var notice by mutableStateOf<Banner?>(null)

    init {
        AudioIo.platform = MediaDecoder()
    }

    fun start() {
        LocalLibrary.ensure(root)
        model.open(root)
    }

    private fun say(text: String, error: Boolean = false) {
        notice = Banner(text, error)
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
                say("Cancelado.")
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
                say("Salvo no aparelho.")
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
        background("Importando ${uris.size} faixa(s)") { j ->
            val cache = File(context.cacheDir, "importar").apply { deleteRecursively(); mkdirs() }
            val edits = ArrayList<Pair<Edit, String>>()
            var failed = 0
            val firstError = StringBuilder()
            try {
                uris.forEachIndexed { n, uri ->
                    if (j.cancelled) throw Cancelled()
                    val name = displayName(uri) ?: "faixa-$n"
                    j.text = "Lendo e analisando ${n + 1}/${uris.size}: $name"
                    val copy = File(cache, "%03d-%s".format(n, name.replace(Regex("[\\\\/:*?\"<>|]"), "_")))
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
                        val p = ImportPrep.prepare(copy)
                        edits += Edit.ImportTrack(p, p.info.title, p.info.artist, p.info.album, p.info.genre, p.read.keyName, p.info.year, p.info.trackNumber, p.suggestedCues) to "Importar \"${p.info.title}\""
                    } catch (e: Exception) {
                        failed++
                        if (firstError.isEmpty()) firstError.append("$name: ${e.message}")
                    }
                }
                if (edits.isNotEmpty()) {
                    val ok = model.submitAll(edits)
                    j.text = "Gravando no aparelho"
                    model.apply(null) { j.text = it }
                    pruneBackups()
                    say("$ok faixa(s) importada(s)." + if (failed > 0) " $failed falhou: $firstError" else "", error = ok == 0)
                } else {
                    say("Nada importado. $firstError", error = true)
                }
            } finally {
                cache.deleteRecursively()
            }
        }
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
                say("A faixa já tem análise. Use \"Reanalisar\" para trocar pela do app.")
            } else if (model.submit(sug.edit!!, "Analisar \"${track.title}\"")) {
                say("Análise pronta: ${"%.1f".format(outcome.prepared!!.read.bpm)} BPM, tom ${outcome.prepared!!.read.keyName}. Salve para gravar.")
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

    fun export() {
        val choice = usb ?: return
        if (model.dirty) {
            say("Salve as mudanças antes de exportar.", error = true)
            return
        }
        background("Exportando para o pendrive") { j ->
            val report = UsbExporter(backupsDir).export(root, choice.storage, replaceExisting = true, progress = { j.text = it }, cancelled = { j.cancelled })
            lastTransfer = report
            usb = UsbChoice(choice.tree, choice.label, choice.storage, UsbExporter(backupsDir).inspect(choice.storage), null)
            say("Exportado: ${report.tracks} faixas, ${report.copied} arquivo(s) copiado(s).")
        }
    }

    fun importFromUsb(includeAudio: Boolean) {
        val choice = usb ?: return
        if (model.dirty) {
            say("Salve ou descarte as mudanças antes de trazer outra biblioteca.", error = true)
            return
        }
        background("Trazendo do pendrive") { j ->
            val report = UsbImporter(backupsDir).import(choice.storage, root, includeAudio, progress = { j.text = it }, cancelled = { j.cancelled })
            lastTransfer = report
            model.open(root)
            say("Trouxe ${report.tracks} faixas do pendrive. A biblioteca anterior do aparelho foi guardada em backup.")
        }
    }

    companion object {
        fun defaultRoot(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "biblioteca/dados")
    }
}
