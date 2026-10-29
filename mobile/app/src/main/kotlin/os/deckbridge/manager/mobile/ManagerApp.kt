package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

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

/**
 * Os quatro passos do trabalho, na ordem: trazer as músicas (e analisar), ouvir e dar nota, juntar numa playlist e
 * levar para o pendrive. É o que o guia mostra e o que a fileira de baixo destaca.
 */
enum class GuideStep(val label: String) {
    IMPORT("Importar"), RATE("Marcar"), PLAYLIST("Playlist"), USB("Pendrive");

    fun done(app: ManagerApp, v: os.deckbridge.manager.pdb.LibraryView): Boolean = when (this) {
        IMPORT -> v.tracks.isNotEmpty() && v.tracks.all { app.model.hasAnalysis(it) }
        RATE -> v.tracks.isNotEmpty() && v.tracks.count { it.rating == 0 } <= v.tracks.size / 2
        PLAYLIST -> v.playlists.any { !it.folder && v.entries[it.id].orEmpty().isNotEmpty() }
        USB -> app.exportedOnce
    }

    /** A área do app onde este passo se faz. */
    val tab: Tab? get() = when (this) {
        IMPORT -> Tab.LIBRARY
        RATE -> null
        PLAYLIST -> Tab.PLAYLISTS
        USB -> Tab.USB
    }
}

/** Quanto um aviso fica na tela antes de sumir sozinho. */
const val NOTICE_MS = 3_500L

enum class Tab(val title: String) { LIBRARY("Biblioteca"), PLAYLISTS("Playlists"), USB("Pendrive"), MENU("Menu") }

/** Um trabalho longo em andamento (importar, analisar, exportar). A tela mostra o texto e o botão de cancelar. */
class Job(val title: String) : StudioJob {
    override var text by mutableStateOf("")
    @Volatile override var cancelled = false
}

/** O pendrive escolhido e o que há nele. */
class UsbChoice(val tree: Uri, val label: String, val storage: Storage, val existing: TargetLibrary?, val problem: String?)

/**
 * Estado do app inteiro. A biblioteca mora numa pasta do app, no aparelho, no mesmo formato de um pendrive do rekordbox;
 * o pendrive só é tocado quando o usuário manda exportar (ou trazer). Tudo que é pesado roda fora da thread de interface.
 */
class ManagerApp(val context: Context, override val root: File = defaultRoot(context)) : StudioHost {
    val backupsDir = File(root.parentFile, "backups")
    // A aba FAIXAS é a biblioteca toda, mesmo com uma playlist aberta na aba PLAYLISTS.
    override val model = ManagerModel(Backups(backupsDir)).also { it.scopeToOpenPlaylist = false }

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
    private var shownNotice by mutableStateOf<Banner?>(null)
    private val noticeTimer = android.os.Handler(android.os.Looper.getMainLooper())

    /** O aviso de baixo. Some sozinho em [NOTICE_MS] (um erro fica até o OK). */
    override var notice: Banner?
        get() = shownNotice
        set(value) {
            shownNotice = value
            noticeTimer.removeCallbacksAndMessages(null)
            if (value != null && !value.error) noticeTimer.postDelayed({ if (shownNotice === value) shownNotice = null }, NOTICE_MS)
        }

    /** Verdadeiro quando o aviso atual veio de uma mudança que o botão "Desfazer" do aviso desfaz. */
    var noticeUndo by mutableStateOf(false)

    // Triagem: passar pelas faixas uma a uma marcando (nota, cor, playlist alvo).
    override var triage by mutableStateOf<List<Int>?>(null)
    override var triageAt by mutableStateOf(0)

    // "Arrumar tudo": sugestões por regra.
    var tidy by mutableStateOf<List<Suggestion>?>(null)

    /** Pedido do MENU para abrir a ajuda de busca na Biblioteca. */
    var helpRequested by mutableStateOf(false)

