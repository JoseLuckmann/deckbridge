package os.deckbridge.manager.organize

/**
 * As cores padrão da faixa (o campo Color do rekordbox: 1 rosa, 2 vermelho, 3 laranja, 4 amarelo, 5 verde, 6 água,
 * 7 azul, 8 roxo) pela energia ou pelo gênero, escolhidas pelo DJ. Valem para faixa sem cor: a cor que o DJ deu nunca
 * é trocada.
 */
data class ColorRules(
    val mode: Mode = Mode.OFF,
    /** A cor de cada faixa de energia: 1–3, 4–5, 6–7 e 8–10 (do frio ao quente, como se costuma colorir a energia). */
    val byEnergy: List<Int> = listOf(7, 5, 4, 2),
    /** A cor de cada gênero (sem caixa); gênero sem cor fica sem cor. */
    val byGenre: Map<String, Int> = DEFAULT_GENRES,
) {
    enum class Mode(val title: String) { OFF("Desligado"), ENERGY("Por energia"), GENRE("Por gênero") }

    /** A cor (1–8) para uma faixa com [energy] e [genre], ou null quando a regra não dá cor. */
    fun colorFor(energy: Int?, genre: String): Int? = when (mode) {
        Mode.OFF -> null
        Mode.ENERGY -> energy?.let { byEnergy.getOrNull(band(it)) }
        Mode.GENRE -> byGenre[genre.trim().lowercase()]
    }?.takeIf { it in 1..8 }

    fun withEnergy(band: Int, color: Int) = copy(byEnergy = byEnergy.toMutableList().also { it[band] = color })

    fun withGenre(genre: String, color: Int?) =
        copy(byGenre = byGenre.toMutableMap().also { if (color == null) it.remove(genre.trim().lowercase()) else it[genre.trim().lowercase()] = color })

    /** Uma linha de texto para guardar (modo; cores da energia; gênero=cor,...). */
    fun encode(): String = mode.name + ";" + byEnergy.joinToString(",") + ";" + byGenre.entries.joinToString(",") { "${it.key.replace(",", " ").replace("=", " ")}=${it.value}" }

    companion object {
        /** As faixas de energia, para a tela. */
        val BANDS = listOf("1–3", "4–5", "6–7", "8–10")

        fun band(energy: Int) = when {
            energy <= 3 -> 0
            energy <= 5 -> 1
            energy <= 7 -> 2
            else -> 3
        }

        /** Os gêneros que a classificação palpita, cada um com uma cor de partida (o DJ troca). */
        val DEFAULT_GENRES = mapOf(
            "house" to 3, "deep house" to 7, "techno" to 8, "nu disco" to 1, "electro house" to 4,
            "bass music" to 2, "drum & bass" to 5, "trap" to 6, "acid house" to 4,
        )

        fun decode(text: String?): ColorRules {
            if (text.isNullOrBlank()) return ColorRules()
            val parts = text.split(';')
            val mode = runCatching { Mode.valueOf(parts[0]) }.getOrDefault(Mode.OFF)
            val energy = parts.getOrNull(1)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 } ?: ColorRules().byEnergy
            val genres = parts.getOrNull(2)?.split(',')?.filter { '=' in it }?.associate { it.substringBefore('=').trim().lowercase() to (it.substringAfter('=').toIntOrNull() ?: 0) }
                ?.filterValues { it in 1..8 } ?: DEFAULT_GENRES
            return ColorRules(mode, energy, genres)
        }
    }
}
