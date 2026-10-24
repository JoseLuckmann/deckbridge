package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BringUpTest {
    private val map = MidiMap.loadResource()

    @Test
    fun checklistCountsOnlyUnshiftedMessagesAndListsUnknowns() {
        val list = BringUpChecklist(map)
        assertTrue("checklist com ${list.expected.size}", list.expected.size in 80..201)
        assertTrue(list.expected.none { it.description.contains("SHIFT", ignoreCase = true) })
        assertTrue(list.expected.none { it.key.endsWith("LSB") })
        // PLAY do deck 1: 0x90 0x0B.
        assertFalse(list.observe(0x90, 0x0B, map.lookup(0x90, 0x0B).mapped))
        assertEquals(1, list.seenCount)
        assertTrue(list.missing.none { it.status == 0x90 && it.midino == 0x0B })
        // Mensagem fora do mapa vira desconhecida, com a contagem.
        assertTrue(list.observe(0x9F, 0x10, false))
        assertTrue(list.observe(0x9F, 0x10, false))
        assertEquals(listOf(Triple(0x9F, 0x10, 2)), list.unknown)
        assertTrue(list.summary(), list.summary().contains("1 desconhecida"))
        assertTrue(list.report(MidiRemap.NONE).contains("0x9F:0x10 ×2"))
        // Ver todas as esperadas fecha o checklist.
        list.expected.forEach { list.observe(it.status, it.midino, true) }
        assertEquals(list.expected.size, list.seenCount)
        assertTrue(list.missing.isEmpty())
    }

    @Test
    fun remapParsesAppliesAndRoundTrips() {
        val remap = MidiRemap.parse("0x9F:0x10=0x90:0x0B; lixo; 0xB6:0x40=0xB6:0x41, 0x00:0x01=0x90:0x02, 9f:11=90:0c")
        assertEquals(0x90 to 0x0B, remap.apply(0x9F, 0x10))
        assertEquals(0xB6 to 0x41, remap.apply(0xB6, 0x40))
        assertEquals(0x90 to 0x0C, remap.apply(0x9F, 0x11))
        // Status inválido (0x00) é ignorado; o resto passa sem mudar.
        assertEquals(0x00 to 0x01, remap.apply(0x00, 0x01))
        assertEquals(0x91 to 0x0B, remap.apply(0x91, 0x0B))
        val again = MidiRemap.parse(remap.serialize())
        assertEquals(remap.serialize(), again.serialize())
        assertTrue(MidiRemap.parse("").isEmpty())
        assertEquals(0x91 to 0x0C, MidiRemap.NONE.with(0x9E, 0x01, 0x91, 0x0C).apply(0x9E, 0x01))
    }

    @Test
    fun noteOffArrivesAsNoteOnWithZeroVelocity() {
        val got = MidiAssembler().push(byteArrayOf(0x80.toByte(), 0x0B, 0x40, 0x81.toByte(), 0x0C, 0x7F))
        assertEquals(listOf(MidiMessage(0x90, 0x0B, 0), MidiMessage(0x91, 0x0C, 0)), got)
    }
}
