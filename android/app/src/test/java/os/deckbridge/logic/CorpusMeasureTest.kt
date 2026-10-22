package os.deckbridge.logic

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Mede BPM, grade e tom no corpus. Não entra no APK. */
class CorpusMeasureTest {
    @Test
    fun printGridAndKey() {
        val root = File("/opt/cursor/artifacts/real-corpus/mono")
        assumeTrue(root.isDirectory)
        val rows = listOf(
            Triple("01-chromosome-boutique.wav", "Chromosome", "Bm"),
            Triple("02-ecosystem.wav", "Ecosystem", "Ebm"),
            Triple("07-silver.wav", "Silver", "Em"),
            Triple("morning-original-mix.wav", "Morning", ""),
            Triple("ocean-original-mix.wav", "Ocean", ""),
            Triple("wrist-watch.wav", "wrist", ""),
            Triple("toodaze.wav", "toodaze", ""),
            Triple("caught-in-the-beat.wav", "Caught", ""),
            Triple("at-the-count.wav", "Count", ""),
        )
        rows.forEach { (name, title, tkey) ->
            val file = File(root, name)
            assumeTrue(file.isFile)
            val pcm = decodeWavMono(file.readBytes()) ?: error(name)
            val read = analyzeTrack(pcm.samples, pcm.rate)
            val weight = if (tkey.isBlank()) -1.0 else mirexWeight(read.keyName, tkey)
            val beatFile = File("/tmp/grid-debug/$title.txt")
            beatFile.parentFile?.mkdirs()
            beatFile.writeText(read.beats.joinToString("\n") { it.timeMs.toString() })
            println(
                "$title bpm ${"%.2f".format(read.bpm)} kick ${"%.1f".format(read.gridErrorMs)}/${"%.1f".format(read.gridErrorMaxMs)} " +
                    "cob ${"%.0f".format(read.gridCoverage * 100)}% " +
                    "full ${"%.1f".format(read.fullBandErrorMs)} key ${read.keyName} tkey ${tkey.ifBlank { "-" }} " +
                    "mirex $weight beats ${read.beats.size} ms ${read.elapsedMs}",
            )
        }
    }
}
