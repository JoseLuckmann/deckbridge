package os.deckbridge.manager.mix

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * O set inteiro como se fosse tocado: cada faixa do começo (ou de onde a virada anterior a deixou) até o ponto de saída,
 * a virada planejada (com a mixagem do DJ, se houver) e a próxima segue de onde a virada a deixou. Feito aos poucos
 * enquanto toca — uma thread de fundo lê e mixa até [AHEAD_MS] à frente —, sem montar um arquivo de uma hora.
 *
 * As faixas fora das viradas tocam no andamento delas; a virada toca no andamento da que sai (a que entra estica até ela e
 * volta ao dela quando a virada acaba: um degrau do tamanho da diferença de BPM, como soltar o pitch no fim da troca).
 */
class SetStream(
    private val tracks: List<Track>,
    private val transitions: List<Transition>,
    /** Monta a virada [i] sem folga antes e depois (bloqueante: lê e estica). */
    private val render: (Int) -> MixPreview?,
) : PcmSource {
    /** Uma faixa do set: o leitor do áudio dela, o BPM e a duração (ms). */
    class Track(val title: String, val reader: SegmentReader, val bpm: Double, val durationMs: Int)

    /** Um trecho da linha do tempo do set: uma faixa (de [fromMs] a [toMs] dela) ou a virada [index]. */
    data class Piece(val index: Int, val isTransition: Boolean, val startMs: Int, val lengthMs: Int, val fromMs: Int = 0, val toMs: Int = 0) {
        val endMs get() = startMs + lengthMs
    }

    override val rate = MixRenderer.RATE

    /** A linha do tempo (prevista pela grade: a posição real da que entra depois da virada sai da prévia montada). */
    val pieces: List<Piece> = buildList {
        var t = 0
        var from = 0
        for ((i, tr) in tracks.withIndex()) {
            val tx = transitions.getOrNull(i)
            val to = (tx?.outMs ?: tr.durationMs).coerceIn(from, tr.durationMs.coerceAtLeast(from))
            add(Piece(i, false, t, to - from, from, to))
            t += to - from
            if (tx != null && i + 1 < tracks.size) {
                val len = (tx.bars * SetPlanner.barMs(tr.bpm)).toInt()
                add(Piece(i, true, t, len))
                t += len
                val b = tracks[i + 1]
                from = (tx.inMs + (tx.bars - tx.bLoopExtra) * SetPlanner.barMs(b.bpm)).toInt().coerceIn(0, b.durationMs)
            }
        }
    }

    val lengthMs get() = pieces.lastOrNull()?.endMs ?: 0

    /** Trechos para o player: cada faixa pelo nome e cada virada como "A → B". */
    fun chapters(): List<Pair<Int, String>> = pieces.map { p ->
        p.startMs to if (p.isTransition) "${tracks[p.index].title} → ${tracks[p.index + 1].title}" else tracks[p.index].title
    }

    private class Block(val startMs: Int, val left: FloatArray, val right: FloatArray, val generation: Int)

    private val queue = LinkedBlockingDeque<Block>()
    @Volatile private var generation = 0
    @Volatile private var startAt = 0
    @Volatile private var finished = false
    @Volatile private var closed = false
    private val lock = Object()
    private var current: Block? = null
    private var offset = 0
    @Volatile private var position = 0

    override val renderedMs get() = position

    private val producer = Thread({ produce() }, "deckbridge-set-inteiro").apply { isDaemon = true; start() }

    override fun seek(ms: Int) {
        synchronized(lock) {
            generation++
            startAt = ms.coerceIn(0, lengthMs)
            finished = false
            queue.clear()
            current = null
            position = startAt
            lock.notifyAll()
        }
    }

    /** Para a thread de fundo (o player parou). */
    override fun close() {
        closed = true
        synchronized(lock) { generation++; lock.notifyAll() }
        queue.clear()
    }

    override fun read(left: FloatArray, right: FloatArray, frames: Int): Int {
        var done = 0
        while (done < frames) {
            var b = current
            if (b == null || offset >= b.left.size) {
                // Espera o que a thread de fundo prepara (logo depois de pular, uns instantes).
                b = queue.poll(150, TimeUnit.MILLISECONDS)
                if (b == null) {
                    if (finished && queue.isEmpty() || closed) return done
                    if (done > 0) return done
                    continue
                }
                if (b.generation != generation) continue
                current = b
                offset = 0
            }
            val n = minOf(frames - done, b.left.size - offset)
            System.arraycopy(b.left, offset, left, done, n)
            System.arraycopy(b.right, offset, right, done, n)
            offset += n
            done += n
            position = b.startMs + (offset.toLong() * 1000 / rate).toInt()
        }
        return done
    }

    private fun produce() {
        while (!closed) {
            val gen: Int
            val from: Int
            synchronized(lock) {
                gen = generation
                from = startAt
            }
            runCatching { fill(gen, from) }
            synchronized(lock) {
                if (gen == generation && !closed) finished = true
                while (gen == generation && !closed) lock.wait()
            }
        }
    }

    private fun stale(gen: Int) = gen != generation || closed

    /** Espera a fila andar até ficar a menos de [AHEAD_MS] à frente do que toca. */
    private fun waitRoom(gen: Int, nextStart: Int) {
        while (!stale(gen) && nextStart - position > AHEAD_MS) Thread.sleep(50)
    }

    private fun put(gen: Int, startMs: Int, s: Stereo, fadeIn: Boolean, fadeOut: Boolean) {
        if (stale(gen) || s.frames == 0) return
        // Emendas entre faixa e virada: 5 ms de rampa nos dois lados, para a fase diferente da prévia esticada não estalar.
        val ramp = minOf(rate / 200, s.frames / 2)
        if (fadeIn) for (i in 0 until ramp) { val g = i.toFloat() / ramp; s.left[i] *= g; s.right[i] *= g }
        if (fadeOut) for (i in 0 until ramp) { val k = s.frames - 1 - i; val g = i.toFloat() / ramp; s.left[k] *= g; s.right[k] *= g }
        queue.put(Block(startMs, s.left, s.right, gen))
    }

    private fun fill(gen: Int, from: Int) {
        var first = pieces.indexOfFirst { from < it.endMs }
        if (first < 0) return
        // Onde a faixa seguinte começa de verdade (pela prévia montada da virada).
        var bodyStart: Int? = null
        while (first < pieces.size && !stale(gen)) {
            val p = pieces[first]
            val skip = (from - p.startMs).coerceAtLeast(0)
            if (p.isTransition) {
                waitRoom(gen, p.startMs)
                val prev = render(p.index)
                if (prev == null) {
                    bodyStart = null
                } else {
                    val player = MixPlayer(prev, transitions[p.index])
                    player.seek(skip)
                    val chunk = rate * 2
                    var at = p.startMs + skip
                    while (!stale(gen)) {
                        val l = FloatArray(chunk)
                        val r = FloatArray(chunk)
                        val n = player.read(l, r, chunk)
                        if (n <= 0) break
                        val ms = (n.toLong() * 1000 / rate).toInt()
                        val last = at + ms >= p.endMs - 1
                        put(gen, at, Stereo(l.copyOf(n), r.copyOf(n), rate), fadeIn = at == p.startMs, fadeOut = last)
                        at += ms
                        if (last) break
                        waitRoom(gen, at)
                    }
                    bodyStart = prev.bAt(prev.leadMs + prev.overlapMs).coerceAtLeast(0)
                }
            } else {
                val t = tracks[p.index]
                // Depois de uma virada tocada daqui, a faixa segue de onde a prévia deixou; pulando para cá, pela grade.
                val start = (if (skip == 0 && bodyStart != null) bodyStart else p.fromMs + skip).coerceAtMost(p.toMs)
                var pos = start
                var at = p.startMs + skip
                while (pos < p.toMs && !stale(gen)) {
                    waitRoom(gen, at)
                    val len = minOf(4_000, p.toMs - pos)
                    val pcm = t.reader.read(pos, len) ?: break
                    val s = MixRenderer.resample(pcm, 1.0)
                    put(gen, at, s, fadeIn = pos == start && p.index > 0 && skip == 0, fadeOut = pos + len >= p.toMs && first + 1 < pieces.size)
                    pos += len
                    at += len
                }
                bodyStart = null
            }
            first++
        }
    }

    companion object {
        /** Quanto a thread de fundo prepara à frente do que toca. */
        const val AHEAD_MS = 45_000
    }
}
