package br.escritorio.central.ui.comp

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** O que o personagem está fazendo (vem do estado real do agente na Central). */
enum class Humor(val rotulo: String) {
    TRABALHANDO("trabalhando"), FILA("na fila"), AGUARDANDO("aguardando ordens"), DESCANSANDO("descansando"), SEM_DADOS("sem dados")
}

/** Situação dos tokens segundo o orçamento local da Central. */
enum class Energia { CHEIA, BAIXA, VAZIA, SEM_ORCAMENTO, DESCONHECIDA }

/** Os quatro agentes originais do OpenBot. Personagens próprios do app, nas cores de cada um. */
enum class Original(val motor: String, val nome: String, val cor: Color, val cor2: Color) {
    CLAUDE("claude", "Claude Code", Color(0xFFD97757), Color(0xFFF2A07E)),
    CODEX("codex", "Codex", Color(0xFFE8ECF3), Color(0xFF3DDC97)),
    GEMINI("gemini", "Gemini", Color(0xFF4F8BFF), Color(0xFFB57BEE)),
    KIMI("kimi", "Kimi AI", Color(0xFF2F6BFF), Color(0xFFFFE08A));
    companion object { fun doMotor(m: String?): Original? = entries.firstOrNull { it.motor == m } }
}

private val CINZA = Color(0xFF596079)
private val TINTA = Color(0xFF1B2030)
private val VERDE = Color(0xFF3DDC97)
private val AMBAR = Color(0xFFFFB547)
private val VERMELHO = Color(0xFFFF6B6B)

/**
 * Personagem animado. `u` = 1% do menor lado; tudo é desenhado em vetores (sem imagens), então fica nítido
 * em qualquer tamanho. A animação roda só enquanto o personagem está na tela.
 */
