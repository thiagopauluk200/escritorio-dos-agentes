package br.escritorio.central.ui.graficos

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.escritorio.central.data.Fluxo
import br.escritorio.central.data.Grafo
import br.escritorio.central.data.No
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/* O palco da rede é sempre escuro (como um visor), independente do tema claro/escuro do app. */
private val FUNDO_CENTRO = Color(0xFF1B2748)
private val FUNDO_BORDA = Color(0xFF080C18)
private val PAINEL = Color(0xFF0F1626)
private val LINHA = Color(0xFF26325A)
private val TEXTO = Color(0xFFEEF2FB)
private val TEXTO_SUAVE = Color(0xFF93A0BD)
private val TEXTO_NOTA = Color(0xFFB6C1DC)
private val COR_ENTRADA = Color(0xFF6CB6FF)
private val COR_SAIDA = Color(0xFFFFA94D)
private val COR_VIVO = Color(0xFF3DDC97)
private val COR_MEMORIA = Color(0xFF6CB6FF)
private val COR_ARESTA = Color(0xFF9FB0D8)
private val COR_MOTOR = mapOf("codex" to Color(0xFF3DDC97), "claude" to Color(0xFFF08A5D), "chatgpt" to Color(0xFFB79CFF))
private val COR_CANAL = mapOf("ponte" to Color(0xFF5EEAD4), "app" to Color(0xFFE7ECF7), "openbot" to Color(0xFFE879F9))
private val NOME_CANAL = mapOf("ponte" to "PC · ponte", "app" to "App e painel", "openbot" to "OpenBot")
private val CURTO_CANAL = mapOf("ponte" to "PC", "app" to "App", "openbot" to "OpenBot")
private val PALETA = listOf(Color(0xFFFFD166), Color(0xFF6CB6FF), Color(0xFFEF8FB3), Color(0xFF7BE0D6), Color(0xFFC3E36B),
    Color(0xFFFFA94D), Color(0xFFA5B4FC), Color(0xFF9AA7C2))
private val horaSeg: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun fmtBytes(b: Double): String = when {
    b < 1 -> "0 B"
    b < 1024 -> "${b.toInt()} B"
    b < 1024 * 1024 -> String.format(java.util.Locale("pt", "BR"), if (b < 10240) "%.1f KB" else "%.0f KB", b / 1024)
    else -> String.format(java.util.Locale("pt", "BR"), "%.1f MB", b / 1048576)
}

/** Pré-processamento feito UMA vez por versão do grafo (não a cada frame). */
@Immutable
class RedePreparada(g: Grafo, comOpenbot: Boolean = false, limiteNotas: Int = 400, notaEmFoco: String? = null) {
    val totalNotas = g.nos.count { it.tipo == "nota" }
    val nos: List<No>
    val indice: Map<String, Int>
    val arestas: List<Pair<Int, Int>>
    val vizinhos: Map<Int, Set<Int>>
    val grupos: List<String>
    val centro: Int
    init {
        val notas = g.nos.filter { it.tipo == "nota" }
            .sortedWith(compareByDescending<No> { it.id == notaEmFoco }.thenByDescending { it.conexoes })
            .take(limiteNotas)
        val base = g.nos.filter { it.tipo != "nota" } + notas
        val idCentro = base.firstOrNull { it.tipo == "centro" }?.id
        // pontos de entrada/saída de dados: a Central mede o tráfego de cada um
        val canais = if (idCentro == null) emptyList() else (listOf("ponte", "app") + if (comOpenbot) listOf("openbot") else emptyList())
            .map { No(id = "canal:$it", rotulo = NOME_CANAL[it] ?: it, grupo = it, tipo = "canal", conexoes = 1) }
        nos = base + canais
        indice = nos.withIndex().associate { it.value.id to it.index }
        centro = idCentro?.let { indice[it] } ?: -1
        val brutas = g.arestas.filter { it.size >= 2 }.map { it[0] to it[1] } + canais.map { it.id to idCentro!! }
        arestas = brutas.mapNotNull { (a, b) -> val i = indice[a]; val j = indice[b]; if (i != null && j != null && i != j) i to j else null }
        val v = HashMap<Int, MutableSet<Int>>()
        arestas.forEach { (i, j) -> v.getOrPut(i) { mutableSetOf() }.add(j); v.getOrPut(j) { mutableSetOf() }.add(i) }
        vizinhos = v
        grupos = nos.filter { it.tipo == "pasta" || it.tipo == "nota" }.mapNotNull { it.grupo }.distinct().sorted()
    }
    val visiveisNotas get() = nos.count { it.tipo == "nota" }
    val corGrupo: Map<String, Color> = grupos.withIndex().associate { it.value to PALETA[it.index % PALETA.size] }
    fun cor(n: No): Color = when (n.tipo) {
        "centro" -> COR_MEMORIA
        "agente" -> COR_MOTOR[n.grupo] ?: Color(0xFFFFB547)
        "canal" -> COR_CANAL[n.grupo] ?: TEXTO
        else -> corGrupo[n.grupo] ?: Color(0xFF9AA7C2)
    }
}

