package os.deckbridge.manager.organize

/**
 * Título e artista limpos, a partir das etiquetas do arquivo e do nome dele, como um DJ escreveria na biblioteca:
 * "Artista - Título (Nome da Mix)". Regras fixas e explicáveis, sem consulta a serviço nenhum:
 *
 * - tira o número que a fila de importação punha na frente da cópia (`0003-`, versões até 0.24.0), o número da faixa
 *   (`01 - `, `07. `, `A1 `) e o tom/BPM que programas de análise põem na frente (`8A - 124 - `);
 * - `_` vira espaço e `_-_` vira o separador; travessões (– —) também;
 * - separa "Artista - Título" quando o artista não veio nas etiquetas (ou veio grudado no título);
 * - "Título - Extended Mix" vira "Título (Extended Mix)";
 * - tira lixo de vídeo e de site: "(Official Video)", "[Free Download]", "(Lyrics)", "www.site.com", "320 kbps",
 *   " - Copy", "(1)" de cópia; mantém o que é da música (Original Mix, Remix, Edit, Dub, VIP, feat.);
 * - "ft." / "featuring" vira "feat."; TUDO EM MAIÚSCULAS ou tudo minúsculo vira Título Com Maiúsculas.
 */
object TrackNames {
    data class Names(val title: String, val artist: String)

    /** O número que a fila de importação punha na frente da cópia (até 0.24.0): sempre 4 dígitos e hífen colado. */
    private val importPrefix = Regex("""^\d{4}-(?=\S)""")

    /** Número de faixa: "01 - ", "07. ", "3) ", "A1 ", "12_"; sem zero na frente, só com separador ("808 State" fica). */
    private val trackNumber = Regex("""^(?:[A-Da-d]?\d{1,3}\s*(?:[.)_]|-(?!\d))\s*|0\d\s+|[A-Da-d]\d\s+)""")

    /** Tom ou BPM na frente, como os programas de análise renomeiam: "8A - ", "08B - ", "124 - ", "124bpm - ". */
    private val keyOrBpm = Regex("""^(?:\d{1,2}[ABab]|\d{2,3}(?:[.,]\d+)?\s*(?:bpm|BPM)?)\s+-\s+""")

    private val junkWords = listOf(
        "official", "video", "audio", "lyric", "lyrics", "letra", "legendado", "visualizer", "visualiser", "hq", "hd", "4k",
        "free download", "download", "premiere", "out now", "explicit", "clean", "clip officiel", "videoclip", "oficial",
        "full song", "full version hd",
    )

    /** O que é da música e fica no título entre parênteses. */
    private val mixWord = Regex("""(?i)\b(mix|remix|edit|dub|version|versão|rework|vip|bootleg|remaster(?:ed)?|instrumental|extended|radio|club|acapella|a cappella|flip|refix|reprise|live)\b""")

    private val bracket = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")

    fun tidy(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Limpa um pedaço (título, artista ou nome de arquivo sem extensão), sem separar artista de título. */
    fun scrub(raw: String): String {
        var s = raw.replace("%20", " ")
        if ('_' in s) s = s.replace(Regex("_+-_+"), " - ").replace('_', ' ')
        s = s.replace('–', '-').replace('—', '-').replace('‒', '-')
        // Separador com espaço de um lado só ("Artista -Título", "Artista- Título"); hífen de palavra (Jay-Z) fica.
        s = s.replace(Regex("""(?<=\S)\s+-(?=[^\s\d-])"""), " - ").replace(Regex("""(?<=[^\s\d-])-\s+(?=\S)"""), " - ")
        s = tidy(s)
        s = importPrefix.replace(s, "")
        // Lixo entre parênteses ou colchetes; o resto (Original Mix, feat., Remix) fica.
        s = bracket.replace(s) { m ->
            val inside = m.groupValues[1].trim()
            val lower = inside.lowercase()
            val junk = inside.isEmpty() || lower.matches(Regex("""\d{1,2}""")) || "www." in lower || Regex("""\.(com|net|org|ru|br|io|me)\b""").containsMatchIn(lower) ||
                Regex("""\d{2,3}\s*kbps""").containsMatchIn(lower) ||
                (!mixWord.containsMatchIn(inside) && "feat" !in lower && " ft" !in " $lower" && junkWords.any { w -> Regex("""(^|\W)${Regex.escape(w)}(\W|$)""").containsMatchIn(lower) })
            if (junk) "" else m.value
        }
        s = s.replace(Regex("""(?i)\s*-?\s*(www\.\S+|\S+\.(com|net|org)\b)"""), "")
        s = s.replace(Regex("""(?i)\s*-?\s*\d{2,3}\s*kbps\b"""), "")
        s = s.replace(Regex("""(?i)\s*-?\s*\bcopy$"""), "").replace(Regex("""(?i)\s*-\s*c[oó]pia$"""), "")
        s = s.replace(Regex("""(?i)\b(featuring|feat|ft)\.?\s+"""), "feat. ")
        return tidy(s).trim('-', ' ').let(::tidy)
    }

    /** Tira número de faixa e tom/BPM da frente; o número só sai quando sobra texto. */
    private fun stripLead(s: String, requireSeparator: Boolean): String {
        var out = s
        repeat(3) {
            val k = keyOrBpm.find(out)
            if (k != null && (!requireSeparator || " - " in out.substring(k.range.last + 1))) out = out.substring(k.range.last + 1)
        }
        trackNumber.find(out)?.let { m -> if (out.length - m.range.last > 2) out = out.substring(m.range.last + 1) }
        return tidy(out)
    }

    /** "Extended Mix", "Tale Of Us Remix", "Radio Edit": curto, sem parênteses e terminando no tipo da mix. */
    private val mixTail = Regex("""(?i)^[^()\[\]]{0,30}\b(mix|remix|edit|dub|version|versão|rework|vip|bootleg|remaster(?:ed)?|instrumental|refix|reprise)$""")

    private fun isMix(part: String) = mixTail.matches(part)

    /** "Artista - Título [- Mix]" → (artista ou null, título). */
    fun split(text: String): Pair<String?, String> {
        val parts = text.split(" - ").map { tidy(it) }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 && isMix(parts.last()) -> parts[0] to (parts.subList(1, parts.lastIndex).joinToString(" - ") + " (${parts.last()})")
            parts.size >= 2 && parts.size == 2 && isMix(parts[1]) && !isMix(parts[0]) -> null to "${parts[0]} (${parts[1]})"
            parts.size >= 2 -> parts[0] to parts.drop(1).joinToString(" - ")
            else -> null to (parts.firstOrNull() ?: "")
        }
    }

