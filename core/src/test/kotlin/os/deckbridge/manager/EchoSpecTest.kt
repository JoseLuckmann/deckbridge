package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import os.deckbridge.manager.mix.EchoSpec

/**
 * Um conceito, uma conta: o eco da prévia do MIX (Kotlin) tem de seguir o ECHO do motor de performance (C++). Lê o
 * `dsp.h` e confere que as fórmulas usadas em [EchoSpec] são as que estão lá; se alguém mudar um lado, este teste avisa.
 */
class EchoSpecTest {
    @Test fun `o eco da previa usa as contas do motor`() {
        val dsp = listOf(File("../android/app/src/main/cpp/dsp.h"), File("android/app/src/main/cpp/dsp.h")).first { it.isFile }.readText()
        assertTrue("const float fbTarget = 0.16f + 0.5f * amount;" in dsp, "realimentação do ECHO mudou no motor")
        assertTrue("const float wetTarget = 0.1f + 0.58f * amount;" in dsp, "mistura do ECHO mudou no motor")
        assertTrue("std::min(0.82f, fbTarget)" in dsp, "teto da realimentação mudou no motor")
        assertTrue(kotlin.math.abs(EchoSpec.feedback(0.5f) - 0.41f) < 1e-6f)
        assertTrue(kotlin.math.abs(EchoSpec.wet(0.5f) - 0.39f) < 1e-6f)
        assertTrue(EchoSpec.feedback(1f) <= 0.82f)
    }
}
