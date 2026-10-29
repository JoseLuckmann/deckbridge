package os.deckbridge.manager.organize

import kotlin.math.abs
import os.deckbridge.logic.AudioPrint
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Faixas repetidas: a mesma música entrou mais de uma vez, com o mesmo nome ou com nomes diferentes. Duas faixas são a
 * mesma quando o áudio bate ([AudioPrint], mesmo com outro nome, formato ou taxa de bits) ou, sem impressão, quando os
 * nomes limpos ([TrackNames]) e a duração (até 3 s) batem. Versões diferentes (Radio Edit e Extended) não são repetidas:
 * o áudio não bate e a duração é outra.
 */
object Duplicates {
    /** Um grupo de repetidas, a que fica sugerida primeiro, e por que são a mesma. */
    data class Group(val tracks: List<TrackItem>, val keep: TrackItem, val sameAudio: Boolean) {
        val rest get() = tracks.filter { it.id != keep.id }
    }

    /** Duração parecida o bastante para comparar o áudio (silêncio cortado, outro codificador). */
    private const val NEAR_SEC = 8

    /**
     * Os grupos de repetidas da biblioteca. [printOf] devolve a impressão da faixa (ou null, se não há); só é chamada
     * para pares de duração parecida. [playlistsOf] (quantas playlists a faixa está) e [cuesOf] ajudam a escolher qual
     * fica.
     */
    fun find(
        view: LibraryView, printOf: (TrackItem) -> AudioPrint?,
        playlistsOf: (TrackItem) -> Int = { 0 }, cuesOf: (TrackItem) -> Int = { 0 },
    ): List<Group> {
        val tracks = view.tracks.sortedBy { it.durationSeconds }
        val parent = HashMap<Int, Int>()
        fun root(id: Int): Int { var r = id; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
        fun join(x: Int, y: Int) { val rx = root(x); val ry = root(y); if (rx != ry) parent[ry] = rx }
        val audio = HashSet<Int>()
        val keys = tracks.associate { it.id to TrackNames.key(TrackNames.clean(it.title, it.artist, it.fileName)) }
        for (i in tracks.indices) {
            val a = tracks[i]
            for (j in i + 1 until tracks.size) {
                val b = tracks[j]
                if (b.durationSeconds - a.durationSeconds > NEAR_SEC) break
                val pa = printOf(a)
                val pb = printOf(b)
                if (pa != null && pb != null) {
                    if (AudioPrint.distance(pa, pb) < AudioPrint.SAME) { join(a.id, b.id); audio += a.id; audio += b.id }
                } else if (keys[a.id] == keys[b.id] && keys[a.id] != "|" && abs(a.durationSeconds - b.durationSeconds) <= 3) {
                    join(a.id, b.id)
                }
            }
        }
        return tracks.groupBy { root(it.id) }.values.filter { it.size > 1 }.map { g ->
            val keep = g.maxWith(compareBy<TrackItem>({ score(it, playlistsOf(it), cuesOf(it)) }, { -it.id }))
            Group(g.sortedBy { it.id }, keep, g.all { it.id in audio })
        }.sortedBy { it.keep.title.lowercase() }
    }

    /**
     * Qual fica: a de melhor qualidade (taxa de bits; sem perda conta como alta), a mais usada (playlists, cues, nota) e
     * a de nome mais arrumado (com artista, sem cara de nome de arquivo).
     */
    fun score(t: TrackItem, playlists: Int, cues: Int): Double {
        val lossless = t.filePath.lowercase().let { it.endsWith(".wav") || it.endsWith(".flac") || it.endsWith(".aiff") || it.endsWith(".aif") }
        val kbps = if (t.bitrate <= 0 && lossless) 1411 else t.bitrate
        val clean = TrackNames.clean(t.title, t.artist, t.fileName)
        val tidyName = (if (t.artist.isNotBlank()) 1.0 else 0.0) + (if (clean.title == t.title && clean.artist == t.artist) 1.0 else 0.0)
        return minOf(kbps, 1411) / 100.0 + playlists * 2.0 + minOf(cues, 8) * 0.5 + t.rating + tidyName
    }

    /**
     * Juntar o grupo em [keep]: ela entra nas playlists das outras (no lugar delas, sem repetir), fica com a maior nota,
     * a cor e os campos que só as outras tinham, e as outras saem da biblioteca. Tudo numa leva (um desfazer volta).
     */
    fun merge(view: LibraryView, keep: TrackItem, rest: List<TrackItem>): List<Pair<Edit, String>> {
        val out = ArrayList<Pair<Edit, String>>()
        val restIds = rest.map { it.id }.toSet()
        for (p in view.playlists.filter { !it.folder }) {
            val entries = view.entries[p.id].orEmpty()
            if (entries.none { it in restIds }) continue
            // A que fica toma o lugar da primeira repetida na playlist (a ordem do set não muda); as repetidas vão para o
            // fim e saem com a faixa.
            val after = if (keep.id in entries) entries else entries + keep.id
            val order = ArrayList<Int>()
            for (id in entries) {
                val now = if (id in restIds) keep.id else id
                if (now !in order) order += now
            }
            order += after.filter { it in restIds }
            if (keep.id !in entries) out += Edit.AddToPlaylist(p.id, listOf(keep.id)) to "Juntar repetidas: ${p.name}"
            if (order != after) out += Edit.ReorderPlaylist(p.id, order) to "Juntar repetidas: ordem de ${p.name}"
        }
        val patch = TrackPatch(
            rating = rest.maxOf { it.rating }.takeIf { it > keep.rating },
            colorId = rest.firstOrNull { it.colorId != 0 }?.colorId?.takeIf { keep.colorId == 0 },
            genre = rest.firstOrNull { it.genre.isNotBlank() }?.genre?.takeIf { keep.genre.isBlank() },
            artist = rest.firstOrNull { it.artist.isNotBlank() }?.artist?.takeIf { keep.artist.isBlank() },
            comment = rest.firstOrNull { it.comment.isNotBlank() }?.comment?.takeIf { keep.comment.isBlank() },
        )
        if (!patch.isEmpty) out += Edit.SetTracks(listOf(keep.id), patch) to "Juntar repetidas: \"${keep.title}\""
        out += Edit.RemoveTracks(rest.map { it.id }) to "Tirar repetidas de \"${keep.title}\""
        return out
    }
}
