package os.deckbridge.logic

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pendrive estragado não pode derrubar o app: mutações (bits trocados, cortes, campos de tamanho gigantes, lixo) em
 * export.pdb, exportExt.pdb, ANLZ, WAV e capas passam pelos parsers e pela carga da biblioteca inteira. E a carga
 * (rekordbox e pasta solta) não muda nem cria nenhum arquivo no volume.
 */
class LibraryFuzzTest {
    private val base = SyntheticRekordbox.volume()

    private fun mutate(bytes: ByteArray, rng: Random): ByteArray {
        if (bytes.isEmpty()) return bytes
        return when (rng.nextInt(6)) {
            0 -> bytes.copyOf().also { b -> repeat(rng.nextInt(1, 32)) { val i = rng.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl rng.nextInt(8))).toByte() } }
            1 -> bytes.copyOf(rng.nextInt(bytes.size))
            2 -> bytes.copyOf().also { b ->
                // Campo de 32 bits com valor enorme ou negativo: tamanhos, contagens e deslocamentos.
                repeat(rng.nextInt(1, 6)) {
                    val i = rng.nextInt((b.size - 4).coerceAtLeast(1))
                    val v = listOf(-1, Int.MAX_VALUE, 0x7FFF0000, 0, rng.nextInt())[rng.nextInt(5)]
                    if (i + 4 <= b.size) b.putU32le(i, v)
                }
            }
            3 -> bytes.copyOf().also { b -> val from = rng.nextInt(b.size); for (i in from until minOf(b.size, from + rng.nextInt(1, 256))) b[i] = rng.nextInt(256).toByte() }
            4 -> ByteArray(rng.nextInt(0, 64)) { rng.nextInt(256).toByte() }
            else -> bytes + ByteArray(rng.nextInt(1, 512)) { rng.nextInt(256).toByte() }
        }
    }

    private fun quiet(what: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            throw AssertionError("$what lançou ${error::class.simpleName}: ${error.message}", error)
        }
    }

    @Test
    fun corruptedFilesNeverThrowFromParsers() {
        val rng = Random(2026)
        val keys = base.files.keys.toList()
        val anlz = keys.filter { it.contains("USBANLZ") }
        val wavs = keys.filter { it.endsWith(".wav", ignoreCase = true) }
        assertTrue("volume sintético com ANLZ (${anlz.size}) e WAV (${wavs.size})", anlz.size >= 3 && wavs.isNotEmpty())
        val pdb = base.files.getValue("PIONEER/rekordbox/export.pdb")
        val ext = base.files.getValue("PIONEER/rekordbox/exportExt.pdb")
        val started = System.nanoTime()
        repeat(600) { i ->
            val m = mutate(pdb, rng)
            quiet("parseExportPdb #$i") { parseExportPdb(m) }
            quiet("parseExportExt #$i") { parseExportExt(mutate(ext, rng)) }
            val a = base.files.getValue(anlz[rng.nextInt(anlz.size)])
            quiet("parseAnlz #$i") { parseAnlz(mutate(a, rng)) }
            run {
                val w = mutate(base.files.getValue(wavs[rng.nextInt(wavs.size)]), rng)
                quiet("decodeWav #$i") { decodeWav(w) }
                quiet("decodeWavSegment #$i") { decodeWavSegment(w, rng.nextDouble(-5.0, 600.0), rng.nextDouble(-1.0, 60.0)) }
                quiet("decodeWavMono #$i") { decodeWavMono(w) }
            }
        }
        // A biblioteca inteira com vários arquivos estragados de uma vez.
        repeat(150) { i ->
            val files = base.files.mapValues { (_, bytes) -> if (rng.nextInt(4) == 0) mutate(bytes, rng) else bytes }
            quiet("loadRekordbox #$i") { loadRekordbox(MemoryVolume(files, "fuzz", simulated = true)) }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("fuzz em $seconds s (parser preso em laço?)", seconds < 240)
    }

    private fun snapshot(root: File): Map<String, String> {
        val digest = { f: File -> MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } }
        return root.walkTopDown().filter { it != root }.associate { f ->
            root.toPath().relativize(f.toPath()).toString() to if (f.isFile) "${f.length()}:${f.lastModified()}:${digest(f)}" else "dir:${f.lastModified()}"
        }
    }

    @Test
    fun loadingNeverChangesOrCreatesFilesOnTheVolume() {
        val root = Files.createTempDirectory("rb-readonly").toFile()
        for ((path, bytes) in base.files) File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        // Uma faixa solta também, para a carga de pasta comum.
        val rate = 22050
        File(root, "Solta.wav").writeBytes(encodeWavMono16(FloatArray(rate * 3) { (0.3 * kotlin.math.sin(it * 0.05)).toFloat() }, rate))
        val before = snapshot(root)
        // Como no pendrive de verdade: nada gravável. Se algum código tentar escrever, o SO recusa e o teste vê.
        root.walkTopDown().forEach { it.setWritable(false, false) }
        try {
            val catalog = loadRekordbox(FolderVolume(root, "pendrive"))
            assertEquals(null, catalog.error)
            assertTrue(catalog.tracks.isNotEmpty())
            val cache = Files.createTempDirectory("rb-cache").toFile()
            val plain = loadPlainFolder(FolderVolume(root, "pendrive"), cache, { _, bytes -> decodeWavMono(bytes)?.let { it.samples to it.rate } })
            assertTrue(plain.tracks.isNotEmpty())
            // O cache ficou fora do volume.
            assertFalse(cache.canonicalPath.startsWith(root.canonicalPath))
        } finally {
            root.walkTopDown().forEach { it.setWritable(true, false) }
        }
        assertEquals("o volume não pode mudar", before, snapshot(root))
        root.deleteRecursively()
    }

    @Test
    fun exportToTheLibraryStickIsRefused() {
        val libs = listOf("com.android.externalstorage.documents" to "1A2B-3C4D:")
        assertTrue(exportTargetOnLibraryVolume("com.android.externalstorage.documents", "1A2B-3C4D:sets/deckbridge-set.csv", libs))
        assertTrue(exportTargetOnLibraryVolume("com.android.externalstorage.documents", "1A2B-3C4D:deckbridge-set.m3u8", libs))
        assertFalse(exportTargetOnLibraryVolume("com.android.externalstorage.documents", "primary:Download/deckbridge-set.csv", libs))
        assertFalse(exportTargetOnLibraryVolume("com.android.providers.downloads.documents", "1A2B-3C4D:x.csv", libs))
        assertFalse(exportTargetOnLibraryVolume("com.android.externalstorage.documents", "raw-id-sem-volume", libs))
    }
}
