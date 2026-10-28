package os.deckbridge.dj

import kotlinx.coroutines.runBlocking
import os.deckbridge.android.NativeEngine
import os.deckbridge.android.PerformanceHub
import os.deckbridge.android.SessionDeck
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.SyntheticRekordbox
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O modo DJ no computador (ADR-0012): as telas de DJ do celular, o motor C++ (sobre ALSA; nos testes o PCM `null`, que
 * anda no relógio sem placa) e a mesma regra de ações da controladora.
 */
class DjModeTest {
    private val out = File(System.getenv("SHOTS_DIR") ?: "build/prints").apply { mkdirs() }

    private fun temp(name: String) = Files.createTempDirectory(name).toFile().apply { deleteOnExit() }

    /** O pendrive sintético (faixas geradas, sem música de ninguém) gravado numa pasta, como um pendrive montado. */
    private fun syntheticFolder(): File {
        val dir = temp("dj-usb")
        SyntheticRekordbox.volume().files.forEach { (path, bytes) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        return dir
    }

    @Test fun theHostKeepsSettingsSessionAndHistoryInItsOwnFolder() {
        val home = temp("dj-home")
        val host = DesktopDjHost(home)
        runBlocking { host.saveSettings(PlayerSettings(keyLock = true, zoomBars = 16, crossCurve = 2, recordAac = true)) }
        val again = DesktopDjHost(home)
        val read = runBlocking { again.settings().let { (it as kotlinx.coroutines.flow.StateFlow).value } }
        assertTrue(read.keyLock)
        assertEquals(16, read.zoomBars)
        assertEquals(2, read.crossCurve)
        assertTrue(!read.recordAac, "no computador a gravação é WAV")
        host.saveSession(listOf(SessionDeck("Contents/a.wav", 12.5f, 1.02f), null))
        assertEquals(listOf(SessionDeck("Contents/a.wav", 12.5f, 1.02f), null), again.readSession())
        val id = host.history.session(1000L)
        host.history.insert(id, HistoryRow(1000L, 0, "Primeira", "Demo", 124.0, 125.0, "8A", 60, 1))
        host.history.insert(id, HistoryRow(2000L, 1, "Segunda", "Demo", 126.0, 126.0, "9A", 90, 2))
        assertEquals(listOf("Segunda", "Primeira"), again.history.rows().map { it.title }, "o mais recente primeiro")
        // Tudo dentro da pasta do app.
        assertTrue(home.walkTopDown().filter { it.isFile }.all { it.path.startsWith(home.path) })
    }

    @Test fun theDjScreenLoadsAndPlaysTheLibraryReadOnly() {
        val usb = syntheticFolder()
        val before = usb.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().contentHashCode() }
        val host = DesktopDjHost(temp("dj-home"))
        var decks = listOf<os.deckbridge.logic.RbTrack?>(null, null)
        // A tela do DJ anima sem parar (ondas, relógio): é renderizada à mão, quadro a quadro, sem esperar "ociosa".
        val scene = androidx.compose.ui.ImageComposeScene(1280, 800, androidx.compose.ui.unit.Density(1f)) {
            DjScreen(host, usb, chooseFolder = { null }, toLibrary = null, post = { it() }, onDecks = { decks = it })
        }
        val t0 = System.nanoTime()
        fun frames(ms: Long) {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                // O estado mudado por esta thread (as ações) só chega à cena com as notificações aplicadas, como a
                // thread da tela faz no programa.
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(System.nanoTime() - t0)
                Thread.sleep(16)
            }
        }
        try {
            frames(1500)
            assertTrue(PerformanceHub.onAction != null, "a tela registrou as ações da controladora")
            assertTrue(NativeEngine.running(), "o motor C++ abriu no computador")
            // A controladora pelo mesmo caminho do celular (PerformanceHub.onAction).
            fun press(kind: PerformanceAction.Kind, slider: Double? = null) {
                PerformanceHub.onAction?.invoke(PerformanceAction(0, kind, slider = slider))
                frames(300)
            }
            // Encoder: abre a lista, desce para "Track", entra; LOAD carrega a primeira no deck 1.
            press(PerformanceAction.Kind.BROWSE_ENTER)
            press(PerformanceAction.Kind.BROWSE_MOVE, 1.0)
            press(PerformanceAction.Kind.BROWSE_ENTER)
            File(out, "desktop-dj-lista.png").writeBytes(scene.render(System.nanoTime() - t0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            press(PerformanceAction.Kind.LOAD)
            frames(1500)
            File(out, "desktop-dj-carga.png").writeBytes(scene.render(System.nanoTime() - t0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            val track = decks[0]
            assertTrue(track != null, "LOAD carregou uma faixa no deck 1")
            // A decodificação e a carga no motor terminam fora da tela.
            frames(1500)
            PerformanceHub.onAction?.invoke(PerformanceAction(0, PerformanceAction.Kind.PLAY))
            frames(300)
            val start = NativeEngine.position(0)
            frames(1500)
            val moved = NativeEngine.position(0) > start + 0.5f
            File(out, "desktop-dj.png").writeBytes(scene.render(System.nanoTime() - t0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(moved, "o deck carregou e andou (posição ${NativeEngine.position(0)} s, motor ${NativeEngine.running()})")
            PerformanceHub.onAction?.invoke(PerformanceAction(0, PerformanceAction.Kind.PLAY))
            frames(200)
        } finally {
            scene.close()
        }
        // Só leitura: nenhum arquivo da biblioteca mudou.
        val after = usb.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().contentHashCode() }
        assertEquals(before, after)
    }
}