    override val preview = Preview(this)
    override val sets = SetDesk(this)
    override val setsDir get() = File(root.parentFile, "sets")
    override val cacheDir: File get() = context.cacheDir

    override fun share(title: String, text: String) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title).putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(send, title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // A biblioteca é do aparelho: gravar é sempre na hora.
    override fun saveNow(progress: (String) -> Unit): Boolean {
        model.apply(null, progress) ?: throw IllegalStateException(model.banner?.text ?: "Não consegui salvar")
        pruneBackups()
        return true
    }
    /** "Remover animações" ligado no Android (escala de animação zero). */
    override val reduceMotion: Boolean
        get() = runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

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

    init {
        KeyStyle.musical = prefs.getBoolean("tomMusical", false)
    }

    /** Duração das transições automáticas: null é pela estrutura (até o drop da que entra; 32 sem dados), ou fixa. */
    override var transitionBars by mutableStateOf(prefs.getInt("transicao", 0).takeIf { it > 0 })
        private set

    /** Passa para a próxima opção de duração: Auto → 16 → 32 → 64 → Auto. */
    override fun nextTransitionBars() {
        transitionBars = when (transitionBars) {
            null -> 16
            16 -> 32
            32 -> 64
            else -> null
        }
        prefs.edit().putInt("transicao", transitionBars ?: 0).apply()
    }

    /** O SET em lista (a tela antiga, com tudo por extenso) em vez do quadro. */
    override var setAsList by mutableStateOf(prefs.getBoolean("setLista", false))
        private set

    override fun setListView(on: Boolean) {
        setAsList = on
        prefs.edit().putBoolean("setLista", on).apply()
    }

    /** Tom em nota musical (Am) em vez de Camelot (8A), em todo o app. */
    fun setMusicalKeys(on: Boolean) {
        KeyStyle.musical = on
        prefs.edit().putBoolean("tomMusical", on).apply()
    }

    fun hideGuide() {
        guideHidden = true
        prefs.edit().putBoolean("guiaOculto", true).apply()
    }

    fun showGuide() {
        guideHidden = false
        coachSeen = emptySet()
        prefs.edit().putBoolean("guiaOculto", false).putStringSet("visto", emptySet()).apply()
    }

    /** Os destaques que a pessoa já usou (cada um aparece até ser usado uma vez). */
    private var coachSeen by mutableStateOf(prefs.getStringSet("visto", emptySet())!!.toSet())

    /** Destacar o controle [key]: o guia está ligado e a pessoa ainda não o usou. */
    override fun hint(key: String): Boolean = !guideHidden && key !in coachSeen

    /** A pessoa usou o controle [key]: o destaque dele some de vez. */
    override fun seen(key: String) {
        if (key in coachSeen) return
        coachSeen = coachSeen + key
        prefs.edit().putStringSet("visto", coachSeen).apply()
    }

    /** O passo do caminho (importar → marcar → playlist → pendrive) em que a pessoa está, ou null se já fez tudo. */
    fun step(): GuideStep? {
        val v = model.view ?: return GuideStep.IMPORT
        return GuideStep.values().firstOrNull { !it.done(this, v) }
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
    override fun did(text: String?) {
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

    /** Marcar as que faltam (sem nota). [review]: rever as já marcadas, por escolha. */
    fun startTriage(review: Boolean = false) {
        val v = model.view ?: return
        val ids = if (review) Smart.reviewQueue(v) else Smart.triageQueue(v)
        if (ids.isEmpty()) return did(if (v.tracks.isEmpty()) "Importe músicas primeiro" else if (review) "Nenhuma marcada ainda" else "Tudo marcado ✓ · para rever, MENU → Rever as marcadas")
        triage = ids
        triageAt = 0
        detailId = null
    }

    override fun endTriage() {
        triage = null
        preview.stop()
    }

    // --- Arrumar tudo --------------------------------------------------------------------------

    fun openTidy() {
        val v = model.view ?: return
        // As repetidas têm tela própria (escolher qual fica e juntar as playlists): aqui ficam só os nomes e campos.
        tidy = Organizer.suggest(v) { model.hasAnalysis(it) }.filter { it.edit != null && it.rule != os.deckbridge.manager.organize.Rule.DUPLICATE }
    }

    // --- Repetidas -----------------------------------------------------------------------------

    /** As impressões digitais do áudio de cada faixa (fora da biblioteca, na pasta do app). */
    val prints = os.deckbridge.manager.organize.PrintStore(File(root.parentFile, "digitais")) { t -> File(root, t.filePath.replace('\\', '/').trimStart('/')) }

    /** Os grupos de repetidas achados (a tela de repetidas está aberta enquanto não é null). */
    var duplicates by mutableStateOf<List<os.deckbridge.manager.organize.Duplicates.Group>?>(null)

    /**
     * Procura a mesma música entrando mais de uma vez: ouve (impressão digital) só as faixas que têm outra de duração
     * parecida, numa tarefa de fundo, e abre a tela com os grupos.
     */
    fun findDuplicates() {
        val v = model.view ?: return
        background("Procurando repetidas") { j ->
            val byDur = v.tracks.sortedBy { it.durationSeconds }
            val near = byDur.filterIndexed { i, t ->
                (i > 0 && t.durationSeconds - byDur[i - 1].durationSeconds <= 8) || (i < byDur.lastIndex && byDur[i + 1].durationSeconds - t.durationSeconds <= 8)
            }
            for ((n, t) in near.withIndex()) {
                if (j.cancelled) throw Cancelled()
                j.text = "Ouvindo ${n + 1}/${near.size}: ${t.title}"
                prints.get(t)
            }
            j.text = "Comparando"
            val inLists = v.tracks.associate { t -> t.id to v.entries.count { t.id in it.value } }
            val groups = os.deckbridge.manager.organize.Duplicates.find(v, { prints.cached(it) }, { inLists[it.id] ?: 0 })
            onMain {
                if (groups.isEmpty()) say("Nenhuma repetida ✓") else duplicates = groups
            }
        }
    }

    /** Junta o grupo em [keep] (playlists, nota, cor) e tira as outras da biblioteca. Um desfazer volta tudo. */
    fun mergeDuplicates(g: os.deckbridge.manager.organize.Duplicates.Group, keep: TrackItem) {
        val v = model.view ?: return
        val rest = g.tracks.filter { it.id != keep.id }
        val n = model.submitAll(os.deckbridge.manager.organize.Duplicates.merge(v, keep, rest))
        duplicates = duplicates?.filter { it !== g }?.takeIf { it.isNotEmpty() }
        if (detailId in rest.map { it.id }) detailId = null
        if (n > 0) say("Juntou \"${keep.title}\" (${rest.size} repetida${if (rest.size == 1) "" else "s"} fora)", undo = true)
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

    /**
     * Refaz as grades de todas as faixas analisadas com o analisador atual (só a grade: cues, ondas e tom ficam). As que
     * já estão iguais não mudam; o tempo 1 de cada compasso é mantido quando a grade só se desloca. Grava em lotes, cada
     * um com cópia de segurança, como "Analisar todas".
     */
    fun refreshGrids() {
        val v = model.view ?: return
        val todo = v.tracks.filter { model.hasAnalysis(it) }
        if (todo.isEmpty()) {
            say("Nenhuma faixa analisada")
            return
        }
        background("Refazendo ${todo.size} grade(s)") { j ->
            var changed = 0
            var same = 0
            var failed = 0
            val edits = ArrayList<Pair<Edit, String>>()
            fun flush() {
                if (edits.isEmpty()) return
                model.submitAll(edits)
                edits.clear()
                model.apply(null) { j.text = it }
            }
            for ((n, t) in todo.withIndex()) {
                if (j.cancelled) break
                j.text = "${n + 1}/${todo.size}: ${t.title}"
                val fresh = os.deckbridge.manager.organize.GridRefresh.analyze(root, t)
                if (fresh == null) {
                    failed++
                    continue
                }
                val c = os.deckbridge.manager.organize.GridRefresh.change(t, model.trackFiles(t).beats, fresh.first, fresh.second)
                val e = c.edit
                if (e == null) same++ else {
                    edits += e to "Refazer grade de \"${t.title}\""
                    changed++
                }
                if (edits.size >= 20) flush()
            }
            flush()
            pruneBackups()
            sets.forgetGrids()
            say(listOfNotNull("$changed refeitas", "$same já certas".takeIf { same > 0 }, "$failed sem áudio".takeIf { failed > 0 }).joinToString(" · "))
        }
    }

    /** Último resultado do teste de áudio (MENU), para conferir no próprio aparelho. */
    var audioCheckResult by mutableStateOf(prefs.getString("testeAudio", "") ?: "")
        private set

    /**
     * Teste de áudio no aparelho: lê 30 s de uma música da biblioteca pelo decodificador do Android e monta uma transição
     * com Master Tempo, medindo o tempo de cada parte. É o que os testes de JVM não alcançam (não há MediaCodec neles).
     */
    fun audioCheck() {
        val v = model.view ?: return
        fun fileOf(t: TrackItem) = File(root, t.filePath.replace('\\', '/').trimStart('/'))
        val tracks = v.tracks.filter { fileOf(it).isFile }.sortedByDescending { it.bpm > 0 }.take(2)
        if (tracks.isEmpty()) {
            say("Nenhuma música no aparelho", error = true)
            return
        }
        background("Teste de áudio") { j ->
            val a = tracks[0]
            val b = tracks.getOrElse(1) { a }
            val fa = fileOf(a)
            j.text = fa.name
            val t0 = System.nanoTime()
            val pcm = os.deckbridge.manager.importer.AudioIo.segment(fa, a.durationSeconds * 1000 / 3, 30_000)
                ?: throw ExportException("E_AUDIO", "Não consegui ler ${fa.extension.uppercase()} neste aparelho")
            val read = (System.nanoTime() - t0) / 1e9
            j.text = "Montando transição"
            val ga = sets.grid(a)
            val tr = os.deckbridge.manager.mix.Transition(a.id, b.id, os.deckbridge.manager.mix.SetPlanner.defaultOut(a, ga, 16), os.deckbridge.manager.mix.SetPlanner.defaultIn(sets.grid(b)), 16)
            val t1 = System.nanoTime()
            val reader = { t: TrackItem -> os.deckbridge.manager.mix.SegmentReader { s0, len -> os.deckbridge.manager.importer.AudioIo.segment(fileOf(t), s0, len) } }
            os.deckbridge.manager.mix.MixRenderer.transition(reader(a), a.bpm, reader(b), b.bpm, tr, waves = true)
                ?: throw ExportException("E_AUDIO", "Não consegui montar a transição")
            val mix = (System.nanoTime() - t1) / 1e9
            val result = "%s %d Hz · ler 30 s: %.1f s · transição: %.1f s".format(fa.extension.uppercase(), pcm.sampleRate, read, mix)
            prefs.edit().putString("testeAudio", result).apply()
            android.os.Handler(android.os.Looper.getMainLooper()).post { audioCheckResult = result }
            say("Áudio ok · $result")
        }
    }

    /** Roda [block] numa thread própria, com [Job] visível. Erros viram aviso; nada derruba o app. */
    override fun background(title: String, block: (StudioJob) -> Unit) {
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
                say(friendlyError(e), error = true)
            } finally {
                job = null
            }
        }, "deckbridge-job").start()
    }

    /** Dentro de uma tarefa que vai reler a biblioteca: grava antes o que está na fila (nada se perde). */
    private fun saveFirst(j: StudioJob) {
        if (!model.dirty) return
        j.text = "Gravando"
        if (model.apply(null) { j.text = it } == null) throw ExportException("E_SAVE", model.banner?.text ?: "Não consegui gravar.")
        pruneBackups()
    }

    /**
     * Grava sozinho, sem tela de espera nem aviso: a fila vai para a biblioteca do aparelho numa thread de fundo e a tela
     * fica onde está (dá para continuar mexendo). Chamado depois de um tempo sem mudanças e ao sair do app. Na thread da tela.
     */
    fun autosave() {
        if (job != null) return
        val queued = model.beginQuiet() ?: return
        Thread({
            val done = model.writeQuiet(queued)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                model.finishQuiet(done)
                if (done != null) pruneBackups() else say(model.banner?.text ?: "Não consegui gravar.", error = true)
            }
        }, "deckbridge-gravar").start()
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

    /**
     * Vai para o modo DJ: grava a fila antes (o processo da biblioteca é encerrado na troca) e abre o DJ já com a
     * biblioteca do aparelho. Com importação rodando, espera ela acabar (não perde faixa no meio).
     */
    fun goDj(activity: android.app.Activity) {
        if (imports.progress != null) {
            say("Espere a importação terminar para ir ao modo DJ.")
            return
        }
        preview.stop()
        if (!model.dirty) {
            Modes.switchTo(activity, Modes.Mode.DJ)
            return
        }
        background("Gravando antes do modo DJ") { j ->
            if (model.apply(null) { j.text = it } == null) throw IllegalStateException(model.banner?.text ?: "Não consegui gravar; o modo DJ não abriu.")
            pruneBackups()
            android.os.Handler(android.os.Looper.getMainLooper()).post { Modes.switchTo(activity, Modes.Mode.DJ) }
        }
    }

    internal fun pruneBackups(keep: Int = 15) {
        val list = model.backupList().sortedByDescending { it.created }
        list.drop(keep).forEach { runCatching { it.dir.deleteRecursively() } }
    }

    // --- Importar áudio ------------------------------------------------------------------------

    /** A importação em segundo plano (a tela fica livre enquanto ela roda). */
    val imports = ImportQueue(this)

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        imports.add { uris.mapIndexed { n, u -> u to nameOf(u, n) } }
    }

    /**
     * Importa todos os áudios de uma pasta (e subpastas) escolhida no seletor. A varredura pede os filhos de cada pasta
     * de uma vez (nome e tipo numa consulta só), o que é bem mais rápido que perguntar arquivo por arquivo.
     */
    fun importFolder(tree: Uri) {
        imports.add {
            val found = ArrayList<Pair<Uri, String>>()
            fun walk(docId: String) {
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cols = arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                val dirs = ArrayList<String>()
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) dirs += id
                        else if (os.deckbridge.manager.volume.isAudio(name)) found += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
                dirs.forEach { walk(it) }
            }
            walk(android.provider.DocumentsContract.getTreeDocumentId(tree))
            found.sortedBy { it.second.lowercase() }
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
        background("Juntando") { j ->
            saveFirst(j)
            val r = os.deckbridge.manager.transfer.UsbMerger(model.backups, context.cacheDir).merge(choice.storage, root, progress = { j.text = it }, cancelled = { j.cancelled })
            model.open(root)
            pruneBackups()
            say(listOfNotNull("${r.added} trazidas", r.alreadyThere.takeIf { it > 0 }?.let { "$it já estavam" }, r.failed.takeIf { it > 0 }?.let { "$it falharam" }).joinToString(" · "))
        }
    }

    fun importFromUsb(includeAudio: Boolean) {
        val choice = usb ?: return
        background("Trazendo do pendrive") { j ->
            saveFirst(j)
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

        fun defaultRoot(context: Context): File = Modes.libraryRoot(context)
    }
}
