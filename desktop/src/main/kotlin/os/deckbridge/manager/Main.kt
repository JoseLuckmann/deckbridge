package os.deckbridge.manager

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import javax.swing.JFileChooser
import os.deckbridge.manager.ui.App
import os.deckbridge.manager.ui.ConfirmAsk
import os.deckbridge.manager.ui.UiState

fun chooseFolder(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Escolha a pasta do pendrive (a que tem PIONEER/rekordbox)"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun chooseAudioFiles(): List<File> {
    val chooser = JFileChooser().apply {
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        dialogTitle = "Escolha os arquivos de áudio (ou uma pasta)"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
}

/**
 * `deckbridge-manager [PASTA_DO_PENDRIVE]`. Sem argumento, a tela de boas-vindas lista os pendrives encontrados.
 * `deckbridge-manager --dj [PASTA]`: o modo DJ (ADR-0012), num processo só dele; sem pasta, a biblioteca do computador.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--dj") return os.deckbridge.dj.djMain(args.drop(1))
    libraryMain(args)
}

/** Reabre o programa no outro modo e sai deste: a memória deste modo volta inteira (como no celular). */
fun relaunch(args: List<String>) {
    val self = ProcessHandle.current().info()
    val command = self.command().orElse(null)
    val classpath = System.getProperty("java.class.path")
    val library = System.getProperty("java.library.path")
    val cmd = if (command != null && command.endsWith("java")) {
        listOf(command, "-Djava.library.path=$library", "-cp", classpath, "os.deckbridge.manager.MainKt") + args
    } else {
        listOfNotNull(command) + args
    }
    ProcessBuilder(cmd).inheritIO().start()
    kotlin.system.exitProcess(0)
}

fun libraryMain(args: Array<String>) = application {
    val model = remember { ManagerModel() }
    val ui = remember { UiState().apply { chooser = ::chooseFolder; chooseFiles = ::chooseAudioFiles; toDj = { relaunch(listOf("--dj")) } } }
    LaunchedEffect(Unit) { args.firstOrNull()?.let { model.open(File(it)) } }
    Window(
        onCloseRequest = {
            if (model.dirty) {
                ui.confirm = ConfirmAsk("Sair com mudanças na fila?", "${model.pending.size} mudanças ainda não foram gravadas e se perdem ao sair. O pendrive não foi tocado.", "Sair mesmo assim", true) { exitApplication() }
            } else exitApplication()
        },
        title = "DeckBridge Biblioteca",
        icon = remember { AppIcon },
        state = rememberWindowState(size = DpSize(1360.dp, 820.dp)),
        // Esc volta nas telas de set (como o "voltar" do celular); os outros atalhos estão em StudioKeys (? mostra a lista).
        onKeyEvent = { e ->
            val esc = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape
            if (esc && os.deckbridge.manager.studio.StudioKeys.helpOpen) { os.deckbridge.manager.studio.StudioKeys.helpOpen = false; true }
            else if (esc) ui.studio?.back() == true
            else ui.studio?.let { os.deckbridge.manager.studio.StudioKeys.handle(it, e) } == true
        },
    ) { App(model, ui) }
}


/** Ícone da janela: o mesmo disco do app de celular (anel e ponto brancos, marca azul, arco laranja), no fundo preto. */
object AppIcon : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(108f, 108f)

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        val k = size.minDimension / 108f
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val ink = androidx.compose.ui.graphics.Color(os.deckbridge.logic.StagePalette.ink)
        drawCircle(androidx.compose.ui.graphics.Color(os.deckbridge.logic.StagePalette.bg), radius = 54f * k, center = c)
        drawCircle(ink, radius = 29f * k, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(6f * k))
        drawCircle(ink, radius = 5.5f * k, center = c)
        drawLine(androidx.compose.ui.graphics.Color(os.deckbridge.logic.StagePalette.accentBlue), androidx.compose.ui.geometry.Offset(c.x, c.y - 29f * k), androidx.compose.ui.geometry.Offset(c.x, c.y - 16f * k), 6f * k, androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(androidx.compose.ui.graphics.Color(os.deckbridge.logic.StagePalette.accentOrange), 38f, 22f, false, androidx.compose.ui.geometry.Offset(c.x - 29f * k, c.y - 29f * k), androidx.compose.ui.geometry.Size(58f * k, 58f * k), style = androidx.compose.ui.graphics.drawscope.Stroke(5f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
