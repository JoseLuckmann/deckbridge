package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import java.nio.ByteBuffer

/**
 * Junta o áudio que o decodificador entrega (PCM 16 bits intercalado) já em mono e reduzido para perto de 22 kHz, aos
 * pedaços: nunca guarda a faixa inteira em estéreo a 44,1 kHz (uns 170 MB numa faixa de 8 minutos, o que estourava a
 * memória do celular no meio de uma importação). Mono usa os dois primeiros canais; os outros são consumidos.
 */
class MonoSink(rate: Int, private val channels: Int, estimateFrames: Int, private val capFrames: Int) {
    /** Quantos quadros de entrada viram um de saída (44,1 kHz → 2, 48 kHz → 2, 22,05 kHz → 1). */
    val factor = (rate / 22050).coerceAtLeast(1)
    val outRate = rate / factor
    private var out = FloatArray((estimateFrames / factor).coerceAtMost(capFrames / factor + 1).coerceAtLeast(1))
    private var size = 0
    private var acc = 0f
    private var inStep = 0
    private var frames = 0

    /** Chegou ao limite de duração (o resto da faixa é ignorado). */
    val full get() = frames >= capFrames

    /** Acrescenta [count] quadros de [buffer] (posicionado no começo deles). */
    fun append(buffer: ByteBuffer, count: Int) {
        val shorts = buffer.asShortBuffer()
        for (f in 0 until count) {
            if (frames >= capFrames) return
            val base = f * channels
            val l = shorts.get(base) / 32768f
            val r = if (channels > 1) shorts.get(base + 1) / 32768f else l
            acc += (l + r) * 0.5f
            frames++
            if (++inStep == factor) {
                if (size == out.size) out = out.copyOf(out.size + maxOf(1024, out.size / 2))
                out[size++] = acc / factor
                acc = 0f
                inStep = 0
            }
        }
    }

    /** O áudio mono, do tamanho certo, ou null se nada chegou. */
    fun result(): FloatArray? = if (size == 0) null else if (size == out.size) out else out.copyOf(size)
}
