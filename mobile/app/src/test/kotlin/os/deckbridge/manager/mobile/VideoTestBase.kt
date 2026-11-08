package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * O que os vídeos de tela (a jornada, montar um set) têm em comum: cada toque é um toque de verdade na tela (Compose),
 * anotado em `taps.tsv` para o vídeo mostrar onde foi; `steps.tsv` tem as legendas; os quadros saem a 10 por segundo em
 * `frames`. A pasta de saída é a do teste ([frames] precisa ser definida antes de gravar).
 */
abstract class VideoTestBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    protected val frameMs = 100L
    protected var n = 0
    protected lateinit var frames: File
    protected val taps = StringBuilder()
    protected val steps = StringBuilder()

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Avança o relógio da tela (que aqui não anda sozinho: o cursor piscando nunca deixaria a tela "ociosa"). */
    protected fun tick(ms: Long = 120) {
        // Mudanças feitas daqui (fora da composição) só chegam à tela depois de avisadas.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
        idle()
        rule.waitForIdle()
    }

    /** Captura a tela inteira (com diálogos) e repete o quadro por [seconds]. */
    protected fun hold(seconds: Double) {
        tick()
        val first = File(frames, "%05d.png".format(n++))
        captureScreenRoboImage(first.path)
        repeat(((seconds * 1000 / frameMs).toInt() - 1).coerceAtLeast(0)) { first.copyTo(File(frames, "%05d.png".format(n++)), overwrite = true) }
    }

    protected fun step(text: String) = steps.append("$n\t$text\n")

    /** Toca no nó: marca o ponto (o vídeo desenha o toque nos quadros de antes) e clica. */
    protected fun tap(node: SemanticsNodeInteraction, before: Double = 0.6) {
        val b = node.fetchSemanticsNode().boundsInRoot
        taps.append("$n\t${b.center.x.toInt()}\t${b.center.y.toInt()}\n")
        hold(before)
        node.performClick()
        tick(300)
    }

    protected fun tapText(text: String, last: Boolean = true, substring: Boolean = false) {
        val all = rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes()
        check(all.isNotEmpty()) { "sem \"$text\" na tela" }
        tap(rule.onAllNodes(hasText(text, substring = substring))[if (last) all.lastIndex else 0])
    }

    /** O "+" da linha da faixa [title] na lista. */
    protected fun tapPlusOf(title: String) {
        val y = rule.onAllNodes(hasText(title)).fetchSemanticsNodes().first().boundsInRoot.center.y
        val pluses = rule.onAllNodes(hasText("+")).fetchSemanticsNodes()
        val i = pluses.indices.minByOrNull { kotlin.math.abs(pluses[it].boundsInRoot.center.y - y) }!!
        tap(rule.onAllNodes(hasText("+"))[i], before = 0.5)
    }

    /** Enquanto uma tarefa roda, um quadro a cada ~0,7 s de verdade, mostrado por 0,3 s (acelerado). */
    protected fun whileJob(app: ManagerApp, minimum: Double = 1.0) {
        var shown = 0.0
        // A importação roda fora do "job" (a tela fica livre), então espera as duas coisas.
        while (app.job != null || app.imports.running) {
            Thread.sleep(700)
            idle()
            hold(0.3)
            shown += 0.3
        }
        idle()
        if (shown < minimum) hold(minimum - shown)
    }
}
