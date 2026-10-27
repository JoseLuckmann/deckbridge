package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * O guia: os quatro passos do trabalho em quadrados (feito ✓, o de agora em azul, os próximos apagados), uma palavra em
 * cada. Tocar num passo leva para onde ele se faz. Some quando tudo foi feito ou quando a pessoa fecha (✕).
 */
@Composable
fun GuideStrip(app: ManagerApp, actions: Actions) {
    if (app.guideHidden) return
    val v = app.model.view ?: return
    val now = app.step() ?: return
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(Palette.surface).border(BorderStroke(0.5.dp, Palette.line)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuideStep.values().forEachIndexed { n, s ->
            val done = s.done(app, v)
            val current = s == now
            if (n > 0) Box(Modifier.weight(0.4f).height(2.dp).background(if (done || current) Palette.blue else Palette.line))
            Column(
                Modifier.clickable { go(app, actions, s) }.padding(horizontal = 4.dp, vertical = 4.dp).semantics { contentDescription = "Passo ${n + 1}: ${s.label}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StepMark(n + 1, done, current)
                Text(s.label, color = if (current) Palette.text else Palette.muted, fontSize = 10.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
        TextButton(onClick = { app.hideGuide() }, modifier = Modifier.width(44.dp).semantics { contentDescription = "Fechar o guia" }) {
            Text("✕", color = Palette.muted, fontSize = 13.sp)
        }
    }
}

/** Leva para o passo [s]: importar (ou analisar o que falta), marcar, playlists ou pendrive. */
fun go(app: ManagerApp, actions: Actions, s: GuideStep) {
    val v = app.model.view
    when (s) {
        GuideStep.IMPORT -> if (v == null || v.tracks.isEmpty()) actions.pickAudio() else if (!s.done(app, v)) app.analyzeAll() else app.tab = Tab.LIBRARY
        GuideStep.RATE -> app.startTriage()
        GuideStep.PLAYLIST -> app.tab = Tab.PLAYLISTS
        GuideStep.USB -> app.tab = Tab.USB
    }
}

/** O marcador de um passo: o número (ou ✓ se feito), em azul quando é o de agora. */
@Composable
fun StepMark(n: Int, done: Boolean, current: Boolean) {
    Box(
        Modifier.size(26.dp).clip(RbShape)
            .background(if (done) Palette.keyFill else if (current) Palette.blueActiveFill else Palette.surfaceHigh)
            .border(BorderStroke(if (current) 2.dp else 1.dp, if (done) Palette.keyInk else if (current) Palette.blue else Palette.line), RbShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else "$n", color = if (done) Palette.keyInk else if (current) Palette.text else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
