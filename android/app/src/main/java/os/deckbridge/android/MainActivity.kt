package os.deckbridge.android

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import os.deckbridge.logic.usbVariantReport
import os.deckbridge.logic.buildDiagnosticReport
import os.deckbridge.logic.loadPlainFolder
import os.deckbridge.logic.loadRekordbox
import os.deckbridge.logic.TempoAssembler
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.interpretPerformance

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Em palco a tela não pode apagar nem bloquear no meio da música.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val screen = intent?.getStringExtra("deckbridge.screen").orEmpty()
        val play = intent?.getBooleanExtra("deckbridge.play", false) ?: false
        val midi = intent?.getBooleanExtra("deckbridge.midi", false) ?: false
        val openLocal = intent?.getBooleanExtra(os.deckbridge.manager.mobile.Modes.EXTRA_OPEN_LOCAL, false) ?: false
        // A entrada do app (StartActivity) volta neste modo da próxima vez.
        os.deckbridge.manager.mobile.Modes.remember(this, os.deckbridge.manager.mobile.Modes.Mode.DJ)
        val toLibrary = { os.deckbridge.manager.mobile.Modes.switchTo(this, os.deckbridge.manager.mobile.Modes.Mode.LIBRARY) }
        setContent {
            // O MENU do DJ leva ao modo Biblioteca (que roda no processo dele; este é encerrado).
            androidx.compose.runtime.CompositionLocalProvider(LocalModeSwitch provides ("BIBLIOTECA ›" to toLibrary)) {
                App(screen, play, midi, openLocal)
            }
        }
    }
}

