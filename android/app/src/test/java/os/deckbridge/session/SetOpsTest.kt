package os.deckbridge.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SetOpsTest {
    @Test
    fun writeSetOps() {
        assumeTrue(SessionAssets.enabled() && File(SessionAssets.dir, "src/t1.wav").isFile && System.getenv("SET") == "1")
        val catalog = SessionAssets.catalog()
        val (model, agent) = runSet(catalog, 10)
        File(SessionAssets.dir, "ops.tsv").writeText(model.finish())
        val report = buildString {
            appendLine("duração do set: %.1f s (%.1f min)".format(model.t, model.t / 60.0))
            agent.notes.forEach { appendLine(it) }
            agent.checks.forEach { appendLine("fase %s em %.1f s: %+.1f ms".format(it.label, it.time, it.milliseconds)) }
        }
        File(SessionAssets.dir, "checks.txt").writeText(report)
        println(report)
        assertTrue("sem transições", agent.checks.size == SetAssets.order.size - 1)
        agent.checks.forEach { assertTrue("fase ${it.label} ${it.milliseconds} ms", kotlin.math.abs(it.milliseconds) < 12.0) }
    }
}