@Composable
fun Personagem(q: Original, humor: Humor, energia: Energia, modifier: Modifier = Modifier) {
    val t by produceState(0f) { val base = withFrameNanos { it }; while (true) withFrameNanos { value = (it - base) / 1e9f } }
    val caminho = remember { Path() }
    val fase = q.ordinal * 0.7f
    Canvas(modifier.semantics {
        contentDescription = "${q.nome}: ${humor.rotulo}" + when (energia) {
            Energia.CHEIA -> ", com tokens"; Energia.BAIXA -> ", tokens acabando"; Energia.VAZIA -> ", sem tokens"; else -> "" }
    }) {
        val u = size.minDimension / 100f; val cx = size.width / 2f; val chao = size.height / 2f + 40f * u
        val vazio = energia == Energia.VAZIA
        val dorme = !vazio && (humor == Humor.DESCANSANDO || humor == Humor.SEM_DADOS)
        val trab = !vazio && humor == Humor.TRABALHANDO
        val fila = !vazio && humor == Humor.FILA
        val tt = t + fase
        val apag = if (vazio) 0.72f else if (humor == Humor.SEM_DADOS) 0.45f else 0f
        val cor = lerp(q.cor, CINZA, apag); val cor2 = lerp(q.cor2, CINZA, apag)
        val escura = lerp(cor, TINTA, 0.35f)

        val pulo = when { vazio -> sin(tt * 1.1f) * 0.5f; trab -> -abs(sin(tt * 9f)) * 1.5f; dorme -> 0f; fila -> sin(tt * 3f) * 0.7f; else -> sin(tt * 2.2f) * 1.6f } * u
        val respira = if (dorme) 1f + sin(tt * 1.5f) * 0.035f else 1f
        val inclina = if (vazio) 10f + sin(tt * 1.1f) * 2.5f else if (dorme) 4f else if (trab) 0f else sin(tt * 1.1f) * 1.5f

        // sombra no chão
        drawOval(Color.Black.copy(alpha = 0.32f), Offset(cx - 24f * u, chao - 3f * u), Size(48f * u, 7f * u))

        val pivo = Offset(cx, chao)
        translate(0f, pulo) { rotate(inclina, pivo) { scale(1f, respira, pivo) {
            val ombro = chao - 30f * u; val meiaLarg = when (q) { Original.CLAUDE -> 23f; Original.CODEX -> 21f; Original.GEMINI -> 17f; Original.KIMI -> 21f } * u
            // braços (atrás do corpo): digitam, acenam ou ficam caídos
            val aceno = !trab && !dorme && !vazio && !fila && (tt % 6f) < 1.6f
            for (lado in intArrayOf(-1, 1)) {
                val ini = Offset(cx + lado * (meiaLarg - 2f * u), ombro)
                val fim = when {
                    trab -> Offset(cx + lado * 13f * u, chao - 13f * u + sin(tt * 19f + lado) * 1.8f * u)
                    aceno && lado == 1 -> Offset(cx + (meiaLarg + 9f * u) + sin(tt * 11f) * 3f * u, ombro - 15f * u)
                    fila -> Offset(cx + lado * (meiaLarg + 2f * u), chao - 17f * u + (if (lado == 1) abs(sin(tt * 6f)) * 2f * u else 0f))
                    vazio -> Offset(cx + lado * (meiaLarg + 5f * u), chao - 9f * u)
                    else -> Offset(cx + lado * (meiaLarg + 5f * u), chao - 14f * u + sin(tt * 2.2f + lado) * 0.8f * u)
                }
                drawLine(escura, ini, fim, 6f * u, StrokeCap.Round)
            }
            when (q) {
                Original.CLAUDE -> corpoClaude(cx, chao, u, cor, escura, tt)
                Original.CODEX -> corpoCodex(cx, chao, u, cor, cor2, escura, tt)
                Original.GEMINI -> corpoGemini(cx, chao, u, cor, cor2, caminho, tt, vazio || dorme)
                Original.KIMI -> corpoKimi(cx, chao, u, cor, cor2, caminho)
            }
            // rosto
            val olhoY = chao - when (q) { Original.CLAUDE -> 27f; Original.CODEX -> 31f; Original.GEMINI -> 30f; Original.KIMI -> 29f } * u + (if (trab) 1.6f * u else 0f)
            val sep = (if (q == Original.GEMINI) 6.5f else 8.5f) * u
            val olhar = if (!trab && !dorme && !vazio) sin(tt * 0.8f) * 1.4f * u else 0f
            val pisca = !dorme && !vazio && ((tt / 3.7f) % 1f) > 0.955f
            if (q == Original.CODEX) visorCodex(cx, olhoY, u, cor2, tt, dorme, vazio, trab, humor == Humor.SEM_DADOS)
            else {
                val corOlho = if (q == Original.KIMI || q == Original.GEMINI) Color.White else TINTA
                for (lado in intArrayOf(-1, 1)) {
                    val o = Offset(cx + lado * sep + olhar, olhoY)
                    when {
                        vazio -> { val k = 2.6f * u
                            drawLine(TINTA, o + Offset(-k, -k), o + Offset(k, k), 1.8f * u, StrokeCap.Round)
                            drawLine(TINTA, o + Offset(-k, k), o + Offset(k, -k), 1.8f * u, StrokeCap.Round) }
                        dorme -> drawArc(TINTA, 0f, 180f, false, o + Offset(-3f * u, -2f * u), Size(6f * u, 4f * u), style = Stroke(1.7f * u, cap = StrokeCap.Round))
                        pisca -> drawLine(TINTA, o + Offset(-3f * u, 0f), o + Offset(3f * u, 0f), 1.7f * u, StrokeCap.Round)
                        q == Original.CLAUDE -> drawRoundRect(TINTA, o + Offset(-2f * u, -4f * u), Size(4f * u, 8f * u), CornerRadius(1.2f * u))
                        else -> { drawCircle(corOlho, 4f * u, o); drawCircle(TINTA, 2.4f * u, o + Offset(olhar * 0.4f, if (trab) u else 0f)); drawCircle(Color.White, 0.9f * u, o + Offset(-0.9f * u, -1f * u)) }
                    }
                }
                val bocaY = olhoY + 7.5f * u
                when {
                    vazio -> { caminho.rewind(); caminho.moveTo(cx - 5f * u, bocaY)
                        for (i in 1..4) caminho.lineTo(cx - 5f * u + i * 2.5f * u, bocaY + (if (i % 2 == 1) -1.4f else 1.4f) * u)
                        drawPath(caminho, TINTA, style = Stroke(1.5f * u, cap = StrokeCap.Round)) }
                    dorme -> drawCircle(TINTA, (1.4f + sin(tt * 1.5f) * 0.5f) * u, Offset(cx, bocaY))
                    trab || fila -> drawLine(TINTA, Offset(cx - 2.6f * u, bocaY), Offset(cx + 2.6f * u, bocaY), 1.6f * u, StrokeCap.Round)
                    else -> drawArc(TINTA, 20f, 140f, false, Offset(cx - 4f * u, bocaY - 4f * u), Size(8f * u, 6f * u), style = Stroke(1.6f * u, cap = StrokeCap.Round))
                }
                // bochechas quando está bem e de folga
                if (!trab && !dorme && !vazio) for (lado in intArrayOf(-1, 1))
                    drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.35f), 2.6f * u, Offset(cx + lado * (sep + 5f * u), olhoY + 5f * u))
            }
            // notebook na frente do corpo enquanto trabalha
            if (trab) {
                drawRoundRect(Color(0xFF0B0F1A), Offset(cx - 15f * u, chao - 21f * u), Size(30f * u, 17f * u), CornerRadius(2.5f * u))
                drawRoundRect(lerp(Color(0xFF11253A), cor2, 0.18f), Offset(cx - 13f * u, chao - 19.5f * u), Size(26f * u, 13.5f * u), CornerRadius(1.5f * u))
                for (i in 0 until 4) { val on = ((tt * 2.4f).toInt() + i) % 5 != 0
                    if (on) drawLine(cor2.copy(alpha = 0.9f), Offset(cx - 10.5f * u, chao - 17f * u + i * 3f * u),
                        Offset(cx - 10.5f * u + (9f + ((i * 7 + (tt * 1.2f).toInt() * 3) % 11)) * u, chao - 17f * u + i * 3f * u), 1.4f * u, StrokeCap.Round) }
                drawRoundRect(Color(0xFF39456A), Offset(cx - 19f * u, chao - 5f * u), Size(38f * u, 3.4f * u), CornerRadius(1.7f * u))
            }
        } } }

        // ---- efeitos por cima (não inclinam com o corpo)
        val topo = chao - 62f * u
        if (trab) for (i in 0 until 5) { val f = ((tt * 0.55f + i * 0.2f) % 1f); val x = cx + (i - 2) * 9f * u + sin(tt * 2f + i) * 2f * u
            drawRect(cor2.copy(alpha = (1f - f) * 0.85f), Offset(x, chao - 24f * u - f * 34f * u), Size(2.2f * u, 2.2f * u)) }
        if (dorme && humor != Humor.SEM_DADOS) for (i in 0 until 3) { val f = ((tt * 0.35f + i / 3f) % 1f); val s = (3f + i * 1.4f) * u
            val p = Offset(cx + 15f * u + f * 10f * u + i * 3f * u, topo + 16f * u - f * 20f * u)
            caminho.rewind(); caminho.moveTo(p.x, p.y); caminho.lineTo(p.x + s, p.y); caminho.lineTo(p.x, p.y + s); caminho.lineTo(p.x + s, p.y + s)
            drawPath(caminho, Color(0xFFCFD7EA).copy(alpha = sin(f * PI.toFloat())), style = Stroke(1.5f * u, cap = StrokeCap.Round)) }
        if (humor == Humor.SEM_DADOS && !vazio) { // balão de pensamento "…"
            drawCircle(Color(0x33FFFFFF), 2f * u, Offset(cx + 15f * u, topo + 17f * u)); drawCircle(Color(0x44FFFFFF), 3f * u, Offset(cx + 20f * u, topo + 11f * u))
            drawRoundRect(Color(0x55FFFFFF), Offset(cx + 16f * u, topo - 4f * u), Size(24f * u, 12f * u), CornerRadius(6f * u))
            for (i in 0 until 3) drawCircle(Color.White.copy(alpha = 0.35f + 0.65f * abs(sin(tt * 2f - i * 0.6f))), 1.7f * u, Offset(cx + 22f * u + i * 6f * u, topo + 2f * u))
        }
        if (fila) { // ampulheta girando
            val c = Offset(cx + 25f * u, topo + 6f * u); val ang = ((tt * 0.5f) % 1f).let { if (it > 0.8f) (it - 0.8f) / 0.2f * 180f else 0f }
            rotate(ang, c) {
                caminho.rewind(); caminho.moveTo(c.x - 5f * u, c.y - 7f * u); caminho.lineTo(c.x + 5f * u, c.y - 7f * u); caminho.lineTo(c.x, c.y)
                caminho.lineTo(c.x + 5f * u, c.y + 7f * u); caminho.lineTo(c.x - 5f * u, c.y + 7f * u); caminho.lineTo(c.x, c.y); caminho.close()
                drawPath(caminho, AMBAR.copy(alpha = 0.35f)); drawPath(caminho, AMBAR, style = Stroke(1.5f * u))
            }
        }
        if (energia == Energia.CHEIA && !trab) for (i in 0 until 3) { // brilhos de "energia cheia"
            val f = ((tt * 0.5f + i * 0.33f) % 1f); val a = tt * 0.7f + i * 2.1f
            brilho(Offset(cx + cos(a) * 34f * u, chao - 34f * u + sin(a) * 22f * u), (1.2f + 2.2f * sin(f * PI.toFloat())) * u, VERDE.copy(alpha = 0.8f * sin(f * PI.toFloat())), caminho) }
        // bateria de tokens
        if (energia == Energia.CHEIA || energia == Energia.BAIXA || energia == Energia.VAZIA) {
            val corB = when (energia) { Energia.CHEIA -> VERDE; Energia.BAIXA -> AMBAR; else -> VERMELHO }
            val nivel = when (energia) { Energia.CHEIA -> 0.78f + 0.14f * abs(sin(tt * 1.3f)); Energia.BAIXA -> 0.28f; else -> 0.07f }
            val pisca = if (energia == Energia.CHEIA) 1f else 0.45f + 0.55f * abs(sin(tt * (if (vazio) 4f else 2.4f)))
            val b = Offset(cx - 44f * u, topo - 2f * u)
            drawRoundRect(corB.copy(alpha = pisca), b, Size(17f * u, 9.5f * u), CornerRadius(2.4f * u), style = Stroke(1.5f * u))
            drawRoundRect(corB.copy(alpha = pisca), b + Offset(17.8f * u, 2.8f * u), Size(2f * u, 4f * u), CornerRadius(u))
            drawRoundRect(corB.copy(alpha = pisca), b + Offset(2f * u, 2f * u), Size(13f * u * nivel, 5.5f * u), CornerRadius(1.2f * u))
        }
    }
}

