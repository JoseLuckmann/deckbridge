package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Onda colorida do export, só leitura.
 *
 * O desenho dos bytes segue a documentação pública, sem copiar parser:
 * - ANLZ: https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html
 * - Pedido da onda nxs2 (PWV4, seis bytes por coluna, intercalados):
 *   https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html
 * - PWV5, u16 big-endian: 3 bits R, 3 G, 3 B, 5 de altura, 2 bits baixos sem uso.
 *   O texto do ANLZ chama a figura de “height” e “0 0” nos bits baixos.
 * - PWV6/PWV7: três bytes, médio, agudo, grave, nessa ordem. PWV6 começa no byte 0x14;
 *   PWV7 no 0x18, como o PWV5.
 * - PWAV/PWV3: altura nos 5 bits baixos, brancura nos 3 altos. PWV3 tem 150 colunas por segundo.
 *
 * A cor da preferência do usuário não está em MYSETTING.DAT, MYSETTING2.DAT nem DJMMYSETTING.DAT
 * (https://pyrekordbox.readthedocs.io/en/latest/formats/mysetting.html).
 * O byte documentado fica em DEVSETTING.DAT, offset 104 + 10:
 * 0x01 azul, 0x03 RGB, 0x04 3Band. A posição do playhead é o byte seguinte + 3
 * (offset 104 + 13): 0x01 centro, 0x02 esquerda.
 * Fonte do mapa: https://docs.rs/rekordcrate/latest/rekordcrate/setting/enum.WaveformColor.html
 * e enum.WaveformCurrentPosition no mesmo módulo. Byte fora dessa lista não vira modo.
 */

data class ColorWave(
    val height: ByteArray,
    val rgb: IntArray,
    val perSecond: Double,
    val source: String,
) {
    val columns: Int get() = minOf(height.size, rgb.size)
}

data class UsbWavePrefs(
    val waveColor: String?,
    val playhead: Float?,
    val note: String,
)

data class PcmWaves(
    val rgbDetail: ColorWave,
    val rgbPreview: ColorWave,
    val bandDetail: WaveBands,
    val bandPreview: WaveBands,
    val blueDetail: ByteArray,
    val bluePreview: ByteArray,
)

fun scale3(bits: Int): Int = bits.coerceIn(0, 7) * 255 / 7

fun scale5(bits: Int): Int = bits.coerceIn(0, 31) * 255 / 31

fun quantize3(value: Int): Int = (value.coerceIn(0, 255) * 7 + 127) / 255

fun quantize5(value: Int): Int = (value.coerceIn(0, 255) * 31 + 127) / 255

/** Palavra PWV5. Os dois bits baixos ficam em zero. */
fun pwv5Word(red: Int, green: Int, blue: Int, height: Int): Int {
    return ((red and 7) shl 13) or ((green and 7) shl 10) or ((blue and 7) shl 7) or ((height and 31) shl 2)
}

fun decodePwv5Word(word: Int): IntArray {
    val red = scale3((word ushr 13) and 7)
    val green = scale3((word ushr 10) and 7)
    val blue = scale3((word ushr 7) and 7)
    val height = scale5((word ushr 2) and 31)
    return intArrayOf(height, red, green, blue)
}

/**
 * RGB aditivo, um canal por banda. Grave vermelho, médio verde, agudo azul.
 * Amarelo, ciano, rosa e branco saem da soma, não de uma mistura extra.
 */
fun blendRgb(low: Int, mid: Int, high: Int): Int {
    val red = low.coerceIn(0, 255)
    val green = mid.coerceIn(0, 255)
    val blue = high.coerceIn(0, 255)
    return (red shl 16) or (green shl 8) or blue
}

fun rgbParts(color: Int): IntArray = intArrayOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

fun anlzEnvelope(sections: List<Triple<String, Int, ByteArray>>): ByteArray {
    val writer = BeWriter()
    val bodies = sections.map { (tag, lenHeader, body) ->
        val head = BeWriter()
        head.ascii(tag)
        head.u32(lenHeader)
        head.u32(12 + body.size)
        head.toByteArray() + body
    }
    val payload = bodies.fold(ByteArray(0)) { acc, section -> acc + section }
    writer.ascii("PMAI")
    writer.u32(28)
    writer.u32(28 + payload.size)
    repeat(16) { writer.u8(0) }
    return writer.toByteArray() + payload
}

fun pwv5Words(wave: ColorWave): IntArray {
    return IntArray(wave.columns) { index ->
        val parts = rgbParts(wave.rgb[index])
        val height = wave.height[index].toInt() and 0xFF
        pwv5Word(quantize3(parts[0]), quantize3(parts[1]), quantize3(parts[2]), quantize5(height))
    }
}

fun pwv5Body(words: IntArray): ByteArray {
    val writer = BeWriter()
    writer.u32(2)
    writer.u32(words.size)
    writer.u32(0x00960305)
    words.forEach { writer.u16(it and 0xFFFF) }
    return writer.toByteArray()
}

fun pwv4Body(columns: ByteArray): ByteArray {
    val writer = BeWriter()
    val count = columns.size / 6
    writer.u32(6)
    writer.u32(count)
    writer.u32(0)
    writer.bytes(columns)
    return writer.toByteArray()
}

fun pwv3Body(entries: ByteArray): ByteArray {
    val writer = BeWriter()
    writer.u32(1)
    writer.u32(entries.size)
    writer.u32(0x00960000)
    writer.bytes(entries)
    return writer.toByteArray()
}

fun pwv6Body(entries: ByteArray): ByteArray {
    val writer = BeWriter()
    writer.u32(3)
    writer.u32(entries.size / 3)
    writer.bytes(entries)
    return writer.toByteArray()
}

fun pwv7Body(entries: ByteArray): ByteArray {
    val writer = BeWriter()
    writer.u32(3)
    writer.u32(entries.size / 3)
    writer.u32(0x00960000)
    writer.bytes(entries)
    return writer.toByteArray()
}

fun packPwv4(low: ByteArray, mid: ByteArray, high: ByteArray): ByteArray {
    val count = minOf(low.size, mid.size, high.size)
    val out = ByteArray(count * 6)
    for (index in 0 until count) {
        val bass = low[index].toInt() and 0xFF
        val middle = mid[index].toInt() and 0xFF
        val treble = high[index].toInt() and 0xFF
        val at = index * 6
        out[at] = 0
        out[at + 1] = 0
        out[at + 2] = max(bass, middle).toByte()
        out[at + 3] = bass.toByte()
        out[at + 4] = middle.toByte()
        out[at + 5] = treble.toByte()
    }
    return out
}

fun packPwv7(bands: WaveBands): ByteArray {
    val count = bands.columns
    val out = ByteArray(count * 3)
    for (index in 0 until count) {
        out[index * 3] = bands.mid[index]
        out[index * 3 + 1] = bands.high[index]
        out[index * 3 + 2] = bands.low[index]
    }
    return out
}

fun packMono(height: ByteArray): ByteArray {
    return ByteArray(height.size) { index ->
        val bits = quantize5(height[index].toInt() and 0xFF)
        bits.toByte()
    }
}

fun decodeMonoByte(raw: Int): Int = scale5(raw and 0x1F)

fun colorIndex(wave: ColorWave, time: Double, duration: Double): Int {
    if (wave.columns <= 1) return 0
    // Com taxa fixa, a coluna que contém o instante (não a mais próxima pelo centro: isso puxava meia coluna).
    if (wave.perSecond > 0.0) return Math.floor(time * wave.perSecond).toInt().coerceIn(0, wave.columns - 1)
    val pos = if (duration > 0.0) (time / duration) * (wave.columns - 1) else 0.0
    return pos.roundToInt().coerceIn(0, wave.columns - 1)
}

/** Devolve altura 0–255 e cor 0xRRGGBB, ou -1 se esse modo não tem coluna. */
fun rgbSample(analysis: RbAnalysis?, mode: String, overview: Boolean, time: Double, duration: Double): Long {
    if (analysis == null || mode != "RGB") return -1
    val wave = if (overview) analysis.rgbPreview ?: analysis.rgbDetail else analysis.rgbDetail ?: analysis.rgbPreview
    if (wave == null || wave.columns <= 0) return -1
    val index = colorIndex(wave, time, duration)
    val height = wave.height[index].toInt() and 0xFF
    val color = wave.rgb[index]
    return (height.toLong() shl 32) or (color.toLong() and 0xFFFFFF)
}

fun monoSample(analysis: RbAnalysis?, overview: Boolean, time: Double, duration: Double): Float {
    if (analysis == null) return 0f
    val detailed = analysis.blueDetail
    val preview = analysis.bluePreview
    val raw = if (overview) {
        if (preview.isNotEmpty()) preview else detailed
    } else if (detailed.isNotEmpty()) {
        detailed
    } else {
        preview
    }
    if (raw.isEmpty()) {
        val wave = analysis.waveform
        if (wave.isEmpty() || duration <= 0.0) return 0f
        val index = ((time / duration) * (wave.size - 1)).roundToInt().coerceIn(0, wave.lastIndex)
        return (wave[index].toInt() and 0xFF) / 255f
    }
    val perSecond = if (raw === detailed && detailed.isNotEmpty() && !overview) 150.0 else 0.0
    val rawIndex = if (perSecond > 0.0) {
        (time * perSecond).roundToInt()
    } else if (duration > 0.0) {
        ((time / duration) * (raw.size - 1)).roundToInt()
    } else {
        0
    }
    val index = rawIndex.coerceIn(0, raw.lastIndex)
    return (raw[index].toInt() and 0xFF) / 255f
}

fun waveOrigin(analysis: RbAnalysis?, mode: String): String {
    if (analysis == null) return ""
    val fromApp = when (mode) {
        "BLUE" -> analysis.blueApp
        "THREE_BAND" -> analysis.bandApp
        else -> analysis.rgbApp
    }
    if (fromApp) return "analisado no app"
    return when (mode) {
        "BLUE" -> if (analysis.blueDetail.isNotEmpty()) "PWV3" else if (analysis.waveform.isNotEmpty()) "PWAV" else ""
        "THREE_BAND" -> when {
            analysis.bandDetail != null && !analysis.bandApp -> "PWV7"
            analysis.bands != null -> analysis.waveSource
            else -> ""
        }
        else -> analysis.rgbDetail?.source ?: analysis.rgbPreview?.source ?: ""
    }
}

/**
 * MYSETTING*.DAT e DJMMYSETTING.DAT não entram: a cor documentada está no DEVSETTING.DAT.
 * Cabeçalho de 104 bytes, como em
 * https://pyrekordbox.readthedocs.io/en/latest/formats/mysetting.html
 */
fun readDevSetting(bytes: ByteArray): UsbWavePrefs {
    if (bytes.size < 104 + 14) {
        return UsbWavePrefs(null, null, "DEVSETTING.DAT curto demais. A cor não foi chutada.")
    }
    val lenStrings = bytes.u32le(0).toInt()
    if (lenStrings != 96) {
        return UsbWavePrefs(null, null, "DEVSETTING.DAT sem o cabeçalho de 96 bytes de texto.")
    }
    val brand = String(bytes, 4, 32, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
    if (!brand.contains("PIONEER", ignoreCase = true)) {
        return UsbWavePrefs(null, null, "DEVSETTING.DAT sem a marca esperada no cabeçalho.")
    }
    val lenData = bytes.u32le(100).toInt()
    if (lenData < 14 || 104 + lenData > bytes.size + 4) {
        return UsbWavePrefs(null, null, "DEVSETTING.DAT com tamanho de dados fora do arquivo.")
    }
    val colorByte = bytes.u8(104 + 10)
    val positionByte = bytes.u8(104 + 13)
    val color = when (colorByte) {
        0x01 -> "BLUE"
        0x03 -> "RGB"
        0x04 -> "THREE_BAND"
        else -> null
    }
    val playhead = when (positionByte) {
        0x01 -> 0.50f
        0x02 -> 0.20f
        else -> null
    }
    val note = when {
        color == null -> "DEVSETTING.DAT byte de cor ${colorByte.toString(16)}. Não está na lista documentada. Não foi chutado."
        playhead == null -> "DEVSETTING.DAT cor $color. Posição ${positionByte.toString(16)} fora de centro/esquerda."
        else -> "DEVSETTING.DAT: $color, playhead ${if (playhead == 0.50f) "centro" else "esquerda"}."
    }
    return UsbWavePrefs(color, playhead, note)
}

fun readUsbWavePrefs(volume: ReadableVolume): UsbWavePrefs {
    val bytes = volume.read("PIONEER/DEVSETTING.DAT") ?: volume.read("PIONEER/rekordbox/DEVSETTING.DAT")
    if (bytes == null) {
        return UsbWavePrefs(
            null,
            null,
            "Sem DEVSETTING.DAT. MYSETTING.DAT, MYSETTING2.DAT e DJMMYSETTING.DAT não documentam a cor da onda.",
        )
    }
    return readDevSetting(bytes)
}

fun resolveUsbSettings(settings: PlayerSettings, color: String?, playhead: Float?): PlayerSettings {
    if (!settings.followUsb) return settings
    if (color == null && playhead == null) return settings
    val nextColor = color ?: settings.waveColor
    val nextHead = playhead ?: settings.playhead
    if (nextColor == settings.waveColor && nextHead == settings.playhead) return settings
    return settings.copy(waveColor = nextColor, playhead = nextHead)
}

data class WavPcm(val samples: FloatArray, val rate: Int)

fun decodeWavMono(bytes: ByteArray): WavPcm? {
    if (bytes.size < 44) return null
    if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var cursor = 12
    var rate = 0
    var channels = 0
    var bits = 0
    var dataAt = -1
    var dataLen = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val len = bytes.u32le(cursor + 4).toInt()
        if (len < 0 || cursor + 8 + len > bytes.size) return null
        if (id == "fmt " && len >= 16) {
            channels = bytes.u16le(cursor + 8 + 2)
            rate = bytes.u32le(cursor + 8 + 4).toInt()
            bits = bytes.u16le(cursor + 8 + 14)
        } else if (id == "data") {
            dataAt = cursor + 8
            dataLen = len
            break
        }
        cursor += 8 + len + (len and 1)
    }
    if (dataAt < 0 || rate < 8000 || channels != 1 || bits != 16) return null
    val samples = dataLen / 2
    if (samples <= 0 || samples > 30_000_000 || dataAt + samples * 2 > bytes.size) return null
    val out = FloatArray(samples)
    for (index in 0 until samples) {
        val raw = bytes.u16le(dataAt + index * 2).toShort().toInt()
        out[index] = raw / 32767f
    }
    return WavPcm(out, rate)
}

fun wavesFromPcm(samples: FloatArray, rate: Int): PcmWaves {
    val seconds = if (rate > 0) samples.size.toDouble() / rate else 0.0
    // 150 colunas por segundo, exatas (como o detalhe do rekordbox). Sem teto baixo: com o teto de 80 000 uma faixa
    // de mais de 8 min 53 s tinha menos colunas por segundo do que a leitura supunha e a onda escorregava da grade.
    val detailCount = Math.ceil(seconds * 150.0).toInt().coerceIn(2, 150 * 1500)
    val bands = splitBands(samples, rate, detailCount)
    val preview = downsampleBands(bands, 1200)
    val rgbDetail = colorWave(bands, 150.0, "APP")
    val rgbPreview = colorWave(preview, 0.0, "APP")
    val blueDetail = heightOf(bands)
    val bluePreview = heightOf(preview)
    return PcmWaves(rgbDetail, rgbPreview, bands, preview, blueDetail, bluePreview)
}

private fun colorWave(bands: WaveBands, perSecond: Double, source: String): ColorWave {
    val count = bands.columns
    val height = ByteArray(count)
    val rgb = IntArray(count)
    for (index in 0 until count) {
        val low = bands.low[index].toInt() and 0xFF
        val mid = bands.mid[index].toInt() and 0xFF
        val high = bands.high[index].toInt() and 0xFF
        height[index] = columnLoudness(bands, index, low, mid, high).toByte()
        rgb[index] = blendRgb(low, mid, high)
    }
    return ColorWave(height, rgb, perSecond, source)
}

private fun heightOf(bands: WaveBands): ByteArray {
    return ByteArray(bands.columns) { index ->
        val low = bands.low[index].toInt() and 0xFF
        val mid = bands.mid[index].toInt() and 0xFF
        val high = bands.high[index].toInt() and 0xFF
        columnLoudness(bands, index, low, mid, high).toByte()
    }
}

private fun columnLoudness(bands: WaveBands, index: Int, low: Int, mid: Int, high: Int): Int {
    if (index < bands.loud.size) return bands.loud[index].toInt() and 0xFF
    val energy = low * low + mid * mid + high * high
    return sqrt(energy / 3.0).toInt().coerceIn(0, 255)
}

private fun splitBands(samples: FloatArray, rate: Int, columns: Int): WaveBands {
    val low = DoubleArray(columns)
    val mid = DoubleArray(columns)
    val high = DoubleArray(columns)
    val loud = DoubleArray(columns)
    if (samples.isNotEmpty() && rate > 0 && columns > 0) {
        // 200 Hz e 2,2 kHz. A banda do meio é larga e quase contínua; o grave e o
        // agudo são mais pontudos. O percentil de cada uma compensa isso.
        val lowBand = zeroPhaseButterworth(samples, rate, 200.0)
        val belowHigh = zeroPhaseButterworth(samples, rate, 2200.0)
        // Uma coluna = 1/150 s. A janela é de uma coluna e meia (10 ms), centrada: os ataques ficam numa coluna só.
        // A janela antiga, de 32 ms, espalhava cada bumbo por cinco colunas e a onda ficava em bolhas.
        val per = if (columns >= 2 && samples.size.toDouble() / columns > rate / 150.0 * 1.5) samples.size.toDouble() / columns else rate / 150.0
        val window = (per * 1.5).roundToInt().coerceIn(8, rate)
        val half = window / 2
        for (column in 0 until columns) {
            val center = ((column + 0.5) * per).roundToInt().coerceIn(0, samples.lastIndex)
            val start = (center - half).coerceAtLeast(0)
            val end = (center + half).coerceAtMost(samples.size)
            var eL = 0.0
            var eM = 0.0
            var eH = 0.0
            var n = 0
            var sample = start
            while (sample < end) {
                val bass = lowBand[sample]
                val below = belowHigh[sample]
                val midBand = below - bass
                val highBand = samples[sample] - below
                eL += bass * bass
                eM += midBand * midBand
                eH += highBand * highBand
                sample += 1
                n += 1
            }
            if (n > 0) {
                low[column] = sqrt(eL / n)
                mid[column] = sqrt(eM / n)
                high[column] = sqrt(eH / n)
                loud[column] = sqrt((eL + eM + eH) / n)
            }
        }
    }
    // Referência perto do topo (percentil 97 a 98,5): os picos chegam ao teto e o resto fica proporcional, então um
    // breakdown fica baixo e cada batida aparece separada. Com percentis de 55 a 72 % e colunas de 1/150 s, o fundo
    // também batia no teto e a onda virava um bloco cheio.
    val lowRef = percentile(low, 0.97)
    val midRef = percentile(mid, 0.97)
    val highRef = percentile(high, 0.97)
    val loudRef = percentile(loud, 0.985)
    val floor = max(lowRef, max(midRef, highRef)) * 0.12
    return WaveBands(
        shapeBand(low, max(lowRef, floor), 1.0, 0.85),
        shapeBand(mid, max(midRef, floor), 0.9, 0.85),
        shapeBand(high, max(highRef, floor), 1.0, 0.85),
        shapeBand(loud, loudRef, 1.0, 0.9),
    )
}

private fun percentile(values: DoubleArray, fraction: Double): Double {
    if (values.isEmpty()) return 1e-6
    val copy = values.copyOf()
    copy.sort()
    val index = ((copy.size - 1) * fraction).roundToInt().coerceIn(0, copy.lastIndex)
    return copy[index].coerceAtLeast(1e-8)
}

private fun shapeBand(values: DoubleArray, reference: Double, weight: Double, gamma: Double): ByteArray {
    return ByteArray(values.size) { index ->
        val norm = (values[index] / reference * weight).coerceIn(0.0, 1.6)
        val curved = norm.pow(gamma).coerceIn(0.0, 1.0)
        (curved * 255.0).roundToInt().coerceIn(0, 255).toByte()
    }
}

private class WaveBiquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val nb0 = (b0 / a0).toFloat()
    private val nb1 = (b1 / a0).toFloat()
    private val nb2 = (b2 / a0).toFloat()
    private val na1 = (a1 / a0).toFloat()
    private val na2 = (a2 / a0).toFloat()
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = nb0 * x + z1
        z1 = nb1 * x - na1 * y + z2
        z2 = nb2 * x - na2 * y
        return y
    }
}

