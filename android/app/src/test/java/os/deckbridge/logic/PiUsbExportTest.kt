package os.deckbridge.logic

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Monta um pendrive no formato do rekordbox (export.pdb + ANLZ) com faixas locais e a análise do próprio app, para a
 * demonstração da tela do Pi. Só roda com PI_USB=1 e as faixas na pasta da sessão (fora do git: áudio do dono não
 * entra no repositório). Saída em $SESSION_DIR/pi-usb.
 *
 *   SESSION_DIR=/tmp/deckbridge-session PI_USB=1 ./gradlew testMultiDebugUnitTest --tests '*PiUsbExportTest'
 */
class PiUsbExportTest {
    @Test
    fun exportLocalTracksForThePi() {
        val dir = File(System.getenv("SESSION_DIR") ?: "/tmp/deckbridge-session")
        assumeTrue(System.getenv("PI_USB") == "1" && File(dir, "set/src/t1.wav").isFile)
        val picks = listOf("t1" to "Faixa 1", "t2" to "Faixa 2")
        val songs = picks.map { (slug, title) ->
            // Análise no mono de 24 kHz (como a sessão de vídeo); o áudio do pendrive é o WAV estéreo de 48 kHz.
            val mono = decodeWavMono(File(dir, "set/in/$slug.wav").readBytes()) ?: error("mono $slug")
            val stereo = File(dir, "set/src/$slug.wav").readBytes()
            val read = analyzeTrack(mono.samples, mono.rate)
            val waves = wavesFromPcm(mono.samples, mono.rate)
            val s = read.structure
            OurSong(
                title = title,
                artist = "Sessão local",
                album = "Demonstração do Pi",
                keyName = read.keyName,
                bpm = read.bpm,
                seconds = mono.samples.size / mono.rate,
                sampleRate = 48000,
                fileName = "$slug.wav",
                wav = stereo,
                waves = waves,
                beats = read.beats,
                hot = listOf(
                    CueSpec(1, (s.introSec * 1000).toInt(), "Intro", 255, 59, 59),
                    CueSpec(2, (s.buildSec * 1000).toInt(), "Build", 46, 197, 255),
                    CueSpec(3, (s.dropSec * 1000).toInt(), "Drop", 61, 220, 106),
                    CueSpec(4, (s.breakSec * 1000).toInt(), "Break", 180, 77, 255),
                    CueSpec(5, (s.outroSec * 1000).toInt(), "Outro", 61, 220, 106),
                ),
                memory = listOf(CueSpec(0, (s.introSec * 1000).toInt(), "Intro", 180, 40, 40)),
                loop = null,
            )
        }
        val volume = ourMusicVolume(songs)
        val out = File(dir, "pi-usb").apply { deleteRecursively() }
        volume.files.forEach { (path, bytes) -> File(out, path).apply { parentFile.mkdirs(); writeBytes(bytes) } }
        val catalog = loadRekordbox(FolderVolume(out, "pi"))
        check(catalog.error == null && catalog.tracks.size == 2) { catalog.error ?: "faixas ${catalog.tracks.size}" }
        val lines = catalog.tracks.map { t ->
            val a = catalog.analyses[t.id]
            "${t.title} ${"%.2f".format(t.bpm)} BPM ${t.keyName} ${t.durationSeconds}s beats=${a?.beats?.size} hot=${a?.hotCues?.joinToString { "${it.index}@${it.timeMs}" }}"
        }
        File(dir, "pi-usb.txt").writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
    }
}
