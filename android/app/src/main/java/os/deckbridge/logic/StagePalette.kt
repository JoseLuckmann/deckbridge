package os.deckbridge.logic

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Cores do palco. Texto não decorativo fica em contraste WCAG AA (4,5:1) sobre o fundo em que aparece. */
object StagePalette {
    val bg = 0xFF000000.toInt()
    val panel = 0xFF0C0C0E.toInt()
    val panelRaised = 0xFF16161A.toInt()
    val ink = 0xFFF4F7FB.toInt()
    val inkMuted = 0xFFC8CDD6.toInt()
    val line = 0xFF3A3D44.toInt()
    val accentBlue = 0xFF8EBEFF.toInt()
    val accentOrange = 0xFFFF9A2E.toInt()
    val onAir = 0xFFD0121A.toInt()
    val playBg = 0xFFB6FFC8.toInt()
    val playInk = 0xFF06210E.toInt()
    val pauseBg = 0xFF2C3038.toInt()
    val simBg = 0xFF3A3118.toInt()
    val simInk = 0xFFFFE7A3.toInt()
    val deckA = 0xFFE08A3C.toInt()
    val deckB = 0xFF3C8FD4.toInt()
    val waveLow = 0xFF3D7CFF.toInt()
    val waveMid = 0xFFE0A04A.toInt()
    val waveHigh = 0xFFF7F8FA.toInt()
    val wave = 0xFF3D7CFF.toInt()
    val waveBg = 0xFF08090B.toInt()
    val navSelected = 0xFFF4F7FB.toInt()
    val navSelectedInk = 0xFF10141A.toInt()

    /** Primeira fileira de pads, no espírito das cores padrão do rekordbox. A cor gravada na faixa ganha. */
    val pads = intArrayOf(
        0xFFD63846.toInt(),
        0xFFFF7A18.toInt(),
        0xFFFFD000.toInt(),
        0xFF22C55E.toInt(),
        0xFF14C8D4.toInt(),
        0xFF3969F9.toInt(),
        0xFFB44DFF.toInt(),
        0xFFFF4F9A.toInt(),
    )
}

fun relativeLuminance(rgb: Int): Double {
    fun channel(value: Int): Double {
        val s = value / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    val r = channel((rgb shr 16) and 0xFF)
    val g = channel((rgb shr 8) and 0xFF)
    val b = channel(rgb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

fun contrastRatio(foreground: Int, background: Int): Double {
    val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
    val darker = min(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

fun inkOn(fill: Int): Int {
    val white = 0xFFFFFFFF.toInt()
    val black = StagePalette.bg
    return if (contrastRatio(white, fill) >= contrastRatio(black, fill)) white else black
}
