package os.deckbridge.logic

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetores de referência dos LEDs para a porta C++ (`pi/control/leds.h`): estados aleatórios em sequência (com
 * reconexões no meio) e os bytes que o LedDiff do app manda. Sem DECKBRIDGE_WRITE_GOLDEN=1, confere o arquivo.
 */
class LedGoldenTest {
    private val file = File("../../pi/control/testdata/leds.txt")

    private fun generate(): String {
        val rng = Random(401)
        val diff = LedDiff()
        val out = StringBuilder("# Gerado por LedGoldenTest (Kotlin). S = estado (por deck: carregado tocando sync pfl hotcues loop pico‰; depois beatfx pads); R = reconectou; O = bytes.\n")
        repeat(800) {
            if (rng.nextInt(40) == 0) {
                diff.resend()
                out.append("R\n")
            }
            val decks = (0..1).map {
                DeckLed(
                    loaded = rng.nextInt(4) != 0,
                    playing = rng.nextBoolean(),
                    sync = rng.nextInt(3) == 0,
                    pfl = rng.nextInt(3) == 0,
                    hotCues = (0..7).filter { rng.nextInt(3) == 0 }.toSet(),
                    beatLoop = rng.nextInt(-1, 8),
                    peak = rng.nextInt(0, 1200) / 1000f,
                )
            }
            val state = LedState(decks, beatFxOn = rng.nextBoolean(), samplerPads = (0..7).filter { rng.nextInt(4) == 0 }.toSet())
            out.append("S")
            for (d in decks) {
                val mask = d.hotCues.fold(0) { acc, i -> acc or (1 shl i) }
                out.append(" ${if (d.loaded) 1 else 0} ${if (d.playing) 1 else 0} ${if (d.sync) 1 else 0} ${if (d.pfl) 1 else 0} $mask ${d.beatLoop} ${(d.peak * 1000).let { kotlin.math.round(it).toInt() }}")
            }
            out.append(" ${if (state.beatFxOn) 1 else 0} ${state.samplerPads.fold(0) { acc, i -> acc or (1 shl i) }}\n")
            out.append("O")
            diff.messages(state).forEach { msg -> msg.forEach { out.append(" %02X".format(it.toInt() and 0xFF)) } }
            out.append('\n')
        }
        return out.toString()
    }

    @Test
    fun ledVectorsMatchTheKotlinLogic() {
        val text = generate()
        assertTrue(text.lines().count { it.startsWith("O ") } > 700)
        if (System.getenv("DECKBRIDGE_WRITE_GOLDEN") == "1") {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        assertTrue("falta ${file.path}: rode com DECKBRIDGE_WRITE_GOLDEN=1", file.exists())
        assertEquals("pi/control/testdata/leds.txt está velho: regrave com DECKBRIDGE_WRITE_GOLDEN=1", file.readText(), text)
    }
}
