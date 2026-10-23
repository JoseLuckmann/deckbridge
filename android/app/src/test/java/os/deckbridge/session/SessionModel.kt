package os.deckbridge.session

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.GridEdit
import os.deckbridge.logic.MidiMap
import os.deckbridge.logic.MixerState
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.TempoAssembler
import os.deckbridge.logic.applyGrid
import os.deckbridge.logic.beatFxAudioKind
import os.deckbridge.logic.beatFxBeats
import os.deckbridge.logic.beatJumpPadBeats
import os.deckbridge.logic.faderFromPitch
import os.deckbridge.logic.gridBpm
import os.deckbridge.logic.interpretPerformance
import os.deckbridge.logic.jumpSeconds
import os.deckbridge.logic.reduceBeatFx
import os.deckbridge.logic.reduceRmxPad
import os.deckbridge.logic.rmxPayload
import os.deckbridge.logic.stepJumpScale
import os.deckbridge.logic.syncPhaseShiftSeconds
import os.deckbridge.logic.syncPitch

/**
 * Modelo sem tela do que o DeckPage faz com uma mensagem MIDI da DDJ-400, só para o que a sessão usa.
 * A mesma `interpretPerformance` do app traduz os bytes; as ações são aplicadas como em `onPerformance`.
 * Cada efeito sobre o motor vira uma linha do ops.tsv, que o `ddj_session` reexecuta no áudio.
 */
class SessionModel(catalog: RbCatalog, private val gridShiftMs: DoubleArray) {
    private val map = MidiMap.loadResource()
    private val tempo = TempoAssembler()
    val tracks: List<RbTrack> = catalog.tracks.take(2)
    private val beats = tracks.map { catalog.analyses[it.id]?.beats.orEmpty() }

