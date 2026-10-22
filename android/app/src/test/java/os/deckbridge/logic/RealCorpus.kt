package os.deckbridge.logic

import java.io.File
import kotlin.math.min

data class RealShot(val title: String, val slug: String, val drop: Float, val breakSec: Float, val buildSec: Float)

data class RealReady(
    val catalog: RbCatalog,
    val root: File,
    val shots: List<RealShot>,
    val report: String,
)

object RealCorpus {
    private val root = File("/opt/cursor/artifacts/real-corpus")
    private val monoDir = File(root, "mono")
    private val usb = File("/opt/cursor/artifacts/real-music-usb")
    private val reportFile = File("/opt/cursor/artifacts/real-music-report.txt")
    @Volatile private var cached: RealReady? = null

    fun prepare(): RealReady? {
        cached?.let { return it }
        if (!root.isDirectory) return null
        synchronized(this) {
            cached?.let { return it }
            val pdb = File(usb, "PIONEER/rekordbox/export.pdb")
            if (reportFile.isFile && pdb.isFile && reportFile.readText().contains("analise 0.12.1 rgb")) {
                val catalog = loadRekordbox(FolderVolume(usb, "export deste app"))
                if (catalog.error == null && catalog.tracks.size >= 6) {
                    val ready = RealReady(catalog, usb, shotsOf(catalog), reportFile.readText())
                    cached = ready
                    return ready
                }
            }
            val ready = build()
            cached = ready
            return ready
        }
    }

    private fun shotsOf(catalog: RbCatalog): List<RealShot> {
        return catalog.tracks.take(4).map { track ->
            val analysis = catalog.analyses[track.id]
            val drop = analysis?.hotCues?.firstOrNull { it.comment == "Drop" }?.timeMs ?: 0
            val breakMs = analysis?.hotCues?.firstOrNull { it.comment == "Break" }?.timeMs ?: 0
            val buildMs = analysis?.hotCues?.firstOrNull { it.comment == "Build" }?.timeMs ?: 0
            RealShot(track.title, track.fileName.substringBeforeLast('.'), drop / 1000f, breakMs / 1000f, buildMs / 1000f)
        }
    }

