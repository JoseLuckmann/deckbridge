package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerTest {
    private fun deck(
        pos: Double = 10.0, bpm: Double = 120.0, pitch: Double = 1.0, vol: Float = 1f, low: Float = 0.5f,
        playing: Boolean = true, key: String = "8A", sync: Boolean = false,
    ) = DeckSnapshot(loaded = true, playing = playing, positionSec = pos, bpm = bpm, pitch = pitch, key = key, volume = vol, low = low, sync = sync)

    private fun mix(a: DeckSnapshot, b: DeckSnapshot, cross: Float = 0.5f, peak: Float = 0f) = MixSnapshot(listOf(a, b), cross, peak)

    @Test
    fun errorsAreSignedFromDeckTwo() {
        // 120 BPM: batida a cada 0,5 s. B em 10,1 está 0,2 batida adiantado em relação a A em 10,0.
        val s = mix(deck(pos = 10.0), deck(pos = 10.1))
        assertEquals(0.2, phaseErrorBeats(s.decks[0], s.decks[1]), 1e-9)
        assertEquals(1.0, tempoErrorPercent(deck(), deck(pitch = 1.01)), 1e-9)
        // Perto da volta da batida o caminho curto é negativo.
        assertEquals(-0.1, phaseErrorBeats(deck(pos = 10.0), deck(pos = 9.95)), 1e-9)
    }

    @Test
    fun tipsNameTheGestureAndStaySilentWhenAllIsRight() {
        val clean = mix(deck(pos = 10.0, low = 0.0f), deck(pos = 10.0, low = 0.5f, key = "9A"))
        assertTrue(guidedTips(clean).isEmpty())
        val slow = mix(deck(), deck(pitch = 0.97))
        assertEquals("tempo", guidedTips(slow).first().id)
        val late = mix(deck(pos = 10.0), deck(pos = 10.2, low = 0.0f))
        assertEquals("fase", guidedTips(late).first().id)
        val bass = mix(deck(), deck())
        assertTrue(guidedTips(bass).any { it.id == "grave" })
        val far = mix(deck(low = 0f), deck(key = "2B", low = 0.5f))
        assertTrue(guidedTips(far).any { it.id == "tom" })
        val hot = mix(deck(), deck(vol = 0f), peak = 0.99f)
        assertEquals("clip", guidedTips(hot).first().id)
        assertTrue(guidedTips(mix(deck(), deck(), peak = 0.99f)).size <= 3)
    }

    @Test
    fun phraseTipCountsBeatsToTheSixteenBarLine() {
        // 120 BPM: 64 batidas = 32 s. Em 20 s (40 batidas) faltam 24; em 28 s (56 batidas) faltam 8.
        val early = mix(deck(pos = 20.0), deck(playing = false, vol = 0f))
        assertTrue(guidedTips(early).none { it.id == "frase" })
        val near = mix(deck(pos = 28.0), deck(playing = false, vol = 0f))
        assertEquals("frase", guidedTips(near).single().id)
        assertEquals(8.0, beatsToPhraseEnd(deck(pos = 28.0)), 1e-9)
    }

    @Test
    fun beatmatchNeedsEightStableSecondsAndRefusesSync() {
        var state = BeatmatchState()
        val good = mix(deck(pos = 10.0), deck(pos = 10.0, pitch = 1.0005))
        repeat(31) { state = stepBeatmatch(state, good, 250) }
        assertNull(state.result)
        state = stepBeatmatch(state, good, 250)
        assertNotNull(state.result)
        assertEquals(100, state.result!!.score)
        // Um deslize de fase zera a contagem.
        var slip = BeatmatchState()
        repeat(20) { slip = stepBeatmatch(slip, good, 250) }
        slip = stepBeatmatch(slip, mix(deck(pos = 10.0), deck(pos = 10.2)), 250)
        assertEquals(0L, slip.stableMs)
        // Com SYNC ligado o exercício não conta.
        val synced = stepBeatmatch(BeatmatchState(), mix(deck(), deck(sync = true)), 250)
        assertTrue(synced.syncBlocked)
        assertEquals(0L, synced.stableMs)
        // Demorar tira nota: 70 s dão 60.
        val slow = BeatmatchState(elapsedMs = 69_750, stableMs = 7_750)
        assertEquals(60, stepBeatmatch(slow, good, 250).result!!.score)
    }

    @Test
    fun bassSwapScoresTheTimeBothLowsAreOpen() {
        fun run(overlapSteps: Int): DrillResult {
            var state = BassSwapState()
            // B sobe com A forte.
            state = stepBassSwap(state, mix(deck(vol = 1f), deck(vol = 0.5f), cross = 0.5f), 250)
            assertTrue(state.active)
            repeat(overlapSteps) { state = stepBassSwap(state, mix(deck(vol = 1f, low = 0.5f), deck(vol = 1f, low = 0.5f)), 250) }
            // A sai pelo crossfader.
            state = stepBassSwap(state, mix(deck(vol = 1f), deck(vol = 1f), cross = 1f), 250)
            return state.result!!
        }
        assertEquals(100, run(0).score)
        assertEquals(100, run(2).score)
        val long = run(16)
        assertTrue(long.score < 70)
        assertTrue(run(40).score < long.score)
    }

    @Test
    fun phraseEntryMeasuresTheDistanceToTheLine() {
        fun open(posA: Double): DrillResult {
            var state = PhraseEntryState()
            val closed = mix(deck(pos = posA), deck(vol = 0f))
            state = stepPhraseEntry(state, closed)
            assertTrue(state.wasClosed)
            state = stepPhraseEntry(state, mix(deck(pos = posA), deck(vol = 1f)))
            return state.result!!
        }
        // 120 BPM: a fronteira de 64 batidas está em 32 s.
        assertEquals(100, open(32.0).score)
        assertEquals(100, open(31.8).score)
        val twoBeatsEarly = open(31.0)
        assertEquals(62, twoBeatsEarly.score)
        assertTrue(open(25.0).score < 20)
        // Começar com o deck 2 já aberto não vale.
        val skipped = stepPhraseEntry(PhraseEntryState(), mix(deck(pos = 32.0), deck(vol = 1f)))
        assertNull(skipped.result)
    }
}