    var t = 0.0
        private set
    val pos = doubleArrayOf(0.0, 0.0)
    var faces = listOf(
        DeckFace(playing = false, onAir = false, gridShiftMs = gridShiftMs[0]),
        DeckFace(playing = false, onAir = false, gridShiftMs = gridShiftMs[1]),
    )
        private set
    private val volume = floatArrayOf(0f, 0f)
    var cross = 0.5f
        private set
    private val eq = arrayOf(floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
    private val trimKnob = floatArrayOf(0.5f, 0.5f)
    var fx = BeatFxState()
        private set
    var rmx = RmxState()
        private set
    var master = 0
        private set
    var quantize = true
        private set
    private val jumpScale = doubleArrayOf(1.0, 1.0)
    private var holdStart = -1.0
    private var holdFrom = 0f
    private var lastPayload = ""
    private var lastBeatLine = ""

    val ops = ArrayList<String>()
    var caption = ""
        private set
    private var captionUntil = 0.0

    init {
        for (deck in 0..1) {
            val grid = gridOf(deck)
            if (grid != null) ops += "grid\t$deck\t${grid.first}\t${grid.second}"
        }
    }

    fun load(deck: Int, seconds: Double) {
        pos[deck] = seconds
        ops += "%.4f\tpos\t%d\t%.6f".format(t, deck, seconds)
        syncFace(deck)
    }

    /** Aviso na tela quando passa de um segundo sem novo comando. */
    fun captionNow(): String = if (t <= captionUntil) caption else ""

    fun say(text: String, forSeconds: Double = 2.4) {
        caption = text
        captionUntil = t + forSeconds
    }

    fun mixer(): MixerState = MixerState(
        eq = eq.map { it.toList() },
        trim = trimKnob.toList(),
        volume = volume.toList(),
        cross = cross,
        headMix = 0f,
        headLevel = 0.75f,
        filter = listOf(faces[0].filter, faces[1].filter),
        colorFx = 5,
    )

    /** Passa o tempo: posições, rampa do RMX e o estado do rail de efeitos. */
    fun advance(dt: Double) {
        t += dt
        for (deck in 0..1) {
            if (faces[deck].playing) pos[deck] += dt * faces[deck].pitch
            syncFace(deck)
        }
        updateRmxRamp(dt)
    }

    private fun updateRmxRamp(dt: Double) {
        val bpm = max(1.0, tracks[0].bpm)
        if (rmx.holding) {
            if (holdStart < 0) {
                holdStart = t - dt
                holdFrom = rmx.intensity
            }
            val ramp = max(0.3, rmx.bars * 4.0 * 60.0 / bpm)
            val progress = ((t - holdStart) / ramp).toFloat().coerceIn(0f, 1f)
            rmx = rmx.copy(intensity = holdFrom + (1f - holdFrom) * progress)
        } else {
            holdStart = -1.0
            if (rmx.intensity > 0.02f) {
                val level = rmx.intensity * 0.8.pow(dt / 0.04).toFloat()
                rmx = rmx.copy(intensity = if (level > 0.02f) level else 0f)
            }
        }
        emitEffects()
    }

    private fun syncFace(deck: Int) {
        faces = faces.mapIndexed { index, face -> if (index == deck) face.copy(position = pos[deck].toFloat()) else face }
    }

    private fun edit(deck: Int, block: (DeckFace) -> DeckFace) {
        faces = faces.mapIndexed { index, face -> if (index == deck) block(face) else face }
    }

    fun gridOf(deck: Int): Pair<Double, Double>? {
        val track = tracks.getOrNull(deck) ?: return null
        val edit = GridEdit(gridShiftMs[deck], faces.getOrNull(deck)?.gridStretch ?: 1.0)
        val grid = applyGrid(beats[deck], edit)
        val bpm = gridBpm(track.bpm, edit)
        if (bpm <= 1.0) return null
        val origin = if (grid.isEmpty()) 0.0 else grid.first().timeMs / 1000.0 - (grid.first().beatInBar.coerceIn(1, 4) - 1) * (60.0 / bpm)
        return origin to bpm
    }

    /** Uma mensagem MIDI como se viesse da controladora. */
    fun midi(status: Int, data1: Int, data2: Int) {
        val hit = map.lookup(status, data1)
        val action = tempo.interpret(hit, data2) ?: interpretPerformance(hit, data2, tempo)
        if (hit.mapped && data2 > 0 && !hit.description.contains("rotate") && !hit.description.contains("fader") && !hit.description.contains("slider")) {
            say("DDJ-400  ·  ${hit.description.substringBefore(" - ").trim()}")
        } else if (hit.mapped && (hit.description.contains("rotate") || hit.description.contains("fader") || hit.description.contains("slider"))) {
            say("DDJ-400  ·  ${hit.description.substringBefore(" - ").trim()}", 1.2)
        }
        if (action != null) apply(action)
    }

    /** Toque na tela, não na controladora. */
    fun touch(text: String, block: SessionModel.() -> Unit) {
        say("TELA  ·  $text")
        block()
        emitEffects()
    }

    var overlay = ""

    fun openRmx() {
        // Painel RMX acoplado embaixo: as ondas continuam à vista.
        rmx = rmx.copy(panel = true, full = true)
    }

    fun closeRmx() {
        rmx = rmx.copy(panel = false, full = false)
    }

    private fun slider(action: PerformanceAction) = (action.slider ?: 0.0).toFloat()

    private fun apply(action: PerformanceAction) {
        val deck = action.deck
        when (action.kind) {
            PerformanceAction.Kind.PLAY -> if (deck in 0..1) {
                val playing = !faces[deck].playing
                edit(deck) { it.copy(playing = playing) }
                ops += "%.4f\tplay\t%d\t%d".format(t, deck, if (playing) 1 else 0)
            }
            PerformanceAction.Kind.VOLUME -> if (deck in 0..1) {
                volume[deck] = slider(action)
                edit(deck) { it.copy(onAir = volume[deck] > 0.2f) }
                ops += "%.4f\tvol\t%d\t%.5f".format(t, deck, volume[deck])
            }
            PerformanceAction.Kind.CROSSFADER -> {
                cross = slider(action)
                ops += "%.4f\tcross\t%.5f".format(t, cross)
            }
            PerformanceAction.Kind.EQ -> if (deck in 0..1 && action.hotIndex in 0..2) {
                eq[deck][action.hotIndex] = slider(action)
                ops += "%.4f\teq\t%d\t%.5f\t%.5f\t%.5f".format(t, deck, eq[deck][0], eq[deck][1], eq[deck][2])
            }
            PerformanceAction.Kind.TRIM -> if (deck in 0..1) trimKnob[deck] = slider(action)
            PerformanceAction.Kind.PITCH -> if (deck in 0..1) {
                val pitch = slider(action)
                edit(deck) { it.copy(pitch = pitch, fader = faderFromPitch(pitch.toDouble(), it.range).toFloat(), sync = false) }
                ops += "%.4f\tpitch\t%d\t%.6f".format(t, deck, pitch)
            }
            PerformanceAction.Kind.FILTER -> if (deck in 0..1) {
                val value = (((action.slider ?: 0.5) * 127.0).toInt()).coerceIn(0, 127)
                edit(deck) { it.copy(filter = value) }
                ops += "%.4f\tfilter\t%d\t%.5f".format(t, deck, value / 127f)
            }
            PerformanceAction.Kind.SYNC -> syncDeck(deck)
            PerformanceAction.Kind.MASTER -> if (deck in 0..1) master = deck
            PerformanceAction.Kind.BEAT_JUMP_PAGE -> if (deck in 0..1) {
                jumpScale[deck] = stepJumpScale(jumpScale[deck], action.hotIndex == 1)
            }
            PerformanceAction.Kind.BEAT_JUMP -> if (deck in 0..1 && action.hotIndex in 0..7) {
                jumpBy(deck, beatJumpPadBeats(action.hotIndex) * jumpScale[deck])
            }
            PerformanceAction.Kind.HOT_CUE -> if (deck in 0..1) hotCue(deck, action.hotIndex)
            PerformanceAction.Kind.BEAT_FX -> {
                if (rmx.panel && action.hotIndex == 8 && rmx.xOn && rmx.xMode == 2) {
                    rmx = rmx.copy(drumBuild = ((action.slider ?: 0.0) / 127.0).toFloat().coerceIn(0f, 1f))
                } else if (rmx.panel && action.hotIndex == 8) {
                    rmx = rmx.copy(intensity = ((action.slider ?: 0.0) / 127.0).toFloat().coerceIn(0f, 1f))
                } else if (rmx.panel && action.hotIndex == 3) {
                    val index = os.deckbridge.logic.SCENE_BARS.indexOf(rmx.bars).coerceAtLeast(0)
                    rmx = rmx.copy(bars = os.deckbridge.logic.SCENE_BARS[(index - 1).coerceAtLeast(0)])
                } else if (rmx.panel && action.hotIndex == 4) {
                    val index = os.deckbridge.logic.SCENE_BARS.indexOf(rmx.bars).coerceAtLeast(0)
                    rmx = rmx.copy(bars = os.deckbridge.logic.SCENE_BARS[(index + 1).coerceAtMost(os.deckbridge.logic.SCENE_BARS.lastIndex)])
                } else {
                    fx = reduceBeatFx(fx, action)
                    if (action.hotIndex == 7) rmx = rmx.copy(channel = action.deck.coerceIn(0, 2))
                }
            }
            PerformanceAction.Kind.RMX -> if (rmx.panel) rmx = reduceRmxPad(rmx, action)
            else -> Unit
        }
        emitEffects()
    }

    private fun seekTo(deck: Int, seconds: Double) {
        pos[deck] = max(0.0, seconds)
        ops += "%.4f\tseek\t%d\t%.6f".format(t, deck, pos[deck])
        syncFace(deck)
    }

    private fun hotCue(deck: Int, index: Int) {
        val cue = hotCues[deck].firstOrNull { it.first == index } ?: return
        seekTo(deck, cue.second)
    }

    /** Hot cues da análise do app, em segundos, por deck. */
    private val hotCues = arrayOf<List<Pair<Int, Double>>>(emptyList(), emptyList())

    fun setHotCues(deck: Int, cues: List<Pair<Int, Double>>) {
        hotCues[deck] = cues
    }

    private fun jumpBy(deck: Int, beatsToJump: Double) {
        val track = tracks[deck]
        val seconds = jumpSeconds(
            pos[deck], beatsToJump, gridBpm(track.bpm, GridEdit(gridShiftMs[deck], faces[deck].gridStretch)),
            48000, quantize, 1.0, -1.0, -1.0, track.durationSeconds.toDouble(),
        )
        seekTo(deck, seconds)
    }

    private fun syncDeck(deck: Int) {
        if (deck !in 0..1) return
        if (faces[deck].sync) {
            edit(deck) { it.copy(sync = false) }
            return
        }
        val lead = if (master != deck) master else 1 - deck
        val own = tracks[deck].bpm
        val other = tracks[lead].bpm
        val synced = syncPitch(own, other, faces[lead].pitch.toDouble()) ?: return
        edit(deck) { it.copy(pitch = synced.toFloat(), sync = true, fader = faderFromPitch(synced, it.range).toFloat()) }
        ops += "%.4f\tpitch\t%d\t%.6f".format(t, deck, synced)
        if (quantize && faces[deck].playing && faces[lead].playing) {
            val ownGrid = gridOf(deck)
            val leadGrid = gridOf(lead)
            if (ownGrid != null && leadGrid != null) {
                val shift = syncPhaseShiftSeconds(pos[deck], ownGrid.first, ownGrid.second, pos[lead], leadGrid.first, leadGrid.second)
                if (abs(shift) > 0.002) seekTo(deck, pos[deck] + shift)
            }
        }
    }

    /** Segue o líder quando o SYNC está ligado (o efeito do DeckPage). */
    private fun followLead() {
        for (deck in 0..1) {
            if (!faces[deck].sync || deck == master) continue
            val target = syncPitch(tracks[deck].bpm, tracks[master].bpm, faces[master].pitch.toDouble()) ?: continue
            if (abs(faces[deck].pitch - target.toFloat()) > 1e-5f) {
                edit(deck) { it.copy(pitch = target.toFloat(), fader = faderFromPitch(target, it.range).toFloat()) }
                ops += "%.4f\tpitch\t%d\t%.6f".format(t, deck, target)
            }
        }
    }

    /** O mesmo envio de Beat FX e RMX do LaunchedEffect do DeckPage. */
    private fun emitEffects() {
        followLead()
        val deck = when (rmx.channel) {
            1 -> 1
            2 -> master
            else -> 0
        }
        val bpm = (tracks[deck].bpm * faces[deck].pitch).toFloat()
        val payload = rmxPayload(rmx, bpm, deck).joinToString("\t") { "%.5f".format(it) }
        val beats = beatFxBeats(fx.fractionLabel()).toFloat()
        val on = if (rmx.panel) 0 else if (fx.on) 1 else 0
        val beatLine = "beatfx\t${beatFxAudioKind(fx.effect)}\t$on\t${fx.level}\t${fx.channel}\t%.4f\t%.5f".format(bpm, beats)
        if (payload != lastPayload || beatLine != lastBeatLine) {
            if (rmx.panel) {
                if (beatLine != lastBeatLine) ops += "%.4f\t%s".format(t, beatLine)
                if (payload != lastPayload) ops += "%.4f\trmx\t%s".format(t, payload)
            } else {
                if (payload != lastPayload) ops += "%.4f\trmx\t%s".format(t, payload)
                if (beatLine != lastBeatLine) ops += "%.4f\t%s".format(t, beatLine)
            }
            lastPayload = payload
            lastBeatLine = beatLine
        }
    }

    fun finish(): String {
        ops += "%.4f\tend".format(t)
        return ops.joinToString("\n") + "\n"
    }
}