    private fun build(): RealReady {
        monoDir.mkdirs()
        val lines = ArrayList<String>()
        lines += "Corpus em ${root.absolutePath}. Os arquivos de áudio não entram no git."
        lines += "A exportação em ${usb.absolutePath} foi gerada por este app. Não é rekordbox."
        val songs = ArrayList<OurSong>()
        val shots = ArrayList<RealShot>()
        val mix = ArrayList<Measured>()
        for (spec in specs) {
            val source = File(root, spec.file)
            if (!source.isFile) {
                lines += "AUSENTE ${spec.file}"
                continue
            }
            val mono = File(monoDir, spec.file.substringBeforeLast('.') + ".wav")
            val decodeMs = ffmpegWav(source, mono)
            val bytes = mono.readBytes()
            val started = System.nanoTime()
            val pcm = decodeWav(bytes)
            val wavMs = (System.nanoTime() - started) / 1_000_000
            if (pcm == null) {
                lines += "FALHA decodeWav ${spec.file}"
                continue
            }
            val monoSamples = FloatArray(pcm.frameCount) { index -> (pcm.left[index] + pcm.right[index]) * 0.5f }
            val before = usedMb()
            val waveStarted = System.nanoTime()
            val waves = wavesFromPcm(monoSamples, pcm.sampleRate)
            val waveMs = (System.nanoTime() - waveStarted) / 1_000_000
            val read = analyzeTrack(monoSamples, pcm.sampleRate)
            val after = usedMb()
            val clip = monoSamples.copyOf(min(monoSamples.size, pcm.sampleRate))
            val drop = read.structure.dropSec
            val breakSec = read.structure.breakSec
            val loopStart = (drop * 1000.0).toInt().coerceAtLeast(0)
            val beatMs = if (read.bpm > 1.0) (60000.0 / read.bpm).toInt() else 500
            val song = OurSong(
                title = spec.title,
                artist = spec.artist,
                album = spec.album,
                keyName = read.keyName,
                bpm = read.bpm,
                seconds = pcm.frameCount / pcm.sampleRate,
                sampleRate = pcm.sampleRate,
                fileName = spec.slug + ".wav",
                wav = encodeWavMono16(clip, pcm.sampleRate),
                waves = waves,
                beats = read.beats,
                hot = listOf(
                    CueSpec(1, (read.structure.buildSec * 1000).toInt(), "Build", 80, 180, 255),
                    CueSpec(2, loopStart, "Drop", 255, 90, 30),
                    CueSpec(3, (breakSec * 1000).toInt(), "Break", 40, 200, 80),
                ),
                memory = listOf(CueSpec(0, (read.structure.introSec * 1000).toInt(), "Intro", 180, 40, 40)),
                loop = CueSpec(0, loopStart, "Loop", 255, 140, 20) to (loopStart + beatMs * 4),
            )
            songs += song
            mix += Measured(spec, mono, read, decodeMs, wavMs, waveMs, before, after, pcm.sampleRate, pcm.frameCount)
            if (shots.size < 4) {
                shots += RealShot(spec.title, spec.slug, drop.toFloat(), breakSec.toFloat(), read.structure.buildSec.toFloat())
            }
        }
        check(songs.isNotEmpty()) { "nenhuma faixa do corpus foi analisada" }
        val volume = ourMusicVolume(songs)
        writeVolume(usb, volume)
        val catalog = loadRekordbox(FolderVolume(usb, "export deste app"))
        check(catalog.error == null) { catalog.error ?: "erro" }
        check(catalog.tracks.size == songs.size)
        lines += "analise 0.12.1 rgb"
        lines += "faixas analisadas: ${songs.size}"
        lines += "faixas no export: ${catalog.tracks.size}"
        lines += "playlists: ${catalog.playlists.joinToString { it.name }}"
        lines += inspectText(catalog)
        lines += ""
        lines += "Medidas no JVM deste host. Não são o tablet."
        mix.forEach { item ->
            lines += "--- ${item.spec.title} ---"
            lines += "arquivo ${item.spec.file} artista ${item.spec.artist}"
            val ffmpegNote = if (item.decodeMs < 0) "cache quente, tempo não medido nesta passagem" else "${item.decodeMs} ms"
            lines += "ffmpeg para wav mono 22050: $ffmpegNote"
            lines += "decodeWav: ${item.wavMs} ms, ${item.frames} frames, ${item.rate} Hz"
            lines += "BPM detectado ${"%.2f".format(item.read.bpm)} confiança ${"%.2f".format(item.read.bpmConfidence)}"
            lines += "BPM publicado: não estava no item"
            lines += "erro do kick grave na batida (até 20 ms): mediana ${"%.1f".format(item.read.gridErrorMs)} ms, máximo ${"%.1f".format(item.read.gridErrorMaxMs)} ms, cobertura ${"%.0f".format(item.read.gridCoverage * 100)}%"
            lines += "erro mediano onset de banda cheia, métrica 0.12.0: ${"%.1f".format(item.read.fullBandErrorMs)} ms"
            val mirex = if (item.spec.keyMeta.isBlank()) "sem TKEY" else "%.2f".format(mirexWeight(item.read.keyName, item.spec.keyMeta))
            lines += "tom detectado ${item.read.keyName} confiança ${"%.2f".format(item.read.keyConfidence)} metadado ${item.spec.keyMeta.ifBlank { "ausente" }} MIREX $mirex"
            lines += "estrutura s intro ${"%.1f".format(item.read.structure.introSec)} build ${"%.1f".format(item.read.structure.buildSec)} drop ${"%.1f".format(item.read.structure.dropSec)} break ${"%.1f".format(item.read.structure.breakSec)}"
            lines += "análise ${item.read.elapsedMs} ms, onda ${item.waveMs} ms, heap antes ${item.beforeMb} MB depois ${item.afterMb} MB"
        }
        val wavCheck = wavAgreement()
        lines += wavCheck
        val compressed = compressedTimes()
        lines += compressed
        val mixLine = renderMix(mix)
        lines += mixLine
        val text = lines.joinToString("\n")
        reportFile.writeText(text)
        println(text)
        return RealReady(catalog, usb, shots, text)
    }

