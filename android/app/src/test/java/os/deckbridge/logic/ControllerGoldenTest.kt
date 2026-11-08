package os.deckbridge.logic

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetores de referência da lógica de controle para a porta em C++ do Pi (ADR-0008, `pi/control/controller.h`).
 * Bytes MIDI em pedaços (como chegam do USB) passam pelo MidiAssembler, pelo mapa oficial e pelo interpretPerformance
 * do app; o arquivo guarda os bytes e as ações. O teste C++ exige as mesmas ações. Aqui, sem a variável
 * DECKBRIDGE_WRITE_GOLDEN=1, o arquivo do repositório tem de ser o que o Kotlin de hoje gera.
 */
class ControllerGoldenTest {
    private val file = File("../../pi/control/testdata/golden.txt")

    private fun generate(): String {
        val map = MidiMap.loadResource()
        val mapped = map.all.filter { it.mapped }
        val rng = Random(400)
        val assembler = MidiAssembler()
        val tempo = TempoAssembler()
        val out = StringBuilder()
        out.append("# Gerado por ControllerGoldenTest (Kotlin). B = bytes de um pacote; M = mensagem e a ação do app.\n")
        repeat(6000) {
            val bytes = when (rng.nextInt(100)) {
                in 0..74 -> {
                    val hit = mapped[rng.nextInt(mapped.size)]
                    val value = when (rng.nextInt(5)) {
                        0 -> 0
                        1 -> 127
                        2 -> 64 + rng.nextInt(-6, 7)
                        else -> rng.nextInt(128)
                    }
                    // Às vezes em running status (sem repetir o status), às vezes note-off (0x8n).
                    val status = if (hit.status in 0x90..0x9F && value == 0 && rng.nextInt(3) == 0) hit.status - 0x10 else hit.status
                    if (rng.nextInt(5) == 0) byteArrayOf(hit.midino.toByte(), value.toByte()) else byteArrayOf(status.toByte(), hit.midino.toByte(), value.toByte())
                }
                in 75..84 -> ByteArray(rng.nextInt(1, 5)) { rng.nextInt(256).toByte() }
                in 85..89 -> byteArrayOf(0xF0.toByte(), 0x00, 0x40, rng.nextInt(128).toByte(), 0xF7.toByte())
                in 90..94 -> byteArrayOf(0xF8.toByte(), 0xFE.toByte())
                else -> byteArrayOf(0xF2.toByte(), rng.nextInt(128).toByte(), rng.nextInt(128).toByte())
            }
            out.append("B")
            bytes.forEach { out.append(" %02X".format(it.toInt() and 0xFF)) }
            out.append('\n')
            for (m in assembler.push(bytes)) {
                val hit = map.lookup(m.status, m.data1)
                val action = interpretPerformance(hit, m.data2, tempo)
                out.append("M ${m.status} ${m.data1} ${m.data2} | ")
                if (action == null) out.append("-") else out.append("${action.kind} ${action.deck} ${action.hotIndex} ${action.slider?.toString() ?: "null"}")
                out.append('\n')
            }
        }
        return out.toString()
    }

    @Test
    fun goldenVectorsMatchTheKotlinLogic() {
        val text = generate()
        val actions = text.lines().count { it.startsWith("M ") && !it.endsWith("| -") }
        assertTrue("vetores com ações ($actions)", actions > 2000)
        if (System.getenv("DECKBRIDGE_WRITE_GOLDEN") == "1") {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        assertTrue("falta ${file.path}: rode com DECKBRIDGE_WRITE_GOLDEN=1", file.exists())
        assertEquals("pi/control/testdata/golden.txt está velho: regrave com DECKBRIDGE_WRITE_GOLDEN=1", file.readText(), text)
    }
}
