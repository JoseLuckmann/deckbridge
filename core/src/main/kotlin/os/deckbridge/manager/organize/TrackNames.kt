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
    fun clean(
        title: String, artist: String, fileName: String,
        /** Artistas que a biblioteca já conhece ([knownArtists]): ajudam a separar nomes sem separador. */
        known: Set<String> = emptySet(),
    ): Names {
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
        // Sem separador nenhum: pelo artista conhecido ou pela lista com vírgula ("A, B Título").
        if (a.isEmpty()) guessSplit(t, known)?.let { g -> a = g.artist; t = g.title }
        t = mixParens(tidy(t), known + knownArtists(listOf(a)))
        t = Organizer.titleCase(tidy(t))
        a = Organizer.titleCase(tidy(a))
        return Names(t.ifEmpty { tidy(title).ifEmpty { stem } }, a)
    }

    /**
     * Os artistas que a biblioteca já conhece: o campo artista de cada faixa e cada nome das listas ("A, B & C feat. D"
     * dá A, B, C e D, e também "A, B & C"). Para separar os nomes de arquivo sem separador ("Duke Dumont Your Love").
     */
    fun knownArtists(artists: Iterable<String>): Set<String> {
        val out = HashSet<String>()
        for (raw in artists) {
            val a = tidy(raw)
            if (a.length < 2) continue
            out += a
            a.split(Regex("""\s*(?:,|&|\bfeat\.|\bx\b|\bvs\.?|\band\b|\be\b)\s*""", RegexOption.IGNORE_CASE)).map { tidy(it) }.filter { it.length >= 2 }.forEach { out += it }
        }
        return out
    }

    /**
     * Separa "Artista Título" quando o começo do título é um artista conhecido (ou uma lista deles, "A, B"): o artista
     * mais longo que casa por palavras inteiras, e sobra título. Null quando nenhum casa.
     */
    fun splitByKnown(title: String, known: Set<String>): Names? {
        val t = tidy(title)
        val lower = t.lowercase()
        fun startsAt(pos: Int, name: String): Boolean {
            val n = name.lowercase()
            if (!lower.startsWith(n, pos)) return false
            val end = pos + n.length
            return end < lower.length && !lower[end].isLetterOrDigit()
        }
        val byLength = known.sortedByDescending { it.length }
        var pos = 0
        var artistEnd = -1
        // Um artista conhecido, e depois quantos ", B" / " & B" conhecidos vierem.
        while (true) {
            val name = byLength.firstOrNull { startsAt(pos, it) } ?: break
            artistEnd = pos + name.length
            val rest = t.substring(artistEnd)
            val joiner = Regex("""^\s*(,|&|feat\.|x)\s+""", RegexOption.IGNORE_CASE).find(rest) ?: break
            val next = artistEnd + joiner.value.length
            if (byLength.none { startsAt(next, it) }) break
            pos = next
        }
        if (artistEnd <= 0) return null
        val artist = tidy(t.substring(0, artistEnd))
        val rest = tidy(t.substring(artistEnd).trimStart(' ', '-', ',', '–'))
        if (rest.isEmpty()) return null
        return Names(rest, artist)
    }

    /**
     * "A, B Título" sem separador: o último artista da lista termina onde o nome casa com um artista conhecido ou com
     * um nome que aparece depois de "feat." no próprio texto ("Praise Cats, Andrea Love Shined On Me feat. Andrea Love").
     * Null se não dá para saber.
     */
    fun splitByCommaList(text: String, known: Set<String>): Names? {
        val t = tidy(text)
        val comma = t.indexOf(',')
        if (comma <= 0) return null
        // A lista de artistas é o começo: o último ", " antes de qualquer palavra de mix ou "feat".
        var last = comma
        while (true) {
            val nx = t.indexOf(',', last + 1)
            if (nx < 0 || nx > 120 || mixWord.containsMatchIn(t.substring(0, nx)) || "feat" in t.substring(0, nx).lowercase()) break
            last = nx
        }
        val head = t.substring(0, last)
        if (head.split(' ').size > 8 || mixWord.containsMatchIn(head) || "feat" in head.lowercase()) return null
        val after = t.substring(last + 1).trim().split(' ').filter { it.isNotEmpty() }
        val feats = Regex("""(?i)\bfeat\.?\s+(.+)$""").find(t)?.groupValues?.get(1)?.lowercase().orEmpty()
        val lowerKnown = known.map { it.lowercase() }.toSet()
        var best = 0
        for (k in 1..minOf(4, after.size - 1)) {
            val n = after.take(k).joinToString(" ").lowercase()
            val inFeat = feats.startsWith(n) && (feats.length == n.length || !feats[n.length].isLetterOrDigit())
            if (n in lowerKnown || inFeat) best = k
        }
        if (best == 0) return null
        return Names(after.drop(best).joinToString(" "), "$head, ${after.take(best).joinToString(" ")}")
    }

    /** Palavras que costumam abrir um título e quase nunca fazem parte de nome de artista ("At the Disko", "On My Mind"). */
    private val titleStarters = setOf(
        "the", "a", "an", "at", "in", "on", "of", "to", "my", "your", "me", "you", "i", "i'm", "im", "we", "it", "it's", "is",
        "all", "no", "for", "with", "from", "into", "up", "out", "this", "that", "what", "when", "don't", "can't", "let",
        "o", "os", "as", "de", "do", "da", "meu", "minha", "eu", "você", "na", "em", "um", "uma",
        "el", "la", "los", "las", "mi", "tu", "yo", "en", "que",
    )

    /**
     * "A, B Título" quando nada diz onde B acaba (nem artista conhecido, nem "feat."). Título de música quase nunca tem
     * vírgula: a vírgula no começo é lista de artistas, então separa sempre, com um palpite para o tamanho de B:
     * até 3 palavras, acabando antes de uma palavra que abre título ("Purple Disco Machine | At the Disko"); se não há,
     * acabando uma antes dela ("Purple Disco Machine | Something On My Mind"); senão 2 palavras (nome e sobrenome, o mais
     * comum). O título fica com pelo menos uma palavra antes do nome da mix. Null se o começo não parece lista.
     */
    fun splitByCommaGuess(text: String): Names? {
        val t = tidy(text)
        val last = t.lastIndexOf(',', startIndex = minOf(t.length - 1, 120)).takeIf { it > 0 } ?: return null
        val head = t.substring(0, last)
        if (head.split(' ').size > 8 || mixWord.containsMatchIn(head) || "feat" in head.lowercase() || '(' in head) return null
        val after = t.substring(last + 1).trim().split(' ').filter { it.isNotEmpty() }
        // Onde começa o nome da mix (ou "feat."): o título precisa de ao menos uma palavra antes.
        val mixAt = after.indices.firstOrNull { i ->
            after[i].lowercase().startsWith("feat") || after[i].startsWith("(") || after.drop(i).all { w -> mixWord.matches(w) || w.lowercase() in setOf("original", "main", "vocal") }
        } ?: after.size
        val max = minOf(3, mixAt - 1)
        if (max < 1) return null
        fun starter(i: Int) = i < after.size && after[i].lowercase().trim(',', '.', '!', '?') in titleStarters
        val k = (1..max).firstOrNull { starter(it) } ?: (1..max).firstOrNull { starter(it + 1) } ?: minOf(2, max)
        return Names(after.drop(k).joinToString(" "), "$head, ${after.take(k).joinToString(" ")}")
    }

    /**
     * A melhor divisão sem separador: artista conhecido no começo, a lista com vírgula que acaba num conhecido (ou no
     * nome depois de "feat."), ou — vírgula sem nada conhecido — o palpite de [splitByCommaGuess].
     */
    fun guessSplit(text: String, known: Set<String>): Names? = sureSplit(text, known) ?: splitByCommaGuess(text)

    /** Só as divisões certas (artista conhecido ou repetido depois de "feat."), sem palpite. */
    fun sureSplit(text: String, known: Set<String>): Names? = splitByKnown(text, known) ?: splitByCommaList(text, known)

    /**
     * O que é da mix no fim do título vai para parênteses: "Título Extended Mix" → "Título (Extended Mix)"; "Título feat.
     * Fulano Les Bisous Remix" → "Título (feat. Fulano) (Les Bisous Remix)" quando Fulano é conhecido; "Título Purple
     * Disco Machine Extended Remix" → "Título (Purple Disco Machine Extended Remix)" quando o remixer é conhecido.
     */
    fun mixParens(title: String, known: Set<String>): String {
        val t = tidy(title)
        if (t.endsWith(")") || t.endsWith("]")) return t
        val feat = Regex("""(?i)\s+feat\.\s+(.+)$""").find(t)
        if (feat != null) {
            val rest = feat.groupValues[1]
            val name = known.filter { k -> rest.lowercase().startsWith(k.lowercase()) && (rest.length == k.length || !rest[k.length].isLetterOrDigit()) }.maxByOrNull { it.length }
            if (name != null) {
                val tail = tidy(rest.substring(name.length))
                val base = t.substring(0, feat.range.first)
                return tidy("$base (feat. ${rest.substring(0, name.length)})" + if (tail.isNotEmpty()) " (${tail.trim('(', ')')})" else "")
            }
        }
        val std = Regex("""(?i)\s+((?:extended|original|club|radio|dub|instrumental|vocal|main|long|short|vip)\s+(?:mix|edit|version|remix|dub)|radio edit|extended)$""")
        // "<Remixer conhecido> ... Remix" primeiro: o nome do remixer é parte do que vai entre parênteses.
        if (Regex("""(?i)\b(remix|rework|dub)$""").containsMatchIn(t)) {
            val lower = t.lowercase()
            val hit = known.mapNotNull { k -> Regex("""\b${Regex.escape(k.lowercase())}\b""").find(lower)?.let { it.range.first to k } }
                .filter { it.first > 0 }.minByOrNull { it.first }
            if (hit != null) return tidy(t.substring(0, hit.first)) + " (" + tidy(t.substring(hit.first)) + ")"
        }
        std.find(t)?.let { m -> return tidy(t.substring(0, m.range.first)) + " (" + tidy(m.groupValues[1]) + ")" }
        return t
    }

    /** Separa nas primeiras [words] palavras: elas são o artista, o resto é o título (o DJ toca onde o artista acaba). */
    fun splitAtWord(title: String, words: Int): Names {
        val parts = tidy(title).split(' ')
        val k = words.coerceIn(1, (parts.size - 1).coerceAtLeast(1))
        return Names(parts.drop(k).joinToString(" "), parts.take(k).joinToString(" ").trimEnd(',', '-', ' '))
    }

    /** Chave para comparar nomes (sem caixa, acentos, pontuação, "feat." e o nome da mix). */
    fun key(n: Names): String {
        fun base(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("""\bfeat\..*$"""), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return base(n.artist) + "|" + base(n.title.replace(Regex("""\s*[(\[](?i:original mix|original|extended mix|extended)[)\]]"""), ""))
    }
}