private fun butterworthLowpass(rate: Int, freq: Double): WaveBiquad {
    val w0 = 2.0 * PI * freq / rate
    val cosine = cos(w0)
    val sine = sin(w0)
    val alpha = sine / (2.0 * 0.7071067811865476)
    val b0 = (1.0 - cosine) / 2.0
    val b1 = 1.0 - cosine
    val b2 = (1.0 - cosine) / 2.0
    val a0 = 1.0 + alpha
    val a1 = -2.0 * cosine
    val a2 = 1.0 - alpha
    return WaveBiquad(b0, b1, b2, a0, a1, a2)
}

private fun zeroPhaseButterworth(samples: FloatArray, rate: Int, freq: Double): FloatArray {
    if (samples.isEmpty()) return samples
    val forward = FloatArray(samples.size)
    val forth = butterworthLowpass(rate, freq)
    for (index in samples.indices) forward[index] = forth.process(samples[index])
    val back = FloatArray(samples.size)
    val reverse = butterworthLowpass(rate, freq)
    for (index in samples.indices.reversed()) back[index] = reverse.process(forward[index])
    return back
}

private fun downsampleBands(source: WaveBands, columns: Int): WaveBands {
    val count = columns.coerceIn(2, 1200)
    if (source.columns <= count) return source
    val low = ByteArray(count)
    val mid = ByteArray(count)
    val high = ByteArray(count)
    val loud = ByteArray(count)
    val hasLoud = source.loud.size >= source.columns
    for (index in 0 until count) {
        val start = index * source.columns / count
        val end = ((index + 1) * source.columns / count).coerceAtLeast(start + 1).coerceAtMost(source.columns)
        var l = 0
        var m = 0
        var h = 0
        var g = 0
        for (at in start until end) {
            l += source.low[at].toInt() and 0xFF
            m += source.mid[at].toInt() and 0xFF
            h += source.high[at].toInt() and 0xFF
            g += if (hasLoud) source.loud[at].toInt() and 0xFF else columnLoudness(source, at, source.low[at].toInt() and 0xFF, source.mid[at].toInt() and 0xFF, source.high[at].toInt() and 0xFF)
        }
        val n = (end - start).coerceAtLeast(1)
        low[index] = (l / n).toByte()
        mid[index] = (m / n).toByte()
        high[index] = (h / n).toByte()
        loud[index] = (g / n).toByte()
    }
    return WaveBands(low, mid, high, loud)
}

