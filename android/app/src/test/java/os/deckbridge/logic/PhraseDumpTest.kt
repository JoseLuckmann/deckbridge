package os.deckbridge.logic

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Só diagnóstico (PHRASE_DUMP=1): medidas por bloco de 4 compassos das faixas do corpus. */
class PhraseDumpTest {
    @Test
    fun dump() {
        assumeTrue(System.getenv("PHRASE_DUMP") == "1")
        val dir = File("/opt/cursor/artifacts/real-corpus/mono")
        val out = StringBuilder()
        for (stem in (System.getenv("PHRASE_STEMS") ?: "ocean-original-mix,morning-original-mix,toodaze").split(',')) {
            val pcm = decodeWavMono(File(dir, "$stem.wav").readBytes()) ?: continue
            val read = analyzeTrack(pcm.samples, pcm.rate)
            val downbeat = read.beats.firstOrNull { it.beatInBar == 1 }!!.timeMs / 1000.0
            val f = barFeatures(pcm.samples, pcm.rate, read.bpm, downbeat)!!
            out.append("$stem bpm %.2f origin %.2f bar %.3f\n".format(read.bpm, f.originSec, f.barSec))
            for (b in 0 until f.bars step 4) {
                val to = minOf(f.bars, b + 4)
                fun avg(v: DoubleArray) = (b until to).map { v[it] }.average()
                out.append("  bar %3d  t %6.1f  low %6.1f  mid %6.1f  high %6.1f  kick %.2f  tot %6.1f\n".format(
                    b, f.originSec + b * f.barSec, avg(f.lowDb), avg(f.midDb), avg(f.highDb), avg(f.kick), avg(f.totalDb)))
            }
        }
        File("build/phrase-dump.txt").writeText(out.toString())
    }
}
