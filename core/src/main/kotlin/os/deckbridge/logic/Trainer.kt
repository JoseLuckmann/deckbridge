package os.deckbridge.logic

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Camada de ensino (P4). Só observa e avalia: nada aqui mexe no mixer, no transporte nem nos efeitos.
 * As faixas de tolerância e as notas são nossas, não vêm de manual nem de aparelho.
 */

/** Foto de um deck no instante da leitura. Knobs de 0 a 1 com o centro em 0,5. */
data class DeckSnapshot(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    val bpm: Double = 0.0,
    val pitch: Double = 1.0,
    val gridOrigin: Double = 0.0,
    val key: String = "",
    val volume: Float = 0f,
    val low: Float = 0.5f,
    val sync: Boolean = false,
) {
    val effectiveBpm: Double get() = bpm * pitch
    val phase: Double get() = beatPhase(positionSec, gridOrigin, bpm)
}

data class MixSnapshot(
    val decks: List<DeckSnapshot> = listOf(DeckSnapshot(), DeckSnapshot()),
    val cross: Float = 0.5f,
    val masterPeak: Float = 0f,
) {
    /** Quanto de cada deck chega ao master (fader e crossfader). */
    fun audible(deck: Int): Float = masterGain(decks[deck].volume.toDouble(), cross.toDouble(), deck == 1).toFloat()
}

/** Diferença de tempo do deck B em relação ao A, em %. */
fun tempoErrorPercent(a: DeckSnapshot, b: DeckSnapshot): Double {
    if (a.effectiveBpm <= 1.0 || b.effectiveBpm <= 1.0) return 0.0
    return (b.effectiveBpm / a.effectiveBpm - 1.0) * 100.0
}

/** Diferença de fase de batida de B em relação a A, em batidas, de −0,5 a +0,5. Positivo: B está adiantado. */
fun phaseErrorBeats(a: DeckSnapshot, b: DeckSnapshot): Double {
    var delta = b.phase - a.phase
    if (delta > 0.5) delta -= 1.0
    if (delta < -0.5) delta += 1.0
    return delta
}

/** Posição de A dentro da frase de 16 compassos contada da grade, em batidas (0 a 64). */
fun phraseBeat(deck: DeckSnapshot): Double {
    if (deck.bpm <= 1.0) return 0.0
    val beat = (deck.positionSec - deck.gridOrigin) * deck.bpm / 60.0
    val wrapped = beat - floor(beat / 64.0) * 64.0
    return wrapped
}

/** Quantas batidas faltam para a próxima fronteira de frase de 16 compassos. */
fun beatsToPhraseEnd(deck: DeckSnapshot): Double = 64.0 - phraseBeat(deck)

enum class TipLevel { INFO, WARN }

data class Tip(val id: String, val text: String, val level: TipLevel)

/**
 * Dicas do modo GUIDED MIX, da mais urgente para a menos. No máximo três. Cada dica diz o que se vê e o
 * gesto que o DJ faria; nenhuma é executada pelo app.
 */
