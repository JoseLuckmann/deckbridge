package os.deckbridge.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catálogo leve do app: sem as ondas de detalhe na memória, mas com o mesmo detalhe quando a faixa vai para o deck. */
class CatalogMemoryTest {
    private fun detailBytes(a: RbAnalysis): Long {
        var n = a.blueDetail.size.toLong()
        a.rgbDetail?.let { n += it.height.size + it.rgb.size * 4L }
        a.bandDetail?.let { n += it.low.size + it.mid.size + it.high.size }
        return n
    }

    @Test
    fun lightCatalogDropsDetailAndReloadsTheSameDetailPerTrack() {
        val volume = SyntheticRekordbox.volume()
        val full = loadRekordbox(volume)
        val light = loadRekordboxLight(volume)
        assertEquals(full.tracks, light.tracks)
        val fullBytes = full.analyses.values.sumOf(::detailBytes)
        val lightBytes = light.analyses.values.sumOf(::detailBytes)
        println("detalhe na memória: completo $fullBytes bytes, leve $lightBytes bytes, ${full.tracks.size} faixas")
        assertTrue("o catálogo sintético tem detalhe ($fullBytes)", fullBytes > 0)
        assertEquals(0L, lightBytes)
        for (track in full.tracks) {
            val want = full.analyses.getValue(track.id)
            val got = light.analyses.getValue(track.id)
            // O que a lista e o deck usam sem o detalhe continua igual.
            assertEquals(want.beats, got.beats)
            assertEquals(want.hotCues, got.hotCues)
            assertEquals(want.phrases, got.phrases)
            assertArrayEquals(want.bluePreview, got.bluePreview)
            assertEquals(want.rgbPreview?.rgb?.toList(), got.rgbPreview?.rgb?.toList())
            // Na carga, o detalhe relido do ANLZ é o mesmo do catálogo completo.
            if (track.analyzePath.isBlank()) continue
            val again = loadTrackDetail(volume, track)
            assertArrayEquals(want.blueDetail, again.blueDetail)
            assertEquals(want.rgbDetail?.rgb?.toList(), again.rgbDetail?.rgb?.toList())
            assertEquals(want.bandDetail?.low?.toList(), again.bandDetail?.low?.toList())
            assertEquals(detailBytes(want), detailBytes(again))
        }
    }
}