fun devSettingBytes(color: Int, position: Int): ByteArray {
    val data = ByteArray(32)
    byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x01, 0x00, 0x00, 0x00, 0x01).copyInto(data)
    data[9] = 0x01
    data[10] = color.toByte()
    data[11] = 0x01
    data[13] = position.toByte()
    val file = ByteArray(104 + 32 + 4)
    file.putU32le(0, 96)
    "PIONEER DJ".toByteArray(Charsets.US_ASCII).copyInto(file, 4)
    "rekordbox".toByteArray(Charsets.US_ASCII).copyInto(file, 36)
    "0.1.0".toByteArray(Charsets.US_ASCII).copyInto(file, 68)
    file.putU32le(100, 32)
    data.copyInto(file, 104)
    return file
}


/** Onda ampliada pronta para desenhar: uma coluna por pixel, altura 0..1 e cor 0xRRGGBB (ou três bandas 0..1). */
class WaveStrip(val heights: FloatArray, val colors: IntArray, val low: FloatArray, val mid: FloatArray, val high: FloatArray)

/**
 * Uma coluna por pixel a partir de dados de taxa fixa (`perSecond` entradas por segundo). Quando um pixel cobre várias
 * entradas, fica com a maior (o pico de um bumbo nunca some entre dois pixels, como na tela do leitor); quando cobre
 * menos de uma, interpola entre os centros das entradas. Nada de suavizar: a forma de cada batida aparece.
 */
