package os.deckbridge.manager.importer

import java.io.File
import java.util.concurrent.TimeUnit
import os.deckbridge.logic.PcmWaves
import os.deckbridge.logic.TrackRead
import os.deckbridge.logic.analyzeTrack
import os.deckbridge.logic.wavesFromPcm
import os.deckbridge.manager.anlz.Cue

class ImportException(message: String) : Exception(message)

data class AudioInfo(
    val durationSec: Int,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
)

class MonoAudio(val samples: FloatArray, val rate: Int)

/** Decodificador da plataforma (no Android, o MediaCodec), usado quando não há ffmpeg. Devolve null se não souber ler. */
interface PlatformDecoder {
    fun probe(file: File): AudioInfo?
    fun mono(file: File): MonoAudio?

    /** Um trecho estéreo, em qualidade de escuta (para a prévia de transições). */
    fun segment(file: File, startMs: Int, lengthMs: Int): os.deckbridge.logic.DecodedPcm? = null
}

/**
 * Leitura de áudio para importar. WAV PCM (16, 24 ou 32 bits, ou float) é lido aqui. MP3, FLAC, AAC e o resto precisam do
 * `ffmpeg`/`ffprobe` do sistema, se houver. Nada é embutido: as ferramentas são chamadas como programas separados,
 * com argumentos em lista (sem shell).
 */
