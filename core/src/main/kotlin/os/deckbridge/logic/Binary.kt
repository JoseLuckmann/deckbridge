package os.deckbridge.logic

internal fun ByteArray.u8(at: Int): Int {
    if (at < 0 || at >= size) return -1
    return this[at].toInt() and 0xFF
}

internal fun ByteArray.u16le(at: Int): Int {
    if (at < 0 || at + 1 >= size) return -1
    return u8(at) or (u8(at + 1) shl 8)
}

internal fun ByteArray.u32le(at: Int): Long {
    if (at < 0 || at + 3 >= size) return -1
    return (u8(at).toLong()) or
        (u8(at + 1).toLong() shl 8) or
        (u8(at + 2).toLong() shl 16) or
        (u8(at + 3).toLong() shl 24)
}

internal fun ByteArray.u16be(at: Int): Int {
    if (at < 0 || at + 1 >= size) return -1
    return (u8(at) shl 8) or u8(at + 1)
}

internal fun ByteArray.u32be(at: Int): Long {
    if (at < 0 || at + 3 >= size) return -1
    return (u8(at).toLong() shl 24) or
        (u8(at + 1).toLong() shl 16) or
        (u8(at + 2).toLong() shl 8) or
        u8(at + 3).toLong()
}

internal fun ByteArray.putU8(at: Int, value: Int) {
    this[at] = (value and 0xFF).toByte()
}

internal fun ByteArray.putU16le(at: Int, value: Int) {
    putU8(at, value)
    putU8(at + 1, value shr 8)
}

fun ByteArray.putU32le(at: Int, value: Int) {
    putU8(at, value)
    putU8(at + 1, value shr 8)
    putU8(at + 2, value shr 16)
    putU8(at + 3, value shr 24)
}

class BeWriter {
    private val out = ArrayList<Byte>()

    fun u8(value: Int) {
        out += (value and 0xFF).toByte()
    }

    fun u16(value: Int) {
        u8(value shr 8)
        u8(value)
    }

    fun u32(value: Int) {
        u8(value shr 24)
        u8(value shr 16)
        u8(value shr 8)
        u8(value)
    }

    fun bytes(value: ByteArray) {
        value.forEach { out += it }
    }

    fun ascii(value: String) {
        bytes(value.toByteArray(Charsets.US_ASCII))
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
