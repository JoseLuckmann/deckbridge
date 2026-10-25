package os.deckbridge.manager.smart

import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

/** Filtros prontos da biblioteca: respondem "o que ainda falta fazer?" com um toque. */
enum class QuickFilter(val title: String, val hint: String) {
    ALL("Todas", "Toda a biblioteca"),
    UNANALYZED("Sem análise", "Sem grade nem cues no player. Dá para analisar todas de uma vez."),
    UNRATED("Sem nota", "Ainda não marcadas com estrelas."),
    NO_PLAYLIST("Fora de playlists", "Não estão em nenhuma playlist."),
    NO_GENRE("Sem gênero", "Gênero vazio."),
    NO_ARTIST("Sem artista", "Artista vazio."),
    TOP("4 ou 5 estrelas", "As melhores."),
    RECENT("Recentes", "As últimas importadas."),
}

/** Busca com termos livres e filtros escritos: `bpm:120-128`, `bpm:>124`, `tom:8A`, `nota:4`, `cor:verde`, `genero:house`. */
data class Query(
    val terms: List<String> = emptyList(),
    val bpm: ClosedFloatingPointRange<Double>? = null,
    val key: String? = null,
    val minRating: Int? = null,
    val color: Int? = null,
    val genre: String? = null,
) {
    val isEmpty get() = this == Query()
}

object Smart {
    private val colorNames = listOf("rosa", "vermelho", "laranja", "amarelo", "verde", "agua", "água", "azul", "roxo")
    private val colorIds = listOf(1, 2, 3, 4, 5, 6, 6, 7, 8)

    fun parseQuery(text: String): Query {
        var q = Query()
        val terms = ArrayList<String>()
        for (raw in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val at = raw.indexOf(':')
            if (at <= 0 || at == raw.lastIndex) {
                terms += raw.lowercase()
                continue
            }
            val name = raw.substring(0, at).lowercase()
            val value = raw.substring(at + 1)
            when (name) {
                "bpm" -> {
                    val range = parseBpm(value)
                    if (range != null) q = q.copy(bpm = range) else terms += raw.lowercase()
                }
                "tom", "key" -> q = q.copy(key = value.lowercase())
                "nota", "rating", "estrelas" -> value.trimStart('>', '=').toIntOrNull()?.let { q = q.copy(minRating = it) } ?: run { terms += raw.lowercase() }
                "cor", "color" -> {
                    val i = colorNames.indexOf(value.lowercase())
                    if (i >= 0) q = q.copy(color = colorIds[i]) else terms += raw.lowercase()
                }
                "genero", "gênero", "genre" -> q = q.copy(genre = value.lowercase())
                else -> terms += raw.lowercase()
            }
        }
        return q.copy(terms = terms)
    }

    private fun parseBpm(v: String): ClosedFloatingPointRange<Double>? {
        v.toDoubleOrNull()?.let { return (it - 1.0)..(it + 1.0) }
        if (v.startsWith(">")) return v.drop(1).toDoubleOrNull()?.let { it..999.0 }
        if (v.startsWith("<")) return v.drop(1).toDoubleOrNull()?.let { 0.0..it }
        val parts = v.split('-')
        if (parts.size == 2) {
            val a = parts[0].toDoubleOrNull()
            val b = parts[1].toDoubleOrNull()
            if (a != null && b != null) return minOf(a, b)..maxOf(a, b)
        }
        return null
    }

    fun matches(q: Query, t: TrackItem): Boolean {
        if (q.bpm != null && t.bpm !in q.bpm) return false
        if (q.key != null && !keyMatches(q.key, t.key)) return false
        if (q.minRating != null && t.rating < q.minRating) return false
        if (q.color != null && t.colorId != q.color) return false
        if (q.genre != null && !t.genre.lowercase().contains(q.genre)) return false
        if (q.terms.isEmpty()) return true
        val hay = listOf(t.title, t.artist, t.album, t.genre, t.key, t.comment, t.fileName).joinToString("\n") { it.lowercase() }
        return q.terms.all { it in hay }
    }

    private fun keyMatches(wanted: String, key: String): Boolean {
        if (key.lowercase() == wanted) return true
        val a = camelot(wanted)
        val b = camelot(key)
        return a != null && a == b
    }