private class Pulso(val a: Int, val b: Int, val cor: Color, val tam: Float, val prof: Int, val t0: Float, val dur: Float)

/**
 * Simulação 3D da rede (repulsão + molas + deriva lenta) e câmera orbital. Fica fora do sistema de estado
 * do Compose: o Canvas é redesenhado a cada frame lendo estes vetores, sem recomposição.
 */
private class SimRede(val r: RedePreparada, anterior: SimRede?) {
    val n = r.nos.size
    val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
    val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)
    val fase = FloatArray(n) { (r.nos[it].id.hashCode() and 0x3ff) / 163f }
    val ehNota = BooleanArray(n) { r.nos[it].tipo == "nota" }
    val raioBase = FloatArray(n) {
        when (r.nos[it].tipo) { "centro" -> 13f; "pasta" -> 8f; "agente" -> 9f; "canal" -> 7.5f
            else -> if ((r.vizinhos[it]?.size ?: 0) <= 1) 3.4f else 4.6f }
    }
    val cores = Array(n) { r.cor(r.nos[it]) }
    val m = r.arestas.size
    val ea = IntArray(m) { r.arestas[it].first }; val eb = IntArray(m) { r.arestas[it].second }
    val alvo = FloatArray(m) {
        val a = r.nos[ea[it]].tipo; val b = r.nos[eb[it]].tipo
        when { a == "canal" || b == "canal" -> 125f; a == "agente" || b == "agente" -> 115f; a == "centro" || b == "centro" -> 105f; else -> 48f }
    }
    // projeção do último frame
    val sx = FloatArray(n); val sy = FloatArray(n); val sc = FloatArray(n); val zr = FloatArray(n); val prof = FloatArray(n); val rt = FloatArray(n)
    val ordem = IntArray(n) { it }
    val flashT = FloatArray(n) { -1e9f }; val flashCor = Array(n) { COR_ENTRADA }

    var energia = 1f
    var yaw: Float = anterior?.yaw ?: 0.5f; var pitch: Float = anterior?.pitch ?: -0.3f
    var vyaw = 0f; var vpitch = 0f
    var zoom: Float = anterior?.zoom ?: 1f
    var raio = 160f
    var tocando = false; var ultimoToque = -1e9f; var agora: Float = 0f; private var anteriorT: Float = 0f
    var selecionado = -1
    var memoria: Set<String> = emptySet()
    val pulsos = ArrayList<Pulso>()
    private val vistos = HashMap<String, Long>()
    private var primeiro = true

    init {
        val r0 = 70f + 9f * sqrt(n.toFloat())
        for (i in 0 until n) {
            val j = anterior?.r?.indice?.get(r.nos[i].id)
            if (anterior != null && j != null) { px[i] = anterior.px[j]; py[i] = anterior.py[j]; pz[i] = anterior.pz[j] }
            else { val yy = 1f - 2f * (i + 0.5f) / n; val rr = sqrt(1f - yy * yy); val th = i * 2.39996f
                px[i] = cos(th) * rr * r0; py[i] = yy * r0; pz[i] = sin(th) * rr * r0 }
        }
        if (anterior != null) { energia = 0.6f; agora = anterior.agora; anteriorT = anterior.agora }
    }

    private fun repelir(i: Int, j: Int, mult: Float, em: Float) {
        var dx = px[i] - px[j]; var dy = py[i] - py[j]; var dz = pz[i] - pz[j]
        val d2 = dx * dx + dy * dy + dz * dz + 0.01f
        val f = min(4f, (if (ehNota[i] && ehNota[j]) 1500f else 5200f) / d2) * em * mult
        val d = sqrt(d2); dx /= d; dy /= d; dz /= d
        vx[i] += dx * f; vy[i] += dy * f; vz[i] += dz * f; vx[j] -= dx * f; vy[j] -= dy * f; vz[j] -= dz * f
    }

    fun passo(t: Float) {
        val dt = ((t - anteriorT) / 16.67f).coerceIn(0.25f, 3f); anteriorT = t; agora = t
        // câmera: inércia do arrasto e giro lento quando ninguém mexe
        if (!tocando) {
            yaw += vyaw * dt; pitch += vpitch * dt; vyaw *= 0.95f; vpitch *= 0.9f
            if (selecionado < 0 && t - ultimoToque > 1800f) yaw += 0.0016f * dt
        }
        pitch = pitch.coerceIn(-1.35f, 1.35f)
        if (n == 0) return
        energia = max(0.05f, energia * 0.992f)
        val em = max(energia, 0.35f)
        if (n <= 300) { for (i in 0 until n) for (j in i + 1 until n) repelir(i, j, 1f, em) }
        else { val k = 30; val mult = n / k / 2f; for (i in 0 until n) repeat(k) { val j = Random.nextInt(n); if (j != i) repelir(i, j, mult, em) } }
        for (i in 0 until n) {
            vx[i] -= px[i] * 0.004f; vy[i] -= py[i] * 0.004f; vz[i] -= pz[i] * 0.004f
            vx[i] += cos(t * 0.00045f + fase[i]) * 0.07f; vy[i] += sin(t * 0.00038f + fase[i] * 1.7f) * 0.07f; vz[i] += cos(t * 0.00041f + fase[i] * 2.3f) * 0.07f
        }
        for (k in 0 until m) {
            val a = ea[k]; val b = eb[k]
            var dx = px[b] - px[a]; var dy = py[b] - py[a]; var dz = pz[b] - pz[a]
            val d = max(1f, sqrt(dx * dx + dy * dy + dz * dz)); val f = (d - alvo[k]) * 0.02f
            dx /= d; dy /= d; dz /= d
            vx[a] += dx * f; vy[a] += dy * f; vz[a] += dz * f; vx[b] -= dx * f; vy[b] -= dy * f; vz[b] -= dz * f
        }
        var rmax = 40f
        for (i in 0 until n) {
            if (i == r.centro) { px[i] = 0f; py[i] = 0f; pz[i] = 0f; vx[i] = 0f; vy[i] = 0f; vz[i] = 0f; continue }
            vx[i] *= 0.82f; vy[i] *= 0.82f; vz[i] *= 0.82f
            px[i] += vx[i].coerceIn(-12f, 12f); py[i] += vy[i].coerceIn(-12f, 12f); pz[i] += vz[i].coerceIn(-12f, 12f)
            rmax = max(rmax, sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i]))
        }
        raio += (rmax - raio) * 0.06f
        // pulsos que chegaram ao destino acendem o ponto e se ramificam
        var i = 0
        while (i < pulsos.size) {
            val p = pulsos[i]
            if (t - p.t0 >= p.dur) {
                pulsos.removeAt(i); flashT[p.b] = t; flashCor[p.b] = p.cor
                if (p.prof > 0) {
                    val viz = r.vizinhos[p.b].orEmpty().filter { it != p.a && r.nos[it].tipo != "canal" }
                    repeat(min(viz.size, if (p.prof >= 2) 3 else 2)) { q -> disparar(p.b, viz[Random.nextInt(viz.size)], p.cor, max(1.4f, p.tam * 0.78f), p.prof - 1, q * 70f) }
                }
            } else i++
        }
    }

    fun disparar(a: Int, b: Int, cor: Color, tam: Float, prof: Int, atraso: Float) {
        if (a < 0 || b < 0 || pulsos.size > 240) return
        val dx = px[b] - px[a]; val dy = py[b] - py[a]; val dz = pz[b] - pz[a]
        pulsos.add(Pulso(a, b, cor, tam, prof, agora + atraso, (sqrt(dx * dx + dy * dy + dz * dz) * 7f).coerceIn(380f, 1300f)))
    }

    /** Cada transferência medida pela Central vira um pulso (uma única vez). */
    fun receber(f: Fluxo, agoraServidor: Long) {
        val corte = agoraServidor - 20_000; vistos.entries.removeAll { it.value < corte }
        for (ev in f.recentes) {
            val chave = "${ev.t}|${ev.canal}|${ev.sentido}|${ev.bytes}"
            if (vistos.put(chave, ev.t) != null) continue
            val idade = agoraServidor - ev.t
            if (primeiro && idade > 3000) continue
            val no = r.indice["canal:${ev.canal}"] ?: continue
            if (r.centro < 0) continue
            val tam = 1.8f + log10(ev.bytes + 10f) * 0.85f
            val atraso = (2400 - idade).coerceIn(0, 2400).toFloat()
            val prof = when { ev.bytes > 40_000 -> 3; ev.bytes > 4_000 -> 2; ev.bytes > 600 -> 1; else -> 0 }
            if (ev.sentido == "entrada") disparar(no, r.centro, COR_ENTRADA, tam, if (ev.canal == "app") min(prof, 1) else prof, atraso)
            else disparar(r.centro, no, COR_SAIDA, tam, 0, atraso)
        }
        primeiro = false
    }

    fun projetar(w: Float, h: Float, dens: Float) {
        val lado = min(w, h); val f = raio * 2.8f; val margem = (if (w / dens < 520f) 22f else 48f) * dens
        val k = (lado / 2f - margem) * (1f + 0.22f * (h / w - 1f).coerceIn(0f, 1f)) / (raio * 0.94f) * zoom
        val u = (lado / dens / 520f).coerceIn(0.8f, 1.35f) * sqrt(zoom) * dens
        val cy = cos(yaw); val sn = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        for (i in 0 until n) {
            val x1 = px[i] * cy + pz[i] * sn; val z1 = -px[i] * sn + pz[i] * cy
            val y1 = py[i] * cp - z1 * sp; val z2 = py[i] * sp + z1 * cp; val s = f / (f + z2)
            sx[i] = w / 2f + x1 * s * k; sy[i] = h / 2f + y1 * s * k; sc[i] = s; zr[i] = z2
            prof[i] = ((z2 + raio) / (2f * raio)).coerceIn(0f, 1f); rt[i] = max(1.6f * dens, raioBase[i] * u * s)
        }
        // do fundo para a frente; a ordem muda pouco entre frames, então a inserção é quase linear
        for (i in 1 until n) { val v = ordem[i]; var j = i - 1; while (j >= 0 && zr[ordem[j]] < zr[v]) { ordem[j + 1] = ordem[j]; j-- }; ordem[j + 1] = v }
    }

    fun achar(x: Float, y: Float, dens: Float): Int {
        var melhor = -1; var nota = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = sqrt((x - sx[i]) * (x - sx[i]) + (y - sy[i]) * (y - sy[i]))
            if (d > max(22f * dens, rt[i] + 12f * dens)) continue
            val v = d + prof[i] * 10f * dens - (if (ehNota[i]) 0f else 4f * dens)
            if (v < nota) { nota = v; melhor = i }
        }
        return melhor
    }
}

