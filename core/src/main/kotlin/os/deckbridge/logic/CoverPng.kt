package os.deckbridge.logic

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.sqrt

/** PNG RGB gerado aqui. A capa sintética não é JPEG de um export rekordbox. */
fun coverPng(width: Int, height: Int, rgb: Int, accent: Int): ByteArray {
    val raw = ByteArray((width * 3 + 1) * height)
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val ar = (accent shr 16) and 0xFF
    val ag = (accent shr 8) and 0xFF
    val ab = accent and 0xFF
    var cursor = 0
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    val outer = minOf(width, height) * 0.42
    val inner = outer * 0.28
    val barX = (width / 5 + (accent and 7)).coerceIn(1, width - 2)
    for (y in 0 until height) {
        raw[cursor++] = 0
        for (x in 0 until width) {
            val dx = x - cx
            val dy = y - cy
            val dist = sqrt(dx * dx + dy * dy)
            val mark = dist in inner..outer || (x == barX && y > height / 5 && y < height * 4 / 5)
            raw[cursor++] = (if (mark) ar else r).toByte()
            raw[cursor++] = (if (mark) ag else g).toByte()
            raw[cursor++] = (if (mark) ab else b).toByte()
        }
    }
    val deflater = Deflater(Deflater.BEST_SPEED)
    deflater.setInput(raw)
    deflater.finish()
    val compressed = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) {
        val wrote = deflater.deflate(buffer)
        if (wrote > 0) compressed.write(buffer, 0, wrote)
    }
    deflater.end()
    val ihdr = ByteArray(13)
    putBe32(ihdr, 0, width)
    putBe32(ihdr, 4, height)
    ihdr[8] = 8
    ihdr[9] = 2
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    out.write(pngChunk("IHDR", ihdr))
    out.write(pngChunk("IDAT", compressed.toByteArray()))
    out.write(pngChunk("IEND", ByteArray(0)))
    return out.toByteArray()
}

private val COVER_BG = intArrayOf(0x1A3A68, 0x8E3B1F, 0x1E4D3A, 0x4A2A68, 0x6B2A32, 0x1F4A5A, 0x5A4A1A, 0x243044)

fun syntheticCover(id: Int, large: Boolean): ByteArray {
    val size = if (large) 96 else 48
    val bg = COVER_BG[((id % COVER_BG.size) + COVER_BG.size) % COVER_BG.size]
    return coverPng(size, size, bg, 0xF4F7FB)
}

fun pngWidth(bytes: ByteArray): Int {
    if (bytes.size < 24) return -1
    if (bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) return -1
    return ((bytes[16].toInt() and 0xFF) shl 24) or
        ((bytes[17].toInt() and 0xFF) shl 16) or
        ((bytes[18].toInt() and 0xFF) shl 8) or
        (bytes[19].toInt() and 0xFF)
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    putBe32(out, 0, data.size)
    val name = type.toByteArray(Charsets.US_ASCII)
    name.copyInto(out, 4)
    data.copyInto(out, 8)
    val crc = CRC32()
    crc.update(name)
    crc.update(data)
    putBe32(out, 8 + data.size, crc.value.toInt())
    return out
}

private fun putBe32(target: ByteArray, at: Int, value: Int) {
    target[at] = ((value ushr 24) and 0xFF).toByte()
    target[at + 1] = ((value ushr 16) and 0xFF).toByte()
    target[at + 2] = ((value ushr 8) and 0xFF).toByte()
    target[at + 3] = (value and 0xFF).toByte()
}
