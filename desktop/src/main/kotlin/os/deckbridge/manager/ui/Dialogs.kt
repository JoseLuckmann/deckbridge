package os.deckbridge.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.concurrent.thread
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.importer.ImportException
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.importer.PreparedTrack
import os.deckbridge.manager.organize.OnDemand
import os.deckbridge.manager.organize.Organizer
import os.deckbridge.manager.organize.Rule
import os.deckbridge.manager.organize.Suggestion
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.volume.isAudio

// ---- importar ------------------------------------------------------------------------------------------------

class ImportRow(val file: File) {
    var status by mutableStateOf("lendo e analisando…")
    var prepared by mutableStateOf<PreparedTrack?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var album by mutableStateOf("")
    var genre by mutableStateOf("")
    var on by mutableStateOf(true)
}

/** Lê e analisa os arquivos um de cada vez, em segundo plano. Nada é escrito no pendrive aqui. */
fun startImport(files: List<File>): List<ImportRow> {
    val rows = files.filter { isAudio(it.name) }.map { ImportRow(it) }
    thread(name = "importar-analise", isDaemon = true) {
        for (row in rows) {
            try {
                val p = ImportPrep.prepare(row.file)
                row.title = p.info.title
                row.artist = p.info.artist
                row.album = p.info.album
                row.genre = p.genre
                // O texto antes do "pronto": quem vê a linha pronta já vê o BPM.
                row.status = "%.1f BPM · %s · %d:%02d".format(p.read.bpm, p.read.keyName, p.info.durationSec / 60, p.info.durationSec % 60)
                row.prepared = p
            } catch (e: ImportException) {
                row.status = e.message ?: "erro"
                row.on = false
            } catch (e: Exception) {
                row.status = "erro: ${e.message}"
                row.on = false
            }
        }
    }
    return rows
}

@Composable
fun ImportModal(model: ManagerModel, rows: List<ImportRow>, onClose: () -> Unit) {
    val target = model.openPlaylist?.let { model.playlist(it) }?.takeIf { !it.folder }
    var intoPlaylist by remember { mutableStateOf(true) }
    Modal("Importar áudio para o pendrive", 1000.dp, onClose) {
        if (!AudioIo.ffmpeg) Label("ffmpeg não encontrado: só WAV é lido. Instale o ffmpeg para importar MP3, FLAC e AAC.", Pal.Orange, 12.sp, maxLines = 2)
        Label("Cada faixa é copiada para o pendrive (Contents/Artista/Álbum), analisada pelo analisador do app (BPM, tom, grade, ondas, cues sugeridos) e entra na fila. O arquivo original não é tocado.", Pal.Muted, 12.sp, maxLines = 3)
        Column(Modifier.fillMaxWidth().height(300.dp).background(Pal.Bg).border(1.dp, Pal.Line).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) Label("Nenhum arquivo de áudio escolhido.", Pal.Muted, 13.sp)
            rows.forEach { r ->
                val ready = r.prepared != null
                val dup = ready && model.view?.tracks?.any { it.title.equals(r.title, true) && it.artist.equals(r.artist, true) } == true
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label(if (r.on) "☑" else "☐", if (ready) Pal.Blue else Pal.Muted, 16.sp, modifier = Modifier.clickable(enabled = ready) { r.on = !r.on })
                        Label(r.file.name, Pal.Ink, 12.sp, bold = true, modifier = Modifier.width(300.dp))
                        Label(r.status, if (ready) Pal.Green else if (r.status.startsWith("lendo")) Pal.Orange else Pal.Red, 12.sp, modifier = Modifier.weight(1f))
                        if (dup) Label("parece já estar na biblioteca", Pal.Orange, 11.sp)
                    }
                    if (ready) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextBox(r.title, { r.title = it }, Modifier.weight(1.4f), placeholder = "Título")
                        TextBox(r.artist, { r.artist = it }, Modifier.weight(1f), placeholder = "Artista")
                        TextBox(r.album, { r.album = it }, Modifier.weight(1f), placeholder = "Álbum")
                        TextBox(r.genre, { r.genre = it }, Modifier.width(120.dp), placeholder = "Gênero")
                    }
                }
            }
        }
        val chosen = rows.filter { it.on && it.prepared != null && it.title.isNotBlank() }
        if (target != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(if (intoPlaylist) "☑" else "☐", Pal.Blue, 16.sp, modifier = Modifier.clickable { intoPlaylist = !intoPlaylist })
            Label("Adicionar também à playlist aberta: ${target.name}", Pal.Ink, 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Adicionar ${chosen.size} à fila", {
                for (r in chosen) {
                    val p = r.prepared!!
                    val edit = Edit.ImportTrack(p, r.title.trim(), r.artist.trim(), r.album.trim(), r.genre.trim(), p.read.keyName.takeIf { it != "—" }.orEmpty(), p.info.year, p.info.trackNumber, p.suggestedCues, p.comment, p.myTags)
                    if (!model.submit(edit, "Importar \"${r.title.trim()}\"" + if (r.artist.isNotBlank()) " — ${r.artist.trim()}" else "")) break
                    if (target != null && intoPlaylist) model.view?.tracks?.maxOfOrNull { it.id }?.let { id -> model.submit(Edit.AddToPlaylist(target.id, listOf(id)), "Adicionar \"${r.title.trim()}\" a \"${target.name}\"") }
                }
                onClose()
            }, primary = true, enabled = chosen.isNotEmpty())
            Btn("Cancelar", onClose)
        }
    }
}