/**
 * Rede de conhecimento em 3D. Arrastar gira (com inércia), pinça aproxima, toque seleciona e destaca as
 * relacionadas. Os pulsos azuis/laranja são transferências reais medidas pela Central (campo `fluxo`).
 */
@Composable
fun Rede(r: RedePreparada, selecionado: String?, fluxo: Fluxo?, agoraServidor: Long, memoria: Set<String>, onSelecionar: (No?) -> Unit) {
    val tm = rememberTextMeasurer()
    val guarda = remember { arrayOfNulls<SimRede>(1) }
    val sim = remember(r) { SimRede(r, guarda[0]).also { guarda[0] = it } }
    var quadro by remember { mutableLongStateOf(0L) }
    var dicaUso by remember { mutableStateOf(true) }
    val estGrupo = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, shadow = Shadow(Color(0xFF060A14), Offset.Zero, 8f)) }
    val estNota = remember { TextStyle(fontSize = 10.sp, shadow = Shadow(Color(0xFF060A14), Offset.Zero, 8f)) }
    val rotulos = remember(r) { arrayOfNulls<TextLayoutResult>(r.nos.size) }
    val tracejado = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 10f)) }
    val losango = remember { Path() }
    val postos = remember { FloatArray(4 * 80) }

    SideEffect { sim.selecionado = selecionado?.let { r.indice[it] } ?: -1; sim.memoria = memoria }
    LaunchedEffect(sim) {
        var base = 0L
        while (true) withFrameNanos { ns -> if (base == 0L) base = ns - (sim.agora * 1_000_000f).toLong(); sim.passo((ns - base) / 1_000_000f); quadro = ns }
    }
    LaunchedEffect(sim, fluxo) { fluxo?.let { sim.receber(it, agoraServidor) } }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(7000); dicaUso = false }

    val altura = (LocalConfiguration.current.screenHeightDp * 0.6f).dp.coerceIn(360.dp, 680.dp)
    val forma = RoundedCornerShape(24.dp)
    Column {
        Box(Modifier.fillMaxWidth().height(altura).clip(forma)
            .drawWithCache {
                val fundo = Brush.radialGradient(listOf(FUNDO_CENTRO, FUNDO_BORDA), Offset(size.width / 2, size.height * 0.45f), max(size.width, size.height) * 0.72f)
                onDrawBehind { drawRect(fundo) }
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF3A4A80), Color(0xFF182040))), forma)) {
            Canvas(Modifier.matchParentSize()
                .semantics { contentDescription = "Rede de conhecimento em 3D com ${r.nos.size} pontos. Arraste para girar, pinça para zoom, toque para selecionar." }
                .pointerInput(sim) {
                    val folga = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val inicio = awaitFirstDown(requireUnconsumed = false)
                        var movido = 0f
                        sim.tocando = true; sim.vyaw = 0f; sim.vpitch = 0f
                        do {
                            val ev = awaitPointerEvent()
                            val ativos = ev.changes.count { it.pressed }
                            if (ativos >= 2) {
                                sim.zoom = (sim.zoom * ev.calculateZoom()).coerceIn(0.5f, 4f); movido = Float.MAX_VALUE / 2; dicaUso = false
                            } else if (ativos == 1) {
                                val d = ev.calculatePan(); movido += abs(d.x) + abs(d.y)
                                if (movido > folga) {
                                    val g = 0.008f / density
                                    sim.yaw += d.x * g; sim.pitch += d.y * g; sim.vyaw = d.x * g; sim.vpitch = d.y * g
                                    sim.energia = max(sim.energia, 0.22f); dicaUso = false
                                }
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (ev.changes.any { it.pressed })
                        sim.tocando = false; sim.ultimoToque = sim.agora
                        if (movido <= folga) { val i = sim.achar(inicio.position.x, inicio.position.y, density); onSelecionar(if (i >= 0) r.nos[i] else null) }
                    }
                }) {
                quadro // lido aqui: só o desenho é refeito a cada frame
                val w = size.width; val h = size.height; val dens = density
                sim.projetar(w, h, dens)
                val t = sim.agora; val sel = sim.selecionado; val viz = if (sel >= 0) r.vizinhos[sel].orEmpty() else emptySet()
                // sinapses: mais apagadas quanto mais ao fundo
                for (k in 0 until sim.m) {
                    val a = sim.ea[k]; val b = sim.eb[k]; val ta = r.nos[a].tipo; val tb = r.nos[b].tipo
                    val ag = ta == "agente" || tb == "agente"; val cn = ta == "canal" || tb == "canal"
                    val viva = ag && ((ta == "agente" && r.nos[a].grupo in sim.memoria) || (tb == "agente" && r.nos[b].grupo in sim.memoria))
                    val pf = (sim.prof[a] + sim.prof[b]) / 2f; val daSel = sel >= 0 && (a == sel || b == sel)
                    var al = (if (viva) 0.9f else if (cn) 0.5f else if (ag) 0.4f else 0.46f) * (1f - 0.6f * pf)
                    if (sel >= 0) al = if (daSel) 0.95f else al * 0.3f
                    drawLine((if (viva || daSel) COR_MEMORIA else if (cn) COR_CANAL.getValue("ponte") else COR_ARESTA).copy(alpha = al),
                        Offset(sim.sx[a], sim.sy[a]), Offset(sim.sx[b], sim.sy[b]), (if (daSel) 1.8f else 1f) * (1.25f - 0.5f * pf) * dens,
                        cap = StrokeCap.Round, pathEffect = if ((ag && !viva) || cn) tracejado else null)
                }
                // atividade de fundo leve + agentes consultando a memória
                if (sim.m > 0) {
                    val qtd = (sim.m shr 2).coerceIn(3, 12)
                    for (i in 0 until qtd) {
                        val fase = t / 2600f + i * 0.37f; val k = ((i * 7919 + fase.toInt() * 31) % sim.m + sim.m) % sim.m; val f = fase - fase.toInt()
                        val a = sim.ea[k]; val b = sim.eb[k]
                        drawCircle(Color(0xFFCFE3FF).copy(alpha = 0.5f * (1f - 0.6f * (sim.prof[a] + sim.prof[b]) / 2f)), 1.3f * dens,
                            Offset(sim.sx[a] + (sim.sx[b] - sim.sx[a]) * f, sim.sy[a] + (sim.sy[b] - sim.sy[a]) * f))
                    }
                    if (sim.memoria.isNotEmpty()) for (k in 0 until sim.m) {
                        val a0 = sim.ea[k]; val b0 = sim.eb[k]
                        val ag = if (r.nos[a0].tipo == "agente" && r.nos[a0].grupo in sim.memoria) a0 else if (r.nos[b0].tipo == "agente" && r.nos[b0].grupo in sim.memoria) b0 else -1
                        if (ag < 0) continue
                        val o = if (ag == a0) b0 else a0
                        for (i in 0 until 3) { val fase = t / 900f + i / 3f; val f = fase - fase.toInt()
                            drawCircle(Color.White, 2.4f * dens, Offset(sim.sx[ag] + (sim.sx[o] - sim.sx[ag]) * f, sim.sy[ag] + (sim.sy[o] - sim.sy[ag]) * f)) }
                    }
                }
                // pulsos do tráfego medido
                for (p in sim.pulsos) {
                    val f = (t - p.t0) / p.dur; if (f < 0f || f >= 1f) continue
                    val ax = sim.sx[p.a]; val ay = sim.sy[p.a]; val bx = sim.sx[p.b]; val by = sim.sy[p.b]
                    val tam = p.tam * dens * (sim.sc[p.a] + (sim.sc[p.b] - sim.sc[p.a]) * f) * sqrt(sim.zoom)
                    for (q in 4 downTo 1) { val g = max(0f, f - q * 0.04f)
                        drawCircle(p.cor.copy(alpha = 0.16f * (5 - q) / 5f), tam * (1f - q * 0.12f), Offset(ax + (bx - ax) * g, ay + (by - ay) * g)) }
                    val c = Offset(ax + (bx - ax) * f, ay + (by - ay) * f)
                    drawCircle(Brush.radialGradient(listOf(p.cor.copy(alpha = 0.55f), p.cor.copy(alpha = 0f)), c, tam * 4.5f), tam * 4.5f, c)
                    drawCircle(Color.White, tam * 0.55f, c)
                }
                // neurônios, do fundo para a frente
                for (o in 0 until sim.n) {
                    val i = sim.ordem[o]; val no = r.nos[i]; val x = sim.sx[i]; val y = sim.sy[i]; val rr = sim.rt[i]; val c = Offset(x, y)
                    val fl = (1f - (t - sim.flashT[i]) / 700f).coerceIn(0f, 1f)
                    var al = 1f - 0.55f * sim.prof[i]
                    if (sel >= 0 && i != sel && i !in viz) al *= 0.3f
                    al = min(1f, al + fl)
                    val cor = sim.cores[i]; val foco = i == sel
                    val ativo = no.tipo == "agente" && no.grupo in sim.memoria
                    if (ativo) { val fase = t / 1400f; val f = fase - fase.toInt(); drawCircle(cor.copy(alpha = (1f - f) * al), rr + (3f + f * 14f) * dens, c, style = Stroke(1.5f * dens)) }
                    if (no.tipo != "nota" || fl > 0f || foco) {
                        val cf = if (fl > 0f) sim.flashCor[i] else cor; val halo = rr + (6f + fl * 12f + (if (foco) 6f else 0f)) * dens
                        drawCircle(Brush.radialGradient(listOf(cf.copy(alpha = (if (fl > 0f || foco) 0.66f else 0.3f) * al), cf.copy(alpha = 0f)), c, halo), halo, c)
                    }
                    when (no.tipo) {
                        "agente" -> { val q = rr * 0.92f; drawRoundRect(cor.copy(alpha = al), Offset(x - q, y - q), Size(q * 2, q * 2), CornerRadius(q * 0.3f)) }
                        "canal" -> { losango.rewind(); losango.moveTo(x, y - rr); losango.lineTo(x + rr, y); losango.lineTo(x, y + rr); losango.lineTo(x - rr, y); losango.close()
                            drawPath(losango, cor.copy(alpha = al)) }
                        else -> {
                            drawCircle(cor.copy(alpha = al), rr * (1f + fl * 0.35f), c)
                            // brilho especular discreto: dá volume aos pontos maiores
                            if (no.tipo != "nota") drawCircle(Color.White.copy(alpha = 0.28f * al), rr * 0.34f, Offset(x - rr * 0.3f, y - rr * 0.32f))
                        }
                    }
                    if (foco) drawCircle(Color.White, rr + 3f * dens, c, style = Stroke(1.6f * dens))
                }
                // rótulos: centro e seleção primeiro, depois grupos, depois notas; nenhum rótulo cobre outro
                var np = 0
                val notasVisiveis = sim.n <= 34 || sim.zoom >= 1.6f
                for (passo in 0..2) for (o in sim.n - 1 downTo 0) {
                    if (np >= 80) break
                    val i = sim.ordem[o]; val no = r.nos[i]; val nota = no.tipo == "nota"
                    val pr = if (i == sel || no.tipo == "centro") 0 else if (!nota) 1 else 2
                    if (pr != passo) continue
                    if (pr == 2 && !(i in viz || (notasVisiveis && sim.prof[i] < 0.6f))) continue
                    val lay = rotulos[i] ?: tm.measure(if (no.rotulo.length > 22) no.rotulo.take(21) + "…" else no.rotulo, if (nota) estNota else estGrupo).also { rotulos[i] = it }
                    val lw = lay.size.width + 8f * dens; val lh = lay.size.height.toFloat()
                    val lx = (sim.sx[i] - lw / 2f).coerceIn(2f, max(2f, w - lw - 2f)); val ly = sim.sy[i] + sim.rt[i] + 4f * dens
                    if (ly < 2f || ly > h - lh) continue
                    var livre = true
                    if (pr > 0) for (q in 0 until np) { val b = q * 4
                        if (lx < postos[b] + postos[b + 2] && lx + lw > postos[b] && ly < postos[b + 1] + postos[b + 3] && ly + lh > postos[b + 1]) { livre = false; break } }
                    if (!livre) continue
                    postos[np * 4] = lx; postos[np * 4 + 1] = ly; postos[np * 4 + 2] = lw; postos[np * 4 + 3] = lh; np++
                    var al = if (pr == 0) 1f else max(0.45f, 1f - 0.6f * sim.prof[i])
                    if (sel >= 0 && i != sel && i !in viz) al *= 0.45f
                    drawText(lay, color = if (nota) TEXTO_NOTA else if (no.tipo == "canal") sim.cores[i] else TEXTO, topLeft = Offset(lx + 4f * dens, ly), alpha = al)
                }
            }
            // sobreposições do palco
            Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Color(0xB30B1120)).border(1.dp, LINHA, CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                PontoVivo(fluxo != null); Spacer(Modifier.width(7.dp))
                Text("${r.nos.size} pontos · ${r.arestas.size} conexões", color = TEXTO, fontSize = 11.5.sp)
            }
            Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BotaoPalco("+", "Aproximar") { sim.zoom = min(4f, sim.zoom * 1.3f); dicaUso = false }
                BotaoPalco("−", "Afastar") { sim.zoom = max(0.5f, sim.zoom / 1.3f); dicaUso = false }
                BotaoPalco("⌖", "Recentrar a rede") { sim.zoom = 1f; sim.pitch = -0.3f; sim.vyaw = 0f; sim.vpitch = 0f; sim.energia = max(sim.energia, 0.5f) }
            }
            androidx.compose.animation.AnimatedVisibility(dicaUso, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), enter = fadeIn(), exit = fadeOut(tween(600))) {
                Text("Arraste para girar · pinça para zoom · toque num ponto", color = TEXTO_SUAVE, fontSize = 11.5.sp,
                    modifier = Modifier.clip(CircleShape).background(Color(0xCC0B1120)).border(1.dp, LINHA, CircleShape).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        if (r.visiveisNotas < r.totalNotas)
            Text("Mostrando ${r.visiveisNotas} de ${r.totalNotas} notas (as mais conectadas) para manter a fluidez.",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        LegendaRede(r)
        PainelTrafego(fluxo)
    }
}

@Composable
private fun PontoVivo(ativo: Boolean) {
    val a by rememberInfiniteTransition(label = "vivo").animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "vivo")
    Box(Modifier.size(7.dp).alpha(if (ativo) a else 1f).clip(CircleShape).background(if (ativo) COR_VIVO else TEXTO_SUAVE))
}

