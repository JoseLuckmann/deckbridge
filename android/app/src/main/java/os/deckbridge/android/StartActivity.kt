package os.deckbridge.android

import android.app.Activity
import android.os.Bundle
import os.deckbridge.manager.mobile.Modes

/**
 * A entrada do app (o ícone): sem tela, abre o modo usado da última vez, sem carregar o outro (ADR-0012). Roda no
 * processo principal, que é o do modo DJ; a Biblioteca abre no processo dela.
 */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = when (Modes.last(this)) {
            Modes.Mode.DJ -> Modes.djIntent(this, openLocal = true)
            Modes.Mode.LIBRARY -> Modes.libraryIntent(this)
        }
        startActivity(intent)
        finish()
    }
}