    /** Parece nome de arquivo em vez de título? (sublinhados, número da importação, extensão de áudio.) */
    private fun looksLikeFile(t: String) = '_' in t || importPrefix.containsMatchIn(t) || Regex("""(?i)\.(mp3|wav|flac|aiff?|m4a|aac|ogg)$""").containsMatchIn(t)

    /**
     * Os nomes limpos de uma faixa: [title] e [artist] das etiquetas (ou do que está na biblioteca) e [fileName] (com ou
     * sem extensão e pasta). Se as etiquetas não dizem, vale o nome do arquivo.
     */
    fun clean(title: String, artist: String, fileName: String): Names {
        val stem = fileName.substringAfterLast('/').substringAfterLast('\\').let { n -> if ('.' in n && n.substringAfterLast('.').length in 2..4) n.substringBeforeLast('.') else n }
        var t = tidy(title)
        var a = tidy(artist)
        if (t.isEmpty() || looksLikeFile(t) || t.equals(stem, ignoreCase = true)) {
            // O título é o próprio nome do arquivo (ou falta): ler o nome inteiro, com o artista se estiver nele.
            val (fa, ft) = split(stripLead(scrub(if (t.isEmpty()) stem else t.removeSuffix("." + t.substringAfterLast('.', ""))), requireSeparator = true))
            t = ft
            // Com artista nas etiquetas, o do nome do arquivo só serve se o das etiquetas também é nome de arquivo.
            if (fa != null && (a.isEmpty() || looksLikeFile(a) || importPrefix.containsMatchIn(a))) a = fa
        } else {
            t = scrub(t)
            if (a.isEmpty() && " - " in t) {
                val (fa, ft) = split(stripLead(t, requireSeparator = true))
                if (fa != null) { a = fa; t = ft }
            } else {
                t = stripLead(t, requireSeparator = true)
                // "Título - Extended Mix" (sem artista grudado).
                val (fa, ft) = split(t)
                if (fa == null) t = ft
            }
        }
        a = stripLead(scrub(a), requireSeparator = false)
        // O artista grudado no começo do título: "Artista - Título".
        if (a.isNotEmpty() && t.startsWith("$a - ", ignoreCase = true)) t = t.substring(a.length + 3)
        if (a.isEmpty()) {
            val (fa, _) = split(stripLead(scrub(stem), requireSeparator = true))
            if (fa != null && !looksLikeFile(fa)) a = fa
        }
        t = Organizer.titleCase(tidy(t))
        a = Organizer.titleCase(tidy(a))
        return Names(t.ifEmpty { tidy(title).ifEmpty { stem } }, a)
    }

    /** Chave para comparar nomes (sem caixa, acentos, pontuação, "feat." e o nome da mix). */
    fun key(n: Names): String {
        fun base(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("""\bfeat\..*$"""), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return base(n.artist) + "|" + base(n.title.replace(Regex("""\s*[(\[](?i:original mix|original|extended mix|extended)[)\]]"""), ""))
    }
}