private fun DrawScope.brilho(c: Offset, r: Float, cor: Color, p: Path) {
    p.rewind(); p.moveTo(c.x, c.y - r * 2f); p.quadraticTo(c.x, c.y, c.x + r * 2f, c.y); p.quadraticTo(c.x, c.y, c.x, c.y + r * 2f)
    p.quadraticTo(c.x, c.y, c.x - r * 2f, c.y); p.quadraticTo(c.x, c.y, c.x, c.y - r * 2f); p.close(); drawPath(p, cor)
}

/** Claude Code: bichinho quadradinho cor de terracota, com perninhas e um tufo de raios no topo. */
private fun DrawScope.corpoClaude(cx: Float, chao: Float, u: Float, cor: Color, escura: Color, t: Float) {
    for (i in 0 until 4) drawRoundRect(escura, Offset(cx - 19f * u + i * 10.7f * u, chao - 9f * u), Size(6f * u, 8f * u), CornerRadius(1.6f * u))
    for (i in -2..2) { val a = (-90f + i * 28f) * PI.toFloat() / 180f; val base = Offset(cx + i * 4f * u, chao - 41f * u); val comp = (7f + sin(t * 3f + i) * 1.2f) * u
        drawLine(cor, base, base + Offset(cos(a) * comp, sin(a) * comp), 2.6f * u, StrokeCap.Round) }
    drawRoundRect(escura, Offset(cx - 27f * u, chao - 32f * u), Size(8f * u, 10f * u), CornerRadius(2f * u))
    drawRoundRect(escura, Offset(cx + 19f * u, chao - 32f * u), Size(8f * u, 10f * u), CornerRadius(2f * u))
    drawRoundRect(Brush.verticalGradient(listOf(lerp(cor, Color.White, 0.16f), cor), chao - 42f * u, chao - 8f * u), Offset(cx - 23f * u, chao - 42f * u), Size(46f * u, 34f * u), CornerRadius(7f * u))
}

