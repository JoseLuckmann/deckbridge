package os.deckbridge.manager.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.mix.SetTempo
import os.deckbridge.manager.smart.Smart

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** A cor de cada caminho: a linha principal em azul, os ramos nas cores de destaque do player. */
private fun routeColor(n: Int): Color =
    if (n == 0) Palette.blue else listOf(Palette.orange, Palette.keyInk, Palette.trackColors[5], Palette.trackColors[7], Palette.trackColors[0])[(n - 1) % 5]

/**
 * O mapa do set: o andamento (master) ao longo do tempo da linha principal e de cada ramo — onde sai, por onde passa e onde
 * volta — e a lista dos caminhos com duração, BPM de início, pico e fim e avisos. Tocar num caminho mostra as faixas dele
 * com a hora em que entram; ABRIR leva para a linha (a principal ou a do ramo).
 */
@Composable
fun SetMapScreen(app: StudioHost) {
    val desk = app.sets
    val v = app.model.view ?: return
    val root = desk.rootId() ?: return
    val name = v.playlists.firstOrNull { it.id == root }?.name.orEmpty()
    val routes = remember(v, desk.branches, desk.masterFixed, root) { desk.routes() }
    // Começa no caminho que está aberto (o ramo, se a tela de trás é a de um ramo).
    var picked by remember { mutableStateOf(routes.indexOfFirst { it.route.branch?.playlistId == desk.playlistId }.coerceAtLeast(0)) }
    BackHandler { desk.mapOpen = false }
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { desk.mapOpen = false }) { Text("‹", fontSize = 22.sp) }
            Text("MAPA", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(
                if (routes.size == 1) "sem ramos" else "${routes.size} caminhos", color = Palette.muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        if (routes.isEmpty() || routes[0].route.ids.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Playlist vazia", color = Palette.muted, fontSize = 14.sp) }
            return@Column
        }
        RouteChart(routes, picked) { v.track(it)?.bpm ?: 0.0 }
        LazyColumn(Modifier.weight(1f)) {
            routes.forEachIndexed { n, r ->
                item(key = "r$n") { RouteRow(app, n, r, selected = n == picked, onPick = { picked = n }, onOpen = { desk.openRoute(r.route) }) }
            }
            val sel = routes.getOrNull(picked)
            if (sel != null) {
                item(key = "h") {
                    Text(
                        "ANDAMENTO · ${if (sel.route.branch == null) "LINHA PRINCIPAL" else "RAMO ${sel.route.letter}"}", color = Palette.blue, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                sel.route.ids.forEachIndexed { i, id ->
                    val t = v.track(id) ?: return@forEachIndexed
                    val own = sel.route.own(i)
                    item(key = "s$i-$id") { StepRow(t, sel.tempo.startMs.getOrElse(i) { 0 }, sel.tempo.masters.getOrNull(i), if (own) routeColor(picked) else null) }
                }
            }
        }
    }
}

/**
 * O gráfico: tempo do set na horizontal, master na vertical. A linha principal em azul; cada ramo sai do ponto de virada
 * na sua cor e, se volta, segue fino pelo resto da linha (deslocado no tempo: o ramo encurta ou alonga o set).
 */
@Composable
private fun RouteChart(routes: List<SetDesk.RouteView>, picked: Int, natives: (Int) -> Double) {
    // A escala cobre o master e o BPM de cada faixa do caminho escolhido (com o master fixo, a linha é reta e os pontos mostram as faixas).
    val sel0 = routes.getOrNull(picked) ?: routes.first()
    val all = (routes.flatMap { it.tempo.masters } + sel0.route.ids.map(natives)).filter { it > 0 }
    if (all.isEmpty()) return
    val lo = all.min() - 2
    val hi = all.max() + 2
    val total = routes.maxOf { it.tempo.lengthMs }.coerceAtLeast(1)
    val grid = Palette.line
    Column(Modifier.fillMaxWidth().background(Palette.surface).border(BorderStroke(1.dp, Palette.line)).padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(200.dp)) {
            Column(Modifier.width(34.dp).fillMaxHeight()) {
                Text("%.0f".format(hi - 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
                Spacer(Modifier.weight(1f))
                Text("%.0f".format(lo + 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
            }
            val colors = routes.indices.map { routeColor(it) }
            val warn = Palette.orange
            val native0 = Palette.mutedBright
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                fun x(ms: Long) = size.width * (ms.toFloat() / total)
                fun y(bpm: Double) = size.height * (1 - ((bpm - lo) / (hi - lo)).toFloat())
                // Linhas de 10 em 10 minutos.
                var m = 0L
                while (m <= total) {
                    drawLine(grid, Offset(x(m), 0f), Offset(x(m), size.height), 1f)
                    m += 600_000
                }
                // Os ramos primeiro (a principal por cima nos trechos em comum); o escolhido mais forte.
                val order = routes.indices.drop(1) + 0
                for (n in order) {
                    val r = routes[n]
                    val pts = r.route.ids.indices.map { i -> Offset(x(r.tempo.startMs[i]), y(r.tempo.masters[i])) } + Offset(x(r.tempo.lengthMs), y(r.tempo.last))
                    val strong = if (n == picked) 1f else 0.55f
                    for (i in 0 until pts.size - 1) {
                        // Num ramo, só o trecho próprio é cheio; antes da virada é a linha principal e depois da volta, fino.
                        val own = n == 0 || r.route.ownStep(i)
                        val after = n != 0 && r.route.afterMerge(i)
                        if (n != 0 && !own && !after) continue
                        drawLine(
                            colors[n].copy(alpha = if (after) 0.45f * strong else strong), pts[i], pts[i + 1],
                            strokeWidth = (if (after) 1.5f else if (n == picked) 3.5f else 2.5f) * density,
                            pathEffect = if (after) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                        )
                    }
                    r.route.ids.indices.forEach { i ->
                        val own = n == 0 || r.route.own(i)
                        if (own) drawCircle(colors[n].copy(alpha = strong), (if (n == picked) 4f else 3f) * density, pts[i])
                    }
                }
                // No caminho escolhido: o BPM de cada faixa (ponto claro, ligado ao master por um traço) e, onde estica
                // mais que o confortável (acima de 10%), um anel laranja.
                val sel = routes[picked]
                sel.route.ids.indices.forEach { i ->
                    val native = natives(sel.route.ids[i])
                    val mst = sel.tempo.masters.getOrNull(i) ?: return@forEach
                    val at = x(sel.tempo.startMs[i])
                    if (native > 0) {
                        drawLine(native0.copy(alpha = 0.5f), Offset(at, y(mst)), Offset(at, y(native)), 1f * density)
                        drawCircle(native0, 2.5f * density, Offset(at, y(native)))
                    }
                    if (native > 0 && kotlin.math.abs(SetTempo.stretchPct(native, mst)) > SetTempo.COMFORT_PCT) {
                        drawCircle(warn, 7f * density, Offset(x(sel.tempo.startMs[i]), y(mst)), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 2.dp)) {
            Text("0:00", color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
            Spacer(Modifier.weight(1f))
            Text(mmss(total), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
        }
    }
}

/** Um caminho: a cor, a letra, de onde sai e onde volta, e o andamento resumido. Tocar escolhe; › abre a linha. */
@Composable
private fun RouteRow(app: StudioHost, n: Int, r: SetDesk.RouteView, selected: Boolean, onPick: () -> Unit, onOpen: () -> Unit) {
    val v = app.model.view ?: return
    val route = r.route
    fun title(id: Int) = v.track(id)?.let { shortTitle(it.title.ifEmpty { it.fileName }) }.orEmpty()
    val from = route.branch?.fromTrackId
    val back = route.rejoinId
    val where = when {
        from == null -> "Linha principal"
        back != null -> "${title(from)} → ${title(back)}"
        else -> "${title(from)} → outro final"
    }
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(if (selected) Palette.selectedRow else Palette.bg).clickable(onClick = onPick).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 36.dp).clip(RbShape).background(routeColor(n)))
        Text(route.letter.toString(), color = routeColor(n), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.padding(start = 10.dp).width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(where, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "≈${mmss(r.tempo.lengthMs)} · %.0f → %.0f → %.0f".format(r.tempo.first, r.tempo.peak, r.tempo.last), color = Palette.mutedBright,
                    fontSize = 12.sp, fontFamily = Mono,
                )
                Text(
                    if (r.issues == 0) "  ✓" else "  ⚠ ${r.issues}", color = if (r.issues == 0) Palette.keyInk else Palette.orange, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(Modifier.size(52.dp).clickable(onClick = onOpen), contentAlignment = Alignment.Center) { Text("›", color = Palette.blue, fontSize = 22.sp) }
    }
}

/** Uma faixa do caminho escolhido: quando entra, o BPM em que toca e o tom. [own]: a cor do ramo, se é do trecho dele. */
@Composable
private fun StepRow(t: os.deckbridge.manager.pdb.TrackItem, startMs: Long, master: Double?, own: Color?) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mmss(startMs), color = Palette.orange, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.width(48.dp))
        Box(Modifier.size(width = 3.dp, height = 22.dp).background(own ?: Palette.line))
        Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (master != null && t.bpm > 0) {
            val st = SetTempo.stretchPct(t.bpm, master)
            Text(
                "%.0f ".format(master) + pctLabel(st), fontSize = 12.sp, fontFamily = Mono,
                color = if (kotlin.math.abs(st) > SetTempo.MAX_PCT) Palette.danger else if (kotlin.math.abs(st) > SetTempo.COMFORT_PCT) Palette.orange else Palette.mutedBright,
            )
        }
        Text(keyLabel(t.key), color = Palette.keyInk, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.width(30.dp))
    }
}
