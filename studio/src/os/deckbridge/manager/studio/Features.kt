package os.deckbridge.manager.studio

/**
 * Recursos guardados para depois: o código fica no app, compilado e testado, mas a tela não mostra.
 *
 * [liveMode]: o AO VIVO da biblioteca (marcar a que está tocando, o pedido da pista e "guardar o que tocou"). Saiu da
 * tela em 2026-10-27, por decisão do dono: quem sabe o que está tocando é o app de performance, e ter os dois
 * duplicaria o conceito. Volta quando os dois apps trocarem o plano do set e o histórico (ver ADR-0011).
 */
object Features {
    var liveMode = false
}
