package os.deckbridge.manager

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.logic.parseExportExt
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.mix.TrackProfile
import os.deckbridge.logic.RbTag
import os.deckbridge.manager.organize.TrackClassifier
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.transfer.EXT_RELATIVE
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Volumes

/** Gênero, energia e My Tags na entrada da faixa: faixas sintéticas de estilos diferentes e a gravação no exportExt.pdb. */
class ClassifierTest {
    private val rate = 22050

    /** Uma faixa sintética: bumbo, chimbal, baixo e acorde, cada um ligado ou não, com o volume [gain]. */
    private fun track(bpm: Double, seconds: Int, kick: Boolean, hats: Boolean, bass: Boolean, pad: Boolean, gain: Float): FloatArray {
        val beat = rate * 60.0 / bpm
        var noise = 7
        return FloatArray(seconds * rate) { i ->
            val t = i.toDouble() / rate
            val inBeat = (i % beat) / rate
            val half = ((i + beat / 2) % beat) / rate
            noise = noise * 1103515245 + 12345
            val n = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
            var x = 0.0
            if (kick) x += 0.9 * exp(-inBeat * 18) * sin(2 * PI * (50 + 80 * exp(-inBeat * 30)) * inBeat)
            if (hats) x += 0.35 * exp(-half * 60) * n
            if (bass) x += 0.35 * sin(2 * PI * 55 * t) * (1 - exp(-inBeat * 40))
            if (pad) x += 0.15 * (sin(2 * PI * 330 * t) + sin(2 * PI * 415 * t) + sin(2 * PI * 494 * t))
            (gain * x).toFloat()
        }
    }

    private fun classify(s: FloatArray, bpm: Double, title: String = "Faixa") =
        TrackClassifier.classify(assertNotNull(TrackProfile.of(s, rate, bpm, 0.0)), bpm, title)

    @Test fun `energia e situacao seguem o que se ouve`() {
        val peak = classify(track(128.0, 40, kick = true, hats = true, bass = true, pad = false, gain = 1f), 128.0)
        val deep = classify(track(122.0, 40, kick = true, hats = false, bass = true, pad = false, gain = 0.35f), 122.0)
        val ambient = classify(track(90.0, 40, kick = false, hats = false, bass = false, pad = true, gain = 0.15f), 90.0)
        assertTrue(peak.energy >= 7, "pista cheia: ${peak.energy} ${peak.why}")
        assertTrue(ambient.energy <= 3, "ambiente: ${ambient.energy} ${ambient.why}")
        assertTrue(deep.energy in 3..6, "deep: ${deep.energy} ${deep.why}")
        assertTrue(peak.energy > deep.energy && deep.energy > ambient.energy)
        assertTrue("Lounge" in ambient.situations, ambient.situations.toString())
        assertTrue("Peak Time" in peak.situations || "Main Floor" in peak.situations, peak.situations.toString())
        // O deep: energia média, escuro e hipnótico — warm-up ou after hours, nunca peak time.
        assertTrue("Peak Time" !in deep.situations && deep.situations.any { it == "Warm Up" || it == "After Hours" || it == "Main Floor" }, deep.situations.toString())
        assertTrue("Beat" in peak.components && "Sub Bass" in peak.components, peak.components.toString())
        assertTrue("Beat" !in ambient.components)
        // Grave e bumbo sem agudo, 122 BPM, energia média: o palpite é Deep House (gênero e My Tag).
        assertTrue("Dark" in deep.components, deep.components.toString() + deep.why)
        assertEquals("Deep House", deep.genre, deep.why.toString())
        assertTrue(RbTag("Deep House", "Genre") in deep.myTags)
        // O gênero das etiquetas do arquivo sempre ganha do palpite.
        assertEquals("Afro House", TrackClassifier.classify(TrackProfile.of(track(122.0, 30, true, false, true, false, 0.35f), rate, 122.0, 0.0)!!, 122.0, "x", "Afro House").genre)
    }

    @Test fun `vocal pelo titulo e energia no comentario`() {
        val s = track(124.0, 30, kick = true, hats = true, bass = true, pad = false, gain = 0.6f)
        assertTrue("Vocal" in classify(s, 124.0, "Shined On Me (feat. Andrea Love)").components)
        assertTrue("Vocal" !in classify(s, 124.0, "Shined On Me feat. Andrea Love (Dub Mix)").components)
        assertEquals("Energy 7", TrackClassifier.comment("", 7))
        assertEquals("Achado na loja - Energy 6", TrackClassifier.comment("Achado na loja", 6))
        // Outra análise (ou o Mixed In Key) já escreveu: troca só a energia.
        assertEquals("8A - Energy 5", TrackClassifier.comment("8A - Energy 9", 5))
        assertEquals(5, TrackClassifier.energyOf("8A - Energy 5"))
    }

