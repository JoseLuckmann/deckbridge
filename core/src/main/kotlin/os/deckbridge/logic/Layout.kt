package os.deckbridge.logic

data class Frame(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

val REFERENCE_SIZES = listOf(800 to 480, 1024 to 600, 2000 to 1200)

fun sideBySide(width: Int, height: Int): Boolean = width >= height && width >= 640

fun textSp(width: Int): Int = when {
    width >= 1600 -> 22
    width >= 1000 -> 16
    else -> 13
}

fun performanceFrames(width: Int, height: Int, waveforms: Boolean): List<Frame> {
    val margin = 8
    val bannerH = maxOf(48, height / 14)
    val footerH = maxOf(20, height / 18)
    val bodyTop = margin + bannerH + 4
    val bodyH = height - bodyTop - footerH - margin - 4
    val gap = 8
    val mixerW = when {
        width >= 1600 -> 200
        width >= 900 -> 148
        else -> 112
    }
    val deckW = (width - 2 * margin - mixerW - 2 * gap) / 2
    val frames = mutableListOf(
        Frame("banner", margin, margin, width - 2 * margin, bannerH),
        Frame("deck_a", margin, bodyTop, deckW, bodyH),
        Frame("mixer", margin + deckW + gap, bodyTop, mixerW, bodyH),
        Frame("deck_b", margin + deckW + gap + mixerW + gap, bodyTop, deckW, bodyH),
    )
    val line = if (height >= 1000) 40 else if (height >= 600) 28 else 20
    val innerY = bodyTop + 8
    val deckOrigin = listOf(
        "a" to (margin + 8),
        "b" to (margin + deckW + gap + mixerW + gap + 8),
    )
    deckOrigin.forEach { (prefix, origin) ->
        val innerW = deckW - 16
        frames += Frame("title_$prefix", origin, innerY, innerW, line)
        frames += Frame("bpm_$prefix", origin, innerY + line, innerW / 2, line + 8)
        frames += Frame("time_$prefix", origin + innerW / 2, innerY + line, innerW - innerW / 2, line + 8)
        frames += Frame("play_$prefix", origin, innerY + 2 * line + 8, innerW, line)
        val cuesY = bodyTop + bodyH - 28
        if (waveforms) {
            val waveTop = innerY + 3 * line + 8
            val waveH = cuesY - waveTop - 4
            if (waveH >= 16) {
                frames += Frame("waveform_$prefix", origin, waveTop, innerW, waveH)
            }
        }
        frames += Frame("cues_$prefix", origin, cuesY, innerW, 20)
    }
    frames += Frame("footer", margin, height - margin - footerH, width - 2 * margin, footerH)
    return frames
}

fun layoutProblems(frames: List<Frame>, width: Int, height: Int): List<String> {
    val issues = mutableListOf<String>()
    frames.forEach { frame ->
        if (frame.w <= 0 || frame.h <= 0) issues += frame.name
        if (frame.x < 0 || frame.y < 0 || frame.x + frame.w > width || frame.y + frame.h > height) {
            issues += frame.name
        }
    }
    val left = frames.find { it.name == "deck_a" }
    val right = frames.find { it.name == "deck_b" }
    if (left != null && right != null) {
        val overlap = left.x < right.x + right.w && right.x < left.x + left.w &&
            left.y < right.y + right.h && right.y < left.y + left.h
        if (overlap) issues += "deck_overlap"
    }
    return issues
}