/** Codex: robozinho claro com visor escuro de terminal e antena com luz verde. */
private fun DrawScope.corpoCodex(cx: Float, chao: Float, u: Float, cor: Color, luz: Color, escura: Color, t: Float) {
    drawRoundRect(escura, Offset(cx - 13f * u, chao - 7f * u), Size(9f * u, 6f * u), CornerRadius(3f * u))
    drawRoundRect(escura, Offset(cx + 4f * u, chao - 7f * u), Size(9f * u, 6f * u), CornerRadius(3f * u))
    drawLine(escura, Offset(cx, chao - 46f * u), Offset(cx, chao - 53f * u), 1.8f * u, StrokeCap.Round)
    drawCircle(luz.copy(alpha = 0.3f), (4.5f + sin(t * 4f) * 1.2f) * u, Offset(cx, chao - 54.5f * u)); drawCircle(luz, 2.4f * u, Offset(cx, chao - 54.5f * u))
    drawRoundRect(Brush.verticalGradient(listOf(Color.White, cor, lerp(cor, TINTA, 0.18f)), chao - 46f * u, chao - 5f * u), Offset(cx - 21f * u, chao - 46f * u), Size(42f * u, 41f * u), CornerRadius(17f * u))
    drawRoundRect(Color(0xFF0D1117), Offset(cx - 16f * u, chao - 39f * u), Size(32f * u, 17f * u), CornerRadius(7f * u))
    drawRoundRect(lerp(cor, TINTA, 0.25f), Offset(cx - 7f * u, chao - 17f * u), Size(14f * u, 3f * u), CornerRadius(1.5f * u))
}