    private fun renderMix(mix: List<Measured>): String {
        if (mix.size < 2) return "mix: menos de duas faixas"
        val bin = File("/tmp/real_mix")
        val compile = ProcessBuilder(
            "g++", "-std=c++17", "-O2", "-o", bin.absolutePath,
            "/workspace/android/app/src/test/cpp/real_mix.cpp",
        ).redirectErrorStream(true).start()
        val compileLog = compile.inputStream.bufferedReader().readText()
        if (compile.waitFor() != 0) return "mix não compilou\n$compileLog"
        val a = mix[0]
        val b = mix[1]
        val out = File("/opt/cursor/artifacts/real-mix-120s.wav")
        val startA = (a.read.structure.dropSec - 12.0).coerceAtLeast(0.0)
        val startB = (b.read.structure.dropSec - 8.0).coerceAtLeast(0.0)
        val run = ProcessBuilder(
            bin.absolutePath,
            a.mono.absolutePath,
            b.mono.absolutePath,
            out.absolutePath,
            startA.toString(),
            startB.toString(),
            a.read.bpm.toString(),
            b.read.bpm.toString(),
        ).redirectErrorStream(true).start()
        val log = run.inputStream.bufferedReader().readText()
        val code = run.waitFor()
        val excerptWav = File("/opt/cursor/artifacts/real-mix-excerpt.wav")
        val excerptMp3 = File("/opt/cursor/artifacts/real-mix-excerpt.mp3")
        val cut = ProcessBuilder(
            "ffmpeg", "-nostdin", "-y", "-ss", "40", "-t", "45", "-i", out.absolutePath, excerptWav.absolutePath,
        ).redirectErrorStream(true).start()
        cut.waitFor()
        val mp3 = ProcessBuilder(
            "ffmpeg", "-nostdin", "-y", "-i", excerptWav.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "192k", excerptMp3.absolutePath,
        ).redirectErrorStream(true).start()
        mp3.waitFor()
        return buildString {
            append("mix de ${a.spec.title} + ${b.spec.title} saída ${code}\n")
            append(log.trim())
            append("\n")
            append("trecho ${excerptWav.absolutePath} ${excerptWav.length()} bytes\n")
            append("trecho ${excerptMp3.absolutePath} ${excerptMp3.length()} bytes\n")
            append("wav completo ${out.absolutePath} ${out.length()} bytes")
        }
    }

