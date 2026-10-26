package os.deckbridge.manager.organize

import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Sugestões de organização por regras fixas e explicáveis. Não há modelo de aprendizado: cada sugestão diz de que regra
 * veio e mostra o antes e o depois. Nada é aplicado sozinho; o usuário marca o que quer e as mudanças entram na fila.
 */
enum class Rule(val title: String) {
    TIDY("Espaços sobrando"),
    CASE("Maiúsculas e minúsculas"),
    FILENAME("Artista e título pelo nome do arquivo"),
    DUPLICATE("Possíveis duplicatas"),
    MISSING("Campos vazios"),
    ANALYSIS("Sem análise"),
    GRID("Grade refeita"),
}

data class Suggestion(
    val rule: Rule,
    val trackIds: List<Int>,
    val title: String,
    val detail: String,
    /** Null quando a sugestão só informa (campo vazio, duplicata): o usuário decide o que fazer. */
    val edit: Edit?,
    /** Marcada por padrão? Remover faixas nunca é. */
    val defaultOn: Boolean = edit != null,
)

object Organizer {
    private val smallWords = setOf("a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em", "no", "na", "um", "uma", "the", "of", "and", "in", "on", "at", "to", "for", "with", "vs", "feat", "ft")

    fun tidy(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** "NOME EM CAIXA ALTA" ou "tudo minúsculo" vira "Nome em Caixa Alta". Texto com caixa mista fica como está. */
    fun titleCase(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3 || !(letters == letters.uppercase() || letters == letters.lowercase())) return text
        val words = text.split(" ")
        return words.mapIndexed { n, w ->
            val lower = w.lowercase()
            when {
                w.isEmpty() -> w
                n > 0 && lower in smallWords -> lower
                // Siglas curtas em maiúsculas (DJ, UK, VIP) ficam como estão quando o resto estava em maiúsculas.
                else -> lower.split("-").joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
            }
        }.joinToString(" ")
    }

    private val numbered = Regex("""^\s*(\d{1,3})\s*[.\-_ ]+\s*(.+)$""")

    /** "Artista - Título" (com ou sem número na frente) a partir do nome do arquivo, sem extensão. */
    fun fromFileName(fileName: String): Triple<String?, String, Int?> {
        var stem = fileName.substringBeforeLast('.').replace('_', ' ')
        var number: Int? = null
        numbered.matchEntire(stem)?.let {
            number = it.groupValues[1].toIntOrNull()
            stem = it.groupValues[2]
        }
        val parts = stem.split(" - ", limit = 2).map { tidy(it) }
        return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) Triple(parts[0], parts[1], number) else Triple(null, tidy(stem), number)
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun suggest(view: LibraryView, hasAnalysis: (TrackItem) -> Boolean = { true }): List<Suggestion> {
        val out = ArrayList<Suggestion>()

        // 1. Espaços
        for (t in view.tracks) {
            val patch = TrackPatch(
                title = tidy(t.title).takeIf { it != t.title },
                artist = tidy(t.artist).takeIf { it != t.artist },
                album = tidy(t.album).takeIf { it != t.album },
                genre = tidy(t.genre).takeIf { it != t.genre },
            )
            if (!patch.isEmpty) out += Suggestion(Rule.TIDY, listOf(t.id), "\"${t.title}\"", changes(t, patch), Edit.SetTracks(listOf(t.id), patch))
        }

        // 2. Caixa
        for (t in view.tracks) {
            val patch = TrackPatch(
                title = titleCase(tidy(t.title)).takeIf { it != tidy(t.title) },
                artist = titleCase(tidy(t.artist)).takeIf { it != tidy(t.artist) },
                album = titleCase(tidy(t.album)).takeIf { it != tidy(t.album) },
            )
            if (!patch.isEmpty) out += Suggestion(Rule.CASE, listOf(t.id), "\"${t.title}\"", changes(t, patch), Edit.SetTracks(listOf(t.id), patch))
        }

        // 3. Nome do arquivo → artista/título/número
        for (t in view.tracks) {
            val (artist, title, number) = fromFileName(t.fileName)
            val patch = TrackPatch(
                artist = artist?.takeIf { t.artist.isBlank() },
                title = title.takeIf { t.title.isBlank() && it.isNotEmpty() },
                trackNumber = number?.takeIf { t.trackNumber == 0 },
            )
            if (!patch.isEmpty) out += Suggestion(Rule.FILENAME, listOf(t.id), t.fileName, changes(t, patch), Edit.SetTracks(listOf(t.id), patch))
        }

        // 4. Duplicatas: mesmo artista e título (sem pontuação nem caixa) e duração próxima.
        view.tracks.groupBy { norm(it.artist) + "|" + norm(it.title) }.filter { it.value.size > 1 && it.key != "|" }.forEach { (_, group) ->
            val sorted = group.sortedBy { it.durationSeconds }
            val clusters = ArrayList<MutableList<TrackItem>>()
            for (t in sorted) {
                val c = clusters.lastOrNull()
                if (c != null && t.durationSeconds - c.last().durationSeconds <= 3) c += t else clusters += mutableListOf(t)
            }
            clusters.filter { it.size > 1 }.forEach { c ->
                // Fica a de maior taxa de bits (depois a de menor id); as outras podem sair da biblioteca.
                val keep = c.sortedWith(compareByDescending<TrackItem> { it.bitrate }.thenBy { it.id }).first()
                val rest = c.filter { it.id != keep.id }.map { it.id }
                out += Suggestion(
                    Rule.DUPLICATE, c.map { it.id }, "\"${keep.title}\" — ${keep.artist}",
                    "${c.size} faixas parecem ser a mesma (ids ${c.joinToString { it.id.toString() }}). Manteria a ${keep.id} (${keep.bitrate} kbps) e tiraria da biblioteca ${rest.joinToString()}. O áudio no pendrive não é apagado.",
                    Edit.RemoveTracks(rest), defaultOn = false,
                )
            }
        }

        // 5. Campos vazios (só informa)
        fun missing(name: String, ids: List<Int>) {
            if (ids.isNotEmpty()) out += Suggestion(Rule.MISSING, ids, "$name vazio em ${ids.size} faixa${if (ids.size == 1) "" else "s"}", "Preencha à mão no inspetor ou use a análise (tom).", null)
        }
        missing("Artista", view.tracks.filter { it.artist.isBlank() }.map { it.id })
        missing("Álbum", view.tracks.filter { it.album.isBlank() }.map { it.id })
        missing("Gênero", view.tracks.filter { it.genre.isBlank() }.map { it.id })
        missing("Tom", view.tracks.filter { it.key.isBlank() }.map { it.id })
        missing("Ano", view.tracks.filter { it.year == 0 }.map { it.id })

        // 6. Sem análise
        val noAnalysis = view.tracks.filter { !hasAnalysis(it) }.map { it.id }
        if (noAnalysis.isNotEmpty()) out += Suggestion(Rule.ANALYSIS, noAnalysis, "${noAnalysis.size} faixa${if (noAnalysis.size == 1) "" else "s"} sem arquivo de análise", "Use \"Analisar selecionadas\" para gerar grade, ondas e tom com o analisador do app.", null)
        return out
    }