object AudioIo {
    private fun has(tool: String): Boolean = runCatching {
        ProcessBuilder(tool, "-version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    /** Quem decodifica MP3/FLAC/AAC onde não existe ffmpeg. Definido uma vez, na partida do app. */
    @Volatile var platform: PlatformDecoder? = null

    val ffmpeg: Boolean by lazy { has("ffmpeg") }
    val ffprobe: Boolean by lazy { has("ffprobe") }

    private fun stem(name: String) = name.substringBeforeLast('.')

    /**
     * Duração, taxa e etiquetas, com título e artista limpos ([os.deckbridge.manager.organize.TrackNames]): sem número de
     * faixa, sublinhados nem lixo de site, e "Artista - Título" separado quando as etiquetas não dizem.
     */
    fun probe(file: File): AudioInfo {
        val raw = rawProbe(file)
        val n = os.deckbridge.manager.organize.TrackNames.clean(raw.title, raw.artist, file.name)
        return raw.copy(title = n.title, artist = n.artist)
    }

    private fun rawProbe(file: File): AudioInfo {
        if (!file.isFile) throw ImportException("Não achei ${file.path}.")
        val size = file.length()
        var duration = 0.0
        var rate = 0
        var bitrate = 0
        var tags = mapOf<String, String>()
        if (ffprobe) {
            val out = run(listOf("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "format=duration,bit_rate:stream=sample_rate:format_tags=title,artist,album,genre,date,track:stream_tags=title,artist,album,genre,date,track", "-of", "default=noprint_wrappers=1", file.path))
            // As etiquetas podem vir do formato (MP3, FLAC, M4A) ou do fluxo de áudio (Ogg Vorbis): vale a primeira não vazia.
            val kv = HashMap<String, String>()
            for (line in out.lines().filter { '=' in it }) {
                val k = line.substringBefore('=').lowercase()
                val v = line.substringAfter('=').trim()
                if (v.isNotEmpty() && kv[k].isNullOrEmpty()) kv[k] = v
            }
            duration = kv["duration"]?.toDoubleOrNull() ?: 0.0
            rate = kv["sample_rate"]?.toIntOrNull() ?: 0
            bitrate = (kv["bit_rate"]?.toDoubleOrNull() ?: 0.0).div(1000).toInt()
            tags = kv.filterKeys { it.startsWith("tag:") }.mapKeys { it.key.removePrefix("tag:") }
        }
        if (duration <= 0.0 && !ffprobe) platform?.probe(file)?.let { return it }
        if (duration <= 0.0 && file.extension.lowercase() in setOf("wav")) {
            wavHeader(file)?.let { (r, ch, bits, frames) ->
                rate = r
                duration = frames.toDouble() / r
                bitrate = r * ch * bits / 1000
            }
        }
        if (duration <= 0.0) throw ImportException("Não consegui ler a duração de ${file.name}. Para MP3, FLAC e AAC o ffmpeg precisa estar instalado.")
        val name = stem(file.name)
        val split = name.split(" - ", limit = 2)
        return AudioInfo(
            durationSec = duration.toInt().coerceAtLeast(1),
            sampleRate = rate,
            bitrateKbps = bitrate,
            sizeBytes = size,
            title = tags["title"]?.takeIf { it.isNotBlank() } ?: (if (split.size == 2) split[1].trim() else name.trim()),
            artist = tags["artist"]?.takeIf { it.isNotBlank() } ?: (if (split.size == 2) split[0].trim() else ""),
            album = tags["album"].orEmpty(),
            genre = tags["genre"].orEmpty(),
            year = tags["date"]?.take(4)?.toIntOrNull() ?: 0,
            trackNumber = tags["track"]?.substringBefore('/')?.toIntOrNull() ?: 0,
        )
    }

    private fun run(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw ImportException("${cmd.first()} demorou demais.")
        }
        return out
    }

    /** (taxa, canais, bits, quadros) do WAV, ou null. */
    private fun wavHeader(file: File): List<Int>? {
        val b = file.inputStream().use { it.readNBytes(4096) }
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var at = 12
        var rate = 0
        var ch = 0
        var bits = 0
        while (at + 8 <= b.size) {
            val id = String(b, at, 4, Charsets.US_ASCII)
            val len = le32(b, at + 4)
            if (id == "fmt ") {
                ch = le16(b, at + 10)
                rate = le32(b, at + 12)
                bits = le16(b, at + 22)
            } else if (id == "data") {
                val frames = len.toLong().coerceAtMost(file.length() - at - 8) / (ch * bits / 8).coerceAtLeast(1)
                return listOf(rate, ch, bits, frames.toInt())
            }
            at += 8 + len + (len and 1)
            if (len < 0) return null
        }
        return null
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)

    /**
     * Um trecho estéreo de [lengthMs] a partir de [startMs], para ouvir (prévia de transições). WAV é lido aqui; o resto
     * vai pelo decodificador da plataforma ou pelo ffmpeg. Null se não deu para ler.
     */
    fun segment(file: File, startMs: Int, lengthMs: Int): os.deckbridge.logic.DecodedPcm? {
        if (file.extension.lowercase() == "wav") os.deckbridge.logic.decodeWavSegment(file.readBytes(), startMs / 1000.0, lengthMs / 1000.0)?.let { return it }
        platform?.segment(file, startMs, lengthMs)?.let { return it }
        if (!ffmpeg) return null
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0), "-t", "%.3f".format(java.util.Locale.ROOT, lengthMs / 1000.0), "-i", file.path, "-vn", "-ac", "2", "-ar", "44100", "-f", "s16le", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val raw = p.inputStream.readBytes()
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0 || raw.size < 4) return null
        val n = raw.size / 4
        val l = FloatArray(n) { (((raw[it * 4].toInt() and 0xFF) or (raw[it * 4 + 1].toInt() shl 8)).toShort()) / 32768f }
        val r = FloatArray(n) { (((raw[it * 4 + 2].toInt() and 0xFF) or (raw[it * 4 + 3].toInt() shl 8)).toShort()) / 32768f }
        return os.deckbridge.logic.DecodedPcm(l, r, 44100)
    }

    /** Áudio mono para a análise, até ~24 kHz. */
    fun mono(file: File): MonoAudio {
        if (file.extension.lowercase() == "wav") wavMono(file)?.let { return it }
        if (!ffmpeg) platform?.mono(file)?.let { return it }
        if (!ffmpeg) throw ImportException("Não consegui ler ${file.name}. Só WAV PCM é lido sem o ffmpeg; instale o ffmpeg para MP3, FLAC e AAC.")
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-vn", "-ac", "1", "-ar", "22050", "-f", "s16le", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val raw = p.inputStream.readBytes()
        if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0 || raw.size < 4) throw ImportException("O ffmpeg não conseguiu decodificar ${file.name}.")
        val n = raw.size / 2
        return MonoAudio(FloatArray(n) { (((raw[it * 2].toInt() and 0xFF) or (raw[it * 2 + 1].toInt() shl 8)).toShort()) / 32768f }, 22050)
    }

