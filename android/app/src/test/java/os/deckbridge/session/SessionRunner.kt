package os.deckbridge.session

import os.deckbridge.logic.RbCatalog

/** Passo fixo do modelo entre eventos: 20 ms, igual à granularidade do que o DeckPage atualiza. */
const val SESSION_TICK = 0.02

/**
 * Roda o roteiro no modelo. `onFrame` é chamado a cada `1/fps` segundos, depois de aplicar todos os eventos
 * até esse instante. Devolve o modelo no fim, com o registro de operações.
 */
fun runSession(catalog: RbCatalog, fps: Int, maxSeconds: Double = SessionScript.END, onFrame: (SessionModel, Int) -> Unit = { _, _ -> }): SessionModel {
    val model = SessionModel(catalog, doubleArrayOf(-222.0, -123.0))
    // Hot cues da análise do app (pad 1..4 = índices 0..3).
    for (deck in 0..1) {
        val cues = catalog.analyses[catalog.tracks[deck].id]?.hotCues.orEmpty().map { it.index to it.timeMs / 1000.0 }
        model.setHotCues(deck, cues)
    }
    model.load(0, 0.0)
    model.load(1, 0.0)
    val events = SessionScript.events()
    var next = 0
    val frames = (maxSeconds * fps).toInt()
    for (frame in 0..frames) {
        val frameTime = frame.toDouble() / fps
        while (next < events.size && events[next].t <= frameTime + 1e-9) {
            advanceTo(model, events[next].t)
            events[next].run(model)
            next += 1
        }
        advanceTo(model, frameTime)
        onFrame(model, frame)
    }
    return model
}

private fun advanceTo(model: SessionModel, time: Double) {
    while (model.t + SESSION_TICK < time) model.advance(SESSION_TICK)
    if (time > model.t) model.advance(time - model.t)
}