@Composable
private fun App(screen: String, play: Boolean, injectMidi: Boolean, openLocal: Boolean = false) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            listOf("kick.flac", "clap.flac", "snare.flac", "hat-closed.flac", "hat-open.flac").forEachIndexed { index, name ->
                val bytes = context.assets.open("drums/$name").use { it.readBytes() }
                NativeEngine.loadDrum(index, bytes)
            }
        }
    }
    var audioDevice by remember { mutableIntStateOf(usbAudioDeviceId(context) ?: 0) }
    DisposableEffect(Unit) {
        // A DDJ-400 pode ser ligada, desligada ou religada com o app aberto; o id do aparelho muda a cada vez.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                audioDevice = usbAudioDeviceId(context) ?: 0
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                audioDevice = usbAudioDeviceId(context) ?: 0
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    var page by remember {
        // O app abre direto nos decks. O diagnóstico fica em "deckbridge.screen=diagnostico" e no menu.
        mutableIntStateOf(if (screen == "diagnostico") 0 else 2)
    }
    var browsePulse by remember { mutableIntStateOf(if (screen == "biblioteca") 1 else 0) }
    var volume by remember { mutableStateOf<ReadableVolume?>(null) }
    var catalog by remember { mutableStateOf<RbCatalog?>(null) }
    val catalogHolder = remember { arrayOf<RbCatalog?>(null) }
    catalogHolder[0] = catalog
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Só leitura. Permissão persistente depois de autorizar o pendrive.") }
    var usbLabel by remember { mutableStateOf(removableVolumeLabel(context)) }
    var deckTracks by remember { mutableStateOf(listOf<RbTrack?>(null, null)) }
    // As últimas mensagens ficam cruas num anel; o texto só é montado com o diagnóstico aberto. Antes cada giro do
    // prato formatava texto e mudava um estado na raiz, o que recompunha a tela dos decks a cada mensagem.
    val recentMidi = remember { ArrayDeque<IntArray>() }
    var midiTick by remember { mutableIntStateOf(0) }
    val map = remember { MidiMap.loadResource() }
    val assembler = remember { MidiAssembler() }
    val bringUp = remember { os.deckbridge.logic.BringUpChecklist(map) }
    var remap by remember { mutableStateOf(os.deckbridge.logic.MidiRemap.NONE) }
    var learning by remember { mutableStateOf<os.deckbridge.logic.ControlHit?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        remap = os.deckbridge.logic.MidiRemap.parse(SettingsStore.remapFlow(context).first())
    }
    val tempo = remember { TempoAssembler() }
    PerformanceHub.tempo = tempo
    val handler = remember { Handler(Looper.getMainLooper()) }
    val oneLibrary = remember { AndroidOneLibrary(context) }
    val host = remember { AndroidDjHost(context) }

    fun describe(bytes: ByteArray, simulated: Boolean) {
        val parsed = assembler.push(bytes)
        if (parsed.isEmpty()) return
        for (message in parsed) {
            var (status, data1) = remap.apply(message.status, message.data1)
            var hit = map.lookup(status, data1)
            val unknown = bringUp.observe(status, data1, hit.mapped)
            val target = learning
            if (unknown && target != null && !simulated) {
                // Aprender: a próxima mensagem fora do mapa passa a valer pelo controle escolhido na lista.
                remap = remap.with(message.status, message.data1, target.status, target.midino)
                val saved = remap.serialize()
                scope.launch { SettingsStore.saveRemap(context, saved) }
                bringUp.forget(message.status, message.data1)
                learning = null
                status = target.status
                data1 = target.midino
                hit = map.lookup(status, data1)
                bringUp.observe(status, data1, true)
            }
            interpretPerformance(hit, message.data2, tempo)?.let { PerformanceHub.onAction?.invoke(it) }
            recentMidi.addFirst(intArrayOf(status, data1, message.data2, if (simulated) 1 else 0))
            while (recentMidi.size > 12) recentMidi.removeLast()
        }
        if (page == 0) midiTick += 1
    }

    fun midiLines(): List<String> = recentMidi.map { (status, data1, value, simulated) ->
        val hit = map.lookup(status, data1)
        "${if (simulated == 1) "SIMULADO " else ""} status=0x%02X nota=0x%02X valor=%d | %s | %s".format(status, data1, value, hit.key.ifBlank { "—" }, hit.description)
    }

    fun openVolume(next: ReadableVolume) {
        loading = true
        notice = "Lendo ${next.label}…"
        Thread {
            val loaded = os.deckbridge.logic.loadRekordboxLight(next, oneLibrary)
            if (loaded.error != "E_RB_EXPORT_NOT_FOUND") {
                handler.post {
                    volume = next
                    catalog = loaded
                    loading = false
                    notice = if (loaded.error != null) {
                        "Falha ${loaded.error}. O app não gravou no volume."
                    } else if (next.simulated) {
                        "Pendrive simulado aberto. Não é USB nem rekordbox real."
                    } else {
                        "Biblioteca lida. Somente leitura."
                    }
                }
                return@Thread
            }
            val cache = java.io.File(context.cacheDir, "plain-analysis")
            loadPlainFolder(next, cache, { name, bytes -> decodeTrackPcm(context, name, bytes) }) { step ->
                handler.post {
                    volume = next
                    catalog = step
                    loading = step.scanNote.startsWith("A analisar")
                    notice = step.scanNote.ifBlank { step.error ?: "Pasta de áudio lida. Somente leitura." }
                }
            }
        }.start()
    }

    // Autorizar o pendrive: o seletor do Android, só leitura (permissão persistente).
    val tree = androidx.activity.compose.rememberLauncherForActivityResult(OpenTreeReadOnly()) { uri ->
        if (uri != null) {
            runCatching { keepReadPermission(context, uri) }
            openVolume(DocumentVolume(context, uri, "pendrive autorizado"))
        }
    }

    // Biblioteca autorizada antes: abre sozinha ao iniciar, com o pendrive já ligado. Sem pendrive (ou vindo do modo
    // Biblioteca), abre a biblioteca do aparelho, só para ler: o modo DJ não escreve nela (ADR-0012).
    LaunchedEffect(Unit) {
        if (catalog != null) return@LaunchedEffect
        val usb = if (removableVolumeLabel(context).isNotBlank()) persistedRekordboxVolumes(context).firstOrNull() else null
        val local = os.deckbridge.manager.mobile.Modes.hasLocalLibrary(context)
        when {
            usb != null && !(openLocal && local) -> openVolume(usb)
            local -> openVolume(os.deckbridge.logic.FolderVolume(os.deckbridge.manager.mobile.Modes.libraryRoot(context), "Biblioteca do aparelho"))
        }
    }

    DisposableEffect(Unit) {
        val opened = mutableMapOf<Int, MidiDevice>()
        val manager = context.getSystemService(MidiManager::class.java)
        fun openMidi(info: MidiDeviceInfo) {
            if (opened.containsKey(info.id)) return
            val outputs = info.ports.mapIndexedNotNull { index, port ->
                if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) index else null
            }
            if (outputs.isEmpty()) return
            val inputs = info.ports.mapIndexedNotNull { index, port ->
                if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) index else null
            }
            manager?.openDevice(info, { device ->
                if (device == null) return@openDevice
                opened[info.id] = device
                PerformanceHub.controllerConnected = true
                // Porta de saída do app: acende os LEDs da controladora.
                inputs.firstOrNull()?.let { index ->
                    PerformanceHub.ledPort = runCatching { device.openInputPort(index) }.getOrNull()?.let { port ->
                        { bytes: ByteArray -> port.send(bytes, 0, bytes.size) }
                    }
                    PerformanceHub.ledEpoch += 1
                }
                outputs.forEach { index ->
                    val port = device.openOutputPort(index) ?: return@forEach
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            val slice = msg.copyOfRange(offset, (offset + count).coerceAtMost(msg.size))
                            handler.post { describe(slice, false) }
                        }
                    })
                }
                handler.post { notice = "Controladora MIDI conectada: ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()}" }
            }, handler)
        }
        manager?.devices?.forEach { openMidi(it) }
        // Hotplug: a controladora pode entrar depois do app, ou sair e voltar no meio da apresentação.
        val midiCallback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                openMidi(info)
            }

            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                opened.remove(info.id)?.let {
                    PerformanceHub.ledPort = null
                    PerformanceHub.controllerConnected = opened.isNotEmpty()
                    runCatching { it.close() }
                }
                handler.post { notice = "Controladora MIDI desconectada. A música continua; ao religar o cabo ela volta sozinha." }
            }
        }
        manager?.registerDeviceCallback(midiCallback, handler)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                usbLabel = removableVolumeLabel(context)
                val action = intent?.action
                if (action == UsbManager.ACTION_USB_DEVICE_DETACHED && catalogHolder[0]?.simulated == false) {
                    // As faixas dos decks já estão na memória: a música NÃO para quando um USB sai (pendrive ou o
                    // cabo da DDJ-400). O catálogo fica, mas carregar uma faixa nova precisa do pendrive de volta.
                    notice = "USB removido. A música dos decks continua; carregar faixas novas precisa do pendrive."
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
            manager?.unregisterDeviceCallback(midiCallback)
            opened.values.forEach { runCatching { it.close() } }
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
            notice = "Pendrive simulado aberto para esta tela. SIMULAÇÃO."
        }
        if (injectMidi) {
            describe(byteArrayOf(0x90.toByte(), 0x0B, 0x7F), true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            0 -> {
                @Suppress("UNUSED_EXPRESSION") midiTick
                DiagnosticPage(
                    midiLines(),
                    null,
                    libraryReport = catalog?.let { os.deckbridge.logic.inspectText(it) }.orEmpty(),
                    onPage = { page = it },
                    bringUp = BringUpView(
                        summary = bringUp.summary(),
                        missing = bringUp.missing.take(24).map { it.description.substringBefore(" - ") },
                        unknown = bringUp.unknown.take(8).map { (st, d1, n) -> "${os.deckbridge.logic.midiPair(st, d1)} ×$n" },
                        learning = learning?.description?.substringBefore(" - "),
                        remap = remap.serialize(),
                        report = bringUp.report(remap),
                    ),
                    onLearn = { index -> learning = bringUp.missing.getOrNull(index) },
                    onClearRemap = {
                        remap = os.deckbridge.logic.MidiRemap.NONE
                        learning = null
                        scope.launch { SettingsStore.saveRemap(context, "") }
                    },
                ) {
                    describe(byteArrayOf(0x90.toByte(), 0x0B, 0x7F), true)
                }
            }
            else -> DeckPage(
                catalog = catalog,
                volume = volume,
                deviceId = audioDevice,
                autoplay = play,
                loaded = deckTracks,
                onLoaded = { deck, track ->
                    deckTracks = deckTracks.toMutableList().also { it[deck] = track }
                },
                usbLabel = usbLabel.ifBlank { if (catalog?.simulated == true) "USB simulado" else "" },
                browsePulse = browsePulse,
                onAuthorize = { tree.launch(removableInitialUri(context)) },
                host = host,
                onSimulate = {
                    loading = true
                    Thread {
                        val simulated = SyntheticRekordbox.volume()
                        handler.post { openVolume(simulated) }
                    }.start()
                },
                onPage = { next ->
                    if (next == 1) {
                        browsePulse += 1
                        page = 2
                    } else {
                        page = next
                    }
                },
            )
        }
    }
}