    private fun inPlaylists(view: LibraryView): Set<Int> = view.playlists.filter { !it.folder }.flatMap { view.entries[it.id].orEmpty() }.toSet()

    fun apply(filter: QuickFilter, view: LibraryView, hasAnalysis: (TrackItem) -> Boolean, ids: List<TrackItem>): List<TrackItem> = when (filter) {
        QuickFilter.ALL -> ids
        QuickFilter.UNANALYZED -> ids.filter { !hasAnalysis(it) }
        QuickFilter.UNRATED -> ids.filter { it.rating == 0 }
        QuickFilter.NO_PLAYLIST -> inPlaylists(view).let { used -> ids.filter { it.id !in used } }
        QuickFilter.NO_GENRE -> ids.filter { it.genre.isBlank() }
        QuickFilter.NO_ARTIST -> ids.filter { it.artist.isBlank() }
        QuickFilter.TOP -> ids.filter { it.rating >= 4 }
        QuickFilter.RECENT -> view.tracks.sortedByDescending { it.id }.take(20).let { recent -> ids.filter { t -> recent.any { it.id == t.id } } }
    }

    /** Quantas faixas cada filtro mostra agora (para o número no botão). */
    fun counts(view: LibraryView, hasAnalysis: (TrackItem) -> Boolean): Map<QuickFilter, Int> =
        QuickFilter.values().associateWith { apply(it, view, hasAnalysis, view.tracks).size }

    // ---- tom e ordem harmônica -------------------------------------------------------------------------------

    private val majorCamelot = mapOf("b" to 1, "f#" to 2, "gb" to 2, "db" to 3, "c#" to 3, "ab" to 4, "g#" to 4, "eb" to 5, "d#" to 5, "bb" to 6, "a#" to 6, "f" to 7, "c" to 8, "g" to 9, "d" to 10, "a" to 11, "e" to 12)
    private val minorCamelot = mapOf("g#" to 1, "ab" to 1, "d#" to 2, "eb" to 2, "bb" to 3, "a#" to 3, "f" to 4, "c" to 5, "g" to 6, "d" to 7, "a" to 8, "e" to 9, "b" to 10, "f#" to 11, "gb" to 11, "c#" to 12, "db" to 12)

    /** Número (1–12) e letra (A menor, B maior) do tom, aceitando "8A", "Am", "F#m", "Bb". Null se não entender. */
    fun camelot(key: String): Pair<Int, Char>? {
        val k = key.trim()
        Regex("^(1[0-2]|[1-9])([ABab])$").matchEntire(k)?.let { return it.groupValues[1].toInt() to it.groupValues[2].uppercase()[0] }
        val m = Regex("^([A-Ga-g][#b♯♭]?)(m|min|minor)?$").matchEntire(k) ?: return null
        val note = m.groupValues[1].lowercase().replace('♯', '#').replace('♭', 'b')
        val minor = m.groupValues[2].isNotEmpty()
        val n = (if (minor) minorCamelot[note] else majorCamelot[note]) ?: return null
        return n to if (minor) 'A' else 'B'
    }

    /** 0 = igual, 1 = vizinho harmônico (±1 na roda ou troca A/B), maior = pior. */
    fun harmonicDistance(a: Pair<Int, Char>, b: Pair<Int, Char>): Int {
        val d = Math.floorMod(a.first - b.first, 12).let { minOf(it, 12 - it) }
        return d + if (a.second != b.second) 1 else 0
    }

    enum class SortMode(val title: String) { BPM_UP("BPM crescente"), BPM_DOWN("BPM decrescente"), TITLE("Título"), ARTIST("Artista"), RATING("Nota"), HARMONIC("Harmônica (tom e BPM)") }

    /** Nova ordem dos ids de uma playlist. A harmônica encadeia a partir da faixa de menor BPM, sempre indo ao vizinho mais próximo em tom e andamento. */
    fun sorted(view: LibraryView, ids: List<Int>, mode: SortMode): List<Int> {
        val tracks = ids.mapNotNull { view.track(it) }
        val out = when (mode) {
            SortMode.BPM_UP -> tracks.sortedBy { it.bpm }
            SortMode.BPM_DOWN -> tracks.sortedByDescending { it.bpm }
            SortMode.TITLE -> tracks.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            SortMode.RATING -> tracks.sortedByDescending { it.rating }
            SortMode.HARMONIC -> harmonic(tracks)
        }
        return out.map { it.id } + ids.filter { view.track(it) == null }
    }