    @Test fun `a importacao grava genero, energia e My Tags nos campos do rekordbox`() {
        val dir = LocalLibrary.ensure(TestVolumes.tempDir("dbm-class"))
        val src = File(TestVolumes.tempDir("dbm-class-src"), "Nara Vale - Night Bus.wav")
        src.writeBytes(encodeWavMono16(track(128.0, 40, kick = true, hats = true, bass = true, pad = false, gain = 1f), rate))
        val p = ImportPrep.prepare(src)
        val c = assertNotNull(p.classification)
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        e.apply(Edit.ImportTrack(p, p.info.title, p.info.artist, "", p.genre, p.read.keyName, 0, 0, p.suggestedCues, p.comment, p.myTags))
        SafeWriter.apply(vol, e, listOf("importar"), Backups(TestVolumes.tempDir("dbm-class-bk")))
        val raw = RawPdb.parse(File(dir, PDB_RELATIVE).readBytes())
        val t = LibraryView.of { raw.table(it)?.rows ?: emptyList() }.tracks.single()
        assertEquals(p.genre, t.genre)
        assertEquals("Energy ${c.energy}", t.comment)
        // As My Tags lidas de volta pelo mesmo leitor do modo DJ (colunas padrão do rekordbox: Components, Situation).
        val got = os.deckbridge.logic.tagsByTrack(parseExportExt(File(dir, EXT_RELATIVE).readBytes()))[t.id].orEmpty().toSet()
        assertTrue(got.isNotEmpty(), "gravou My Tags")
        assertTrue(c.situations.all { RbTag(it, "Situation") in got }, "todos os momentos do set: $got vs ${c.situations}")
        assertTrue(got.all { it in c.myTags }, "só as da classificação: $got vs ${c.myTags}")
        // Acrescentar de novo não duplica, e uma tag que não existe no arquivo é ignorada.
        val vol2 = Volumes.open(dir)
        val e2 = PdbEditor(vol2.raw, vol2.root)
        e2.apply(Edit.AddMyTags(t.id, c.myTags + RbTag("Não Existe", "Situation")))
        // Nada a gravar: as tags já estavam e a outra não existe no arquivo.
        val nothing = runCatching { SafeWriter.apply(vol2, e2, listOf("de novo"), Backups(TestVolumes.tempDir("dbm-class-bk2"))) }
        assertTrue(nothing.exceptionOrNull()?.message?.contains("Não há mudanças") == true, nothing.toString())
        assertEquals(got.size, assertNotNull(parseExportExt(File(dir, EXT_RELATIVE).readBytes())).tagTracks.count { it.trackId == t.id })
    }

    @Test fun `cores padrao por energia e por genero, guardadas e relidas`() {
        val r = os.deckbridge.manager.organize.ColorRules()
        assertEquals(null, r.colorFor(9, "Techno"), "desligado: sem cor")
        val e = r.copy(mode = os.deckbridge.manager.organize.ColorRules.Mode.ENERGY).withEnergy(3, 1)
        assertEquals(1, e.colorFor(9, ""))
        assertEquals(7, e.colorFor(2, ""))
        assertEquals(null, e.colorFor(null, "Techno"), "sem energia, sem cor")
        val g = r.copy(mode = os.deckbridge.manager.organize.ColorRules.Mode.GENRE).withGenre("Afro House", 5)
        assertEquals(5, g.colorFor(null, "afro house"))
        assertEquals(8, g.colorFor(null, "Techno"))
        assertEquals(null, g.colorFor(null, "Samba"))
        assertEquals(g, os.deckbridge.manager.organize.ColorRules.decode(g.encode()))
        assertEquals(e, os.deckbridge.manager.organize.ColorRules.decode(e.encode()))
    }

    @Test fun `momentos do set que o rekordbox nao traz sao criados uma vez na coluna Situation`() {
        val dir = LocalLibrary.ensure(TestVolumes.tempDir("dbm-sit"))
        val src = File(TestVolumes.tempDir("dbm-sit-src"), "A - B.wav")
        src.writeBytes(encodeWavMono16(track(124.0, 30, kick = true, hats = true, bass = true, pad = false, gain = 0.6f), rate))
        val p = ImportPrep.prepare(src)
        val wanted = listOf(RbTag("Warm Up", "Situation"), RbTag("Closing", "Situation"), RbTag("Peak Time", "Situation"))
        fun importOnce(tag: String) {
            val vol = Volumes.open(dir)
            val e = PdbEditor(vol.raw, vol.root)
            e.apply(Edit.ImportTrack(p, "Faixa $tag", "A", "", "", "", 0, 0, emptyList(), "", wanted))
            SafeWriter.apply(vol, e, listOf("importar $tag"), Backups(TestVolumes.tempDir("dbm-sit-bk")))
        }
        importOnce("1")
        importOnce("2")
        val ext = assertNotNull(parseExportExt(File(dir, EXT_RELATIVE).readBytes()))
        val situation = ext.tags.first { it.category && it.name == "Situation" }.id
        // Cada nome uma vez só na coluna (o segundo import reaproveita), e Peak Time é o padrão do rekordbox.
        for (n in listOf("Warm Up", "Closing", "Peak Time")) assertEquals(1, ext.tags.count { !it.category && it.categoryId == situation && it.name == n }, n)
        // As tags padrão continuam lá, com as mesmas ids (a tabela foi só acrescentada).
        val before = assertNotNull(parseExportExt(os.deckbridge.manager.pdb.ExportFactory.exportExtPdb()))
        assertTrue(before.tags.all { b -> ext.tags.any { it.id == b.id && it.name == b.name } })
        // As duas faixas ficaram com os três momentos.
        val byTrack = os.deckbridge.logic.tagsByTrack(ext)
        assertEquals(2, byTrack.size)
        byTrack.values.forEach { assertEquals(wanted.toSet(), it.toSet()) }
    }
}