    private fun wavMono(file: File): MonoAudio? {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var at = 12
        var fmt = 0
        var ch = 0
        var rate = 0
        var bits = 0
        var dataAt = -1
        var dataLen = 0
        while (at + 8 <= b.size) {
            val id = String(b, at, 4, Charsets.US_ASCII)
            val len = le32(b, at + 4)
            if (len < 0) return null
            if (id == "fmt ") {
                fmt = le16(b, at + 8)
                ch = le16(b, at + 10)
                rate = le32(b, at + 12)
                bits = le16(b, at + 22)
                if (fmt == 0xFFFE && len >= 26) fmt = le16(b, at + 32)
            } else if (id == "data") {
                dataAt = at + 8
                dataLen = minOf(len, b.size - dataAt)
                break
            }
            at += 8 + len + (len and 1)
        }
        val ok = (fmt == 1 && bits in setOf(16, 24, 32)) || (fmt == 3 && bits == 32)
        if (dataAt < 0 || ch !in 1..8 || rate < 8000 || !ok) return null
        val bytes = bits / 8
        val frames = dataLen / (bytes * ch)
        val factor = (rate / 22050).coerceAtLeast(1)
        val outLen = frames / factor
        val out = FloatArray(outLen)
        for (o in 0 until outLen) {
            var acc = 0.0
            for (k in 0 until factor) {
                val frame = o * factor + k
                var mix = 0.0
                for (c in 0 until ch) {
                    val p = dataAt + (frame * ch + c) * bytes
                    mix += when {
                        fmt == 3 -> java.lang.Float.intBitsToFloat(le32(b, p)).toDouble()
                        bits == 16 -> (le16(b, p).toShort()) / 32768.0
                        bits == 24 -> (((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)) / 8388608.0)
                        else -> le32(b, p) / 2147483648.0
                    }
                }
                acc += mix / ch
            }
            out[o] = (acc / factor).toFloat()
        }
        return MonoAudio(out, rate / factor)
    }
}

/** Faixa lida e analisada, pronta para virar uma edição de importação. Nada foi escrito no pendrive ainda. */
class PreparedTrack(
    val source: File,
    val info: AudioInfo,
    val read: TrackRead,
    val waves: PcmWaves,
    val suggestedCues: List<Cue>,
    /** Impressão digital do áudio, para não importar a mesma música com outro nome. */
    val print: os.deckbridge.logic.AudioPrint? = null,
)

object ImportPrep {
    private val colors = listOf(0xFF3B3B, 0x2EC5FF, 0x3DDC6A, 0xB44DFF, 0xFF8A1E)

    /** Lê, decodifica e analisa com o analisador do app (BPM, tom, grade, ondas, estrutura). Pesado: rode fora da interface. */
    fun prepare(file: File): PreparedTrack {
        val info = AudioIo.probe(file)
        val mono = AudioIo.mono(file)
        if (mono.samples.size < mono.rate * 2) throw ImportException("${file.name} é curto demais para analisar (menos de 2 s).")
        val read = analyzeTrack(mono.samples, mono.rate)
        val waves = wavesFromPcm(mono.samples, mono.rate)
        val s = read.structure
        val points = listOf(os.deckbridge.logic.Section.INTRO to s.introSec, os.deckbridge.logic.Section.BUILD to s.buildSec, os.deckbridge.logic.Section.DROP to s.dropSec, os.deckbridge.logic.Section.BREAK to s.breakSec, os.deckbridge.logic.Section.OUTRO to s.outroSec)
        val limit = info.durationSec * 1000
        val hot = points.mapIndexedNotNull { n, (name, sec) ->
            val ms = (sec * 1000).toInt()
            if (sec <= 0.0 || ms >= limit) null else Cue(n + 1, ms, comment = name, rgb = colors[n])
        }.distinctBy { it.timeMs }.mapIndexed { n, c -> c.copy(hot = n + 1, rgb = colors[n % colors.size]) }
        val memory = hot.firstOrNull()?.let { listOf(Cue(0, it.timeMs, comment = it.comment, rgb = null)) }.orEmpty()
        return PreparedTrack(file, info, read, waves, hot + memory, os.deckbridge.logic.AudioPrint.of(mono.samples, mono.rate))
    }
}
