package os.deckbridge.manager.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Os dois modos do app DeckBridge (ADR-0012): DJ (processo principal: motor, controladora, decks) e Biblioteca (processo
 * ":biblioteca": organizar, montar sets, exportar). Só um fica vivo de cada vez: ao entrar num, o processo do outro é
 * encerrado, e a memória e o processador voltam para quem está na frente. Este objeto é pequeno de propósito: o modo DJ
 * o usa sem carregar nada da biblioteca.
 */
object Modes {
    enum class Mode { DJ, LIBRARY }

    /** A classe da tela do modo DJ (no módulo do app; aqui pelo nome, para este módulo não depender dele). */
    const val DJ_ACTIVITY = "os.deckbridge.android.MainActivity"

    /** Pedido ao modo DJ: abrir a biblioteca do aparelho ao entrar. */
    const val EXTRA_OPEN_LOCAL = "os.deckbridge.ABRIR_BIBLIOTECA_DO_APARELHO"

    // Um arquivo, não SharedPreferences: os dois processos leem e gravam, e o SharedPreferences guarda cópia em memória
    // por processo (um não veria o que o outro gravou).
    private fun file(context: Context) = File(context.filesDir, "modo.txt")

    /** O modo usado da última vez (a entrada do app abre nele). Biblioteca na primeira vez. */
    fun last(context: Context): Mode = runCatching { Mode.valueOf(file(context).readText().trim()) }.getOrDefault(Mode.LIBRARY)

    fun remember(context: Context, mode: Mode) {
        runCatching { file(context).writeText(mode.name) }
    }

    /** A pasta da biblioteca do aparelho (no formato de um pendrive exportado). O modo DJ a lê; só a Biblioteca escreve. */
    fun libraryRoot(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "biblioteca/dados")

    /** Há uma biblioteca no aparelho para o modo DJ tocar. */
    fun hasLocalLibrary(context: Context): Boolean = File(libraryRoot(context), "PIONEER/rekordbox/export.pdb").isFile

    fun djIntent(context: Context, openLocal: Boolean = false): Intent =
        Intent().setClassName(context.packageName, DJ_ACTIVITY).putExtra(EXTRA_OPEN_LOCAL, openLocal).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun libraryIntent(context: Context): Intent =
        Intent(context, ManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * Sai do modo atual para [to]: abre a tela do outro modo e encerra este processo (o outro roda no dele). Quem chama
     * já gravou o que precisava (a Biblioteca grava a fila antes; o DJ não grava nada).
     */
    fun switchTo(from: Activity, to: Mode) {
        remember(from, to)
        val intent = if (to == Mode.DJ) djIntent(from, openLocal = true) else libraryIntent(from)
        from.startActivity(intent)
        from.finishAffinity()
        // O processo deste modo sai: a memória dele volta para o outro.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
