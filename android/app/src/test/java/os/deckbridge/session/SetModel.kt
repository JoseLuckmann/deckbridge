package os.deckbridge.session

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.browseRows
import os.deckbridge.logic.moveBrowse
import os.deckbridge.logic.advancePlayhead
import os.deckbridge.logic.jogSeconds
import os.deckbridge.logic.scaleLoop
import os.deckbridge.logic.toggleBeatLoop
import os.deckbridge.logic.quantizeSeconds
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
import os.deckbridge.logic.trimDb

/**
 * Modelo sem tela do que o DeckPage faz com uma mensagem MIDI da DDJ-400, só para o que a sessão usa.
 * A mesma `interpretPerformance` do app traduz os bytes; as ações são aplicadas como em `onPerformance`.
 * Cada efeito sobre o motor vira uma linha do ops.tsv, que o `ddj_session` reexecuta no áudio.
 */
class SetModel(private val catalog: RbCatalog, private val realDuration: DoubleArray) {
    private val map = MidiMap.loadResource()
    private val tempo = TempoAssembler()
    val all: List<RbTrack> = catalog.tracks
    /** Índice (em `all`) da faixa que está em cada deck; -1 = vazio. */
    val deckIdx = intArrayOf(-1, -1)
    fun isLoaded(deck: Int) = deckIdx[deck] >= 0
    fun trkOrNull(deck: Int): RbTrack? = all.getOrNull(deckIdx[deck])
    fun trk(deck: Int): RbTrack = all[deckIdx[deck]]
    var browse: BrowsePlace? = null
        private set
    private val beatsAll = all.map { catalog.analyses[it.id]?.beats.orEmpty() }
    private fun beatsOf(deck: Int) = beatsAll.getOrElse(deckIdx[deck]) { emptyList() }

    var t = 0.0
        private set
    val pos = doubleArrayOf(0.0, 0.0)
    /** Posição "sem loop" enquanto o SLIP está ligado (o que o slipAnchor do motor acompanha). */
    private val shadow = doubleArrayOf(0.0, 0.0)
    /** Prato tocado (scratch): a faixa não anda sozinha, só com o giro. */
    val scratching = booleanArrayOf(false, false)
    /** A DDJ-400 simulada: só o que a mão mandou por MIDI. */
    val surface = DdjSurface()
    // Espelho do giro solto do motor (ScratchFollower.coasting): velocidade em x da normal, NaN fora do giro.
    private val coast = doubleArrayOf(Double.NaN, Double.NaN)
    private val lastMove = doubleArrayOf(-1.0, -1.0)
    private val moveGap = doubleArrayOf(1.0, 1.0)
    private val moveSize = doubleArrayOf(0.0, 0.0)
    var faces = listOf(DeckFace(playing = false, onAir = false), DeckFace(playing = false, onAir = false))
        private set
    private val volume = floatArrayOf(0f, 0f)
    var colorFx = 5
        private set
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

    /** Faixas que já estão nos decks (nulo = deck vazio). */
    fun deckTracks(): List<RbTrack?> = listOf(trkOrNull(0), trkOrNull(1))

    private fun rows() = browse?.let { browseRows(catalog, it, emptyList(), null, false) }.orEmpty()

    /** Toque em BROWSE: abre a lista de ficheiros (a mesma da tela real). */
    fun openBrowse() {
        browse = BrowsePlace(inList = true, playlistId = catalog.playlists.first().id, index = 0)
    }

    /** Posição de uma faixa (índice em `all`) na lista de ficheiros, como o encoder BROWSE a percorre. */
    fun rowIndexOfTrack(index: Int): Int {
        val place = BrowsePlace(inList = true, playlistId = catalog.playlists.first().id, index = 0)
        return browseRows(catalog, place, emptyList(), null, false).indexOfFirst { it.track?.id == all[index].id }
    }

    val browseIndex: Int get() = browse?.index ?: 0

    private fun loadSelected(deck: Int) {
        val row = rows().getOrNull(browse?.index ?: return) ?: return
        val index = all.indexOfFirst { it.id == row.track?.id }
        if (index < 0) return
        loadTrack(deck, index)
        browse = null
    }

