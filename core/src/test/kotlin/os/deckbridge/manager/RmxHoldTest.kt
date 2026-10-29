package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.RMX_HOLD_MS
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.rmxKnob
import os.deckbridge.logic.rmxKnobLabel
import os.deckbridge.logic.rmxKnobValue
import os.deckbridge.logic.rmxRiseLeft
import os.deckbridge.logic.rmxRiseStep
import os.deckbridge.logic.rmxScenePress
import os.deckbridge.logic.rmxToggleHold
import os.deckbridge.logic.rmxXPadDown
import os.deckbridge.logic.reduceRmxPad
import os.deckbridge.logic.rmxXPadUp

/** O X-PAD segurado e o HOLD do RMX (como no RMX-1000): segurar toca e acelera, soltar solta, HOLD mantém. */
class RmxHoldTest {
    private val slow = RMX_HOLD_MS + 200

    @Test fun `tocar liga e desliga como antes`() {
        var s = RmxState(panel = true, xBeat = 2)
        // Primeiro toque: liga e fica ligado.
        s = rmxXPadUp(rmxXPadDown(s, 0), 0, heldMs = 80, wasOn = false)
        assertTrue(s.xOn && s.xMode == 0 && !s.xHeld)
        // Segundo toque no mesmo pad: desliga.
        s = rmxXPadUp(rmxXPadDown(s, 0), 0, heldMs = 80, wasOn = true)
        assertFalse(s.xOn)
    }

    @Test fun `segurar toca so enquanto o dedo esta e o tempo volta ao de antes`() {
        var s = RmxState(panel = true, xBeat = 2)
        s = rmxXPadDown(s, 0)
        assertTrue(s.xOn && s.xHeld)
        // Segurando, o roll acelera: 1/4 → 1/8 → 1/16 e para no mais rápido.
        assertEquals(2, rmxRiseLeft(s))
        s = rmxRiseStep(s)
        assertEquals(1, s.xBeat)
        s = rmxRiseStep(s)
        assertEquals(0, s.xBeat)
        assertEquals(0, rmxRiseLeft(s))
        assertEquals(0, rmxRiseStep(s).xBeat, "não passa de 1/16")
        // Soltou: desliga e volta ao 1/4 de antes.
        s = rmxXPadUp(s, 0, heldMs = slow, wasOn = false)
        assertFalse(s.xOn || s.xHeld)
        assertEquals(2, s.xBeat)
    }

    @Test fun `HOLD mantem o roll andando depois de soltar e desligar o HOLD solta`() {
        var s = rmxToggleHold(RmxState(panel = true, xBeat = 2))
        assertTrue(s.latch)
        s = rmxXPadUp(rmxXPadDown(s, 0), 0, heldMs = slow, wasOn = false)
        // Soltou o dedo, mas o HOLD segura: segue ligado e continua acelerando.
        assertTrue(s.xOn && s.xLatched && !s.xHeld)
        assertEquals(2, rmxRiseLeft(s))
        assertEquals(1, rmxRiseStep(s).xBeat)
        // Desligar o HOLD solta o X-PAD e devolve o tempo.
        s = rmxToggleHold(s)
        assertFalse(s.latch || s.xOn || s.xLatched)
        assertEquals(2, s.xBeat)
    }

    @Test fun `HOLD segura a cena depois de soltar o pad`() {
        var s = rmxScenePress(RmxState(panel = true), 3, down = true)
        assertTrue(s.holding && s.scene == 3)
        // Sem HOLD, soltar solta.
        assertFalse(rmxScenePress(s, 3, down = false).holding)
        // Com HOLD, soltar mantém; desligar o HOLD solta.
        s = rmxToggleHold(s)
        s = rmxScenePress(s, 3, down = false)
        assertTrue(s.holding, "HOLD segura a subida")
        assertFalse(rmxToggleHold(s).holding)
    }

    @Test fun `o DRUM nao acelera por aqui porque a bateria ja sobe sozinha`() {
        val s = rmxXPadDown(RmxState(panel = true), 2)
        assertTrue(s.xOn && s.drumBuild >= 0.2f)
        assertEquals(0, rmxRiseLeft(s))
        assertEquals(s.xBeat, rmxRiseStep(s).xBeat)
    }

    @Test fun `o anel e o knob do roll e da cena e assumir para a subida automatica`() {
        // Roll segurado: o knob manda na velocidade (do 1/2 em baixo ao 1/16 em cima) e para a aceleração sozinha.
        var s = rmxXPadDown(RmxState(panel = true, xBeat = 3), 0)
        assertEquals("1/2", rmxKnobLabel(s))
        s = rmxKnob(s, 0.6f)
        assertEquals(1, s.xBeat, "três quintos do curso: 1/8")
        assertEquals("1/8", rmxKnobLabel(s))
        assertEquals(0, rmxRiseLeft(s), "com o knob na mão a aceleração automática para")
        assertEquals(0, rmxKnob(s, 1f).xBeat)
        assertEquals(3, rmxKnob(s, 0f).xBeat)
        assertEquals(1f, rmxKnobValue(rmxKnob(s, 1f)))
        // Soltar devolve o TEMPO e o controle ao automático.
        val off = rmxXPadUp(s, 0, heldMs = slow, wasOn = false)
        assertFalse(off.manual)
        assertEquals(3, off.xBeat)
        // Cena segura: o knob é a intensidade.
        var c = rmxScenePress(RmxState(panel = true), 1, down = true)
        c = rmxKnob(c, 0.7f)
        assertEquals(0.7f, c.intensity)
        assertTrue(c.manual)
        assertEquals("70", rmxKnobLabel(c))
        // Sem nada segurando, o knob não faz nada.
        assertEquals(RmxState(panel = true), rmxKnob(RmxState(panel = true), 0.9f))
    }

    private fun pad(state: RmxState, index: Int, down: Boolean, at: Long) =
        reduceRmxPad(state, PerformanceAction(0, PerformanceAction.Kind.RMX, hotIndex = index, slider = if (down) 127.0 else 0.0), nowMs = at)

    @Test fun `os pads do X-PAD da controladora tocam, seguram e aceleram`() {
        // Toque rápido no ROLL (pad 4 do deck 1): liga; outro toque desliga — como antes.
        var s = RmxState(panel = true, xBeat = 2)
        s = pad(pad(s, 4, true, 1_000), 4, false, 1_100)
        assertTrue(s.xOn && s.xMode == 0)
        s = pad(pad(s, 4, true, 2_000), 4, false, 2_100)
        assertFalse(s.xOn)
        // Segurar o pad: toca enquanto aperta e acelera; soltar desliga e volta o tempo.
        s = pad(s, 4, true, 3_000)
        assertTrue(s.xOn && s.xHeld)
        s = rmxRiseStep(s)
        assertEquals(1, s.xBeat)
        s = pad(s, 4, false, 3_000 + RMX_HOLD_MS + 500)
        assertFalse(s.xOn)
        assertEquals(2, s.xBeat)
        // Com HOLD (o botão BEAT FX ON/OFF da controladora), soltar o pad mantém o roll.
        s = rmxToggleHold(s)
        s = pad(pad(s, 4, true, 5_000), 4, false, 5_000 + RMX_HOLD_MS + 500)
        assertTrue(s.xOn && s.xLatched)
    }
}
