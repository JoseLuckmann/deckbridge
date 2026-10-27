package os.deckbridge.logic

data class EngineStats(
    val opened: Boolean,
    val requestedChannels: Int,
    val openedChannels: Int,
    val sampleRate: Int,
    val deviceId: Int,
    val sharing: String,
    val performance: String,
    val fallbackToTwo: Boolean,
    val route: Int,
    val phonesSent: Boolean,
    val xruns: Int?,
    val latencyMs: Double?,
    val error: String,
)

fun parseEngineStats(text: String): EngineStats {
    val values = mutableMapOf<String, String>()
    text.lineSequence().forEach { line ->
        val split = line.indexOf('=')
        if (split > 0) values[line.substring(0, split)] = line.substring(split + 1)
    }
    fun flag(key: String) = values[key] == "true"
    val xruns = if (flag("xruns_known")) values["xruns"]?.toIntOrNull() else null
    val latency = if (flag("latency_known")) values["latency_ms"]?.toDoubleOrNull() else null
    return EngineStats(
        opened = flag("opened"),
        requestedChannels = values["requested_channels"]?.toIntOrNull() ?: 4,
        openedChannels = values["opened_channels"]?.toIntOrNull() ?: 0,
        sampleRate = values["sample_rate"]?.toIntOrNull() ?: 0,
        deviceId = values["device_id"]?.toIntOrNull() ?: 0,
        sharing = values["sharing"] ?: "UNKNOWN",
        performance = values["performance"] ?: "UNKNOWN",
        fallbackToTwo = flag("fallback_to_two"),
        route = values["route"]?.toIntOrNull() ?: 0,
        phonesSent = flag("phones_sent"),
        xruns = xruns,
        latencyMs = latency,
        error = values["error"].orEmpty(),
    )
}