fun guidedTips(s: MixSnapshot): List<Tip> {
    val tips = mutableListOf<Tip>()
    val a = s.decks[0]
    val b = s.decks[1]
    val bothPlaying = a.loaded && b.loaded && a.playing && b.playing
    val audA = s.audible(0)
    val audB = s.audible(1)
    if (s.masterPeak > 0.97f) {
        tips += Tip("clip", "Master perto de 0 dB. Abaixe o TRIM ou o fader do canal mais forte.", TipLevel.WARN)
    }
    if (bothPlaying && audA > 0.05f && audB > 0.05f) {
        val tempo = tempoErrorPercent(a, b)
        if (abs(tempo) > 0.3) {
            val which = if (tempo > 0) "o deck 2 está mais rápido" else "o deck 2 está mais lento"
            tips += Tip("tempo", "Tempos diferentes (%+.1f%%): %s. Ajuste o pitch ou use SYNC.".format(tempo, which), TipLevel.WARN)
        } else {
            val phase = phaseErrorBeats(a, b)
            if (abs(phase) > 0.12) {
                val way = if (phase > 0) "atrase" else "adiante"
                tips += Tip("fase", "Fora da batida (%.2f batida). Com o jog, %s o deck 2.".format(abs(phase), way), TipLevel.WARN)
            }
        }
        if (a.low > 0.35f && b.low > 0.35f) {
            tips += Tip("grave", "Dois graves ao mesmo tempo. Corte o LOW de um dos canais: é a troca de grave.", TipLevel.WARN)
        }
        if (a.key.isNotBlank() && b.key.isNotBlank() && camelotDistance(a.key, b.key) > 2) {
            tips += Tip("tom", "Tons distantes (${a.key} e ${b.key}). Misture rápido, use KEY SHIFT ou escolha uma faixa vizinha.", TipLevel.INFO)
        }
    }
    if (a.loaded && a.playing && b.loaded && !b.playing && audA >= 0.4f) {
        val beats = beatsToPhraseEnd(a)
        if (beats <= 16.0) {
            tips += Tip("frase", "Faltam %d batidas para a próxima frase de 16 compassos (grade). Dê play no deck 2 na fronteira.".format(beats.toInt()), TipLevel.INFO)
        }
    }
    return tips.sortedByDescending { it.level.ordinal }.take(3)
}

// ---------------------------------------------------------------------------------------------
// Exercícios. Cada um é uma máquina de estados alimentada a cada 250 ms por `stepTrainer`.

enum class Drill { BEATMATCH, BASS_SWAP, PHRASE_ENTRY }

val DRILL_TITLES = mapOf(
    Drill.BEATMATCH to "BEATMATCH",
    Drill.BASS_SWAP to "TROCA DE GRAVE",
    Drill.PHRASE_ENTRY to "ENTRADA NA FRASE",
)

val DRILL_HELP = mapOf(
    Drill.BEATMATCH to "Toque os dois decks com SYNC desligado. Iguale o pitch do deck 2 ao do deck 1 e alinhe as batidas com o jog. A nota sai quando o tempo (±0,15%) e a fase (±0,08 batida) ficam certos por 8 s.",
    Drill.BASS_SWAP to "Deck 1 no ar, deck 2 pronto. Abra o deck 2 e troque o grave: corte o LOW de um canal antes de subir o do outro. Quanto menos tempo com os dois graves abertos, maior a nota. Termina quando o deck 1 sai.",
    Drill.PHRASE_ENTRY to "Deck 1 no ar. Abra o deck 2 exatamente na fronteira de uma frase de 16 compassos (contada da grade). A nota cai com a distância, em batidas, até a fronteira.",
)

data class DrillResult(val score: Int, val summary: String)

data class BeatmatchState(
    val elapsedMs: Long = 0,
    val stableMs: Long = 0,
    val tempoErrPct: Double = 0.0,
    val phaseErrBeats: Double = 0.0,
    val syncBlocked: Boolean = false,
    val result: DrillResult? = null,
)

data class BassSwapState(
    val active: Boolean = false,
    val overlapMs: Long = 0,
    val result: DrillResult? = null,
)

data class PhraseEntryState(
    val wasClosed: Boolean = false,
    val errBeats: Double = 0.0,
    val result: DrillResult? = null,
)

data class TrainerState(
    val drill: Drill? = null,
    val beatmatch: BeatmatchState = BeatmatchState(),
    val bass: BassSwapState = BassSwapState(),
    val phrase: PhraseEntryState = PhraseEntryState(),
) {
    val result: DrillResult?
        get() = when (drill) {
            Drill.BEATMATCH -> beatmatch.result
            Drill.BASS_SWAP -> bass.result
            Drill.PHRASE_ENTRY -> phrase.result
            null -> null
        }
}

const val BEATMATCH_TEMPO_TOLERANCE_PCT = 0.15
const val BEATMATCH_PHASE_TOLERANCE_BEATS = 0.08
const val BEATMATCH_HOLD_MS = 8000L

fun startDrill(drill: Drill): TrainerState = TrainerState(drill = drill)

