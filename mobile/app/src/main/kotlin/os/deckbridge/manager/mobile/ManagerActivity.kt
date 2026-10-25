package os.deckbridge.manager.mobile

import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import androidx.core.content.ContextCompat
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
    internal lateinit var app: ManagerApp
        private set

    // Fone desconectado: para na hora, para o som não sair pelo alto-falante.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) app.preview.stop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = ManagerApp.get(this)
        setContent { Content(app) }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Saiu da tela (fechou, trocou de app, apagou a tela): a pré-escuta para. Ela só existe enquanto o app está na frente.
     * Também grava o que estiver pendente.
     */
    override fun onStop() {
        app.preview.stop()
        runCatching { unregisterReceiver(noisy) }
        super.onStop()
        Thread { app.saveNow() }.start()
    }

    override fun onDestroy() {
        app.preview.stop()
        super.onDestroy()
    }
}

@Composable
private fun Content(app: ManagerApp) {
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> app.importAudio(uris) }
    val pickUsb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) app.chooseUsb(uri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) app.importFolder(uri) }
    LaunchedEffect(Unit) { if (!app.model.isOpen) app.start() }
    val actions = remember { Actions(pickAudio = { pickAudio.launch(arrayOf("audio/*")) }, pickUsb = { pickUsb.launch(null) }, pickFolder = { pickFolder.launch(null) }) }
    ManagerRoot(app, actions)
}
