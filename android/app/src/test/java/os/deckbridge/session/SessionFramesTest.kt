package os.deckbridge.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

private data class Frame(
    val faces: List<DeckFace>,
    val fx: BeatFxState,
    val rmx: RmxState,
    val mixer: MixerState,
    val master: Int,
    val overlay: String,
    val caption: String,
    val tracks: List<os.deckbridge.logic.RbTrack?>,
    val browse: os.deckbridge.logic.BrowsePlace?,
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-mdpi")
class SessionFramesTest {
    @Test
    fun renderFrames() {
        assumeTrue(SessionAssets.enabled())
        val fps = (System.getenv("SESSION_FPS") ?: "10").toInt()
        val maxSeconds = (System.getenv("SESSION_MAX_SECONDS") ?: SessionScript.END.toString()).toDouble()
        val outDir = File(SessionAssets.dir, "frames").apply { deleteRecursively(); mkdirs() }
        val catalog = SessionAssets.catalog()
        val levels = loadLevels(File(SessionAssets.dir, "mix.wav.levels.tsv"))
        val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        val state = mutableStateOf<Frame?>(null)
        val first = runSessionFirstFrame(catalog)
        state.value = first
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    val frame by state
                    val f = frame ?: return@setContent
                    Column(Modifier.fillMaxSize().background(Color.Black)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PlayerScreen(
                                catalog = catalog,
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
                                settings = PlayerSettings(waveColor = "RGB", followUsb = false),
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
        runSession(catalog, fps, maxSeconds) { model, frame ->
            val window = levels.windowMax(frame.toDouble() / fps - 1.0 / fps, frame.toDouble() / fps)
            held = FloatArray(4) { index -> maxOf(meterFraction(window[index]), held[index] * 0.78f) }
            state.value = Frame(
                faces = model.faces,
                fx = model.fx,
                rmx = model.rmx,
                mixer = model.mixer().copy(levels = held.toList()),
                master = model.master,
                overlay = model.overlay,
                caption = model.captionNow(),
                tracks = model.deckTracks(),
                browse = model.browse,
            )
            repeat(3) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
            activity.window.decorView.captureRoboImage(File(outDir, "frame%05d.png".format(frame)).absolutePath)
            if (frame % 50 == 0) println("frame $frame  ${(System.currentTimeMillis() - started) / 1000} s")
        }
        println("frames em ${(System.currentTimeMillis() - started) / 1000} s")
    }

    private fun runSessionFirstFrame(catalog: os.deckbridge.logic.RbCatalog): Frame {
        val model = SessionModel(catalog, doubleArrayOf(-222.0, -123.0))
        return Frame(model.faces, model.fx, model.rmx, model.mixer(), model.master, "", "", model.deckTracks(), null)
    }
}

private class Levels(val rows: List<FloatArray>) {
    fun windowMax(from: Double, to: Double): FloatArray {
        val out = FloatArray(4)
        for (row in rows) {
            if (row[0] > to + 1e-6) break
            if (row[0] > from) for (k in 0..3) out[k] = maxOf(out[k], row[k + 1])
        }
        return out
    }
}

private fun loadLevels(file: File): Levels {
    if (!file.isFile) return Levels(emptyList())
    return Levels(file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 5) null else FloatArray(5) { parts[it].toFloat() }
    })
}