fun stepTrainer(state: TrainerState, s: MixSnapshot, dtMs: Long): TrainerState = when (state.drill) {
    Drill.BEATMATCH -> state.copy(beatmatch = stepBeatmatch(state.beatmatch, s, dtMs))
    Drill.BASS_SWAP -> state.copy(bass = stepBassSwap(state.bass, s, dtMs))
    Drill.PHRASE_ENTRY -> state.copy(phrase = stepPhraseEntry(state.phrase, s))
    null -> state
}

fun stepBeatmatch(d: BeatmatchState, s: MixSnapshot, dtMs: Long): BeatmatchState {
    if (d.result != null) return d
    val a = s.decks[0]
    val b = s.decks[1]
    if (!(a.loaded && b.loaded && a.playing && b.playing)) return d.copy(stableMs = 0, syncBlocked = false)
    if (a.sync || b.sync) return d.copy(stableMs = 0, syncBlocked = true)
    val elapsed = d.elapsedMs + dtMs
    val tempo = tempoErrorPercent(a, b)
    val phase = phaseErrorBeats(a, b)
    val ok = abs(tempo) <= BEATMATCH_TEMPO_TOLERANCE_PCT && abs(phase) <= BEATMATCH_PHASE_TOLERANCE_BEATS
    val stable = if (ok) d.stableMs + dtMs else 0L
    val next = d.copy(elapsedMs = elapsed, stableMs = stable, tempoErrPct = tempo, phaseErrBeats = phase, syncBlocked = false)
    if (stable < BEATMATCH_HOLD_MS) return next
    val seconds = elapsed / 1000.0
    val score = (100.0 - max(0.0, seconds - 30.0)).toInt().coerceIn(40, 100)
    return next.copy(result = DrillResult(score, "Beatmatch segurado por 8 s em %.0f s.".format(seconds)))
}

fun stepBassSwap(d: BassSwapState, s: MixSnapshot, dtMs: Long): BassSwapState {
    if (d.result != null) return d
    val a = s.decks[0]
    val b = s.decks[1]
    if (!(a.loaded && b.loaded && a.playing && b.playing)) return d
    val audA = s.audible(0)
    val audB = s.audible(1)
    if (!d.active) {
        // Começa quando o deck 2 sobe com o deck 1 ainda forte.
        return if (audB > 0.15f && audA >= 0.4f) d.copy(active = true) else d
    }
    val clash = audA > 0.2f && audB > 0.2f && a.low > 0.35f && b.low > 0.35f
    val overlap = d.overlapMs + if (clash) dtMs else 0L
    if (audA >= 0.08f) return d.copy(overlapMs = overlap)
    // O deck 1 saiu: fecha o exercício. Até 0,5 s de sobreposição vale 100; cada 75 ms a mais tira um ponto.
    val score = (100.0 - max(0.0, (overlap - 500) / 75.0)).toInt().coerceIn(0, 100)
    return d.copy(overlapMs = overlap, result = DrillResult(score, "Graves abertos juntos por %.1f s.".format(overlap / 1000.0)))
}

fun stepPhraseEntry(d: PhraseEntryState, s: MixSnapshot): PhraseEntryState {
    if (d.result != null) return d
    val a = s.decks[0]
    if (!(a.loaded && a.playing)) return d
    val audB = s.audible(1)
    // O exercício só vale se o deck 2 começa fechado; a abertura é o momento medido.
    if (audB < 0.05f) return d.copy(wasClosed = true)
    if (!d.wasClosed || audB < 0.25f) return d
    val pb = phraseBeat(a)
    val err = if (pb > 32.0) pb - 64.0 else pb
    val score = (100.0 - max(0.0, abs(err) - 0.5) * 25.0).toInt().coerceIn(0, 100)
    val when_ = if (abs(err) < 0.25) "na fronteira" else if (err < 0) "%.1f batida(s) antes".format(-err) else "%.1f batida(s) depois".format(err)
    return d.copy(errBeats = err, result = DrillResult(score, "Deck 2 abriu $when_ da frase."))
}