private fun DrawScope.visorCodex(cx: Float, y: Float, u: Float, luz: Color, t: Float, dorme: Boolean, vazio: Boolean, trab: Boolean, semDados: Boolean) {
    val c = if (vazio) VERMELHO else luz.copy(alpha = if (dorme) 0.45f else 1f); val l = 1.9f * u
    when {
        vazio -> for (lado in intArrayOf(-1, 1)) { val o = Offset(cx + lado * 6.5f * u, y); val k = 2.6f * u
            drawLine(c, o + Offset(-k, -k), o + Offset(k, k), l, StrokeCap.Round); drawLine(c, o + Offset(-k, k), o + Offset(k, -k), l, StrokeCap.Round) }
        dorme -> for (lado in intArrayOf(-1, 1)) drawLine(c, Offset(cx + lado * 6.5f * u - 3f * u, y + u), Offset(cx + lado * 6.5f * u + 3f * u, y + u), l, StrokeCap.Round)
        else -> { // ">_" : o cursor pisca mais rápido quando trabalha
            drawLine(c, Offset(cx - 10f * u, y - 3.5f * u), Offset(cx - 4.5f * u, y), l, StrokeCap.Round)
            drawLine(c, Offset(cx - 4.5f * u, y), Offset(cx - 10f * u, y + 3.5f * u), l, StrokeCap.Round)
            if (((t * (if (trab) 5f else 1.6f)).toInt() % 2 == 0) && !semDados) drawLine(c, Offset(cx + 1f * u, y + 3.5f * u), Offset(cx + 9.5f * u, y + 3.5f * u), l, StrokeCap.Round)
        }
    }
}

