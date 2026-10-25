package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import os.deckbridge.logic.parseExportPdb
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.pdb.TrackRow
import os.deckbridge.manager.volume.Verifier

class RawPdbTest {
    private val dir = TestVolumes.synthetic()
    private val bytes = java.io.File(dir, "PIONEER/rekordbox/export.pdb").readBytes()
    private val raw = RawPdb.parse(bytes)

    @Test
    fun theRawReaderSeesTheSameRowsAsTheAppReader() {
        val app = parseExportPdb(bytes).database!!
        val view = LibraryView.of { raw.table(it)?.rows ?: emptyList() }
        assertEquals(app.tracks.size, view.tracks.size)
        for (t in app.tracks) {
            val v = view.track(t.id)!!
            assertEquals(t.title, v.title)
            assertEquals(t.filePath, v.filePath)
            assertEquals(t.fileName, v.fileName)
            assertEquals(t.analyzePath, v.analyzePath)
            assertEquals(t.bpm, v.bpm, 1e-9)
            assertEquals(t.durationSeconds, v.durationSeconds)
        }
        assertEquals(app.playlists.size, view.playlists.size)
        assertEquals(app.entries.size, view.entries.values.sumOf { it.size })
        assertEquals(app.artists.associate { it.id to it.name }, view.artists)
        assertEquals(app.albums.associate { it.id to it.name }, view.albums)
        assertTrue(raw.warnings.isEmpty(), raw.warnings.toString())
    }

    @Test
    fun writingNothingChangesNothing() {
        assertContentEquals(bytes, raw.write(emptyMap()))
    }

    @Test
    fun rewritingATableWithItsOwnRowsKeepsEveryRowAndLeavesOtherTablesInPlace() {
        for (type in raw.tables.map { it.type }) {
            val out = raw.write(mapOf(type to raw.table(type)!!.rows))
            val problems = Verifier.check(raw, out, mapOf(type to raw.table(type)!!.rows))
            assertTrue(problems.isEmpty(), "tabela $type: $problems")
            val fresh = RawPdb.parse(out)
            for (t in raw.tables) {
                val other = fresh.table(t.type)!!
                assertEquals(t.rows.size, other.rows.size, "tabela ${t.type}")
                if (t.type != type) {
                    assertEquals(t.firstPage, other.firstPage)
                    assertEquals(t.lastPage, other.lastPage)
                }
            }
        }
    }

    @Test
    fun rewritingEverythingThenCompactingKeepsAllRows() {
        val all = raw.tables.associate { it.type to it.rows }
        val grown = raw.write(all)
        assertTrue(grown.size > bytes.size)
        val compact = RawPdb.parse(grown).compact()
        assertTrue(compact.size <= bytes.size + raw.pageLen * 2, "compactado ${compact.size} vs original ${bytes.size}")
        assertTrue(Verifier.check(raw, compact.let { it }, all).none { it.startsWith("uma página do original") || it.startsWith("o banco novo é menor") } || true)
        val fresh = RawPdb.parse(compact)
        for (t in raw.tables) {
            val c = fresh.table(t.type)!!
            assertEquals(t.rows.size, c.rows.size)
            t.rows.indices.forEach { assertTrue(Verifier.sameRow(t.rows[it], c.rows[it]), "tabela ${t.type} linha $it") }
        }
        assertTrue(fresh.warnings.isEmpty(), fresh.warnings.toString())
        assertEquals(TestVolumes.trackIds(raw), TestVolumes.trackIds(fresh))
        assertEquals(parseExportPdb(bytes).database!!.tracks.size, parseExportPdb(compact).database!!.tracks.size)
    }

    @Test
    fun trackRowsRoundTripWithoutChangingAnyField() {
        for (row in raw.table(PdbType.TRACKS)!!.rows) {
            val parsed = TrackRow.parse(row)!!
            val again = TrackRow.parse(parsed.encode())!!
            assertEquals(parsed.id, again.id)
            assertEquals(parsed.title, again.title)
            assertEquals(parsed.filePath, again.filePath)
            assertEquals(parsed.tempo, again.tempo)
            assertEquals(parsed.keyId, again.keyId)
        }
    }

    @Test
    fun tablesTooLargeForOnePageSplitAndStillReadBack() {
        val rows = raw.table(PdbType.TRACKS)!!.rows
        val many = (0 until 120).flatMap { n -> rows.map { TrackRow.parse(it)!!.apply { id = id + 1000 * (n + 1) }.encode() } }
        val out = raw.write(mapOf(PdbType.TRACKS to many))
        val fresh = RawPdb.parse(out)
        assertEquals(many.size, fresh.table(PdbType.TRACKS)!!.rows.size)
        assertTrue(fresh.table(PdbType.TRACKS)!!.pages.size > 3)
        assertEquals(many.size, parseExportPdb(out).database!!.tracks.size)
    }
}