    private fun wavAgreement(): String {
        val wav = File(root, "07-silver.wav")
        if (!wav.isFile) return "WAV de conferência ausente"
        val raw = File("/tmp/silver-f32.raw")
        val started = System.nanoTime()
        val proc = ProcessBuilder(
            "ffmpeg", "-nostdin", "-y", "-v", "error", "-vn", "-i", wav.absolutePath, "-f", "f32le", "-ac", "2", raw.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return "ffmpeg wav falhou $log"
        val ffmpegMs = (System.nanoTime() - started) / 1_000_000
        val decodedStarted = System.nanoTime()
        val pcm = decodeWav(wav.readBytes()) ?: return "decodeWav devolveu nulo no WAV"
        val ourMs = (System.nanoTime() - decodedStarted) / 1_000_000
        val bytes = raw.readBytes()
        val frames = min(pcm.frameCount, bytes.size / 8)
        var dot = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var index = 0
        val limit = min(frames, pcm.sampleRate * 5)
        while (index < limit) {
            val bits = java.nio.ByteBuffer.wrap(bytes, index * 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
            val sample = pcm.left[index].toDouble()
            dot += bits * sample
            leftEnergy += bits * bits
            rightEnergy += sample * sample
            index += 1
        }
        val corr = dot / kotlin.math.sqrt(leftEnergy * rightEnergy).coerceAtLeast(1e-12)
        return "WAV Silver: decodeWav ${ourMs} ms, ffmpeg ${ffmpegMs} ms, correlação do canal esquerdo nos primeiros 5 s ${"%.5f".format(corr)}, frames ${pcm.frameCount}"
    }

    private fun compressedTimes(): String {
        val files = listOf(
            "01-chromosome-boutique.mp3",
            "wrist-watch.flac",
            "01-chromosome-boutique.m4a",
        )
        return files.joinToString("\n") { name ->
            val file = File(root, name)
            if (!file.isFile) return@joinToString "$name ausente"
            val started = System.nanoTime()
            val proc = ProcessBuilder(
                "ffmpeg", "-nostdin", "-v", "error", "-vn", "-i", file.absolutePath, "-f", "null", "-",
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            val ms = (System.nanoTime() - started) / 1_000_000
            "$name ffmpeg saída $code em ${ms} ms. MediaCodec deste formato existe no app e não rodou neste host."
        }
    }

    private fun ffmpegWav(source: File, dest: File): Long {
        if (dest.isFile && dest.length() > 1000 && dest.lastModified() >= source.lastModified()) return -1
        val started = System.nanoTime()
        val proc = ProcessBuilder(
            "ffmpeg", "-nostdin", "-y", "-v", "error", "-vn", "-i", source.absolutePath, "-ac", "1", "-ar", "22050", "-c:a", "pcm_s16le", dest.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0 && dest.isFile) { "ffmpeg ${source.name} $code $log" }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun writeVolume(dir: File, volume: MemoryVolume) {
        if (dir.exists()) dir.deleteRecursively()
        volume.files.forEach { (path, bytes) ->
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun usedMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    private data class Measured(
        val spec: Spec,
        val mono: File,
        val read: TrackRead,
        val decodeMs: Long,
        val wavMs: Long,
        val waveMs: Long,
        val beforeMb: Long,
        val afterMb: Long,
        val rate: Int,
        val frames: Int,
    )

    private data class Spec(
        val file: String,
        val slug: String,
        val title: String,
        val artist: String,
        val album: String,
        val keyMeta: String,
    )

    private val specs = listOf(
        Spec("01-chromosome-boutique.mp3", "chromosome-boutique", "Chromosome Boutique", "Fature", "Choice by Definition", "Bm"),
        Spec("02-ecosystem.mp3", "ecosystem", "Ecosystem", "Fature", "Choice by Definition", "Ebm"),
        Spec("07-silver.mp3", "silver", "Silver", "Fature", "Choice by Definition", "Em"),
        Spec("morning-original-mix.mp3", "morning", "Morning (Original Mix)", "Miroslav Wilde", "Ocean EP", ""),
        Spec("ocean-original-mix.mp3", "ocean", "Ocean (Original Mix)", "Miroslav Wilde", "Ocean EP", ""),
        Spec("wrist-watch.flac", "wrist-watch", "wrist watch", "raymundo mendoza", "translations ep", ""),
        Spec("toodaze.mp3", "toodaze", "toodaze", "raymundo mendoza", "translations ep", ""),
        Spec("caught-in-the-beat.mp3", "caught-in-the-beat", "Caught In The Beat", "Broke For Free", "Slam Funk", ""),
        Spec("at-the-count.mp3", "at-the-count", "At The Count", "Broke For Free", "Slam Funk", ""),
    )
}
