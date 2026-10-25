package os.deckbridge.manager.anlz

import kotlin.math.roundToInt
import os.deckbridge.logic.PcmWaves
import os.deckbridge.logic.TrackRead
import os.deckbridge.logic.packMono
import os.deckbridge.logic.packPwv4
import os.deckbridge.logic.packPwv7
import os.deckbridge.logic.pwv3Body
import os.deckbridge.logic.pwv4Body
import os.deckbridge.logic.pwv5Body
import os.deckbridge.logic.pwv5Words
import os.deckbridge.logic.pwv6Body
import os.deckbridge.logic.pwv7Body

/** Uma batida da grade: posição no compasso (1 a 4), andamento ×100 e tempo em ms. */
data class Beat(val inBar: Int, val tempo100: Int, val timeMs: Int)

object GridIo {
    fun read(dat: AnlzModel): List<Beat> {
        val s = dat.first("PQTZ") ?: return emptyList()
        val b = s.bytes
        if (b.size < 24) return emptyList()
        val n = b.be32(20).coerceIn(0, (b.size - 24) / 8)
        return List(n) { Beat(b.be16(24 + it * 8), b.be16(26 + it * 8), b.be32(28 + it * 8)) }
    }

    /** Grade regular a [bpm], com a primeira batida (batida 1 do compasso) em [firstBeatMs], até [durationMs]. */
    fun regular(bpm: Double, firstBeatMs: Int, durationMs: Int): List<Beat> {
        require(bpm in 20.0..400.0) { "andamento fora de 20–400 BPM" }
        val step = 60000.0 / bpm
        val tempo = (bpm * 100).roundToInt().coerceIn(1, 65535)
        val out = ArrayList<Beat>()
        var i = 0
        while (true) {
            val t = (firstBeatMs + i * step).roundToInt()
            if (t > durationMs || i > 100000) break
            out += Beat(i % 4 + 1, tempo, t)
            i++
        }
        return out.ifEmpty { listOf(Beat(1, tempo, firstBeatMs.coerceAtLeast(0))) }
    }

    fun section(beats: List<Beat>, like: AnlzSection? = null): AnlzSection {
        val body = ByteArray(12 + beats.size * 8)
        if (like != null && like.bytes.size >= 20) like.bytes.copyInto(body, 0, 12, 20) else body.putBe32(4, 0x80000)
        body.putBe32(8, beats.size)
        beats.forEachIndexed { n, beat ->
            body.putBe16(12 + n * 8, beat.inBar.coerceIn(1, 4))
            body.putBe16(14 + n * 8, beat.tempo100)
            body.putBe32(16 + n * 8, beat.timeMs.coerceAtLeast(0))
        }
        return section("PQTZ", body)
    }

    fun write(dat: AnlzModel, beats: List<Beat>): AnlzModel = dat.replacing(setOf("PQTZ"), listOf(section(beats, dat.first("PQTZ"))), after = "PPTH")
}

/** Conjunto de arquivos de análise de uma faixa nova, a partir da análise do próprio app. */
class AnlzSet(val dat: ByteArray, val ext: ByteArray, val ex2: ByteArray)

object AnlzBuild {
    fun path(audioPath: String): AnlzSection {
        val text = (audioPath + "\u0000").toByteArray(Charsets.UTF_16BE)
        val body = ByteArray(4 + text.size)
        body.putBe32(0, text.size)
        text.copyInto(body, 4)
        return section("PPTH", body)
    }

    private fun wave(preview: ByteArray): AnlzSection {
        val packed = packMono(preview)
        val body = ByteArray(8 + packed.size)
        body.putBe32(0, packed.size)
        body.putBe32(4, 0x10000)
        packed.copyInto(body, 8)
        return section("PWAV", body, 0x14)
    }

    /** Miniatura da onda (PWV2): 100 colunas, reamostradas da prévia. */
    private fun tiny(preview: ByteArray): AnlzSection {
        val src = packMono(preview)
        val cols = 100
        val packed = ByteArray(cols) { c -> if (src.isEmpty()) 0 else src[(c.toLong() * src.size / cols).toInt().coerceAtMost(src.size - 1)] }
        val body = ByteArray(8 + packed.size)
        body.putBe32(0, packed.size)
        body.putBe32(4, 0x10000)
        packed.copyInto(body, 8)
        return section("PWV2", body, 0x14)
    }

    /** [audioPath] é o caminho do áudio dentro do pendrive, com barra inicial (como o rekordbox grava em PPTH). */
    fun fullSet(audioPath: String, read: TrackRead, waves: PcmWaves, durationMs: Int, cues: List<Cue>): AnlzSet {
        val analyzed = read.beats.take(4000).map { Beat(it.beatInBar, (it.bpm * 100).roundToInt().coerceIn(1, 65535), it.timeMs) }
        val beats = analyzed.ifEmpty { GridIo.regular(read.bpm.coerceIn(20.0, 400.0), 0, durationMs) }
        val ppth = path(audioPath)
        val dat0 = AnlzModel.fresh(listOf(ppth, GridIo.section(beats), wave(waves.bluePreview), tiny(waves.bluePreview)))
        val (dat, ext0) = CueIo.write(dat0, null, CueSet(emptyList(), emptyMap(), emptyMap()), cues)
        // Ordem dos arquivos reais: EXT = PPTH, PWV3, PCOB, PCOB, PCO2, PCO2, (PQT2), PWV5, PWV4.
        val cueSections = ext0.sections
        val ext = AnlzModel.fresh(listOf(ppth, section("PWV3", pwv3Body(packMono(waves.blueDetail)), 0x18)) + cueSections + listOf(
            section("PWV5", pwv5Body(pwv5Words(waves.rgbDetail)), 0x18),
            section("PWV4", pwv4Body(packPwv4(waves.bandPreview.low, waves.bandPreview.mid, waves.bandPreview.high)), 0x18),
        ))
        val ex2 = AnlzModel.fresh(listOf(
            ppth,
            section("PWV7", pwv7Body(packPwv7(waves.bandDetail)), 0x18),
            section("PWV6", pwv6Body(packPwv7(waves.bandPreview)), 0x14),
            section("PWVC", byteArrayOf(0, 0, 0, 90, 0, 100, 0, 120)),
        ))
        return AnlzSet(dat.write(), ext.write(), ex2.write())
    }
}
