package os.deckbridge.android

import android.content.Context

/**
 * Guarda o que está nos decks (arquivo, posição e pitch) a cada poucos segundos. Se o app cair ou o sistema o
 * matar no meio do set, ele reabre com as mesmas faixas, paradas no mesmo ponto. Só preferências locais; o USB
 * não é escrito.
 */
object SessionStore {
    data class Deck(val path: String, val position: Float, val pitch: Float)

    private fun prefs(context: Context) = context.getSharedPreferences("deckbridge-session", Context.MODE_PRIVATE)

    fun save(context: Context, decks: List<Deck?>) {
        val edit = prefs(context).edit()
        decks.forEachIndexed { index, deck ->
            if (deck == null) {
                edit.remove("path$index")
            } else {
                edit.putString("path$index", deck.path)
                edit.putFloat("position$index", deck.position)
                edit.putFloat("pitch$index", deck.pitch)
            }
        }
        edit.apply()
    }

    fun read(context: Context): List<Deck?> = (0..1).map { index ->
        val p = prefs(context)
        val path = p.getString("path$index", null) ?: return@map null
        Deck(path, p.getFloat("position$index", 0f), p.getFloat("pitch$index", 1f))
    }
}