    /** O que o DeckPage faz ao carregar: o deck volta ao estado de fábrica da faixa nova, parado no começo. */
    private fun loadTrack(deck: Int, index: Int) {
        deckIdx[deck] = index
        pos[deck] = 0.0
        shadow[deck] = 0.0
        jumpScale[deck] = 1.0
        faces = faces.mapIndexed { i, face -> if (i == deck) DeckFace(playing = false, onAir = false, filter = face.filter) else face }
        ops += "%.4f\tload\t%d\t%d".format(t, deck, index)
        gridOf(deck)?.let { ops += "%.4f\tgridat\t%d\t%.6f\t%.6f".format(t, deck, it.first, it.second) }
        ops += "%.4f\tpitch\t%d\t1.000000".format(t, deck)
        // Ganho automático como o DeckPage (alvo -10 LUFS, no máximo ±12 dB).
        val lufs = catalog.analyses[all[index].id]?.loudnessLufs ?: Double.NaN
        val gainDb = trimDb(lufs, -10.0, true)
        ops += "%.4f\ttrim\t%d\t%.5f".format(t, deck, 10.0.pow(gainDb / 20.0))
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
        colorFx = colorFx,
    )

    /** Passa o tempo: posições, rampa do RMX e o estado do rail de efeitos. */
    fun advance(dt: Double) {
        t += dt
        for (deck in 0..1) {
            if (!coast[deck].isNaN()) {
                // Mesmo freio do dsp.h: 5x/s freando, 8x/s partindo.
                val target = if (faces[deck].playing) faces[deck].pitch.toDouble() else 0.0
                val v = coast[deck]
                val braking = (v < 0 && target >= 0) || (v > 0 && target <= 0) || kotlin.math.abs(v) > kotlin.math.abs(target)
                val delta = (if (braking) 5.0 else 8.0) * dt
                val next = if (v < target) min(target, v + delta) else max(target, v - delta)
                pos[deck] = max(0.0, pos[deck] + (v + next) * 0.5 * dt)
                coast[deck] = if (next == target) Double.NaN else next
            } else if (faces[deck].playing && !scratching[deck]) {
                val face = faces[deck]
                pos[deck] = advancePlayhead(pos[deck], dt * face.pitch, face.loopStart.toDouble(), face.loopEnd.toDouble())
                if (face.slip) shadow[deck] += dt * face.pitch
            }
            syncFace(deck)
        }
        updateRmxRamp(dt)
    }

    private fun updateRmxRamp(dt: Double) {
        val rmxDeck = when (rmx.channel) {
            1 -> 1
            2 -> master
            else -> 0
        }
        val bpm = max(1.0, (trkOrNull(rmxDeck)?.bpm ?: 120.0) * faces[rmxDeck].pitch)
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
        val track = trkOrNull(deck) ?: return null
        val edit = GridEdit(0.0, faces.getOrNull(deck)?.gridStretch ?: 1.0)
        val grid = applyGrid(beatsOf(deck), edit)
        val bpm = gridBpm(track.bpm, edit)
        if (bpm <= 1.0) return null
        val origin = if (grid.isEmpty()) 0.0 else grid.first().timeMs / 1000.0 - (grid.first().beatInBar.coerceIn(1, 4) - 1) * (60.0 / bpm)
        return origin to bpm
    }

    /** Uma mensagem MIDI como se viesse da controladora. */
    fun midi(status: Int, data1: Int, data2: Int) {
        val hit = map.lookup(status, data1)
        surface.feed(t, status, data1, data2, if (hit.mapped) hit.description.substringBefore(" - ").trim() else "")
        val action = tempo.interpret(hit, data2) ?: interpretPerformance(hit, data2, tempo)
        if (hit.mapped && data2 > 0 && !hit.description.contains("rotate") && !hit.description.contains("fader") && !hit.description.contains("slider")) {
            say("DDJ-400  ·  ${hit.description.substringBefore(" - ").trim()}")
        } else if (hit.mapped && (hit.description.contains("rotate") || hit.description.contains("fader") || hit.description.contains("slider"))) {
            say("DDJ-400  ·  ${hit.description.substringBefore(" - ").trim()}", 1.2)
        }
        if (action != null) apply(action)
    }

