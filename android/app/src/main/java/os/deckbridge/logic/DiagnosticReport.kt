package os.deckbridge.logic

data class DeviceIdentity(
    val manufacturer: String = "UNKNOWN",
    val model: String = "UNKNOWN",
    val device: String = "UNKNOWN",
    val hardware: String = "UNKNOWN",
    val soc: String = "UNKNOWN",
    val androidRelease: String = "UNKNOWN",
    val sdkInt: Int? = null,
    val ramMb: Int? = null,
    val cores: Int? = null,
)

data class ListedDevice(
    val kind: String,
    val name: String,
    val detail: String,
)

enum class Heard {
    NAO_TESTEI,
    SEPARADO,
    MESMO_LUGAR,
    SEM_SOM,
}

private val OWNER_NOTE = """
Relato do dono em 21/10/2026. Não é medição deste app.
O tablet alvo é um Samsung Galaxy Tab S6 Lite. O modelo exato e o ano não foram confirmados.
Candidatos citados pelo dono: SM-P610 ou SM-P615 (Exynos 9611), ou as versões 2022/2024 (Snapdragon 720G ou Exynos 1280), com 4 GB de RAM.
Com o app djay, a DDJ-400 tocou master e fone separados nesse tablet.
Isso é referência de compatibilidade daquele aparelho. Não prova os 4 canais deste app nem o SoC.
""".trimIndent()

fun buildDiagnosticReport(
    identity: DeviceIdentity,
    audioDevices: List<ListedDevice>,
    usbDevices: List<ListedDevice>,
    midiPorts: List<ListedDevice>,
    recentMidi: List<String>,
    engine: EngineStats?,
    batteryPercent: Int?,
    batteryTemperatureC: String,
    heard: Heard,
    selectedDeviceId: Int?,
): String {
    val profile = classifyCapability(identity.cores, identity.ramMb)
    val sdk = identity.sdkInt
    val family = if (sdk == null) "UNKNOWN" else if (AndroidRange.coversTabS6LiteFamily(sdk)) "dentro de Android 10 a 14" else "fora da faixa Android 10 a 14"
    val heardText = when (heard) {
        Heard.NAO_TESTEI -> "ainda não testei"
        Heard.SEPARADO -> "ouvi master e fone separados (orelha de quem testou, não é medição de laboratório)"
        Heard.MESMO_LUGAR -> "ouvi tudo no mesmo lugar"
        Heard.SEM_SOM -> "não ouvi som"
    }
    val engineText = if (engine == null) {
        "Motor ainda não foi aberto nesta sessão. Latência UNKNOWN. X-runs UNKNOWN."
    } else {
        val latency = engine.latencyMs?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "UNKNOWN"
        val xruns = engine.xruns?.toString() ?: "UNKNOWN"
        val phones = if (engine.phonesSent) {
            "o tom do fone foi enviado aos canais 3–4 deste fluxo"
        } else {
            "o tom do fone não foi enviado aos canais 3–4"
        }
        """
        aberto=${engine.opened}
        canais_pedidos=${engine.requestedChannels}
        canais_abertos=${engine.openedChannels}
        fallback_para_2=${engine.fallbackToTwo}
        taxa=${engine.sampleRate}
        dispositivo=${engine.deviceId}
        modo=${engine.sharing} / ${engine.performance}
        latência_ms=$latency
        xruns=$xruns
        $phones
        erro=${engine.error.ifBlank { "nenhum" }}
        """.trimIndent()
    }
    return """
DeckBridge diagnóstico A0
Isto não fecha prova no hardware. O que não foi lido fica UNKNOWN.
Separação de master e fone neste app: BLOCKED_HARDWARE até este relatório voltar do tablet com a DDJ-400.

$OWNER_NOTE

Leitura automática do sistema (não é chute a partir do nome comercial):
fabricante=${identity.manufacturer}
modelo=${identity.model}
device=${identity.device}
hardware=${identity.hardware}
soc=${identity.soc}
android=${identity.androidRelease}
sdk=${sdk?.toString() ?: "UNKNOWN"}
faixa_android_10_a_14=$family
ram_mb=${identity.ramMb?.toString() ?: "UNKNOWN"}
nucleos_visiveis=${identity.cores?.toString() ?: "UNKNOWN"}
perfil=${profile.tier} waveforms=${profile.waveforms} efeitos_pesados=${profile.heavyEffects}
motivo_do_perfil=${profile.reason}

Bateria:
porcentagem=${batteryPercent?.toString() ?: "UNKNOWN"}
temperatura_c=$batteryTemperatureC

Dispositivo de áudio escolhido para o tom: ${selectedDeviceId?.toString() ?: "padrão do Android"}

Áudio de saída visto pelo Android:
${audioDevices.joinToString("\n").ifBlank { "nenhum" }}

USB visto pelo Android:
${usbDevices.joinToString("\n").ifBlank { "nenhum" }}

Portas MIDI:
${midiPorts.joinToString("\n").ifBlank { "nenhuma" }}

Últimos MIDI com nome do mapa oficial (bytes sem nome ficam "sem nome no mapa oficial"):
${recentMidi.joinToString("\n").ifBlank { "nenhum evento nesta sessão" }}

Motor Oboe:
$engineText

O que a pessoa ouviu: $heardText

O app não grava no pendrive. Arquivos de música, se houver, são só leitura.
minSdk=${AndroidRange.MIN_SDK} targetSdk=${AndroidRange.TARGET_SDK}
""".trimIndent().trim() + "\n"
}

fun localTrackKind(displayName: String): String {
    val lower = displayName.lowercase()
    return when {
        lower.endsWith(".mp3") -> "mp3"
        lower.endsWith(".wav") -> "wav"
        else -> "recusado"
    }
}