@Composable
internal fun DiagnosticPage(
    events: List<String>,
    jvmBanner: String?,
    libraryReport: String = "",
    onPage: (Int) -> Unit = {},
    bringUp: BringUpView = BringUpView(),
    onLearn: (Int) -> Unit = {},
    onClearRemap: () -> Unit = {},
    onSimulate: () -> Unit,
) {
    val context = LocalContext.current
    var identity by remember { mutableStateOf(readIdentity(context)) }
    var battery by remember { mutableStateOf(readBattery(context)) }
    var audio by remember { mutableStateOf(listAudioOutputs(context)) }
    var usb by remember { mutableStateOf(listUsb(context)) }
    var midi by remember { mutableStateOf(listMidiPorts(context)) }
    var engine by remember { mutableStateOf<EngineStats?>(null) }
    var heard by remember { mutableStateOf(Heard.NAO_TESTEI) }
    var deviceId by remember { mutableIntStateOf(usbAudioDeviceId(context) ?: 0) }
    var usbReport by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        usbReport = withContext(Dispatchers.Default) { usbVariantReport() }
    }

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
    ) + if (bringUp.report.isNotBlank()) "\n\nBring-up da controladora\n${bringUp.report}" else ""

    DiagnosticScreen(
        headline = "Prova A0. O som desta tela ainda não é prova até você mandar o relatório.",
        identityLine = "${identity.manufacturer} ${identity.model} · Android ${identity.androidRelease} · SoC ${identity.soc}",
        ramLine = "RAM ${identity.ramMb ?: "UNKNOWN"} MB · núcleos ${identity.cores ?: "UNKNOWN"}",
        engineLine = engineLine(engine),
        events = events,
        heard = heard,
        report = report,
        jvmBanner = jvmBanner,
        onPage = onPage,
        onRefresh = { refresh() },
        onMaster = { engine = NativeEngine.start(deviceId, 1) },
        onPhones = { engine = NativeEngine.start(deviceId, 2) },
        onStop = { NativeEngine.stop(); engine = engine?.copy(route = 0, phonesSent = false) },
        onSimulate = onSimulate,
        onHeard = { heard = it },
        usbReport = usbReport,
        libraryReport = libraryReport,
        bringUp = bringUp,
        onLearn = onLearn,
        onClearRemap = onClearRemap,
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
    onPage: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    onMaster: () -> Unit,
    onPhones: () -> Unit,
    onStop: () -> Unit,
    onSimulate: () -> Unit,
    onHeard: (Heard) -> Unit,
    onShare: () -> Unit,
    usbReport: String = "",
    libraryReport: String = "",
    bringUp: BringUpView = BringUpView(),
    onLearn: (Int) -> Unit = {},
    onClearRemap: () -> Unit = {},
) {
    StageScaffold(page = 0, onPage = onPage, simulationNote = simulationNote(jvmBanner, jvmBanner != null)) {
        Row(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.weight(1.05f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextInk(headline, fontSize = 22.sp, weight = FontWeight.SemiBold)
                Panel {
                    TextInk(identityLine, fontSize = 16.sp, weight = FontWeight.SemiBold)
                    TextInk(ramLine, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
                TextInk("O que você ouviu?", fontSize = 14.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                Heard.entries.forEach { option ->
                    StageButton(
                        label = heardLabel(option),
                        onClick = { onHeard(option) },
                        selected = heard == option,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextInk(
                    "O MIDI simulado não é a DDJ-400. Serve para ver o nome do mapa.",
                    fontSize = 14.sp,
                    color = Color(StagePalette.inkMuted),
                )
            }
            Column(
                Modifier.weight(0.95f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StageButton("Atualizar", onRefresh, Modifier.weight(1f), fontSize = 13.sp)
                    StageButton("Master 1–2", onMaster, Modifier.weight(1f), fontSize = 13.sp)
                    StageButton("Fone 3–4", onPhones, Modifier.weight(1f), fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StageButton("Parar tom", onStop, Modifier.weight(1f), fontSize = 13.sp)
                    StageButton("MIDI simulado", onSimulate, Modifier.weight(1f), fontSize = 13.sp)
                    StageButton("Relatório", onShare, Modifier.weight(1f), fontSize = 13.sp)
                }
                Panel {
                    TextInk("Motor", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    TextInk(engineLine, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Panel {
                    TextInk("Bring-up da controladora", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    TextInk(bringUp.summary, fontSize = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    if (bringUp.learning != null) {
                        TextInk("APRENDENDO: mexa agora no controle \"${bringUp.learning}\" da DDJ-400.", fontSize = 14.sp, color = Color(StagePalette.accentOrange), modifier = Modifier.padding(top = 4.dp))
                    }
                    if (bringUp.unknown.isNotEmpty()) {
                        TextInk("Fora do mapa: " + bringUp.unknown.joinToString("  "), fontSize = 13.sp, color = Color(StagePalette.accentOrange), modifier = Modifier.padding(top = 4.dp))
                    }
                    if (bringUp.missing.isNotEmpty()) {
                        TextInk("Ainda não vistos (toque para aprender):", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
                        bringUp.missing.chunked(3).forEachIndexed { row, labels ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                                labels.forEachIndexed { col, label ->
                                    StageButton(label, { onLearn(row * 3 + col) }, Modifier.weight(1f), fontSize = 11.sp)
                                }
                                repeat(3 - labels.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (bringUp.remap.isNotBlank()) {
                        TextInk("Remapeado: ${bringUp.remap}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        StageButton("Limpar remapeamento", onClearRemap, Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 12.sp)
                    }
                }
                if (events.isNotEmpty()) {
                    Panel {
                        TextInk("MIDI", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                        events.forEach { TextInk(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                if (libraryReport.isNotBlank()) {
                    Panel {
                        TextInk("Biblioteca lida", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                        TextInk(libraryReport, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Panel {
                    TextInk("Variantes USB", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    TextInk(usbReport.ifBlank { "Sem relatório de variantes nesta sessão." }, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Panel {
                    TextInk("Relatório", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    TextInk(report, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(StagePalette.panel))
            .padding(12.dp),
    ) {
        content()
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

/** O que a tela de diagnóstico mostra do bring-up (ver `BringUpChecklist`). */
data class BringUpView(
    val summary: String = "Mapa: nenhuma mensagem da controladora ainda",
    val missing: List<String> = emptyList(),
    val unknown: List<String> = emptyList(),
    val learning: String? = null,
    val remap: String = "",
    val report: String = "",
)
