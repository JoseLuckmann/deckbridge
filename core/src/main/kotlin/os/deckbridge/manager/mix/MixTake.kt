package os.deckbridge.manager.mix

import kotlin.math.abs

/**
 * Um controle do mixer da prévia, como na controladora: em cada canal o fader, HI, MID e LOW (0 é o corte total, 1 o
 * meio-dia), o FILTER (0,5 é desligado; para a esquerda passa-baixa, para a direita passa-alta) e o ECHO (0 ou 1).
 */
enum class Ctl(val deck: Int, val label: String, val neutral: Float) {
    A_VOL(0, "FADER", 1f), A_HIGH(0, "HI", 1f), A_MID(0, "MID", 1f), A_LOW(0, "LOW", 1f), A_FILTER(0, "FILTER", 0.5f), A_ECHO(0, "ECHO", 0f),
    B_VOL(1, "FADER", 1f), B_HIGH(1, "HI", 1f), B_MID(1, "MID", 1f), B_LOW(1, "LOW", 1f), B_FILTER(1, "FILTER", 0.5f), B_ECHO(1, "ECHO", 0f),
    ;

    companion object {
        fun of(deck: Int, label: String): Ctl = entries.first { it.deck == deck && it.label == label }
    }
}

/** Um ponto gravado: em [bar] compassos do ponto de saída, o controle estava em [v] (NaN: daqui volta ao automático). */
data class Pt(val bar: Double, val v: Float)

/**
 * O que a mão do DJ fez num controle, ao longo da troca. Como um botão de verdade, o valor fica onde a mão o deixou
 * ("latch", como a automação das mesas de som e dos programas de áudio) até o próximo ponto; entre pontos próximos (um
 * gesto contínuo) o valor anda em linha reta. Um ponto NaN devolve o controle ao automático.
 */
class Lane(val points: List<Pt>) {
    /** O valor em [bar], ou null onde vale o automático. */
    fun at(bar: Double): Float? {
        if (points.isEmpty() || bar < points[0].bar) return null
        // Busca binária pelo último ponto em ou antes de [bar].
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (points[mid].bar <= bar) lo = mid else hi = mid - 1
        }
        val p = points[lo]
        if (p.v.isNaN()) return null
        val next = points.getOrNull(lo + 1) ?: return p.v
        if (next.v.isNaN() || next.bar - p.bar > GESTURE_GAP_BARS) return p.v
        val f = ((bar - p.bar) / (next.bar - p.bar)).toFloat()
        return p.v + (next.v - p.v) * f
    }

    companion object {
        /** Dois pontos mais afastados que isso não são o mesmo gesto: o valor fica parado entre eles. */
        const val GESTURE_GAP_BARS = 0.5
    }
}

/**
 * A mixagem do DJ numa troca: o que ele fez com cada controle (só os que tocou; o resto segue o automático). Guardada no
 * plano do set, junto da transição: é "a mixagem certa desta transição deste set". Ver `docs/ux-mixagem.md`.
 */
class MixTake(val lanes: Map<Ctl, Lane>) {
    val isEmpty get() = lanes.values.all { it.points.isEmpty() }

    fun at(c: Ctl, bar: Double): Float? = lanes[c]?.at(bar)

    /**
     * Junta uma passada nova: em cada controle que a mão tocou, o que ela fez do primeiro toque até [end] (onde a passada
     * parou) substitui o que havia nesse trecho; fora dele, fica o que já estava. Se depois de [end] havia outra coisa
     * gravada, o valor volta a ela em [end].
     */
    fun merge(pass: Map<Ctl, List<Pt>>, end: Double): MixTake {
        val out = lanes.toMutableMap()
        for ((c, pts) in pass) {
            if (pts.isEmpty()) continue
            val from = pts.first().bar
            val until = maxOf(end, pts.last().bar)
            val old = lanes[c]
            val keep = old?.points.orEmpty().filter { it.bar < from || it.bar > until }
            val resume = old?.let { o -> if (o.points.any { it.bar > until }) listOf(Pt(until + 1e-6, o.at(until) ?: Float.NaN)) else emptyList() }.orEmpty()
            out[c] = Lane((keep + pts + resume).sortedBy { it.bar })
        }
        return MixTake(out)
    }

