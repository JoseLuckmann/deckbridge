package os.deckbridge.logic

data class PerformanceAction(
    val deck: Int,
    val kind: Kind,
    val hotIndex: Int = -1,
    val slider: Double? = null,
) {
    enum class Kind { PLAY, CUE, HOT_CUE, VOLUME, CROSSFADER, PITCH }
}

fun masterGain(volume: Double, crossfader: Double, deckIsB: Boolean): Double {
    val side = if (deckIsB) crossfader else 1.0 - crossfader
    return (volume.coerceIn(0.0, 1.0) * side.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
}

fun syncPitch(ownBpm: Double, otherBpm: Double, otherPitch: Double): Double? {
    if (ownBpm <= 0.0 || otherBpm <= 0.0) return null
    val target = otherBpm * otherPitch
    if (target <= 0.0) return null
    return (target / ownBpm).coerceIn(0.5, 2.0)
}

fun performanceAction(hit: ControlHit, value: Int): PerformanceAction? {
    if (!hit.mapped) return null
    val deck = when (hit.group) {
        "[Channel1]" -> 0
        "[Channel2]" -> 1
        else -> -1
    }
    val slider = (value.coerceIn(0, 127) / 127.0)
    return when {
        hit.key == "play" && value > 0 && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.PLAY)
        hit.key == "cue_default" && value > 0 && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.CUE)
        hit.key.startsWith("hotcue_") && hit.key.endsWith("_activate") && value > 0 && deck >= 0 -> {
            val index = hit.key.removePrefix("hotcue_").substringBefore("_").toIntOrNull()?.minus(1) ?: return null
            PerformanceAction(deck, PerformanceAction.Kind.HOT_CUE, index)
        }
        hit.key == "volume" && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.VOLUME, slider = slider)
        hit.key == "crossfader" -> PerformanceAction(-1, PerformanceAction.Kind.CROSSFADER, slider = slider)
        hit.key.endsWith("tempoSliderMSB") && deck >= 0 -> {
            PerformanceAction(deck, PerformanceAction.Kind.PITCH, slider = 0.9 + slider * 0.2)
        }
        else -> null
    }
}
