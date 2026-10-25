package os.deckbridge.manager

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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

/** `deckbridge-manager [PASTA_DO_PENDRIVE]`. Sem argumento, a tela de boas-vindas lista os pendrives encontrados. */
fun main(args: Array<String>) = application {
    val model = remember { ManagerModel() }
    val ui = remember { UiState().apply { chooser = ::chooseFolder; chooseFiles = ::chooseAudioFiles } }
    LaunchedEffect(Unit) { args.firstOrNull()?.let { model.open(File(it)) } }
    Window(
        onCloseRequest = {
            if (model.dirty) {
                ui.confirm = ConfirmAsk("Sair com mudanças na fila?", "${model.pending.size} mudanças ainda não foram gravadas e se perdem ao sair. O pendrive não foi tocado.", "Sair mesmo assim", true) { exitApplication() }
            } else exitApplication()
        },
        title = "DeckBridge Library Manager",
        state = rememberWindowState(size = DpSize(1360.dp, 820.dp)),
    ) { App(model, ui) }
}
