package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.manager.organize.OnDemand
import os.deckbridge.manager.organize.Organizer
import os.deckbridge.manager.organize.Rule
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Volumes

class OrganizerTest {
    private fun t(id: Int, title: String = "T$id", artist: String = "A", album: String = "Al", genre: String = "G", key: String = "Am", fileName: String = "f$id.wav", dur: Int = 200, bitrate: Int = 320, year: Int = 2020, no: Int = 1, path: String = "Contents/f$id.wav") =
        TrackItem(id, title, artist, album, genre, key, "", "", 120.0, year, 0, 0, no, dur, bitrate, 44100, path, fileName, "PIONEER/x$id.DAT")

    private fun view(vararg tracks: TrackItem) = LibraryView(tracks.toList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    @Test
    fun tidyAndCaseRulesShowBeforeAndAfter() {
        val v = view(t(1, title = "  Night   Bus ", artist = "NARA VALE"), t(2, title = "Já Está Certo"), t(3, title = "tudo minúsculo de novo"))
        val s = Organizer.suggest(v)
        val tidy = s.filter { it.rule == Rule.TIDY }.single()
        assertEquals(listOf(1), tidy.trackIds)
        assertEquals("Night Bus", (tidy.edit as Edit.SetTracks).patch.title)
        val cases = s.filter { it.rule == Rule.CASE }
        assertEquals("Nara Vale", (cases.first { 1 in it.trackIds }.edit as Edit.SetTracks).patch.artist)
        assertEquals("Tudo Minúsculo de Novo", (cases.first { 3 in it.trackIds }.edit as Edit.SetTracks).patch.title)
        assertTrue(cases.none { 2 in it.trackIds }, "texto com caixa mista não é mexido")
        assertTrue(Organizer.titleCase("DJ") == "DJ" || Organizer.titleCase("DJ").length == 2)
    }

    @Test
    fun fileNamesFillOnlyEmptyFields() {
        val v = view(
            t(1, title = "", artist = "", no = 0, fileName = "07 - Nara Vale - Night Bus.mp3"),
            t(2, title = "Mantido", artist = "Mantido", no = 5, fileName = "Outro - Nome.mp3"),
            t(3, title = "", artist = "", no = 0, fileName = "so_o_titulo.mp3"),
        )
        val f = Organizer.suggest(v).filter { it.rule == Rule.FILENAME }
        val p1 = (f.first { 1 in it.trackIds }.edit as Edit.SetTracks).patch
        assertEquals("Nara Vale", p1.artist)
        assertEquals("Night Bus", p1.title)
        assertEquals(7, p1.trackNumber)
        assertTrue(f.none { 2 in it.trackIds })
        val p3 = (f.first { 3 in it.trackIds }.edit as Edit.SetTracks).patch
        assertNull(p3.artist)
        assertEquals("so o titulo", p3.title)
    }

    @Test
    fun duplicatesAreOfferedButNeverPreselected() {
        val v = view(t(1, title = "Night Bus!", artist = "Nara Vale", bitrate = 128, dur = 200), t(2, title = "night bus", artist = "NARA VALE", bitrate = 320, dur = 201), t(3, title = "Night Bus", artist = "Nara Vale", dur = 400))
        val d = Organizer.suggest(v).filter { it.rule == Rule.DUPLICATE }.single()
        assertEquals(listOf(1, 2), d.trackIds.sorted())
        assertEquals(listOf(1), (d.edit as Edit.RemoveTracks).trackIds, "fica a de maior taxa de bits")
        assertTrue(!d.defaultOn)
    }

    @Test
    fun missingFieldsAndMissingAnalysisAreOnlyReported() {
        val v = view(t(1, artist = "", genre = "", year = 0), t(2))
        val s = Organizer.suggest(v) { it.id != 2 }
        assertTrue(s.filter { it.rule == Rule.MISSING }.all { it.edit == null })
        assertEquals(setOf(1), s.first { it.title.startsWith("Artista") }.trackIds.toSet())
        assertEquals(listOf(2), s.single { it.rule == Rule.ANALYSIS }.trackIds)
    }

    @Test
    fun renamePatternsBuildSafePathsAndAvoidCollisions() {
        val a = t(1, title = "Night/Bus: ext?", artist = "Nara Vale", album = "North Line", path = "Contents/velho1.wav", fileName = "velho1.wav")
        val b = t(2, title = "Night/Bus: ext?", artist = "Nara Vale", album = "North Line", path = "Contents/velho2.wav", fileName = "velho2.wav")
        val plan = Organizer.renamePlan(listOf(a, b), "{artista}/{album}/{artista} - {titulo}") { false }
        assertEquals("Contents/Nara Vale/North Line/Nara Vale - Night_Bus_ ext_.wav", plan[0].newPath)
        assertEquals("Contents/Nara Vale/North Line/Nara Vale - Night_Bus_ ext_ (2).wav", plan[1].newPath)
        // Já no lugar: nada a fazer.
        val same = t(3, title = "X", artist = "Y", album = "Z", path = "Contents/Y - X.wav", fileName = "Y - X.wav")
        assertTrue(Organizer.renamePlan(listOf(same), "{artista} - {titulo}") { false }.isEmpty())
    }

    @Test
    fun theRenamePlanAppliesToARealVolume() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val tracks = e.view().tracks.filter { File(dir, it.filePath.trimStart('/')).isFile }.take(4)
        val plan = Organizer.renamePlan(tracks, "{artista}/{album}/{artista} - {titulo}") { e.files.exists(it) }
        assertTrue(plan.isNotEmpty())
        e.apply(Edit.RenameFiles(plan))
        SafeWriter.apply(vol, e, listOf("padrão"), Backups(TestVolumes.tempDir("dbm-bk")))
        val after = Volumes.open(dir)
        val v = LibraryView.of { after.raw.table(it)?.rows ?: emptyList() }
        for (r in plan) assertTrue(File(dir, v.track(r.trackId)!!.filePath.trimStart('/')).isFile)
    }

