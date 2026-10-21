package os.deckbridge.logic

/**
 * Os mesmos limiares de src/deckbridge/capability.py (OD-01).
 * O nome do modelo não entra. Não é medida de desempenho.
 */
data class RuntimeProfile(
    val tier: String,
    val waveforms: String,
    val heavyEffects: String,
    val reason: String,
)

fun classifyCapability(cores: Int?, ramMb: Int?): RuntimeProfile {
    if (cores == null || ramMb == null) {
        return RuntimeProfile(
            tier = "UNKNOWN",
            waveforms = "simple",
            heavyEffects = "off",
            reason = "RAM ou núcleos ausentes; degradação segura, sem assumir máquina forte",
        )
    }
    if (ramMb < 1024 || cores < 2) {
        return RuntimeProfile("low", "off", "off", "RAM abaixo de 1024 MB ou menos de 2 núcleos")
    }
    if (ramMb < 2048 || cores < 4) {
        return RuntimeProfile(
            "mid",
            "simple",
            "off",
            "RAM abaixo de 2048 MB ou menos de 4 núcleos",
        )
    }
    return RuntimeProfile(
        "high",
        "full",
        "on",
        "RAM e núcleos acima dos limiares de política; desempenho ainda não medido",
    )
}

object AndroidRange {
    const val MIN_SDK = 26
    const val TARGET_SDK = 34
    const val TABLET_FAMILY_MIN = 29
    const val TABLET_FAMILY_MAX = 34

    fun covers(sdk: Int): Boolean = sdk in MIN_SDK..TARGET_SDK

    fun coversTabS6LiteFamily(sdk: Int): Boolean = sdk in TABLET_FAMILY_MIN..TABLET_FAMILY_MAX
}
