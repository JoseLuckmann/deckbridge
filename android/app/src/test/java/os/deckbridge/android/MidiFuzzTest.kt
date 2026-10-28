package os.deckbridge.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import os.deckbridge.logic.ControlHit
import os.deckbridge.logic.MidiAssembler
import os.deckbridge.logic.MidiMap
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.TempoAssembler
import os.deckbridge.logic.interpretPerformance
import os.deckbridge.logic.loadRekordbox

/**
 * Fuzz do caminho inteiro da controladora no app: bytes MIDI → `MidiAssembler` → mapa oficial → `interpretPerformance`
 * → `onPerformance` do DeckPage de verdade, com a tela composta. Mistura mensagens válidas do mapa (com valores
 * quaisquer, inclusive SHIFT, prato rápido e pads), bytes aleatórios, running status, SysEx e clock no meio.
 * O app não pode lançar exceção nem travar a composição.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1340dp-h800dp-mdpi")
class MidiFuzzTest {
    private val map = MidiMap.loadResource()

    /** Todos os controles do mapa, achados por busca: status 0x80..0xBF, nota 0..127. */
    private val mapped: List<ControlHit> = (0x80..0xBF).flatMap { status -> (0..127).map { map.lookup(status, it) } }.filter { it.mapped }

    private fun message(rng: Random): ByteArray {
        return when (rng.nextInt(100)) {
            in 0..69 -> {
                val hit = mapped[rng.nextInt(mapped.size)]
                val value = when (rng.nextInt(5)) {
                    0 -> 0
                    1 -> 127
                    2 -> 64 + rng.nextInt(-8, 9)
                    else -> rng.nextInt(128)
                }
                byteArrayOf(hit.status.toByte(), hit.midino.toByte(), value.toByte())
            }
            in 70..79 -> ByteArray(rng.nextInt(1, 6)) { rng.nextInt(256).toByte() }
            in 80..87 -> ByteArray(rng.nextInt(1, 5)) { rng.nextInt(128).toByte() } // running status
            in 88..92 -> byteArrayOf(0xF0.toByte(), 0x00, 0x40, 0x05, rng.nextInt(128).toByte(), 0xF7.toByte())
            in 93..96 -> byteArrayOf(0xF8.toByte(), 0xFA.toByte(), 0xFE.toByte())
            else -> byteArrayOf(0xF2.toByte(), rng.nextInt(128).toByte(), rng.nextInt(128).toByte())
        }
    }

    @Test
    fun randomControllerTrafficNeverBreaksTheDeckPage() {
        assertTrue("mapa com controles (${mapped.size})", mapped.size > 150)
        val catalog = loadRekordbox(SyntheticRekordbox.volume())
        val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    var tracks by remember { mutableStateOf(listOf<RbTrack?>(catalog.tracks.getOrNull(0), catalog.tracks.getOrNull(1))) }
                    DeckPage(
                        catalog = catalog,
                        volume = SyntheticRekordbox.volume(),
                        deviceId = 0,
                        autoplay = false,
                        loaded = tracks,
                        onLoaded = { deck, track -> tracks = tracks.toMutableList().also { it[deck] = track } },
                        jvmBanner = "fuzz",
                        host = AndroidDjHost(activity),
                    )
                }
            },
        )
        ShadowLooper.idleMainLooper()
        val hub = PerformanceHub.onAction
        assertNotNull("DeckPage registrou o receptor de ações", hub)
        val tempo = TempoAssembler()
        PerformanceHub.tempo = tempo
        val assembler = MidiAssembler()
        var actions = 0
        for (seed in 1..3) {
            val rng = Random(seed)
            repeat(4000) { i ->
                for (msg in assembler.push(message(rng))) {
                    val hit = map.lookup(msg.status, msg.data1)
                    interpretPerformance(hit, msg.data2, tempo)?.let {
                        PerformanceHub.onAction?.invoke(it)
                        actions += 1
                    }
                }
                // Recompõe a tela de vez em quando, como o looper faz entre rajadas de MIDI.
                if (i % 50 == 0) ShadowLooper.idleMainLooper()
            }
        }
        ShadowLooper.idleMainLooper()
        assertTrue("ações aplicadas ($actions)", actions > 5000)
    }
    @Test
    fun assemblerIsIndependentOfPacketSplitsAndRealtimeBytes() {
        val rng = Random(7)
        repeat(200) {
            val sent = List(rng.nextInt(1, 60)) { mapped[rng.nextInt(mapped.size)] to rng.nextInt(128) }
            val bytes = ArrayList<Byte>()
            for ((hit, value) in sent) {
                bytes += hit.status.toByte()
                bytes += hit.midino.toByte()
                if (rng.nextInt(4) == 0) bytes += 0xF8.toByte() // clock no meio da mensagem (permitido pelo MIDI)
                bytes += value.toByte()
                if (rng.nextInt(6) == 0) bytes.addAll(listOf(0xF0, 0x7E, 0x01, 0xF7).map { it.toByte() })
            }
            val assembler = MidiAssembler()
            val got = ArrayList<Triple<Int, Int, Int>>()
            var at = 0
            while (at < bytes.size) {
                val n = rng.nextInt(1, 8).coerceAtMost(bytes.size - at)
                assembler.push(bytes.subList(at, at + n).toByteArray()).forEach { got += Triple(it.status, it.data1, it.data2) }
                at += n
            }
            val want = sent.map { (hit, value) -> Triple(hit.status, hit.midino, value) }
            org.junit.Assert.assertEquals(want, got)
        }
        // Lixo puro: nunca sai status fora de 0x80..0xEF nem dado acima de 127.
        val assembler = MidiAssembler()
        repeat(20000) {
            for (m in assembler.push(ByteArray(rng.nextInt(1, 9)) { rng.nextInt(256).toByte() })) {
                assertTrue("status ${m.status}", m.status in 0x80..0xEF)
                assertTrue(m.data1 in 0..127 && m.data2 in 0..127)
            }
        }
    }
}
