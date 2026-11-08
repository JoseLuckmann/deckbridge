package os.deckbridge.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.deckbridge.android.DeckPage
import os.deckbridge.android.LocalModeSwitch
import os.deckbridge.android.NativeEngine
import os.deckbridge.logic.FolderVolume
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.StagePalette
import java.io.File

/** A biblioteca do computador (a mesma pasta que o modo Biblioteca grava), no formato de um pendrive exportado. */
fun desktopLibraryRoot(): File = File(System.getProperty("user.home"), ".deckbridge/library")

/** Há uma biblioteca para tocar em [root] (um pendrive ou a biblioteca do computador). */
fun hasExport(root: File): Boolean = File(root, "PIONEER/rekordbox/export.pdb").isFile

/**
 * O modo DJ no computador (ADR-0012): as mesmas telas e a mesma lógica dos decks do celular (`DeckPage`), o mesmo motor
 * C++ (aqui sobre ALSA) e a DDJ-400 pelo javax.sound.midi. Lê a biblioteca do computador ou um pendrive, sempre só
 * para ler (`FolderVolume` não tem como escrever).
 */
@Composable
fun DjScreen(
    host: DesktopDjHost,
    start: File?,
    chooseFolder: () -> File?,
    toLibrary: (() -> Unit)?,
    post: (() -> Unit) -> Unit,
    onDecks: (List<RbTrack?>) -> Unit = {},
) {
    var volume by remember { mutableStateOf<ReadableVolume?>(null) }
    var catalog by remember { mutableStateOf<RbCatalog?>(null) }
    var decks by remember { mutableStateOf(listOf<RbTrack?>(null, null)) }
    var request by remember { mutableStateOf(start) }
    val midi = remember { DesktopMidi(post) }

    DisposableEffect(Unit) {
        midi.open()
        // Sem placa USB escolhida: a saída padrão do sistema (master nos canais 1 e 2).
        NativeEngine.start(0, 1)
        onDispose {
            midi.close()
            NativeEngine.stop()
        }
    }

    LaunchedEffect(request) {
        val root = request ?: return@LaunchedEffect
        val next = FolderVolume(root, if (root == desktopLibraryRoot()) "Biblioteca do computador" else root.name)
        // Leitura e análise fora da tela, como no celular.
        catalog = withContext(Dispatchers.IO) { os.deckbridge.logic.loadRekordboxLight(next, null) }
        volume = next
        decks = listOf(null, null)
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalModeSwitch provides toLibrary?.let { "BIBLIOTECA ›" to it }) {
        Box(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
            DeckPage(
                catalog = catalog,
                volume = volume,
                deviceId = 0,
                autoplay = false,
                loaded = decks,
                onLoaded = { deck, track ->
                    decks = decks.toMutableList().also { it[deck] = track }
                    onDecks(decks)
                },
                usbLabel = volume?.label.orEmpty(),
                onAuthorize = { chooseFolder()?.let { request = it } },
                onSimulate = {},
                host = host,
            )
        }
    }
}

/** O programa no modo DJ: só a janela de DJ (nada da Biblioteca é carregado neste processo). */
fun djMain(args: List<String>) = androidx.compose.ui.window.application {
    val host = remember { DesktopDjHost() }
    val start = remember {
        args.firstOrNull()?.let(::File) ?: desktopLibraryRoot().takeIf(::hasExport)
    }
    androidx.compose.ui.window.Window(
        onCloseRequest = ::exitApplication,
        title = "DeckBridge DJ",
        icon = remember { os.deckbridge.manager.AppIcon },
        state = androidx.compose.ui.window.rememberWindowState(size = androidx.compose.ui.unit.DpSize(1280.dp, 800.dp)),
    ) {
        DjScreen(
            host = host,
            start = start,
            chooseFolder = { os.deckbridge.manager.chooseFolder() },
            toLibrary = { os.deckbridge.manager.relaunch(emptyList()) },
            post = { block -> javax.swing.SwingUtilities.invokeLater(block) },
        )
    }
}
