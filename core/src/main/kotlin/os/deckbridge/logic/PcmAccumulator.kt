package os.deckbridge.logic

import java.nio.ByteBuffer

/**
 * Acumula PCM de 16 bit intercalado vindo do decodificador em dois vetores de float. Dimensionado
 * pela duração do arquivo, cresce só se a estimativa errar, e corta no limite de quadros.
 */
class PcmAccumulator(private val sampleRate: Int, private val channels: Int, estimateFrames: Int, private val capFrames: Int) {
    private var left = FloatArray(maxOf(1, minOf(estimateFrames, capFrames)) + sampleRate / 2)
    private var right = FloatArray(left.size)
    var count = 0
        private set
    var truncated = false
        private set

    val full: Boolean get() = truncated

    /** Lê `frames` quadros de `buffer`, já posicionado. Devolve false quando o limite foi atingido. */
    fun append(buffer: ByteBuffer, frames: Int): Boolean {
        if (truncated) return false
        if (count + frames > left.size) {
            val target = minOf(capFrames + sampleRate, maxOf(count + frames + sampleRate, left.size + left.size / 4))
            left = left.copyOf(target)
            right = right.copyOf(target)
        }
        for (n in 0 until frames) {
            if (count >= capFrames || count >= left.size) {
                truncated = true
                return false
            }
            val first = buffer.short.toInt() / 32768f
            if (channels == 1) {
                left[count] = first
                right[count] = first
            } else {
                left[count] = first
                right[count] = buffer.short.toInt() / 32768f
                for (extra in 2 until channels) buffer.short
            }
            count += 1
        }
        if (count >= capFrames) truncated = true
        return !truncated
    }

    fun result(): DecodedPcm? = if (count == 0) null else DecodedPcm(left, right, sampleRate, count, truncated)
}
