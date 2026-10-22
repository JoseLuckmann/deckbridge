package os.deckbridge.logic

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tira faixas da onda RGB nas regiões do corpus. Não entra no APK.
 * Serve para olhar drop, break e build antes de gravar o print do Roborazzi.
 */
class WaveStripTest {
    @Test
    fun writeRegionStrips() {
        val root = File("/opt/cursor/artifacts/real-corpus/mono")
        assumeTrue(root.isDirectory)
        val out = File("/tmp/wave-strips")
        out.mkdirs()
        val regions = listOf(
            Region("01-chromosome-boutique.wav", "chromosome", 26.0, 128.0, 176.0),
            Region("02-ecosystem.wav", "ecosystem", 106.0, 156.0, 184.0),
            Region("07-silver.wav", "silver", 50.0, 98.0, 150.0),
            Region("morning-original-mix.wav", "morning", 16.0, 108.0, 166.0),
        )
        regions.forEach { region ->
            val file = File(root, region.file)
            assumeTrue(file.isFile)
            val pcm = decodeWavMono(file.readBytes()) ?: error(region.file)
            val waves = wavesFromPcm(pcm.samples, pcm.rate)
            val wave = waves.rgbDetail
            listOf("build" to region.build, "drop" to region.drop, "break" to region.broken).forEach { (name, sec) ->
                File(out, "${region.slug}-$name.ppm").writeBytes(strip(wave, sec))
                println("${region.slug} $name ${summary(wave, sec)}")
            }
            println("${region.slug} exemplos ${exemplars(wave)}")
        }
    }

    private fun strip(wave: ColorWave, sec: Double): ByteArray {
        val center = (sec * wave.perSecond).toInt()
        val width = 480
        val height = 120
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(width * height * 3)
        for (x in 0 until width) {
            val index = (center - width / 2 + x).coerceIn(0, wave.columns - 1)
            val color = quantize(wave.rgb[index])
            val column = (wave.height[index].toInt() and 0xFF) * (height - 8) / 255
            for (y in 0 until height) {
                val on = y >= height - 4 - column
                val pixel = if (on) color else 0x101014
                val at = (y * width + x) * 3
                body[at] = ((pixel shr 16) and 0xFF).toByte()
                body[at + 1] = ((pixel shr 8) and 0xFF).toByte()
                body[at + 2] = (pixel and 0xFF).toByte()
            }
        }
        return header + body
    }

    private fun quantize(color: Int): Int {
        val red = scale3(quantize3((color shr 16) and 0xFF))
        val green = scale3(quantize3((color shr 8) and 0xFF))
        val blue = scale3(quantize3(color and 0xFF))
        return (red shl 16) or (green shl 8) or blue
    }

    private fun summary(wave: ColorWave, sec: Double): String {
        val center = (sec * wave.perSecond).toInt()
        var r = 0
        var g = 0
        var b = 0
        var h = 0
        var n = 0
        for (delta in -40..40) {
            val index = (center + delta).coerceIn(0, wave.columns - 1)
            val color = quantize(wave.rgb[index])
            r += (color shr 16) and 0xFF
            g += (color shr 8) and 0xFF
            b += color and 0xFF
            h += wave.height[index].toInt() and 0xFF
            n += 1
        }
        return "rgb ${r / n},${g / n},${b / n} altura ${h / n}"
    }

    private fun exemplars(wave: ColorWave): String {
        var redAt = 0
        var greenAt = 0
        var blueAt = 0
        var whiteAt = 0
        var redScore = -1
        var greenScore = -1
        var blueScore = -1
        var whiteScore = -1
        for (index in 0 until wave.columns) {
            val color = quantize(wave.rgb[index])
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            val redOnly = red - maxOf(green, blue)
            val greenOnly = green - maxOf(red, blue)
            val blueOnly = blue - maxOf(red, green)
            val white = minOf(red, green, blue)
            if (redOnly > redScore) {
                redScore = redOnly
                redAt = index
            }
            if (greenOnly > greenScore) {
                greenScore = greenOnly
                greenAt = index
            }
            if (blueOnly > blueScore) {
                blueScore = blueOnly
                blueAt = index
            }
            if (white > whiteScore) {
                whiteScore = white
                whiteAt = index
            }
        }
        fun at(index: Int): String {
            val color = quantize(wave.rgb[index])
            val sec = if (wave.perSecond > 0.0) index / wave.perSecond else 0.0
            return "%.1fs %d,%d,%d h%d".format(
                sec,
                (color shr 16) and 0xFF,
                (color shr 8) and 0xFF,
                color and 0xFF,
                wave.height[index].toInt() and 0xFF,
            )
        }
        return "vermelho ${at(redAt)} verde ${at(greenAt)} azul ${at(blueAt)} branco ${at(whiteAt)}"
    }

    private data class Region(val file: String, val slug: String, val build: Double, val drop: Double, val broken: Double)
}