    private fun changes(t: TrackItem, p: TrackPatch): String = buildList {
        p.title?.let { add("título \"${t.title}\" → \"$it\"") }
        p.artist?.let { add("artista \"${t.artist}\" → \"$it\"") }
        p.album?.let { add("álbum \"${t.album}\" → \"$it\"") }
        p.genre?.let { add("gênero \"${t.genre}\" → \"$it\"") }
        p.trackNumber?.let { add("nº da faixa ${t.trackNumber} → $it") }
    }.joinToString("; ")

    // ---- renomear arquivos por padrão ---------------------------------------------------------------------------

    /** Campos do padrão: {artista} {titulo} {album} {genero} {tom} {bpm} {ano} {n}. Barras criam pastas. */
    fun pathFor(t: TrackItem, pattern: String, top: String): String {
        fun clean(x: String) = x.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
        val ext = t.fileName.substringAfterLast('.', "")
        val filled = pattern
            .replace("{artista}", clean(t.artist.ifBlank { "Sem artista" }))
            .replace("{titulo}", clean(t.title.ifBlank { t.fileName.substringBeforeLast('.') }))
            .replace("{album}", clean(t.album.ifBlank { "Sem álbum" }))
            .replace("{genero}", clean(t.genre.ifBlank { "Sem gênero" }))
            .replace("{tom}", clean(t.key.ifBlank { "-" }))
            .replace("{bpm}", if (t.bpm > 0) "%.0f".format(t.bpm) else "0")
            .replace("{ano}", if (t.year > 0) t.year.toString() else "0000")
            .replace("{n}", "%02d".format(t.trackNumber))
        val parts = filled.split('/').map { it.trim().trimEnd('.', ' ') }.filter { it.isNotEmpty() }
        return (listOf(top) + parts.dropLast(1) + (parts.last().take(100) + "." + ext)).joinToString("/")
    }

    /** Renomeações que o padrão pede, só das faixas cujo caminho muda. Desfaz colisões acrescentando " (2)", " (3)". */
    fun renamePlan(tracks: List<TrackItem>, pattern: String, taken: (String) -> Boolean): List<Edit.FileRename> {
        val used = HashSet<String>()
        val out = ArrayList<Edit.FileRename>()
        for (t in tracks) {
            val old = t.filePath.replace('\\', '/').trimStart('/')
            val top = old.substringBefore('/')
            var path = pathFor(t, pattern, top)
            if (path == old) { used += old.lowercase(); continue }
            var n = 2
            val ext = path.substringAfterLast('.')
            val stem = path.removeSuffix(".$ext")
            while (path.lowercase() in used || (taken(path) && !path.equals(old, ignoreCase = true))) path = "$stem (${n++}).$ext"
            used += path.lowercase()
            out += Edit.FileRename(t.id, path)
        }
        return out
    }
}
