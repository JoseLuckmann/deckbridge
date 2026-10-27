package os.deckbridge.manager.smart

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

/**
 * Manter a biblioteca viva: o que entrou e ainda espera decisão, as boas que ficaram esquecidas, onde cada faixa já está e
 * por que uma sugestão apareceu. As razões e as fontes estão em `docs/ux-cognicao.md`.
 */
object Keep {
    /** Dias desde a entrada para uma faixa boa fora dos sets contar como esquecida. */
    const val FORGOTTEN_DAYS = 30

    /** Nota a partir da qual a faixa é das que o DJ gosta. */
    const val LIKED = 4

    /**
     * Novas: entraram e ainda não têm decisão nenhuma (sem nota e em nenhuma playlist). É a caixa de entrada: fica tudo
     * junto, sem obrigar a classificar na hora, e esvazia quando o DJ decide (nota ou playlist).
     */
    fun isNew(t: TrackItem, inPlaylists: Set<Int>): Boolean = t.rating == 0 && t.id !in inPlaylists

    /**
     * Esquecidas: as que o DJ marcou como boas, que estão há [FORGOTTEN_DAYS] dias ou mais na biblioteca (ou sem data) e
     * que não entraram em nenhum set. A lista existe para lembrar delas, não para tirar.
     */
    fun isForgotten(t: TrackItem, inSets: Set<Int>, today: LocalDate = LocalDate.now()): Boolean {
        if (t.rating < LIKED || t.id in inSets) return false
        val added = added(t) ?: return true
        return ChronoUnit.DAYS.between(added, today) >= FORGOTTEN_DAYS
    }

    fun added(t: TrackItem): LocalDate? = runCatching { LocalDate.parse(t.dateAdded.take(10)) }.getOrNull()

    /** As faixas que estão em algum dos sets [setPlaylists] (a linha e os ramos são playlists). */
    fun inSets(view: LibraryView, setPlaylists: Set<Int>): Set<Int> = setPlaylists.flatMap { view.entries[it].orEmpty() }.toSet()

    /** Onde a faixa está: os sets e as outras playlists, cada um com o id da playlist para abrir. */
    data class Place(val playlistId: Int, val name: String, val set: Boolean)

    fun places(view: LibraryView, trackId: Int, setPlaylists: Set<Int>): List<Place> =
        view.playlists.filter { !it.folder && trackId in view.entries[it.id].orEmpty() }
            .map { Place(it.id, it.name, it.id in setPlaylists) }
            .sortedWith(compareBy({ !it.set }, { it.name.lowercase() }))

    /** Em quantos sets a faixa já entrou. */
    fun setCount(view: LibraryView, trackId: Int, setPlaylists: Set<Int>): Int = setPlaylists.count { trackId in view.entries[it].orEmpty() }

    /**
     * Por que [next] aparece depois de [last], em poucas palavras: tom (igual, vizinho ou longe), andamento, nota e quantas
     * vezes já foi usada em set ("nunca em set" lembra das esquecidas na hora de escolher).
     */
    fun why(last: TrackItem?, next: TrackItem, sets: Int): String = buildList {
        if (last != null) {
            val fit = SetPlanner.fit(last, next)
            when (fit.keyDistance) {
                null -> {}
                0 -> add("mesmo tom")
                1 -> add("tom vizinho")
                else -> add("tom longe")
            }
            val pct = fit.bpmDiffPct
            if (last.bpm > 0 && next.bpm > 0) add(if (kotlin.math.abs(pct) < 0.5) "mesmo BPM" else String.format(java.util.Locale.ROOT, "%+.1f%% BPM", pct).replace('.', ','))
        }
        if (next.rating > 0) add("★${next.rating}")
        add(if (sets == 0) "nunca em set" else if (sets == 1) "em 1 set" else "em $sets sets")
    }.joinToString(" · ")
}

/**
 * Para onde o DJ quer levar a próxima: o mesmo clima (o que combina), subir ou descer o andamento, ou descobrir as que
 * nunca entraram em set. Dar esse controle a quem recebe a sugestão aumenta a satisfação, desde que sejam poucos
 * controles (Jin, Tintarev, Htun & Verbert 2020).
 */
enum class Steer(val title: String) { MATCH("COMBINA"), UP("SOBE"), DOWN("DESCE"), DISCOVER("DESCOBRIR") }

object Suggest {
    /** Até [limit] faixas para depois de [last], fora de [exclude], no rumo [steer]; [inSets] são as que já entraram em set. */
    fun next(view: LibraryView, last: TrackItem, exclude: Set<Int>, steer: Steer, inSets: Set<Int>, limit: Int = 6): List<TrackItem> {
        val pool = view.tracks.filter { it.id !in exclude && it.id != last.id }
        fun byFit(list: List<TrackItem>) = list.map { it to SetPlanner.fit(last, it) }
            .sortedWith(compareBy<Pair<TrackItem, os.deckbridge.manager.mix.Fit>>({ !it.second.keyOk }, { it.second.keyDistance ?: 9 }, { kotlin.math.abs(it.second.bpmDiffPct) }, { -it.first.rating }))
            .map { it.first }
        return when (steer) {
            Steer.MATCH -> SetPlanner.suggestNext(view, last, exclude, limit)
            // Sobe: mais rápida, até 6% (o que o pitch alcança sem estranhar); desce: o contrário.
            Steer.UP -> byFit(pool.filter { last.bpm > 0 && SetPlanner.fit(last, it).bpmDiffPct in 0.5..6.0 }).take(limit)
            Steer.DOWN -> byFit(pool.filter { last.bpm > 0 && SetPlanner.fit(last, it).bpmDiffPct in -6.0..-0.5 }).take(limit)
            // Descobrir: as que nunca entraram em set, as de nota maior primeiro, e dentro disso as que combinam.
            Steer.DISCOVER -> byFit(pool.filter { it.id !in inSets }).sortedByDescending { it.rating.coerceAtMost(5) >= 3 }.take(limit)
        }
    }
}