    /** Sem a mão nos controles de [deck] a partir de [bar]: voltam ao automático dali. */
    fun release(deck: Int, bar: Double): MixTake =
        MixTake(lanes.mapValues { (c, l) -> if (c.deck == deck && l.at(bar) != null) Lane((l.points.filter { it.bar < bar } + Pt(bar, Float.NaN)).sortedBy { it.bar }) else l })

    /** Texto compacto para o arquivo do set: `A_VOL:0.0=1.0,1.5=0.8|B_LOW:...` (NaN como "auto"). */
    fun encode(): String = lanes.filterValues { it.points.isNotEmpty() }.entries.joinToString("|") { (c, l) ->
        c.name + ":" + l.points.joinToString(",") { p -> "%.4f=%s".format(java.util.Locale.ROOT, p.bar, if (p.v.isNaN()) "auto" else "%.3f".format(java.util.Locale.ROOT, p.v)) }
    }

    override fun equals(other: Any?) = other is MixTake && encode() == other.encode()
    override fun hashCode() = encode().hashCode()
    override fun toString() = "MixTake(${encode()})"

    companion object {
        val EMPTY = MixTake(emptyMap())

        fun decode(text: String?): MixTake? {
            if (text.isNullOrBlank()) return null
            val lanes = text.split('|').mapNotNull { part ->
                val c = runCatching { Ctl.valueOf(part.substringBefore(':')) }.getOrNull() ?: return@mapNotNull null
                val pts = part.substringAfter(':').split(',').mapNotNull { pv ->
                    val bar = pv.substringBefore('=').toDoubleOrNull() ?: return@mapNotNull null
                    val v = pv.substringAfter('=').let { if (it == "auto") Float.NaN else it.toFloatOrNull() } ?: return@mapNotNull null
                    Pt(bar, v)
                }
                c to Lane(pts.sortedBy { it.bar })
            }.toMap()
            return MixTake(lanes).takeIf { !it.isEmpty }
        }
    }
}

/**
 * Grava uma passada: cada mudança da mão num controle vira um ponto (só se o valor andou, para o arquivo não crescer à
 * toa). A passada termina quando a música para, pula ou repete; aí ela é juntada à mixagem ([MixTake.merge]).
 */
class TakeRecorder {
    private val pass = HashMap<Ctl, MutableList<Pt>>()

    /** Cópia para quem toca (outra thread): troca a referência inteira a cada mudança. */
    @Volatile var snapshot: Map<Ctl, Lane> = emptyMap()
        private set

    val touched get() = pass.keys.toSet()
    val isEmpty get() = pass.isEmpty()

    fun set(c: Ctl, bar: Double, v: Float) {
        val pts = pass.getOrPut(c) { ArrayList() }
        val last = pts.lastOrNull()
        // A mão voltou no tempo (a cabeça pulou para trás sem fechar a passada): recomeça o controle dali.
        if (last != null && bar < last.bar) pts.removeAll { it.bar >= bar }
        if (last != null && abs(last.v - v) < 0.004f && bar - last.bar < Lane.GESTURE_GAP_BARS) return
        pts += Pt(bar, v)
        // Só a fileira que mudou é copiada (antes, todas a cada movimento do dedo: lixo a cada quadro arrastando).
        snapshot = snapshot + (c to Lane(pts.toList()))
    }

    /** O valor da passada em [bar] (null: a mão não tocou neste controle até aqui). */
    fun at(c: Ctl, bar: Double): Float? = snapshot[c]?.at(bar)

    /** Fecha a passada em [end]: devolve o que foi tocado e limpa. */
    fun close(): Map<Ctl, List<Pt>> {
        val out = pass.mapValues { it.value.toList() }
        pass.clear()
        snapshot = emptyMap()
        return out
    }
}