    @Test
    fun onDemandAnalysisAttachesANewAnalysisToATrackAndKeepsTheOldFiles() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val track = e.view().tracks.first { File(dir, it.filePath.trimStart('/')).isFile && it.durationSeconds >= 8 }
        val outcome = OnDemand.analyze(dir, track)
        assertNull(outcome.error, outcome.error)
        val s = OnDemand.suggestion(outcome, hasAnalysis = true, force = false)
        assertNull(s, "não sobrescreve a análise que já existe sem pedir")
        val forced = OnDemand.suggestion(outcome, hasAnalysis = true, force = true)!!
        val oldDat = File(dir, track.analyzePath.trimStart('/')).readBytes()
        e.apply(forced.edit!!)
        SafeWriter.apply(vol, e, listOf("reanalisar"), Backups(TestVolumes.tempDir("dbm-bk")))
        val v = LibraryView.of { Volumes.open(dir).raw.let { r -> { t: Int -> r.table(t)?.rows ?: emptyList() } }(it) }
        val now = v.track(track.id)!!
        assertTrue(now.analyzePath != track.analyzePath)
        assertTrue(File(dir, now.analyzePath.trimStart('/')).isFile)
        assertEquals(oldDat.toList(), File(dir, track.analyzePath.trimStart('/')).readBytes().toList(), "o ANLZ antigo não foi tocado")
        assertEquals(outcome.prepared!!.read.bpm, now.bpm, 0.01)
        assertTrue(File(dir, PDB_RELATIVE).isFile)
    }

    @Test
    fun aMissingAudioFileIsReportedNotThrown() {
        val dir = TestVolumes.synthetic()
        val ghost = t(99, path = "Contents/nao-existe.wav")
        val outcome = OnDemand.analyze(dir, ghost)
        assertTrue(outcome.prepared == null && outcome.error!!.contains("não está no pendrive"))
    }
}