// ---- renomear por padrão -----------------------------------------------------------------------------------------

private val presets = listOf(
    "{artista}/{album}/{artista} - {titulo}",
    "{artista} - {titulo}",
    "{genero}/{artista} - {titulo}",
    "{n} - {titulo}",
    "{artista}/{ano} - {album}/{n} - {titulo}",
)

@Composable
fun RenameModal(model: ManagerModel, onClose: () -> Unit) {
    // Lembra os padrões usados (os 4 últimos) entre uma sessão e outra.
    val saved = remember { SavedPatterns.load() }
    var pattern by remember { mutableStateOf(saved.firstOrNull() ?: presets[0]) }
    val view = model.view ?: return
    val scope = model.selection.mapNotNull { view.track(it) }.ifEmpty { view.tracks }
    val files = remember { model.volume?.root?.let { os.deckbridge.manager.volume.FileOverlay(it) } }
    val plan = remember(pattern, scope) { Organizer.renamePlan(scope, pattern) { path -> files?.exists(path) == true } }
    Modal("Renomear arquivos por padrão", 900.dp, onClose) {
        Label("Muda o nome e a pasta do áudio no pendrive (e o caminho no banco e no arquivo de análise). Nunca troca a extensão e nunca sai da pasta de músicas. Restaurar uma cópia de segurança devolve os nomes antigos.", Pal.Muted, 12.sp, maxLines = 3)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label("PADRÃO — campos: {artista} {titulo} {album} {genero} {tom} {bpm} {ano} {n}   ·   a barra / cria pastas", Pal.Muted, 11.sp, bold = true)
            TextBox(pattern, { pattern = it }, Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (saved + presets).distinct().take(8).forEach { p -> Box(Modifier.background(Pal.Raised).border(1.dp, Pal.Line).clickable { pattern = p }.padding(horizontal = 8.dp, vertical = 5.dp)) { Label(p, Pal.Blue, 11.sp) } }
        }
        Caption("${plan.size} de ${scope.size} faixa${if (scope.size == 1) "" else "s"} mudam de nome (${if (model.selection.isEmpty()) "toda a coleção" else "só as selecionadas"})")
        Column(Modifier.fillMaxWidth().height(230.dp).background(Pal.Bg).border(1.dp, Pal.Line).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (plan.isEmpty()) Label("Nada a renomear com esse padrão.", Pal.Muted, 12.sp)
            plan.take(60).forEach { r ->
                val t = view.track(r.trackId)!!
                Column {
                    Label(t.filePath.trimStart('/'), Pal.Muted, 11.sp)
                    Label("→ ${r.newPath}", Pal.Green, 12.sp)
                }
            }
            if (plan.size > 60) Label("… e mais ${plan.size - 60}", Pal.Muted, 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Adicionar à fila (${plan.size})", {
                if (model.submit(Edit.RenameFiles(plan), "Renomear ${plan.size} arquivo${if (plan.size == 1) "" else "s"} pelo padrão $pattern")) { SavedPatterns.remember(pattern); onClose() }
            }, primary = true, enabled = plan.isNotEmpty())
            Btn("Cancelar", onClose)
        }
    }
}

// ---- organizar ---------------------------------------------------------------------------------------------------

class OrganizeState {
    val items = mutableStateListOf<Pair<Suggestion, androidx.compose.runtime.MutableState<Boolean>>>()
    var busy by mutableStateOf<String?>(null)
    var force by mutableStateOf(false)
}

fun refreshSuggestions(model: ManagerModel, st: OrganizeState) {
    val view = model.view ?: return
    val keep = st.items.filter { it.first.rule == Rule.ANALYSIS && it.first.edit != null }
    st.items.clear()
    Organizer.suggest(view) { model.hasAnalysis(it) }.forEach { st.items.add(it to mutableStateOf(it.defaultOn)) }
    keep.forEach { st.items.add(it) }
}