    /** Toque na tela, não na controladora. */
    fun touch(text: String, block: SetModel.() -> Unit) {
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

    /** Release FX do RMX (2 = backspin); -1 desarma. */
    fun setRelease(kind: Int) {
        rmx = rmx.copy(release = kind, panel = kind >= 0 || rmx.panel)
    }

    private fun slider(action: PerformanceAction) = (action.slider ?: 0.0).toFloat()

    private fun apply(action: PerformanceAction) {
        val deck = action.deck
        when (action.kind) {
            PerformanceAction.Kind.PLAY -> if (deck in 0..1 && isLoaded(deck)) {
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
            PerformanceAction.Kind.BEAT_LOOP -> if (deck in 0..1 && isLoaded(deck)) {
                val face = faces[deck]
                val next = toggleBeatLoop(face.loopStart, face.loopEnd, pos[deck].toFloat(), action.slider ?: 4.0, trk(deck).bpm)
                if (next == null) setLoop(deck, null) else setLoop(deck, next)
            }
            PerformanceAction.Kind.LOOP_SCALE -> if (deck in 0..1) {
                val face = faces[deck]
                scaleLoop(face.loopStart, face.loopEnd, action.slider ?: 1.0)?.let { setLoop(deck, it) }
            }
            PerformanceAction.Kind.RELOOP -> if (deck in 0..1) {
                val face = faces[deck]
                if (face.loopEnd > face.loopStart) setLoop(deck, null)
            }
            // Prato no modo vinil (padrão do app): cada passo desloca o ponto, como o DeckPage.
            PerformanceAction.Kind.JOG_TOUCH -> if (deck in 0..1) {
                val was = scratching[deck]
                scratching[deck] = action.hotIndex == 1
                ops += "%.4f\tscratch\t%d\t%d".format(t, deck, if (scratching[deck]) 1 else 0)
                // Soltar com o prato girando: a faixa segue pela inércia (backspin), como o motor.
                coast[deck] = if (was && !scratching[deck] && t - lastMove[deck] < 0.02) moveSize[deck] / moveGap[deck] else Double.NaN
            }
            PerformanceAction.Kind.JOG -> if (deck in 0..1 && isLoaded(deck)) {
                val seconds = jogSeconds((action.slider ?: 0.0))
                if (scratching[deck]) {
                    // Scratch: a posição segue o prato (o motor alcança em ~10 ms).
                    pos[deck] = max(0.0, pos[deck] + seconds)
                    moveGap[deck] = (t - lastMove[deck]).coerceIn(0.001, 0.05)
                    moveSize[deck] = seconds
                    lastMove[deck] = t
                    ops += "%.4f\tscratchmove\t%d\t%.6f".format(t, deck, seconds)
                    syncFace(deck)
                } else if (coast[deck].isNaN()) {
                    seekTo(deck, pos[deck] + seconds)
                }
            }
            PerformanceAction.Kind.BROWSE_MOVE -> browse?.let { place -> browse = moveBrowse(place, (action.slider ?: 0.0).toInt(), rows().size) }
            PerformanceAction.Kind.LOAD -> if (deck in 0..1) loadSelected(deck)
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

    /** Toque no botão SLIP do cartão (menu \"···\"): ligado, a faixa continua por baixo do loop. */
    fun toggleSlip(deck: Int) {
        val face = faces[deck]
        if (!face.slip) {
            shadow[deck] = pos[deck]
            edit(deck) { it.copy(slip = true) }
        } else {
            if (face.loopEnd > face.loopStart) {
                setLoop(deck, null)
            }
            edit(deck) { it.copy(slip = false) }
        }
    }

    private fun setLoop(deck: Int, range: Pair<Float, Float>?) {
        if (range == null) {
            val face = faces[deck]
            val wasLooping = face.loopEnd > face.loopStart
            edit(deck) { it.copy(memoryStart = face.loopStart, memoryEnd = face.loopEnd, loopStart = -1f, loopEnd = -1f) }
            ops += "%.4f\tloop\t%d\t-1\t-1".format(t, deck)
            // Mesmo comportamento do motor: sair do loop com SLIP ligado volta ao ponto em que a faixa estaria.
            if (wasLooping && face.slip) seekTo(deck, shadow[deck])
        } else {
            edit(deck) { it.copy(loopStart = range.first, loopEnd = range.second, memoryStart = range.first, memoryEnd = range.second) }
            ops += "%.4f\tloop\t%d\t%.6f\t%.6f".format(t, deck, range.first, range.second)
        }
    }

    private fun seekTo(deck: Int, seconds: Double) {
        pos[deck] = max(0.0, seconds)
        ops += "%.4f\tseek\t%d\t%.6f".format(t, deck, pos[deck])
        syncFace(deck)
    }

    private fun hotCue(deck: Int, index: Int) {
        if (!isLoaded(deck)) return
        val cue = hotCueTimes(deckIdx[deck]).firstOrNull { it.first == index } ?: return
        // Quantize ligado, como o DeckPage: o ponto salta para a batida mais próxima da grade ajustada.
        val edit = GridEdit(0.0, faces[deck].gridStretch)
        seekTo(deck, quantizeSeconds(cue.second, applyGrid(beatsOf(deck), edit), gridBpm(trk(deck).bpm, edit)))
    }

    /** LEDs que o app mandaria à controladora agora (mesma regra do DeckPage), com o pico de cada canal. */
    fun leds(peaks: List<Float>): os.deckbridge.logic.LedState = os.deckbridge.logic.LedState(
        decks = (0..1).map { deck ->
            val face = faces[deck]
            val track = trkOrNull(deck)
            val bpm = track?.bpm ?: 0.0
            val loopBeats = if (face.loopEnd > face.loopStart && bpm > 1.0) (face.loopEnd - face.loopStart) * bpm / 60.0 else 0.0
            os.deckbridge.logic.DeckLed(
                loaded = track != null,
                playing = face.playing,
                sync = face.sync,
                hotCues = if (isLoaded(deck)) hotCueTimes(deckIdx[deck]).map { it.first }.filter { it in 0..7 }.toSet() else emptySet(),
                beatLoop = os.deckbridge.logic.beatLoopIndex(loopBeats),
                peak = peaks.getOrElse(deck) { 0f },
            )
        },
        beatFxOn = fx.on && !rmx.panel,
        samplerPads = buildSet {
            if (rmx.panel && rmx.holding) add(rmx.scene.coerceIn(0, 7))
            if (rmx.panel && rmx.xOn) add((4 + rmx.xMode).coerceIn(0, 7))
        },
    )

    /** Hot cues da análise do app, em segundos, por faixa. */
    private fun hotCueTimes(trackIndex: Int): List<Pair<Int, Double>> =
        catalog.analyses[all[trackIndex].id]?.hotCues.orEmpty().map { it.index to it.timeMs / 1000.0 }

    private fun jumpBy(deck: Int, beatsToJump: Double) {
        val track = trk(deck)
        val seconds = jumpSeconds(
            pos[deck], beatsToJump, gridBpm(track.bpm, GridEdit(0.0, faces[deck].gridStretch)),
            48000, quantize, 1.0, -1.0, -1.0, realDuration[deckIdx[deck]],
        )
        seekTo(deck, seconds)
    }

    private fun syncDeck(deck: Int) {
        if (deck !in 0..1 || !isLoaded(deck)) return
        if (faces[deck].sync) {
            edit(deck) { it.copy(sync = false) }
            return
        }
        val lead = if (master != deck) master else 1 - deck
        val own = trk(deck).bpm
        val other = trk(lead).bpm
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
            if (!faces[deck].sync || deck == master || !isLoaded(deck) || !isLoaded(master)) continue
            val target = syncPitch(trk(deck).bpm, trk(master).bpm, faces[master].pitch.toDouble()) ?: continue
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
        val bpm = ((trkOrNull(deck)?.bpm ?: 120.0) * faces[deck].pitch).toFloat()
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

    // ---- Agenda: o DJ automático põe ações em tempos futuros; o modelo as executa na ordem ----
    private class Scheduled(val time: Double, val order: Int, val block: SetModel.() -> Unit)

    private val queue = java.util.PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.time }, { it.order }))
    private var counter = 0

    fun at(time: Double, block: SetModel.() -> Unit) {
        queue += Scheduled(max(time, t), counter++, block)
    }

    fun pending(): Int = queue.size

    /** Última hora já agendada (ou agora, se a agenda está vazia). */
    fun lastScheduled(): Double = max(t, queue.maxOfOrNull { it.time } ?: t)

    /** Executa tudo o que vence até `target`, avançando o relógio do modelo em passos de 20 ms entre uma ação e outra. */
    fun runUntil(target: Double) {
        while (true) {
            val next = queue.peek() ?: break
            if (next.time > target + 1e-9) break
            queue.poll()
            stepTo(next.time)
            next.block(this)
        }
        stepTo(target)
    }

    private fun stepTo(time: Double) {
        while (t + SESSION_TICK < time) advance(SESSION_TICK)
        if (time > t) advance(time - t)
    }

    fun setColorFx(deck: Int, kind: Int) {
        colorFx = kind
        ops += "%.4f\tcolorfx\t%d\t%d".format(t, deck, kind)
    }

    /** Origem do compasso 1 e BPM da grade de uma faixa (índice em `all`), como o app a calcula. */
    fun gridOfTrack(index: Int): Pair<Double, Double> {
        val track = all[index]
        val beats = beatsAll[index]
        val bpm = track.bpm
        val origin = if (beats.isEmpty()) 0.0 else beats.first().timeMs / 1000.0 - (beats.first().beatInBar.coerceIn(1, 4) - 1) * (60.0 / bpm)
        return origin to bpm
    }

    fun durationOf(index: Int): Double = realDuration[index]

    fun pitchOf(deck: Int): Double = faces[deck].pitch.toDouble()

    /** Primeira batida (grade já quantizada) de uma faixa: onde o hot cue 1 cai. */
    fun firstBeatOf(index: Int): Double {
        val cue = hotCueTimes(index).firstOrNull { it.first == 0 }?.second ?: gridOfTrack(index).first
        val edit = GridEdit(0.0, 1.0)
        return quantizeSeconds(cue, applyGrid(beatsAll[index], edit), gridBpm(all[index].bpm, edit))
    }

    fun finish(): String {
        ops += "%.4f\tend".format(t)
        return ops.joinToString("\n") + "\n"
    }
}