    private fun harmonic(tracks: List<TrackItem>): List<TrackItem> {
        if (tracks.size < 3) return tracks.sortedBy { it.bpm }
        val left = tracks.sortedBy { it.bpm }.toMutableList()
        val out = ArrayList<TrackItem>()
        out += left.removeAt(0)
        while (left.isNotEmpty()) {
            val last = out.last()
            val ka = camelot(last.key)
            val next = left.minBy { t ->
                val kb = camelot(t.key)
                val kd = if (ka != null && kb != null) harmonicDistance(ka, kb) else 3
                kd * 10.0 + Math.abs(t.bpm - last.bpm)
            }
            left.remove(next)
            out += next
        }
        return out
    }

    private fun norm(x: String) = x.lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * A faixa que já está na biblioteca com o mesmo título e artista (sem diferença de caixa e espaços) e duração a até
     * 2 s; null se não há. Serve para não importar a mesma música duas vezes.
     */
    fun findDuplicate(view: LibraryView, title: String, artist: String, durationSec: Int): TrackItem? {
        val t = norm(title)
        val a = norm(artist)
        if (t.isEmpty()) return null
        return view.tracks.firstOrNull { norm(it.title) == t && norm(it.artist) == a && kotlin.math.abs(it.durationSeconds - durationSec) <= 2 }
    }

    /** Fila da triagem: sem nota primeiro, na ordem em que foram importadas; depois as já marcadas. */
    fun triageQueue(view: LibraryView): List<Int> = view.tracks.sortedWith(compareBy({ it.rating != 0 }, { it.id })).map { it.id }

    /** O que falta para o fluxo "importar → analisar → marcar → playlist → exportar". */
    data class Step(val title: String, val done: Boolean, val detail: String, val action: StepAction)
    enum class StepAction { IMPORT, ANALYZE, TRIAGE, PLAYLIST, EXPORT }

    fun journey(view: LibraryView, hasAnalysis: (TrackItem) -> Boolean, exported: Boolean): List<Step> {
        val n = view.tracks.size
        val un = view.tracks.count { !hasAnalysis(it) }
        val unrated = view.tracks.count { it.rating == 0 }
        val lists = view.playlists.count { !it.folder }
        return listOf(
            Step("Importar músicas", n > 0, if (n > 0) "$n faixas na biblioteca" else "Traga músicas do aparelho", StepAction.IMPORT),
            Step("Analisar", n > 0 && un == 0, if (n == 0) "Depois de importar" else if (un == 0) "Todas com grade e cues" else "$un sem análise", StepAction.ANALYZE),
            Step("Marcar", n > 0 && unrated <= n / 2, if (n == 0) "Depois de importar" else "${n - unrated} de $n com nota", StepAction.TRIAGE),
            Step("Montar playlists", lists > 0, if (lists > 0) "$lists playlists" else "Crie a primeira", StepAction.PLAYLIST),
            Step("Exportar para o pendrive", exported, if (exported) "Já exportado" else "Quando estiver pronto", StepAction.EXPORT),
        )
    }

    /** Problemas a resolver antes de exportar. [blocking] impede a exportação. */
    data class Issue(val text: String, val blocking: Boolean, val fix: Fix?)
    enum class Fix { ANALYZE_ALL, REMOVE_EMPTY_PLAYLISTS }

    fun preflight(view: LibraryView, hasAnalysis: (TrackItem) -> Boolean, audioExists: (TrackItem) -> Boolean): List<Issue> = buildList {
        val noAudio = view.tracks.count { !audioExists(it) }
        if (noAudio > 0) add(Issue("$noAudio faixa(s) sem o arquivo de áudio no aparelho. Remova-as ou importe de novo.", true, null))
        val un = view.tracks.count { !hasAnalysis(it) }
        if (un > 0) add(Issue("$un faixa(s) sem análise: no player ficam sem grade e cues.", false, Fix.ANALYZE_ALL))
        val empty = view.playlists.count { !it.folder && view.entries[it.id].orEmpty().isEmpty() }
        if (empty > 0) add(Issue("$empty playlist(s) vazia(s).", false, Fix.REMOVE_EMPTY_PLAYLISTS))
        if (view.tracks.isEmpty()) add(Issue("A biblioteca está vazia.", true, null))
    }
}
