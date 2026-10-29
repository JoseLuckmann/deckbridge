package os.deckbridge.manager

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.AudioPrint
import os.deckbridge.manager.organize.Duplicates
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PlaylistItem
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

/** A mesma música com outro nome, outro formato ou com silêncio no começo; e juntar sem perder playlists e nota. */
class DuplicatesTest {
    /** Uma "música" sintética: notas de um arpejo que muda a cada meio segundo, bumbo e ruído, a partir de [seed]. */
    private fun song(seed: Int, seconds: Int, rate: Int): FloatArray {
        val rnd = java.util.Random(seed.toLong())
        val notes = DoubleArray(seconds * 2) { 200.0 * Math.pow(2.0, rnd.nextInt(24) / 12.0) }
        val out = FloatArray(seconds * rate)
        var noise = seed
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val f = notes[(t * 2).toInt().coerceAtMost(notes.lastIndex)]
            val beat = t % 0.5
            noise = noise * 1103515245 + 12345
            val n = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
            out[i] = (0.3 * sin(2 * PI * f * t) + 0.15 * sin(2 * PI * f * 1.5 * t) + 0.5 * Math.exp(-beat * 30) * sin(2 * PI * 60 * t) + 0.02 * n).toFloat()
        }
        return out
    }

    /** Outra cópia da mesma: 3 s de silêncio na frente, volume mais baixo, ruído de codificação e outra taxa. */
    private fun recoded(x: FloatArray, rate: Int, toRate: Int): FloatArray {
        val lead = 3 * toRate
        val n = (x.size.toLong() * toRate / rate).toInt()
        val rnd = java.util.Random(7)
        return FloatArray(lead + n) { i ->
            if (i < lead) 0f else {
                val pos = (i - lead).toDouble() * rate / toRate
                val k = pos.toInt().coerceAtMost(x.size - 2)
                (0.7 * (x[k] + (x[k + 1] - x[k]) * (pos - k)) + 0.01 * rnd.nextGaussian()).toFloat()
            }
        }
    }

    @Test fun `a mesma gravacao bate e outra musica nao`() {
        val a = song(1, 150, 22050)
        val pa = assertNotNull(AudioPrint.of(a, 22050))
        val same = assertNotNull(AudioPrint.of(recoded(a, 22050, 44100), 44100))
        val other = assertNotNull(AudioPrint.of(song(2, 150, 22050), 22050))
        val dSame = AudioPrint.distance(pa, same)
        val dOther = AudioPrint.distance(pa, other)
        assertTrue(dSame < AudioPrint.SAME, "mesma: $dSame")
        assertTrue(dOther > 0.4, "outra: $dOther")
        // Guardada e lida de volta igual.
        val back = assertNotNull(AudioPrint.decode(pa.encode()))
        assertEquals(0.0, AudioPrint.distance(pa, back))
    }

    private fun t(id: Int, title: String, artist: String, dur: Int, kbps: Int = 320, rating: Int = 0, file: String = "f$id.mp3") =
        TrackItem(id, title, artist, "", "", "9A", "", "", 122.0, 0, rating, 0, 0, dur, kbps, 44100, "Contents/$file", file, "")

    @Test fun `repetidas pelo audio com nomes diferentes, e versoes nao`() {
        val tracks = listOf(
            t(8, "0008-Lorenz_Rhode,_Purple_Disco_Machine_-_Twisted", "", 312, kbps = 192),
            t(9, "Twisted (Original Mix)", "Lorenz Rhode & Purple Disco Machine", 315, rating = 4),
            t(10, "Twisted (Radio Edit)", "Lorenz Rhode", 190),
            t(11, "Outra", "Outro", 313),
        )
        val prints = mapOf(8 to 1, 9 to 1, 10 to 3, 11 to 2).mapValues { (id, seed) ->
            val x = song(seed, 120, 22050)
            AudioPrint.of(if (id == 9) recoded(x, 22050, 22050) else x, 22050)!!
        }
        val v = LibraryView(tracks, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val g = Duplicates.find(v, { prints[it.id] }).single()
        assertEquals(listOf(8, 9), g.tracks.map { it.id })
        assertTrue(g.sameAudio)
        assertEquals(9, g.keep.id, "fica a de nome arrumado, com nota e mais qualidade")
        // Sem impressão, só pelo nome limpo e a duração.
        val byName = Duplicates.find(
            LibraryView(listOf(t(1, "", "", 200, file = "07 - Nara Vale - Night Bus.mp3"), t(2, "Night Bus", "Nara Vale", 201)), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
            { null },
        ).single()
        assertTrue(!byName.sameAudio)
    }

    @Test fun `juntar poe a que fica no lugar da repetida nas playlists e leva a nota`() {
        val keep = t(9, "Twisted", "Lorenz Rhode", 315)
        val dup = t(8, "Twisted", "", 312, rating = 4).copy(colorId = 5)
        val other = t(1, "Antes", "X", 200)
        val v = LibraryView(
            listOf(other, dup, keep), listOf(PlaylistItem(1, 0, "Sexta", false, 0), PlaylistItem(2, 0, "Ambas", false, 1)),
            mapOf(1 to listOf(1, 8), 2 to listOf(9, 8)), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
        )
        val edits = Duplicates.merge(v, keep, listOf(dup)).map { it.first }
        assertTrue(Edit.AddToPlaylist(1, listOf(9)) in edits)
        assertTrue(Edit.ReorderPlaylist(1, listOf(1, 9, 8)) in edits)
        assertTrue(edits.none { it is Edit.AddToPlaylist && it.id == 2 }, "já estava na outra")
        assertEquals(Edit.SetTracks(listOf(9), TrackPatch(rating = 4, colorId = 5)), edits.first { it is Edit.SetTracks })
        assertEquals(Edit.RemoveTracks(listOf(8)), edits.last())
    }
}
