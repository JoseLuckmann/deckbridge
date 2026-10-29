package os.deckbridge.manager.mobile

import android.content.Context
import java.io.File

/**
 * O registro da última queda do app: quando algo derruba o processo, a pilha do erro, a thread, a versão e a memória vão
 * para um arquivo no aparelho (só no aparelho; nada é enviado). No modo desenvolvedor, o MENU mostra e copia. Assim uma
 * queda que só acontece no telefone do DJ deixa rastro para corrigir.
 */
object CrashLog {
    @Volatile private var installed = false

    fun file(context: Context) = File(File(context.filesDir, "quedas"), "ultima.txt")

    /** Liga o registro neste processo (uma vez). A queda segue para o Android depois de anotada, como sempre. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    internal fun write(context: Context, thread: Thread, e: Throwable) {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val text = buildString {
            appendLine("Quando: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()))
            appendLine("Versão: " + version(context))
            appendLine("Aparelho: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Thread: ${thread.name}")
            appendLine("Memória: ${(rt.totalMemory() - rt.freeMemory()) / mb} MB em uso de ${rt.maxMemory() / mb} MB")
            appendLine()
            append(e.stackTraceToString())
        }
        file(context).apply { parentFile?.mkdirs() }.writeText(text)
    }

    /** O texto da última queda, ou null se não houve nenhuma (ou foi apagada). */
    fun last(context: Context): String? = file(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) { file(context).delete() }

    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
