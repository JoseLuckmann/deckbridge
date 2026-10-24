package os.deckbridge.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import os.deckbridge.android.PlayerScreen
import os.deckbridge.android.ScreenshotActivity
import os.deckbridge.android.TextInk
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.MixerState
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.meterFraction

private data class SetFrame(
    val faces: List<DeckFace>,
    val fx: BeatFxState,
    val rmx: RmxState,
    val mixer: MixerState,
    val master: Int,
    val overlay: String,
    val caption: String,
    val tracks: List<os.deckbridge.logic.RbTrack?>,
    val browse: os.deckbridge.logic.BrowsePlace?,
    val colorFx: Int,
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-mdpi")
class SetFramesTest {
    @Test
    fun renderSetFrames() {
        assumeTrue(SessionAssets.enabled() && File(SessionAssets.dir, "src/t1.wav").isFile && System.getenv("SET") == "1")
        val fps = (System.getenv("SESSION_FPS") ?: "10").toInt()
        val outDir = File(SessionAssets.dir, "frames").apply { if (System.getenv("SET_ONLY_DDJ") != "1") deleteRecursively(); mkdirs() }
        val catalog = SessionAssets.catalog()
        // Nomes das faixas de teste na tela (o código usa os nomes de arquivo t1..t6).
        val names = mapOf(
            "t1" to "AUD-20260901-WA0065",
            "t2" to "Franky Rizardo - Shinjuku (Extended Mix)",
            "t3" to "Vessbroz - Fuck It How I Want It (Extended Mix)",
            "t4" to "Killingzoo - Imagination (Tikaro, J. Louis, Ferran Remix)",
            "t5" to "Wiz Khalifa - See You Again (Uplink, Max Fail Remix)",
            "t6" to "Michael Mind Project - How Does It Feel (Club Mix)",
        )
        val shown = catalog.copy(tracks = catalog.tracks.map { it.copy(title = names[it.title] ?: it.title) })
        // SET_LEVELS aponta os picos do áudio renderizado deste roteiro (o padrão é o do set de seis faixas).
        val levels = loadSetLevels(System.getenv("SET_LEVELS")?.let(::File) ?: File(SessionAssets.dir, "mix.wav.levels.tsv"))
        // Segunda tela, em janela própria: a DDJ-400 simulada (controles pelo MIDI da mão, LEDs pelo que o app
        // mandaria). Não entra no layout do app; o vídeo empilha as duas telas no ffmpeg. Criada antes da janela do
        // app, que fica em primeiro plano (a captura do app passa pelo Espresso); esta é desenhada direto num bitmap.
        val ddjDir = File(SessionAssets.dir, "frames-ddj").apply { deleteRecursively(); mkdirs() }
        val ddjActivity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        val ddjState = mutableStateOf<DdjFrame?>(null)
        val ddjView = ComposeView(ddjActivity).apply {
            setContent {
                val frame by ddjState
                Box(Modifier.width(1280.dp).height(720.dp).background(Color(0xFF0B0C0E))) {
                    frame?.let { DdjPanel(it) }
                }
            }
        }
        ddjActivity.setContentView(ddjView, android.view.ViewGroup.LayoutParams(1280, 720))
        val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        val state = mutableStateOf<SetFrame?>(null)
        state.value = SetFrame(listOf(DeckFace(), DeckFace()), BeatFxState(), RmxState(), MixerState(), 0, "", "", listOf(null, null), null, 5)
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    val frame by state
                    val f = frame ?: return@setContent
                    Column(Modifier.fillMaxSize().background(Color.Black)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PlayerScreen(
                                catalog = shown,
                                tracks = f.tracks,
                                browse = f.browse,
                                clock = "21:14",
                                faces = f.faces,
                                master = f.master,
                                quantize = true,
                                zoom = listOf(WaveZoom.BARS_8, WaveZoom.BARS_8),
                                zoomLink = true,
                                fx = f.fx,
                                jvmBanner = "Renderização em JVM. Não é o tablet nem a DDJ-400.",
                                usbLabel = "pasta de teste",
                                overlay = f.overlay,
                                nav = if (f.overlay == "mixer") "MIXER" else "",
                                rmx = f.rmx,
                                settings = PlayerSettings(waveColor = "RGB", followUsb = false, colorFx = f.colorFx),
                                mixer = f.mixer,
                                artworkOf = { null },
                            )
                        }
                        // Faixa fixa da legenda: o que a mão faz na DDJ-400 ou na tela.
                        Box(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF101820)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                            TextInk(f.caption, color = Color(0xFFF4F7FB), fontSize = 18.sp, maxLines = 1)
                        }
                    }
                }
            },
        )
        val started = System.currentTimeMillis()
        var held = floatArrayOf(0f, 0f, 0f, 0f)
        // SET_FROM e SET_TO (s) limitam os quadros gravados; a simulação roda igual até SET_TO.
        val from = (System.getenv("SET_FROM") ?: "0").toDouble()
        val to = (System.getenv("SET_TO") ?: "1e9").toDouble()
        runSet(catalog, fps, to) { model, frame ->
            if (frame.toDouble() / fps < from) return@runSet
            val window = levels.windowMax(frame.toDouble() / fps - 1.0 / fps, frame.toDouble() / fps)
            held = FloatArray(4) { index -> maxOf(meterFraction(window[index]), held[index] * 0.78f) }
            state.value = SetFrame(
                faces = model.faces,
                fx = model.fx,
                rmx = model.rmx,
                mixer = model.mixer().copy(levels = held.toList()),
                master = model.master,
                overlay = model.overlay,
                caption = model.captionNow(),
                tracks = model.deckTracks().map { t -> t?.let { x -> shown.tracks.first { it.id == x.id } } },
                browse = model.browse,
                colorFx = model.colorFx,
            )
            repeat(3) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
            // SET_ONLY_DDJ=1 refaz só a tela da DDJ (os quadros do app ficam como estão).
            if (System.getenv("SET_ONLY_DDJ") != "1") {
                activity.window.decorView.captureRoboImage(File(outDir, "frame%05d.png".format(frame)).absolutePath)
            }
            ddjState.value = DdjFrame(model.surface, os.deckbridge.logic.ledTargets(model.leds(listOf(held[0], held[1]))), model.t)
            repeat(2) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
            val bitmap = android.graphics.Bitmap.createBitmap(ddjView.width, ddjView.height, android.graphics.Bitmap.Config.ARGB_8888)
            ddjView.draw(android.graphics.Canvas(bitmap))
            File(ddjDir, "ddj%05d.png".format(frame)).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (frame % 200 == 0) println("frame $frame  ${(System.currentTimeMillis() - started) / 1000} s")
        }
        println("frames em ${(System.currentTimeMillis() - started) / 1000} s")
    }
}

private class SetLevels(val rows: List<FloatArray>) {
    fun windowMax(from: Double, to: Double): FloatArray {
        val out = FloatArray(4)
        for (row in rows) {
            if (row[0] > to + 1e-6) break
            if (row[0] > from) for (k in 0..3) out[k] = maxOf(out[k], row[k + 1])
        }
        return out
    }
}

private fun loadSetLevels(file: File): SetLevels {
    if (!file.isFile) return SetLevels(emptyList())
    return SetLevels(file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 5) null else FloatArray(5) { parts[it].toFloat() }
    })
}
