package os.deckbridge.logic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fronteiras lidas à mão na energia por compasso. O método está em
 * docs/testing/phrase-annotations.md. Não é escuta.
 */
class PhraseHitRateTest {
    @Test
    fun annotatedBoundariesWithinOneBar() {
        val dir = File("/opt/cursor/artifacts/real-corpus/mono")
        assumeTrue(dir.isDirectory)
        var total = 0
        var withinOne = 0
        var withinFour = 0
        annotations.forEach { (stem, rows) ->
            val file = File(dir, "$stem.wav")
            assumeTrue(file.isFile)
            val pcm = decodeWavMono(file.readBytes())
            assumeTrue(pcm != null)
            val read = analyzeTrack(pcm!!.samples, pcm.rate)
            val bar = 240.0 / read.bpm
            rows.forEach { (kind, sec) ->
                val got = when (kind) {
                    "build" -> read.structure.buildSec
                    "drop" -> read.structure.dropSec
                    "break" -> read.structure.breakSec
                    else -> read.structure.outroSec
                }
                val delta = kotlin.math.abs(got - sec) / bar
                total += 1
                if (delta <= 1.0 + 1e-6) withinOne += 1
                if (delta <= 4.0 + 1e-6) withinFour += 1
            }
        }
        assertEquals(26, total)
        assertTrue("1 compasso: $withinOne/26", withinOne >= 16)
        assertTrue("4 compassos: $withinFour/26", withinFour >= 20)
    }

    private val annotations = mapOf(
        "01-chromosome-boutique" to listOf("outro" to 175.87),
        "02-ecosystem" to listOf("build" to 53.12, "drop" to 110.16, "break" to 175.08, "outro" to 182.95),
        "07-silver" to listOf("drop" to 0.0, "outro" to 205.96),
        "at-the-count" to listOf("drop" to 12.63, "break" to 151.58, "outro" to 227.37),
        "caught-in-the-beat" to listOf(
            "build" to 16.50,
            "drop" to 24.00,
            "break" to 66.00,
            "break" to 132.00,
            "outro" to 246.00,
        ),
        "morning-original-mix" to listOf("build" to 16.27, "drop" to 32.54, "break" to 162.71, "outro" to 357.97),
        "ocean-original-mix" to listOf("drop" to 0.0, "outro" to 357.96),
        "toodaze" to listOf("drop" to 0.0, "break" to 240.00, "outro" to 320.00),
        "wrist-watch" to listOf("drop" to 32.00, "outro" to 304.00),
    )
}
