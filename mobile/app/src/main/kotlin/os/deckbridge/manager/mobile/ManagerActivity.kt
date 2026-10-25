package os.deckbridge.manager.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** A única tela do app. Girar o celular não recria a atividade (veja o manifesto): o estado fica no [ManagerApp]. */
class ManagerActivity : ComponentActivity() {
    private lateinit var app: ManagerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = ManagerApp(applicationContext)
        setContent { Content(app) }
    }

    /** Ao sair do app, grava o que estiver pendente. O que o usuário não salvou não se perde por trocar de app. */
    override fun onStop() {
        super.onStop()
        Thread { app.saveNow() }.start()
    }
}

@Composable
private fun Content(app: ManagerApp) {
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> app.importAudio(uris) }
    val pickUsb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) app.chooseUsb(uri) }
    LaunchedEffect(Unit) { if (!app.model.isOpen) app.start() }
    val actions = remember { Actions(pickAudio = { pickAudio.launch(arrayOf("audio/*")) }, pickUsb = { pickUsb.launch(null) }) }
    ManagerRoot(app, actions)
}
