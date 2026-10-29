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
    FILENAME("Título e artista arrumados"),
    DUPLICATE("Repetidas"),
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

        // 3. Nomes: título e artista limpos (número da importação ou da faixa, sublinhados, "Artista - Título" no título
        // ou no nome do arquivo, lixo de vídeo e de site) e o número da faixa pelo nome do arquivo.
        for (t in view.tracks) {
            val n = TrackNames.clean(t.title, t.artist, t.fileName)
            val number = fromFileName(t.fileName).third
            val patch = TrackPatch(
                // O que só muda espaços ou maiúsculas fica com as regras 1 e 2.
                title = n.title.takeIf { it.isNotEmpty() && it != tidy(t.title) && it != titleCase(tidy(t.title)) },
                artist = n.artist.takeIf { it.isNotEmpty() && it != tidy(t.artist) && it != titleCase(tidy(t.artist)) },
                trackNumber = number?.takeIf { t.trackNumber == 0 && it in 1..99 && TrackNames.clean("", "", t.fileName).title.isNotEmpty() },
            )
            if (!patch.isEmpty) out += Suggestion(Rule.FILENAME, listOf(t.id), "\"${t.title}\"", changes(t, patch), Edit.SetTracks(listOf(t.id), patch))
        }

        // 3b. Sem artista e sem separador no nome: se o título começa com um artista que a biblioteca já conhece, separa.
        val known = TrackNames.knownArtists(view.tracks.map { it.artist })
        for (t in view.tracks.filter { it.artist.isBlank() }) {
            val n = TrackNames.clean(t.title, t.artist, t.fileName)
            if (n.artist.isNotBlank()) continue
            val s = TrackNames.splitByKnown(n.title, known) ?: continue
            val patch = TrackPatch(title = s.title, artist = s.artist)
            out += Suggestion(Rule.FILENAME, listOf(t.id), "\"${t.title}\"", changes(t, patch) + " (artista que a biblioteca já conhece)", Edit.SetTracks(listOf(t.id), patch))
        }

        // 4. Repetidas pelo nome limpo e a duração (o áudio é comparado na tela de repetidas, com a impressão digital).
        for (g in Duplicates.find(view, { null }, { t -> view.entries.count { t.id in it.value } })) {
            val rest = g.rest
            out += Suggestion(
                Rule.DUPLICATE, g.tracks.map { it.id }, "\"${g.keep.title}\" — ${g.keep.artist}",
                "${g.tracks.size} faixas parecem ser a mesma. Fica a ${g.keep.id} (${g.keep.bitrate} kbps); ela entra nas playlists das outras, e as outras (${rest.joinToString { it.id.toString() }}) saem da biblioteca.",
                Edit.RemoveTracks(rest.map { it.id }), defaultOn = false,
            )
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