/** Gemini: corpo em forma de brilho de quatro pontas, em degradê azul → lilás, com um "gêmeo" pequeno orbitando. */
private fun DrawScope.corpoGemini(cx: Float, chao: Float, u: Float, cor: Color, cor2: Color, p: Path, t: Float, parado: Boolean) {
    val c = Offset(cx, chao - 28f * u); val r = 27f * u; val m = 0.42f
    drawRoundRect(lerp(cor, TINTA, 0.35f), Offset(cx - 9f * u, chao - 6f * u), Size(7f * u, 5f * u), CornerRadius(2.5f * u))
    drawRoundRect(lerp(cor2, TINTA, 0.35f), Offset(cx + 2f * u, chao - 6f * u), Size(7f * u, 5f * u), CornerRadius(2.5f * u))
    p.rewind(); p.moveTo(c.x, c.y - r)
    p.cubicTo(c.x + r * m * 0.3f, c.y - r * m, c.x + r * m, c.y - r * m * 0.3f, c.x + r, c.y)
    p.cubicTo(c.x + r * m, c.y + r * m * 0.3f, c.x + r * m * 0.3f, c.y + r * m, c.x, c.y + r)
    p.cubicTo(c.x - r * m * 0.3f, c.y + r * m, c.x - r * m, c.y + r * m * 0.3f, c.x - r, c.y)
    p.cubicTo(c.x - r * m, c.y - r * m * 0.3f, c.x - r * m * 0.3f, c.y - r * m, c.x, c.y - r); p.close()
    drawPath(p, Brush.linearGradient(listOf(cor, cor2, lerp(cor2, Color(0xFFF08FA8), 0.6f)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)))
    drawCircle(Brush.radialGradient(listOf(lerp(cor, Color.White, 0.25f), cor.copy(alpha = 0f)), c, 15f * u), 15f * u, c)
    if (!parado) { val a = t * 1.6f; brilho(Offset(c.x + cos(a) * 31f * u, c.y - 10f * u + sin(a) * 9f * u), 2.6f * u, cor2, Path()) }
}

/** Kimi: bolinha azul de barriga clara, com uma lua crescente amarela no topo. */
private fun DrawScope.corpoKimi(cx: Float, chao: Float, u: Float, cor: Color, lua: Color, p: Path) {
    drawRoundRect(lerp(cor, TINTA, 0.4f), Offset(cx - 13f * u, chao - 7f * u), Size(10f * u, 6f * u), CornerRadius(3f * u))
    drawRoundRect(lerp(cor, TINTA, 0.4f), Offset(cx + 3f * u, chao - 7f * u), Size(10f * u, 6f * u), CornerRadius(3f * u))
    // lua crescente: disco amarelo menos um disco deslocado
    val l = Offset(cx + 9f * u, chao - 50f * u)
    p.rewind(); p.addOval(androidx.compose.ui.geometry.Rect(l.x - 9f * u, l.y - 9f * u, l.x + 9f * u, l.y + 9f * u))
    val corte = Path().apply { addOval(androidx.compose.ui.geometry.Rect(l.x - 4f * u, l.y - 12f * u, l.x + 13f * u, l.y + 5f * u)) }
    drawPath(Path().apply { op(p, corte, androidx.compose.ui.graphics.PathOperation.Difference) }, lua)
    val c = Offset(cx, chao - 25f * u)
    drawCircle(Brush.radialGradient(listOf(lerp(cor, Color.White, 0.3f), cor, lerp(cor, TINTA, 0.3f)), c + Offset(-6f * u, -8f * u), 34f * u), 21f * u, c)
    drawOval(Color.White.copy(alpha = 0.22f), Offset(cx - 11f * u, chao - 22f * u), Size(22f * u, 15f * u))
}
