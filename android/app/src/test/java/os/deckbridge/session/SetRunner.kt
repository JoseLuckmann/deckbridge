package os.deckbridge.session

import java.io.File
import os.deckbridge.logic.RbCatalog

/** Pasta do set (SESSION_DIR): in/tN.wav para a análise, src/tN.wav (48 kHz) para o áudio e off-tN.json dos desvios. */
object SetAssets {
    /** Ordem do set, por título de arquivo: BPM em subida suave e tons vizinhos onde der. */
    val order: List<String> = (System.getenv("SET_ORDER") ?: "t5,t4,t6,t1,t2,t3").split(',')

    /** Duração real (s) de cada faixa do catálogo, lida do WAV de 48 kHz estéreo de 16 bits. */
    fun durations(catalog: RbCatalog): DoubleArray = DoubleArray(catalog.tracks.size) { index ->
        val file = File(SessionAssets.dir, "src/${catalog.tracks[index].title}.wav")
        if (!file.isFile) 0.0 else (file.length() - 44).toDouble() / (48000.0 * 4.0)
    }

    fun orderIndices(catalog: RbCatalog): List<Int> = order.map { title -> catalog.tracks.indexOfFirst { it.title == title } }
}

/**
 * Roda o set inteiro em passos de 20 ms. O DJ automático planeja quando a agenda esvazia; os quadros saem em
 * `fps` (múltiplo exato do passo). Mesma sequência a cada execução: o ops.tsv e os quadros vêm da mesma simulação.
 */
fun runSet(catalog: RbCatalog, fps: Int, stopAt: Double = Double.MAX_VALUE, onFrame: (SetModel, Int) -> Unit = { _, _ -> }): Pair<SetModel, SetAgent> {
    val model = SetModel(catalog, SetAssets.durations(catalog))
    val agent = SetAgent(model, SetAssets.orderIndices(catalog), SessionAssets.dir)
    var frame = 0
    var tick = 0L
    while (true) {
        val time = tick * SESSION_TICK
        model.runUntil(time)
        agent.step()
        while (frame.toDouble() / fps <= time + 1e-9) {
            onFrame(model, frame)
            frame += 1
        }
        if (agent.finishedAt >= 0.0 && time >= agent.finishedAt) break
        if (time >= stopAt) break
        tick += 1
        check(tick < 400_000L) { "o set não terminou" }
    }
    return model to agent
}