/**
 * O que o DJ costuma fazer, aprendido das mixagens que ele guardou: em que ponto da troca ele passa o grave e quanto
 * tempo leva para tirar a que sai. Com isso as trocas automáticas novas começam do jeito dele (programação por
 * demonstração: Cypher 1993; aprendizado com a pessoa no laço: Amershi et al. 2014). Precisa de [MIN_TAKES] mixagens.
 */
data class MixHabits(val swapAt: Double?, val pace: Transition.Pace?, val takes: Int) {
    val ready get() = takes >= MIN_TAKES

    companion object {
        const val MIN_TAKES = 3

        /** Onde (fração da troca) o grave da que entra passa de meio — a troca de grave do DJ. Null se ele não mexeu. */
        fun swapPoint(tr: Transition, take: MixTake): Double? {
            val lane = take.lanes[Ctl.B_LOW] ?: return null
            val p = lane.points.firstOrNull { !it.v.isNaN() && it.v >= 0.5f && it.bar >= 0 } ?: return null
            return (p.bar / tr.bars).coerceIn(0.0, 1.0)
        }

        /** Que parte final da troca a que sai leva para sumir (o fader do CH 1 desce de 95% a 5%). */
        fun fadeShare(tr: Transition, take: MixTake): Double? {
            val lane = take.lanes[Ctl.A_VOL] ?: return null
            val start = lane.points.firstOrNull { !it.v.isNaN() && it.v < 0.95f && it.bar >= 0 } ?: return null
            return ((tr.bars - start.bar) / tr.bars).coerceIn(0.0, 1.0)
        }

        fun learn(saved: List<Transition>): MixHabits {
            val withTake = saved.filter { it.take != null && it.style == MixStyle.BASS_SWAP }
            val swaps = withTake.mapNotNull { swapPoint(it, it.take!!) }
            val fades = withTake.mapNotNull { fadeShare(it, it.take!!) }
            fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            // O ponto do grave nos quartos que a tela oferece; o tempo de saída no ritmo mais perto.
            val swap = median(swaps)?.let { Math.round(it * 4).coerceIn(1, 4) / 4.0 }
            val pace = median(fades)?.let { f ->
                listOf(Transition.Pace.FAST to 0.125, Transition.Pace.NORMAL to 0.25, Transition.Pace.SLOW to 0.5).minByOrNull { abs(it.second - f) }!!.first
            }
            return MixHabits(swap.takeIf { swaps.size >= MIN_TAKES }, pace.takeIf { fades.size >= MIN_TAKES }, withTake.size)
        }

        fun load(file: java.io.File): MixHabits? = runCatching {
            val p = java.util.Properties().apply { file.inputStream().use { load(it) } }
            MixHabits(p.getProperty("swapAt")?.toDoubleOrNull(), p.getProperty("pace")?.let { Transition.Pace.valueOf(it) }, p.getProperty("takes")?.toIntOrNull() ?: 0)
        }.getOrNull()

        fun save(file: java.io.File, h: MixHabits) {
            val p = java.util.Properties()
            h.swapAt?.let { p.setProperty("swapAt", it.toString()) }
            h.pace?.let { p.setProperty("pace", it.name) }
            p.setProperty("takes", h.takes.toString())
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it, "DeckBridge: o jeito de mixar aprendido das mixagens guardadas") }
        }
    }

    /** Aplica o jeito do DJ numa troca automática de grave (o grave no drop, quando a troca vai até ele, fica). */
    fun apply(t: Transition, basis: SetPlanner.Basis): Transition {
        if (!ready || t.style != MixStyle.BASS_SWAP) return t
        val swap = if (basis == SetPlanner.Basis.DROP && t.swapAt >= 0.99) t.swapAt else swapAt ?: t.swapAt
        return t.copy(swapAt = swap, pace = pace ?: t.pace)
    }
}
