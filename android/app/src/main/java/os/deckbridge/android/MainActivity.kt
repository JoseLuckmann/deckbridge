package os.deckbridge.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.deckbridge.logic.EngineStats
import os.deckbridge.logic.Heard
import os.deckbridge.logic.MidiAssembler
import os.deckbridge.logic.MidiMap
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.buildDiagnosticReport
import os.deckbridge.logic.loadRekordbox
import os.deckbridge.logic.TempoAssembler
import os.deckbridge.logic.interpretPerformance
import os.deckbridge.logic.textSp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent?.getStringExtra("deckbridge.screen").orEmpty()
        val play = intent?.getBooleanExtra("deckbridge.play", false) ?: false
        val midi = intent?.getBooleanExtra("deckbridge.midi", false) ?: false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(screen, play, midi)
            }
        }
    }
}

@Composable
private fun App(screen: String, play: Boolean, injectMidi: Boolean) {
    val context = LocalContext.current
    var page by remember {
        mutableIntStateOf(
            when {
                play || screen == "decks" -> 2
                screen == "biblioteca" -> 1
                else -> 0
            },
        )
    }
    var volume by remember { mutableStateOf<ReadableVolume?>(null) }
    var catalog by remember { mutableStateOf<RbCatalog?>(null) }
    val catalogHolder = remember { arrayOf<RbCatalog?>(null) }
    catalogHolder[0] = catalog
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Só leitura. Permissão persistente depois de autorizar o pendrive.") }
    var usbLabel by remember { mutableStateOf(removableVolumeLabel(context)) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var deckTracks by remember { mutableStateOf(listOf<RbTrack?>(null, null)) }
    var midiLines by remember { mutableStateOf(listOf<String>()) }
    val map = remember { MidiMap.loadResource() }
    val assembler = remember { MidiAssembler() }
    val tempo = remember { TempoAssembler() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val oneLibrary = remember { AndroidOneLibrary(context) }

    fun describe(bytes: ByteArray, simulated: Boolean) {
        val parsed = assembler.push(bytes)
        if (parsed.isEmpty()) return
        val prefix = if (simulated) "SIMULADO " else ""
        val lines = parsed.map { message ->
            val hit = map.lookup(message.status, message.data1)
            interpretPerformance(hit, message.data2, tempo)?.let { PerformanceHub.onAction?.invoke(it) }
            "$prefix status=0x%02X nota=0x%02X valor=%d | %s | %s".format(
                message.status,
                message.data1,
                message.data2,
                hit.key.ifBlank { "—" },
                hit.description,
            )
        }
        midiLines = (lines + midiLines).take(12)
    }

    fun openVolume(next: ReadableVolume) {
        loading = true
        notice = "Lendo ${next.label}…"
        Thread {
            val loaded = loadRekordbox(next, oneLibrary)
            handler.post {
                volume = next
                catalog = loaded
                loading = false
                selected = loaded.playlists.firstOrNull { !it.folder }?.id
                notice = if (loaded.error != null) {
                    "Falha ${loaded.error}. O app não gravou no volume."
                } else if (next.simulated) {
                    "Pendrive simulado aberto. Não é USB nem rekordbox real."
                } else {
                    "Biblioteca lida. Somente leitura."
                }
            }
        }.start()
    }

    DisposableEffect(Unit) {
        val opened = mutableListOf<MidiDevice>()
        val manager = context.getSystemService(MidiManager::class.java)
        manager?.devices?.forEach { info ->
            val outputs = info.ports.mapIndexedNotNull { index, port ->
                if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) index else null
            }
            if (outputs.isEmpty()) return@forEach
            manager.openDevice(info, { device ->
                if (device == null) return@openDevice
                opened += device
                outputs.forEach { index ->
                    val port = device.openOutputPort(index) ?: return@forEach
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            val slice = msg.copyOfRange(offset, (offset + count).coerceAtMost(msg.size))
                            handler.post { describe(slice, false) }
                        }
                    })
                }
            }, handler)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                usbLabel = removableVolumeLabel(context)
                val action = intent?.action
                if (action == UsbManager.ACTION_USB_DEVICE_DETACHED && catalogHolder[0]?.simulated == false) {
                    catalog = null
                    volume = null
                    notice = "Pendrive removido. Nada volta a tocar sozinho."
                    NativeEngine.transport(0, false)
                    NativeEngine.transport(1, false)
                }
                if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                    notice = "Pendrive USB visto. Autorize a leitura se ainda não guardou a permissão."
                    val persisted = persistedRekordboxVolumes(context)
                    if (persisted.isNotEmpty()) openVolume(persisted.first())
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            opened.forEach { runCatching { it.close() } }
            context.unregisterReceiver(receiver)
            NativeEngine.stop()
        }
    }

    LaunchedEffect(screen, play) {
        if (screen == "biblioteca" || screen == "decks" || play) {
            val loadedPair = withContext(Dispatchers.Default) {
                val simulated = SyntheticRekordbox.volume()
                simulated to loadRekordbox(simulated, oneLibrary)
            }
            volume = loadedPair.first
            catalog = loadedPair.second
            selected = loadedPair.second.playlists.firstOrNull { it.name == "Abertura" }?.id
            notice = "Pendrive simulado aberto para esta tela. SIMULAÇÃO."
        }
        if (injectMidi) {
            describe(byteArrayOf(0x90.toByte(), 0x0B, 0x7F), true)
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { page = 0 }) { Text("Diagnóstico") }
            Button(onClick = { page = 1 }) { Text("Biblioteca") }
            Button(onClick = { page = 2 }) { Text("Decks") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                0 -> DiagnosticPage(midiLines, null) { describe(byteArrayOf(0x90.toByte(), 0x0B, 0x7F), true) }
                1 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
                    LibraryPage(
                        catalog = catalog,
                        loading = loading,
                        notice = notice,
                        usbLabel = usbLabel,
                        selectedId = selected,
                        onSelect = { selected = it },
                        onAuthorize = { uri ->
                            if (uri == null) return@LibraryPage
                            runCatching { keepReadPermission(context, uri) }
                            openVolume(DocumentVolume(context, uri, "pendrive autorizado"))
                        },
                        onSimulate = {
                            loading = true
                            Thread {
                                val simulated = SyntheticRekordbox.volume()
                                handler.post { openVolume(simulated) }
                            }.start()
                        },
                        onReloadPersisted = {
                            val persisted = persistedRekordboxVolumes(context)
                            if (persisted.isEmpty()) notice = "Nenhuma permissão guardada com export.pdb."
                            else openVolume(persisted.first())
                        },
                        onLoadDeck = { track, deck ->
                            deckTracks = deckTracks.toMutableList().also { it[deck] = track }
                            page = 2
                        },
                        widthPx = widthPx,
                    )
                }
                else -> DeckPage(
                    catalog = catalog,
                    volume = volume,
                    deviceId = usbAudioDeviceId(context) ?: 0,
                    autoplay = play,
                    loaded = deckTracks,
                    onLoaded = { deck, track ->
                        deckTracks = deckTracks.toMutableList().also { it[deck] = track }
                    },
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticPage(events: List<String>, jvmBanner: String?, onSimulate: () -> Unit) {
    val context = LocalContext.current
    var identity by remember { mutableStateOf(readIdentity(context)) }
    var battery by remember { mutableStateOf(readBattery(context)) }
    var audio by remember { mutableStateOf(listAudioOutputs(context)) }
    var usb by remember { mutableStateOf(listUsb(context)) }
    var midi by remember { mutableStateOf(listMidiPorts(context)) }
    var engine by remember { mutableStateOf<EngineStats?>(null) }
    var heard by remember { mutableStateOf(Heard.NAO_TESTEI) }
    var deviceId by remember { mutableIntStateOf(usbAudioDeviceId(context) ?: 0) }

    fun refresh() {
        identity = readIdentity(context)
        battery = readBattery(context)
        audio = listAudioOutputs(context)
        usb = listUsb(context)
        midi = listMidiPorts(context)
        deviceId = usbAudioDeviceId(context) ?: 0
    }

    val report = buildDiagnosticReport(
        identity = identity,
        audioDevices = audio,
        usbDevices = usb,
        midiPorts = midi,
        recentMidi = events,
        engine = engine,
        batteryPercent = battery.first,
        batteryTemperatureC = battery.second,
        heard = heard,
        selectedDeviceId = deviceId.takeIf { it > 0 },
    )

    DiagnosticScreen(
        headline = "Prova A0. O som desta tela ainda não é prova até você mandar o relatório.",
        identityLine = "${identity.manufacturer} ${identity.model} · Android ${identity.androidRelease} · SoC ${identity.soc}",
        ramLine = "RAM ${identity.ramMb ?: "UNKNOWN"} MB · núcleos ${identity.cores ?: "UNKNOWN"}",
        engineLine = engineLine(engine),
        events = events,
        heard = heard,
        report = report,
        jvmBanner = jvmBanner,
        onRefresh = { refresh() },
        onMaster = { engine = NativeEngine.start(deviceId, 1) },
        onPhones = { engine = NativeEngine.start(deviceId, 2) },
        onStop = { NativeEngine.stop(); engine = engine?.copy(route = 0, phonesSent = false) },
        onSimulate = onSimulate,
        onHeard = { heard = it },
        onShare = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "DeckBridge diagnóstico")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(send, "Enviar relatório"))
        },
    )
}

