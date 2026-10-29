package os.deckbridge.manager.organize

import kotlin.math.roundToInt
import os.deckbridge.logic.RbTag
import os.deckbridge.manager.mix.TrackProfile

/**
 * A classificação de uma faixa nos campos do rekordbox: [energy] de 1 a 10 (vai no Comment como "Energy 7", a convenção do
 * Mixed In Key que o rekordbox mostra e busca), [genre] (campo Genre; vazio quando não dá para palpitar) e as My Tags das
 * colunas padrão — [situation] (Situation) e [components] (Components), e o gênero também na coluna Genre quando é um dos
 * nomes dela.
 */
data class TrackClass(val energy: Int, val genre: String, val situations: List<String>, val components: List<String>, val why: List<String>) {
    /** As My Tags (nome e coluna, o mesmo [RbTag] que o modo DJ lê do pendrive). */
    val myTags: List<RbTag>
        get() = buildList {
            if (genre in TrackClassifier.GENRE_TAGS) add(RbTag(genre, "Genre"))
            components.forEach { add(RbTag(it, "Components")) }
            situations.forEach { add(RbTag(it, "Situation")) }
        }
}

/**
 * Classifica uma faixa pelo que se mede no áudio (o mesmo [TrackProfile] do MIX: grave, médio, agudo, batida e nível por
 * compasso), o andamento e o título. Regras fixas e explicáveis, sem serviço nem modelo treinado:
 *
 * - **Energia** (1–10): o nível das partes cheias (como a sonoridade de um master), o quanto a batida pulsa, o andamento e
 *   o brilho (agudo contra médio). São os fatores que a literatura de energia percebida em música aponta (intensidade,
 *   densidade de ataques, andamento e brilho do timbre: Gabrielsson & Lindström 2010; Eerola & Vuoskoski 2011).
 * - **Gênero** (palpite, só quando o arquivo não diz): pelo andamento e pelo timbre e ritmo — as mesmas famílias de medida
 *   que a classificação de gênero clássica usa (Tzanetakis & Cook 2002), que acerta uma parte e erra outra: por isso só
 *   preenche vazio e fica fácil de trocar.
 * - **Situation** (o momento do set, pode ser mais de um): Warm Up (energia 4–5), Main Floor (6–7), Peak Time (8–10),
 *   Lounge (1–3); Closing para a melódica e clara de energia média-alta (fecha a noite com emoção) e After Hours para a
 *   escura e hipnótica (grave e batida fortes, pouco agudo, andamento de pista) — como os DJs organizam o set por fase
 *   (warm-up, peak, closing, after).
 * - **Components**: Vocal (título com feat./vocal, sem Dub/Instrumental), Sub Bass (grave que fica perto do nível total),
 *   Beat (batida marcada), Dark (pouco agudo), Upper (energia alta e brilho).
 */
object TrackClassifier {
    /**
     * Os momentos do set que não existem nas My Tags padrão do rekordbox: o editor cria na coluna Situation quando a
     * classificação usa (Peak Time, Main Floor e Lounge já são padrão).
     */
    val NEW_SITUATIONS = setOf("Warm Up", "Closing", "After Hours")

    /** Os gêneros da coluna Genre das My Tags padrão do rekordbox. */
    val GENRE_TAGS = setOf("Acid House", "Deep House", "Techno", "Nu Disco", "Electro House", "Bass Music", "Trap")

    private fun pct(values: List<Float>, p: Int): Float {
        if (values.isEmpty()) return -60f
        val s = values.sorted()
        return s[(s.size * p / 100).coerceAtMost(s.lastIndex)]
    }

    private fun norm(v: Double, lo: Double, hi: Double) = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    fun classify(profile: TrackProfile, bpm: Double, title: String, tagGenre: String = ""): TrackClass {
        val audible = (0 until profile.bars).filter { profile.totalDb[it] > TrackProfile.SILENCE_DB }
        fun of(a: FloatArray) = audible.map { a[it] }
        // O patamar (85º percentil) das partes cheias, não o pico de um compasso nem a média com a intro.
        val loud = pct(of(profile.totalDb), 85).toDouble()
        val beat = pct(of(profile.beat), 50).toDouble()
        val bright = (pct(of(profile.highDb), 85) - pct(of(profile.midDb), 85)).toDouble()
        val bassRel = (pct(of(profile.bassDb), 85) - pct(of(profile.totalDb), 85)).toDouble()

        val e = 0.40 * norm(loud, -26.0, -8.0) + 0.25 * norm(beat, 0.1, 0.7) + 0.20 * norm(bpm, 90.0, 140.0) + 0.15 * norm(bright, -30.0, -6.0)
        val energy = (1 + 9 * e).roundToInt().coerceIn(1, 10)
        val dark = bright <= -18
        val why = mutableListOf("nível %.0f dB, batida %.2f, %.0f BPM, brilho %.0f dB".format(loud, beat, bpm, bright))

        val genre = tagGenre.trim().ifEmpty {
            when {
                bpm in 160.0..185.0 -> "Drum & Bass"
                bpm in 135.0..150.0 && beat < 0.35 && bassRel >= -10 -> "Bass Music"
                bpm >= 124 && dark && beat >= 0.4 -> "Techno"
                bpm in 115.0..126.0 && dark && energy <= 6 -> "Deep House"
                bpm in 126.0..132.0 && !dark && energy >= 7 -> "Electro House"
                bpm in 108.0..124.0 && !dark && energy <= 7 -> "Nu Disco"
                bpm in 118.0..130.0 -> "House"
                else -> ""
            }.also { if (it.isNotEmpty()) why += "gênero pelo andamento e timbre (palpite)" }
        }

        // Quantos compassos têm melodia ou voz forte (médio e agudo perto do patamar): a faixa "que canta".
        val melodic = if (audible.isEmpty()) 0.0 else audible.count { profile.melodic(it) }.toDouble() / audible.size
        val situations = buildList {
            add(when (energy) {
                in 1..3 -> "Lounge"
                in 4..5 -> "Warm Up"
                in 6..7 -> "Main Floor"
                else -> "Peak Time"
            })
            if (energy in 5..8 && !dark && melodic >= 0.4) add("Closing")
            if (energy in 4..7 && dark && beat >= 0.4 && bpm >= 118) add("After Hours")
        }
        val components = buildList {
            val t = title.lowercase()
            if (Regex("""\b(feat\.?|ft\.?|vocal|vox)\b""").containsMatchIn(t) && !Regex("""\b(dub|instrumental)\b""").containsMatchIn(t)) add("Vocal")
            if (bassRel >= -10) add("Sub Bass")
            if (beat >= 0.5) add("Beat")
            if (dark) add("Dark")
            if (energy >= 7 && bright >= -12) add("Upper")
        }
        return TrackClass(energy, genre, situations, components, why)
    }

    private val energyToken = Regex("""(?i)\benergy\s*\d{1,2}\b""")

    /** O comentário com "Energy N": troca um "Energy" que já houver (de outra análise) ou acrescenta no fim. */
    fun comment(existing: String, energy: Int): String {
        val tag = "Energy $energy"
        val c = existing.trim()
        return when {
            energyToken.containsMatchIn(c) -> energyToken.replace(c, tag)
            c.isEmpty() -> tag
            else -> "$c - $tag"
        }
    }

    /** A energia escrita no comentário ("Energy 7"), se houver. */
    fun energyOf(comment: String): Int? = energyToken.find(comment)?.value?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..10 }
}
