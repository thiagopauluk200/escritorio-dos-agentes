package br.escritorio.central.ui.graficos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.escritorio.central.data.Eixos
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gráficos nativos (Canvas) inspirados na referência Matplotlib: eixos nomeados com unidade, marcas,
 * grade discreta tracejada, legenda quando há mais de uma série. Os valores vêm da Central; nada é inventado.
 */
data class Serie(val nome: String, val cor: Color, val pontos: List<Pair<Double, Double>>)

private fun fmtNum(v: Double): String = when {
    v == 0.0 -> "0"
    v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9 -> String.format(Locale.forLanguageTag("pt-BR"), "%,.0f", v)
    kotlin.math.abs(v) >= 100 -> String.format(Locale.forLanguageTag("pt-BR"), "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(Locale.forLanguageTag("pt-BR"), "%.1f", v)
    else -> String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v)
}

@Composable
private fun estiloEixo() = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Legenda(itens: List<Pair<String, Color>>) {
    if (itens.size < 2) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        itens.forEach { (n, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp))
                Text(n, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Barras horizontais com valor exato ao lado de cada barra (ordenação feita por quem chama). */
@Composable
fun BarrasH(itens: List<Pair<String, Double>>, cor: Color, unidade: String, rotuloEixo: String, empilhada: List<Pair<String, List<Double>>>? = null,
            cores: List<Color> = listOf(cor)) {
    val tm = rememberTextMeasurer()
    val est = estiloEixo()
    val corTexto = MaterialTheme.colorScheme.onSurface
    val grade = MaterialTheme.colorScheme.outlineVariant
    val linhas = empilhada ?: itens.map { it.first to listOf(it.second) }
    val maxV = (linhas.maxOfOrNull { it.second.sum() } ?: 0.0).coerceAtLeast(1e-9)
    val marcas = Eixos.marcas(0.0, maxV)
    val alturaLinha = 26.dp
    val desc = linhas.joinToString("; ") { "${it.first}: ${it.second.joinToString(" + ") { v -> fmtNum(v) }} $unidade" }
    Column(Modifier.semantics { contentDescription = "Gráfico de barras ($rotuloEixo). $desc" }) {
        Canvas(Modifier.fillMaxWidth().height(alturaLinha * linhas.size + 36.dp)) {
            val esq = size.width * 0.34f
            val dir = size.width - 44.dp.toPx()
            val topo = 4.dp.toPx()
            val base = size.height - 30.dp.toPx()
            val lh = (base - topo) / max(1, linhas.size)
            val xDe = { v: Double -> esq + ((v / marcas.last()) * (dir - esq)).toFloat() }
            marcas.forEach { m ->
                val x = xDe(m)
                drawLine(grade, Offset(x, topo), Offset(x, base), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                desenharTexto(tm, fmtNum(m), Offset(x, base + 4.dp.toPx()), est, centro = true)
            }
            desenharTexto(tm, "$rotuloEixo ($unidade)", Offset((esq + dir) / 2, base + 16.dp.toPx()), est, centro = true)
            linhas.forEachIndexed { i, (nome, vals) ->
                val y = topo + i * lh
                desenharTexto(tm, nome.take(22), Offset(0f, y + lh * 0.25f), est.copy(color = corTexto))
                var acc = 0.0
                vals.forEachIndexed { j, v ->
                    val x0 = xDe(acc); val x1 = xDe(acc + v)
                    drawRect(cores[j % cores.size], Offset(x0, y + lh * 0.18f), Size(max(1f, x1 - x0), lh * 0.64f))
                    acc += v
                }
                desenharTexto(tm, fmtNum(acc), Offset(xDe(acc) + 4.dp.toPx(), y + lh * 0.25f), est.copy(color = corTexto))
            }
        }
    }
}

/** Linhas com eixos nomeados, marcas e grade. xFmt formata o rótulo das marcas do eixo X. */
@Composable
fun Linhas(series: List<Serie>, rotuloX: String, rotuloY: String, xFmt: (Double) -> String, alturaDp: Int = 220) {
    val tm = rememberTextMeasurer()
    val est = estiloEixo()
    val grade = MaterialTheme.colorScheme.outlineVariant
    val todos = series.flatMap { it.pontos }
    if (todos.isEmpty()) return
    val (x0, x1) = todos.minOf { it.first } to todos.maxOf { it.first }
    val mY = Eixos.marcas(0.0, max(todos.maxOf { it.second }, 1e-9))
    val mX = Eixos.marcas(x0, if (x1 > x0) x1 else x0 + 1, 4).filter { it in x0..(if (x1 > x0) x1 else x0 + 1) }
    Column(Modifier.semantics { contentDescription = "Gráfico de linhas: $rotuloY por $rotuloX, ${series.size} série(s), ${todos.size} pontos" }) {
        Canvas(Modifier.fillMaxWidth().height(alturaDp.dp)) {
            val esq = 44.dp.toPx(); val dir = size.width - 8.dp.toPx(); val topo = 8.dp.toPx(); val base = size.height - 34.dp.toPx()
            val xa = if (x1 > x0) x1 else x0 + 1
            val px = { x: Double -> esq + ((x - x0) / (xa - x0) * (dir - esq)).toFloat() }
            val py = { y: Double -> base - (y / mY.last() * (base - topo)).toFloat() }
            eixos(grade, esq, dir, topo, base)
            mY.forEach { m ->
                drawLine(grade, Offset(esq, py(m)), Offset(dir, py(m)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                desenharTexto(tm, fmtNum(m), Offset(esq - 4.dp.toPx(), py(m) - 6.dp.toPx()), est, direita = true)
            }
            mX.forEach { m -> desenharTexto(tm, xFmt(m), Offset(px(m), base + 4.dp.toPx()), est, centro = true) }
            desenharTexto(tm, rotuloX, Offset((esq + dir) / 2, base + 18.dp.toPx()), est, centro = true)
            series.forEach { s ->
                val pts = s.pontos.sortedBy { it.first }.map { Offset(px(it.first), py(it.second)) }
                for (i in 1 until pts.size) drawLine(s.cor, pts[i - 1], pts[i], 2.2f)
                pts.forEach { drawCircle(s.cor, 3.2f, it) }
            }
        }
        Text(rotuloY, style = MaterialTheme.typography.labelSmall)
        Legenda(series.map { it.nome to it.cor })
    }
}

data class PontoXY(val id: String, val rotulo: String, val grupo: String, val x: Double, val y: Double)

/**
 * Dispersão: cor por grupo; tamanho = 3 + 2·√(y+1) dp (documentado). Tocar revela TODOS os pontos sob o dedo,
 * sem deslocar valores (pontos coincidentes são listados, não espalhados).
 */
@Composable
fun Dispersao(pontos: List<PontoXY>, cores: Map<String, Color>, rotuloX: String, rotuloY: String, unidadeX: String, unidadeY: String) {
    val tm = rememberTextMeasurer()
    val est = estiloEixo()
    val grade = MaterialTheme.colorScheme.outlineVariant
    var tocados by remember(pontos) { mutableStateOf<List<PontoXY>>(emptyList()) }
    if (pontos.isEmpty()) return
    val mX = Eixos.marcas(0.0, max(pontos.maxOf { it.x }, 1.0))
    val mY = Eixos.marcas(0.0, max(pontos.maxOf { it.y }, 1.0))
    val raio = { y: Double -> (3.0 + 2.0 * sqrt(y + 1.0)).toFloat() }
    Column(Modifier.semantics { contentDescription = "Dispersão: $rotuloY por $rotuloX, ${pontos.size} pontos. Toque para inspecionar." }) {
        Canvas(Modifier.fillMaxWidth().height(260.dp).pointerInput(pontos) {
            detectTapGestures { toque ->
                val esq = 40.dp.toPx(); val dir = size.width - 8.dp.toPx(); val topo = 8.dp.toPx(); val base = size.height - 34.dp.toPx()
                val px = { x: Double -> esq + (x / mX.last() * (dir - esq)).toFloat() }
                val py = { y: Double -> base - (y / mY.last() * (base - topo)).toFloat() }
                val tol = 14.dp.toPx()
                tocados = pontos.filter { (Offset(px(it.x), py(it.y)) - toque).getDistance() <= tol + raio(it.y).dp.toPx() }
            }
        }) {
            val esq = 40.dp.toPx(); val dir = size.width - 8.dp.toPx(); val topo = 8.dp.toPx(); val base = size.height - 34.dp.toPx()
            val px = { x: Double -> esq + (x / mX.last() * (dir - esq)).toFloat() }
            val py = { y: Double -> base - (y / mY.last() * (base - topo)).toFloat() }
            eixos(grade, esq, dir, topo, base)
            mY.forEach { m ->
                drawLine(grade, Offset(esq, py(m)), Offset(dir, py(m)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                desenharTexto(tm, fmtNum(m), Offset(esq - 4.dp.toPx(), py(m) - 6.dp.toPx()), est, direita = true)
            }
            mX.forEach { m -> desenharTexto(tm, fmtNum(m), Offset(px(m), base + 4.dp.toPx()), est, centro = true) }
            desenharTexto(tm, "$rotuloX ($unidadeX)", Offset((esq + dir) / 2, base + 18.dp.toPx()), est, centro = true)
            pontos.forEach { p ->
                val c = cores[p.grupo] ?: Color.Gray
                val sel = p in tocados
                drawCircle(c.copy(alpha = if (sel) 1f else 0.75f), raio(p.y).dp.toPx(), Offset(px(p.x), py(p.y)))
                if (sel) drawCircle(Color.White, raio(p.y).dp.toPx(), Offset(px(p.x), py(p.y)), style = Stroke(2f))
            }
        }
        Text("$rotuloY ($unidadeY) · tamanho do ponto = 3 + 2·√(ligações+1) dp", style = MaterialTheme.typography.labelSmall)
        Legenda(cores.entries.map { it.key to it.value })
        if (tocados.isNotEmpty()) {
            Text("${tocados.size} arquivo(s) neste ponto:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            tocados.take(12).forEach {
                Text("• ${it.rotulo} (${it.grupo}): ${fmtNum(it.x)} $unidadeX, ${fmtNum(it.y)} $unidadeY", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun DrawScope.eixos(cor: Color, esq: Float, dir: Float, topo: Float, base: Float) {
    drawLine(cor, Offset(esq, topo), Offset(esq, base), 1.5f)
    drawLine(cor, Offset(esq, base), Offset(dir, base), 1.5f)
}

private fun DrawScope.desenharTexto(tm: TextMeasurer, t: String, pos: Offset, est: TextStyle, centro: Boolean = false, direita: Boolean = false) {
    val r = tm.measure(t, est)
    val x = when { centro -> pos.x - r.size.width / 2; direita -> pos.x - r.size.width; else -> pos.x }
    drawText(r, topLeft = Offset(x.coerceIn(0f, max(0f, size.width - r.size.width)), pos.y))
}