@Composable
fun DiagnosticScreen(
    headline: String,
    identityLine: String,
    ramLine: String,
    engineLine: String,
    events: List<String>,
    heard: Heard,
    report: String,
    jvmBanner: String?,
    onRefresh: () -> Unit,
    onMaster: () -> Unit,
    onPhones: () -> Unit,
    onStop: () -> Unit,
    onSimulate: () -> Unit,
    onHeard: (Heard) -> Unit,
    onShare: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val size = textSp(widthPx).sp
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (jvmBanner != null) Text(jvmBanner, fontSize = size, color = androidx.compose.ui.graphics.Color(0xFFFFC857))
            Text(headline, fontSize = size)
            Text(identityLine, fontSize = size)
            Text(ramLine, fontSize = size)
            Button(onClick = onRefresh) { Text("Atualizar dispositivos") }
            Button(onClick = onMaster) { Text("Tom no master (1–2)") }
            Button(onClick = onPhones) { Text("Tom no fone (3–4)") }
            Button(onClick = onStop) { Text("Parar tom") }
            Button(onClick = onSimulate) { Text("MIDI simulado") }
            Text("O MIDI simulado não é a DDJ-400. Serve para ver o nome do mapa.", fontSize = size)
            Text(engineLine, fontSize = size)
            Text("O que você ouviu?", fontSize = size)
            Heard.entries.forEach { option ->
                Row {
                    RadioButton(selected = heard == option, onClick = { onHeard(option) })
                    Text(heardLabel(option), modifier = Modifier.padding(top = 12.dp), fontSize = size)
                }
            }
            events.forEach { Text(it, fontSize = size) }
            Button(onClick = onShare) { Text("Compartilhar relatório") }
            Text(report, fontSize = size)
        }
    }
}

private fun engineLine(engine: EngineStats?): String {
    if (engine == null) return "Motor parado. Latência UNKNOWN. X-runs UNKNOWN."
    val latency = engine.latencyMs?.let { String.format(java.util.Locale.US, "%.1f ms", it) } ?: "UNKNOWN"
    val xruns = engine.xruns?.toString() ?: "UNKNOWN"
    val phones = if (engine.phonesSent) "tom do fone enviado nos canais 3–4" else "tom do fone não foi para os canais 3–4"
    return "Aberto=${engine.opened}. Canais ${engine.openedChannels} de ${engine.requestedChannels}. $phones. Latência $latency. X-runs $xruns."
}

private fun heardLabel(heard: Heard): String = when (heard) {
    Heard.NAO_TESTEI -> "Ainda não testei"
    Heard.SEPARADO -> "Ouvi master e fone separados"
    Heard.MESMO_LUGAR -> "Saiu tudo no mesmo lugar"
    Heard.SEM_SOM -> "Não saiu som"
}