@Composable
fun OrganizeModal(model: ManagerModel, st: OrganizeState, onClose: () -> Unit) {
    val view = model.view ?: return
    Modal("Organizar a biblioteca", 1000.dp, onClose) {
        Label("Sugestões por regras fixas (sem inteligência artificial): cada uma mostra o antes e o depois, nada é aplicado sozinho e as que removem faixas vêm desmarcadas. O que você marcar entra na fila.", Pal.Muted, 12.sp, maxLines = 3)
        Column(Modifier.fillMaxWidth().height(330.dp).background(Pal.Bg).border(1.dp, Pal.Line).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st.items.isEmpty()) Label("Nada a sugerir: a biblioteca está em ordem pelas regras.", Pal.Green, 13.sp)
            Rule.entries.forEach { rule ->
                val group = st.items.filter { it.first.rule == rule }
                if (group.isNotEmpty()) {
                    Caption("${rule.title} (${group.size})")
                    group.take(80).forEach { (s, on) ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.edit != null) Label(if (on.value) "☑" else "☐", Pal.Blue, 16.sp, modifier = Modifier.clickable { on.value = !on.value })
                            else Label("•", Pal.Muted, 16.sp)
                            Column {
                                Label(s.title, Pal.Ink, 12.sp, bold = true)
                                Label(s.detail, Pal.Muted, 11.sp, maxLines = 3)
                            }
                        }
                    }
                    if (group.size > 80) Label("… e mais ${group.size - 80}", Pal.Muted, 11.sp)
                }
            }
        }
        val busy = st.busy
        val targets = model.selection.mapNotNull { view.track(it) }.ifEmpty { view.tracks.filter { !model.hasAnalysis(it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val marked = st.items.filter { it.second.value && it.first.edit != null }
            Btn("Adicionar ${marked.size} à fila", {
                val n = model.submitAll(marked.map { it.first.edit!! to (it.first.rule.title + ": " + it.first.title) })
                refreshSuggestions(model, st)
                if (n == marked.size) onClose()
            }, primary = true, enabled = marked.isNotEmpty() && busy == null)
            Btn(if (model.selection.isEmpty()) "Analisar as ${targets.size} sem análise" else "Analisar as ${targets.size} selecionadas", {
                val root = model.volume!!.root
                st.busy = "analisando…"
                thread(name = "organizar-analise", isDaemon = true) {
                    var done = 0
                    for (t in targets) {
                        st.busy = "analisando ${++done}/${targets.size}: ${t.title}"
                        val outcome = OnDemand.analyze(root, t)
                        val s = OnDemand.suggestion(outcome, model.hasAnalysis(t), st.force)
                        if (s != null) st.items.add(s to mutableStateOf(true))
                        else outcome.error?.let { why -> st.items.add(Suggestion(Rule.ANALYSIS, listOf(t.id), "\"${t.title}\" não foi analisada", why, null) to mutableStateOf(false)) }
                    }
                    st.busy = null
                }
            }, enabled = busy == null && targets.isNotEmpty())
            val analyzed = model.selection.mapNotNull { view.track(it) }.ifEmpty { view.tracks }.filter { model.hasAnalysis(it) }
            // Grade do analisador atual (só a grade; cues e ondas ficam), para revisar antes de pôr na fila.
            Btn("Refazer ${analyzed.size} grade(s)", {
                val root = model.volume!!.root
                st.busy = "refazendo grades…"
                thread(name = "organizar-grades", isDaemon = true) {
                    var done = 0
                    var same = 0
                    for (t in analyzed) {
                        st.busy = "grade ${++done}/${analyzed.size}: ${t.title}"
                        val fresh = os.deckbridge.manager.organize.GridRefresh.analyze(root, t)
                        if (fresh == null) {
                            st.items.add(Suggestion(Rule.GRID, listOf(t.id), "\"${t.title}\" sem áudio para refazer a grade", "", null) to mutableStateOf(false))
                            continue
                        }
                        val c = os.deckbridge.manager.organize.GridRefresh.change(t, model.trackFiles(t).beats, fresh.first, fresh.second)
                        val s = os.deckbridge.manager.organize.GridRefresh.suggestion(c)
                        if (s != null) st.items.add(s to mutableStateOf(true)) else same++
                    }
                    st.busy = if (same > 0) "$same grade(s) já estavam certas" else null
                }
            }, enabled = busy == null && analyzed.isNotEmpty())
            Label(if (st.force) "☑" else "☐", Pal.Blue, 16.sp, modifier = Modifier.clickable { st.force = !st.force })
            Label("reanalisar mesmo com análise do rekordbox", Pal.Muted, 11.sp)
            Box(Modifier.weight(1f))
            if (busy != null) Label(busy, Pal.Orange, 12.sp, maxLines = 1)
            Btn("Fechar", onClose)
        }
    }
}


/** Padrões de nome usados por último, guardados nas preferências do usuário (fora do pendrive). */
object SavedPatterns {
    private val prefs get() = java.util.prefs.Preferences.userRoot().node("os/deckbridge/manager")

    fun load(): List<String> = runCatching { prefs.get("padroes", "").split("\n").filter { it.isNotBlank() } }.getOrDefault(emptyList())

    fun remember(pattern: String) {
        val list = (listOf(pattern) + load()).distinct().take(4)
        runCatching { prefs.put("padroes", list.joinToString("\n")) }
    }
}
