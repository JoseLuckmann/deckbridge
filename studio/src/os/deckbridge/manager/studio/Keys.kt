package os.deckbridge.manager.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Atalhos de teclado (no computador). Quem usa muito um programa raramente passa do mouse para as teclas, mesmo sendo
 * mais rápido (Lane et al. 2005), e aprende os atalhos bem mais depressa quando a tecla está escrita onde se clica
 * (Grossman et al. 2007). Por isso cada atalho aparece na tela ao lado do botão (só com teclado e mouse, ver
 * [StudioHost.touch]) e `?` abre a lista toda.
 */
object StudioKeys {
    /** Um campo de texto tem o foco: as teclas são texto, não atalhos (só Ctrl+Z e Esc continuam). */
    var typing by mutableStateOf(false)

    /** A lista de atalhos está aberta. */
    var helpOpen by mutableStateOf(false)

    /** Tecla e o que faz, na ordem da lista. */
    val list = listOf(
        "Espaço" to "Tocar / parar (a faixa escolhida, a da triagem ou a troca no MIX)",
        "← →" to "MIX: um compasso para trás / para frente. MARCAR: anterior / pular",
        ",  ." to "MIX: atrasa / adianta a que entra 5 ms (encaixe)",
        "[  ]" to "MIX: a que entra uma batida para trás / para frente",
        "L" to "MIX: repetir a troca (liga / desliga)",
        "Enter" to "MIX: guardar a sua mixagem",
        "1 – 5" to "MARCAR: dá a nota e passa para a próxima",
        "0" to "MARCAR: tira a nota",
        "Ctrl+Z" to "Desfazer",
        "Ctrl+Shift+Z  ou  Ctrl+Y" to "Refazer",
        "Esc" to "Voltar / fechar",
        "?  ou  F1" to "Esta lista",
    )

    /** Trata uma tecla apertada; true se ela foi usada. */
    fun handle(app: StudioHost, e: KeyEvent): Boolean =
        e.type == KeyEventType.KeyDown && press(app, e.key, ctrl = e.isCtrlPressed || e.isMetaPressed, shift = e.isShiftPressed)

    /** A tecla [key] (com Ctrl/Cmd e Shift); true se foi usada. */
    fun press(app: StudioHost, key: Key, ctrl: Boolean = false, shift: Boolean = false): Boolean {
        val m = app.model
        if (ctrl && (key == Key.Z && shift || key == Key.Y)) return m.redo()
        if (ctrl && key == Key.Z) {
            if (!m.dirty) return false
            m.undo()
            app.notice = null
            return true
        }
        if (ctrl || typing) return false
        if (key == Key.F1 || key == Key.Slash && shift) {
            helpOpen = !helpOpen
            return true
        }
        val v = m.view ?: return false
        // Triagem aberta: nota pelos números, andar pelas setas, ouvir no espaço.
        val ids = app.triage
        if (ids != null && app.triageAt < ids.size) {
            val t = v.track(ids[app.triageAt]) ?: return false
            val digit = digitOf(key)
            when {
                digit != null && digit in 0..5 -> {
                    m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = digit)), "Nota $digit em \"${t.title}\"")
                    if (digit > 0) {
                        app.preview.stop()
                        app.triageAt = app.triageAt + 1
                    }
                }
                key == Key.DirectionRight -> { app.preview.stop(); app.triageAt = app.triageAt + 1 }
                key == Key.DirectionLeft -> { app.preview.stop(); app.triageAt = (app.triageAt - 1).coerceAtLeast(0) }
                key == Key.Spacebar -> app.preview.toggle(t)
                else -> return false
            }
            return true
        }
        // MIX aberto: tocar a troca e andar de compasso em compasso.
        val desk = app.sets
        if (desk.mixing != null) {
            val bar = desk.mixPreview?.barMsA?.toInt() ?: return key == Key.Spacebar
            when (key) {
                Key.Spacebar -> desk.playMix()
                Key.DirectionRight -> desk.seekMix(desk.cursorMs + bar)
                Key.DirectionLeft -> desk.seekMix(desk.cursorMs - bar)
                Key.Comma -> desk.nudge(-5)
                Key.Period -> desk.nudge(5)
                Key.LeftBracket -> desk.shiftBeat(-1)
                Key.RightBracket -> desk.shiftBeat(1)
                Key.L -> desk.toggleLoop()
                Key.Enter -> if (desk.takeChanged) desk.keepTake() else return false
                else -> return false
            }
            return true
        }
        // Na biblioteca: uma faixa escolhida toca no espaço.
        if (key == Key.Spacebar && desk.playlistId == null) {
            val t = m.selection.singleOrNull()?.let { v.track(it) } ?: return false
            app.preview.toggle(t)
            return true
        }
        return false
    }

    private fun digitOf(k: Key): Int? = when (k) {
        Key.Zero, Key.NumPad0 -> 0
        Key.One, Key.NumPad1 -> 1
        Key.Two, Key.NumPad2 -> 2
        Key.Three, Key.NumPad3 -> 3
        Key.Four, Key.NumPad4 -> 4
        Key.Five, Key.NumPad5 -> 5
        else -> null
    }
}

/** Campo de texto: enquanto tem o foco, as teclas são texto e os atalhos de uma tecla ficam desligados. */
fun Modifier.typingField(): Modifier = onFocusChanged { StudioKeys.typing = it.isFocused }

/** A lista de atalhos ([StudioKeys.list]), aberta com `?`: tecla à esquerda, o que faz à direita. */
@androidx.compose.runtime.Composable
fun KeysSheet() {
    if (!StudioKeys.helpOpen) return
    ManagerTheme {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { StudioKeys.helpOpen = false },
            title = { androidx.compose.material3.Text("Atalhos do teclado", color = Palette.text) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    StudioKeys.list.forEach { (k, what) ->
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.Text(k, color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.width(190.dp))
                            androidx.compose.material3.Text(what, color = Palette.text, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { StudioKeys.helpOpen = false }) { androidx.compose.material3.Text("OK") } },
            containerColor = Palette.surfaceHigh,
            shape = RbShape,
        )
    }
}

/** A tecla de um atalho escrita embaixo dos botões (só com teclado e mouse). */
@androidx.compose.runtime.Composable
fun KeyHint(app: StudioHost, text: String) {
    if (app.touch) return
    androidx.compose.material3.Text(
        text, color = Palette.mutedBright, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