fun waveStrip(
    start: Double,
    window: Double,
    pixels: Int,
    perSecond: Double,
    count: Int,
    height: (Int) -> Int,
    color: (Int) -> Int,
    low: (Int) -> Int = { 0 },
    mid: (Int) -> Int = { 0 },
    high: (Int) -> Int = { 0 },
): WaveStrip {
    val heights = FloatArray(pixels)
    val colors = IntArray(pixels)
    val lows = FloatArray(pixels)
    val mids = FloatArray(pixels)
    val highs = FloatArray(pixels)
    if (pixels <= 0 || count <= 0 || perSecond <= 0.0 || window <= 0.0) return WaveStrip(heights, colors, lows, mids, highs)
    for (pixel in 0 until pixels) {
        val a = (start + window * pixel / pixels) * perSecond
        val b = (start + window * (pixel + 1) / pixels) * perSecond
        val first = Math.floor(a).toInt()
        val last = Math.ceil(b).toInt() - 1
        if (last >= 0 && first < count && last > first) {
            var best = -1
            var bestHeight = -1
            var l = 0
            var m = 0
            var h = 0
            for (index in first.coerceAtLeast(0)..last.coerceAtMost(count - 1)) {
                val value = height(index)
                if (value > bestHeight) {
                    bestHeight = value
                    best = index
                }
                l = maxOf(l, low(index))
                m = maxOf(m, mid(index))
                h = maxOf(h, high(index))
            }
            if (best >= 0) {
                heights[pixel] = bestHeight / 255f
                colors[pixel] = color(best)
                lows[pixel] = l / 255f
                mids[pixel] = m / 255f
                highs[pixel] = h / 255f
            }
        } else {
            // Menos de uma entrada por pixel: interpolação entre os centros das entradas vizinhas.
            val centre = (a + b) / 2.0 - 0.5
            val i0 = Math.floor(centre).toInt()
            val frac = (centre - i0).toFloat()
            fun lerp(read: (Int) -> Int): Float {
                val x0 = if (i0 in 0 until count) read(i0) else 0
                val x1 = if (i0 + 1 in 0 until count) read(i0 + 1) else 0
                return (x0 * (1f - frac) + x1 * frac) / 255f
            }
            if (i0 + 1 >= 0 && i0 < count) {
                heights[pixel] = lerp(height)
                colors[pixel] = color((if (frac < 0.5f) i0 else i0 + 1).coerceIn(0, count - 1))
                lows[pixel] = lerp(low)
                mids[pixel] = lerp(mid)
                highs[pixel] = lerp(high)
            }
        }
    }
    return WaveStrip(heights, colors, lows, mids, highs)
}