@Composable
private fun BotaoPalco(simbolo: String, descricao: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xCC151C2E)).border(1.dp, Color(0xFF34427A), CircleShape)
        .clickable(role = Role.Button, onClickLabel = descricao, onClick = onClick).semantics { contentDescription = descricao },
        contentAlignment = Alignment.Center) { Text(simbolo, color = TEXTO, fontSize = 19.sp) }
}

@Composable
private fun LegendaRede(r: RedePreparada) {
    val itens = remember(r) {
        r.grupos.map { it to (r.corGrupo[it] ?: TEXTO_SUAVE) } +
            r.nos.filter { it.tipo == "agente" }.map { it.rotulo to r.cor(it) } +
            listOf("dados entrando" to COR_ENTRADA, "dados saindo" to COR_SAIDA)
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itens.forEach { (nome, cor) ->
            Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(cor)); Spacer(Modifier.width(6.dp))
                Text(nome, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** Painel "Tráfego ao vivo": taxas, gráfico dos últimos 60 s, canais e últimas transferências. */
@Composable
fun PainelTrafego(f: Fluxo?) {
    val forma = RoundedCornerShape(22.dp)
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(forma)
        .background(Brush.verticalGradient(listOf(Color(0xFF141C33), PAINEL)))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF34427A), Color(0xFF182040))), forma).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PontoVivo(f != null); Spacer(Modifier.width(8.dp))
            Text("TRÁFEGO AO VIVO", color = TEXTO_SUAVE, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            if (f != null) Text("${f.req_min} req/min", color = TEXTO_SUAVE, fontSize = 12.sp)
        }
        if (f == null) {
            Text("A Central ainda não informa o tráfego. Atualize a Central para a versão com medição de transferências.",
                color = TEXTO_SUAVE, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metrica("Entrada", "↓", f.taxa.entrada, COR_ENTRADA, Modifier.weight(1f))
            Metrica("Saída", "↑", f.taxa.saida, COR_SAIDA, Modifier.weight(1f))
        }
        val pico = max(512L, max(f.serie_entrada.maxOrNull() ?: 0L, f.serie_saida.maxOrNull() ?: 0L))
        val caminho = remember { Path() }
        Canvas(Modifier.padding(top = 12.dp).fillMaxWidth().height(64.dp)
            .semantics { contentDescription = "Bytes por segundo nos últimos ${f.janela_s} segundos; pico ${fmtBytes(pico.toDouble())} por segundo." }) {
            val w = size.width; val h = size.height
            for (g in 1..3) drawLine(Color(0xFF1F2944), Offset(0f, h * g / 4f), Offset(w, h * g / 4f), 1f)
            listOf(f.serie_saida to COR_SAIDA, f.serie_entrada to COR_ENTRADA).forEach { (s, cor) ->
                if (s.size < 2) return@forEach
                caminho.rewind()
                s.forEachIndexed { i, v -> val x = i * w / (s.size - 1); val y = h - 1f - (v.toFloat() / pico) * (h - 4f); if (i == 0) caminho.moveTo(x, y) else caminho.lineTo(x, y) }
                drawPath(caminho, cor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                caminho.lineTo(w, h); caminho.lineTo(0f, h); caminho.close()
                drawPath(caminho, Brush.verticalGradient(listOf(cor.copy(alpha = 0.28f), cor.copy(alpha = 0f))))
            }
        }
        Row(Modifier.padding(top = 2.dp)) {
            Text("−${f.janela_s} s", color = Color(0xFF5D6A88), fontSize = 10.sp); Spacer(Modifier.weight(1f))
            Text("agora · pico ${fmtBytes(pico.toDouble())}/s", color = Color(0xFF5D6A88), fontSize = 10.sp)
        }
        Divisor()
        Text("POR CANAL · MÉDIA DE 10 S", color = TEXTO_SUAVE, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        listOf("ponte", "app", "openbot").forEach { c ->
            val tx = f.canais[c]; if (tx == null && c == "openbot") return@forEach
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(COR_CANAL.getValue(c))); Spacer(Modifier.width(8.dp))
                Text(NOME_CANAL.getValue(c), color = TEXTO, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("↓ ${fmtBytes(tx?.entrada ?: 0.0)}/s", color = COR_ENTRADA, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
                Text("↑ ${fmtBytes(tx?.saida ?: 0.0)}/s", color = COR_SAIDA, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Divisor()
        Text("ÚLTIMAS TRANSFERÊNCIAS", color = TEXTO_SUAVE, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        val externas = f.recentes.filter { it.canal != "app" }
        val lista = (if (externas.isNotEmpty()) externas else f.recentes).takeLast(4).reversed()
        if (lista.isEmpty()) Text("Sem transferências nos últimos 12 s.", color = TEXTO_SUAVE, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        lista.forEach { tr ->
            val nome = CURTO_CANAL[tr.canal] ?: (tr.canal ?: "?"); val entrada = tr.sentido == "entrada"
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Instant.ofEpochMilli(tr.t).atZone(ZoneId.systemDefault()).format(horaSeg), color = Color(0xFF5D6A88), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(if (entrada) "$nome → Central" else "Central → $nome", color = TEXTO_NOTA, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(fmtBytes(tr.bytes.toDouble()), color = if (entrada) COR_ENTRADA else COR_SAIDA, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Divisor() = Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Color(0xFF1F2944)))

@Composable
private fun Metrica(titulo: String, seta: String, valor: Double, cor: Color, modifier: Modifier = Modifier) {
    val animado by animateFloatAsState(valor.toFloat(), tween(700), label = titulo)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(cor.copy(alpha = 0.09f)).border(1.dp, cor.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(titulo, color = TEXTO_SUAVE, fontSize = 11.5.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$seta ${fmtBytes(animado.toDouble())}", color = cor, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("/s", color = cor.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        }
    }
}
