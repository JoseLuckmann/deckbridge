package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdjLedsTest {
    private fun asSet(messages: List<ByteArray>) = messages.map { Triple(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF, it[2].toInt() and 0xFF) }.toSet()

    @Test
    fun usesTheBytesOfTheMixxxOutputs() {
        val state = LedState(
            decks = listOf(
                DeckLed(loaded = true, playing = true, sync = true, pfl = true, hotCues = setOf(0, 2), beatLoop = 4, peak = 0.5f),
                DeckLed(loaded = true),
            ),
            beatFxOn = true,
        )
        val sent = asSet(LedDiff().messages(state))
        assertTrue(Triple(0x90, 0x0B, 0x7F) in sent)  // play deck 1
        assertTrue(Triple(0x90, 0x0C, 0x00) in sent)  // cue apagado enquanto toca
        assertTrue(Triple(0x91, 0x0C, 0x7F) in sent)  // deck 2 parado: cue aceso
        assertTrue(Triple(0x90, 0x58, 0x7F) in sent)  // sync
        assertTrue(Triple(0x90, 0x54, 0x7F) in sent)  // CUE de fones
        assertTrue(Triple(0x97, 0x00, 0x7F) in sent && Triple(0x97, 0x02, 0x7F) in sent && Triple(0x97, 0x01, 0x00) in sent)  // hot cues
        assertTrue(Triple(0x99, 0x00, 0x00) in sent)  // deck 2 usa 0x99
        assertTrue(Triple(0x97, 0x64, 0x7F) in sent)  // beat loop de 4 batidas
        assertTrue(Triple(0x9F, 0x00, 0x7F) in sent && Triple(0x9F, 0x01, 0x7F) in sent)  // faixa carregada
        assertTrue(Triple(0xB0, 0x02, 75) in sent)    // VU: 0,5 x 150
        assertTrue(Triple(0x94, 0x47, 0x7F) in sent)  // Beat FX
    }

    @Test
    fun onlyChangesAreSentAndResendRepeatsEverything() {
        val diff = LedDiff()
        val state = LedState(decks = listOf(DeckLed(loaded = true), DeckLed()))
        assertTrue(diff.messages(state).isNotEmpty())
        assertTrue(diff.messages(state).isEmpty())
        val changed = diff.messages(state.copy(decks = listOf(DeckLed(loaded = true, playing = true), DeckLed())))
        assertEquals(setOf(Triple(0x90, 0x0B, 0x7F), Triple(0x90, 0x0C, 0x00)), asSet(changed))
        diff.resend()
        assertTrue(diff.messages(state).size > 50)
    }

    @Test
    fun beatLoopSizesMatchThePads() {
        assertEquals(4, beatLoopIndex(4.0))
        assertEquals(0, beatLoopIndex(0.25))
        assertEquals(7, beatLoopIndex(32.0))
        assertEquals(-1, beatLoopIndex(3.0))
        assertEquals(-1, beatLoopIndex(0.0))
    }
}
