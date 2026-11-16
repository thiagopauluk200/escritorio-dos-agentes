package br.escritorio.central.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.escritorio.central.data.Agente
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.OpenbotAgente
import br.escritorio.central.data.OpenbotAgentes
import br.escritorio.central.data.TokensAgente
import br.escritorio.central.ui.comp.BarraConsumo
import br.escritorio.central.ui.comp.Energia
import br.escritorio.central.ui.comp.Humor
import br.escritorio.central.ui.comp.Original
import br.escritorio.central.ui.comp.Personagem

/** Situação de um personagem: o que faz, como estão os tokens e os textos mostrados. */
data class Situacao(val humor: Humor, val energia: Energia, val tokens: String, val linha: String)

private const val DESCANSO_MS = 30 * 60 * 1000L
private val FUNDO = Color(0xFF0F1626)
private val TEXTO = Color(0xFFEEF2FB)
private val SUAVE = Color(0xFF93A0BD)

/** Tokens pelo orçamento LOCAL da Central (não é a cota do provedor). Sem dado → DESCONHECIDA, nunca "cheia". */
fun energiaDe(tk: TokensAgente?): Energia {
    val o = tk?.orcamento_local ?: return Energia.DESCONHECIDA
    return when {
        !o.configurado -> Energia.SEM_ORCAMENTO
        o.estado in setOf("excedido", "atingido", "limite_zero") -> Energia.VAZIA
        o.estado in setOf("limite_desconhecido", "limite_invalido") -> Energia.DESCONHECIDA
        o.alerta_atingido != null || (o.percentual_consumido ?: 0.0) >= 70.0 -> Energia.BAIXA
        else -> Energia.CHEIA
    }
}

fun textoTokens(tk: TokensAgente?, e: Energia): String {
    val livre = tk?.orcamento_local?.percentual_disponivel?.let { " · ${Fmt.pct(it, 0)} livre" } ?: ""
    return when (e) {
        Energia.CHEIA -> "Com tokens$livre"
        Energia.BAIXA -> "Tokens acabando$livre"
        Energia.VAZIA -> "Sem tokens · orçamento esgotado"
        Energia.SEM_ORCAMENTO -> "Sem limite definido" + (tk?.consumo_periodo?.let { " · ${Fmt.inteiro(it)} usados" } ?: "")
        Energia.DESCONHECIDA -> "Tokens: sem dados"
    }
}

fun humorDe(a: Agente, agora: Long): Humor = when (a.status) {
    "trabalhando" -> Humor.TRABALHANDO
    "fila" -> Humor.FILA
    else -> if (a.atualizado != null && agora - a.atualizado < DESCANSO_MS) Humor.AGUARDANDO else Humor.DESCANSANDO
}

fun situacaoAgente(a: Agente, tk: TokensAgente?, agora: Long): Situacao {
    val h = humorDe(a, agora); val e = energiaDe(tk)
    val linha = when {
        e == Energia.VAZIA -> "Parado: sem tokens no orçamento"
        h == Humor.TRABALHANDO -> "Trabalhando" + (a.atividade?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        h == Humor.FILA -> "Na fila · ${a.na_fila.coerceAtLeast(1)} tarefa(s) esperando"
        h == Humor.AGUARDANDO -> "Aguardando ordens"
        else -> "Descansando · última atividade ${Fmt.idade(a.atualizado, agora)}"
    }
    return Situacao(h, e, textoTokens(tk, e), linha)
}

/** Situação de um agente do OpenBot. Tokens: só o que foi observado (última resposta e uso), nunca a cota do provedor. */
fun situacaoOpenbot(o: OpenbotAgente, agora: Long): Situacao {
    val humor = when {
        o.trabalhando -> Humor.TRABALHANDO
        o.atualizado != null && agora - o.atualizado < DESCANSO_MS -> Humor.AGUARDANDO
        else -> Humor.DESCANSANDO
    }
    val usados = o.tokens_24h?.takeIf { it > 0 }?.let { " · ${Fmt.inteiro(it)} em 24 h" } ?: ""
    val (energia, tokens) = when {
        o.sem_cota -> Energia.VAZIA to "Sem tokens · limite do provedor"
        o.falhou_ultimo -> Energia.DESCONHECIDA to "Última resposta falhou"
        o.ultimo_status == "completed" -> Energia.CHEIA to "Com tokens · respondeu OK$usados"
        else -> Energia.DESCONHECIDA to "Tokens: sem uso registrado"
    }
    val modelo = o.modelo?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
    val linha = when {
        energia == Energia.VAZIA -> "Parado: provedor recusou por limite"
        humor == Humor.TRABALHANDO -> "Trabalhando no OpenBot$modelo"
        humor == Humor.AGUARDANDO -> "Aguardando ordens$modelo"
        else -> "Descansando · ${Fmt.idade(o.atualizado, agora)}$modelo"
    }
    return Situacao(humor, energia, tokens, linha)
}

/**
 * Resume num único personagem o agente do OpenBot e os agentes da Central do mesmo tipo.
 * Sem nenhum dos dois → "sem dados", nunca inventado.
 */
fun situacaoOriginal(q: Original, agentes: List<Agente>, tokens: Map<String, TokensAgente>, agora: Long, openbot: List<OpenbotAgente> = emptyList()): Situacao {
    val meus = agentes.filter { it.motor == q.motor }
    val ob = openbot.filter { it.provedor == q.motor }.map { situacaoOpenbot(it, agora) }.minByOrNull { it.humor }
    if (meus.isEmpty()) return ob ?: Situacao(Humor.SEM_DADOS, Energia.DESCONHECIDA, "Tokens: sem dados",
        if (openbot.isEmpty()) "A ponte do PC ainda não informou o OpenBot" else "Não encontrado no OpenBot")
    val central = situacaoCentral(meus, tokens, agora)
    if (ob == null) return central
    // os dois existem: vale o estado mais ativo; nos tokens, o orçamento da Central manda quando está definido
    val humor = minOf(central.humor, ob.humor)
    val comOrcamento = central.energia in setOf(Energia.CHEIA, Energia.BAIXA, Energia.VAZIA)
    val energia = if (comOrcamento) central.energia else ob.energia
    return Situacao(humor, energia, if (comOrcamento) central.tokens else ob.tokens, if (central.humor <= ob.humor) central.linha else ob.linha)
}

private fun situacaoCentral(meus: List<Agente>, tokens: Map<String, TokensAgente>, agora: Long): Situacao {
    val sits = meus.map { it to situacaoAgente(it, tokens[it.id], agora) }
    val humor = sits.minOf { it.second.humor }
    val es = sits.map { it.second.energia }
    val energia = when {
        es.all { it == Energia.VAZIA } -> Energia.VAZIA
        Energia.VAZIA in es || Energia.BAIXA in es -> Energia.BAIXA
        Energia.CHEIA in es -> Energia.CHEIA
        Energia.SEM_ORCAMENTO in es -> Energia.SEM_ORCAMENTO
        else -> Energia.DESCONHECIDA
    }
    // o texto de tokens vem do agente em pior situação dentro do grupo
    val pior = sits.filter { it.second.energia == (if (energia == Energia.BAIXA && Energia.VAZIA in es) Energia.VAZIA else energia) }
        .minByOrNull { tokens[it.first.id]?.orcamento_local?.percentual_disponivel ?: 100.0 }
    val txt = if (energia == Energia.BAIXA && Energia.VAZIA in es) "Tokens acabando · ${es.count { it == Energia.VAZIA }} de ${es.size} sem saldo"
        else textoTokens(pior?.let { tokens[it.first.id] }, energia)
    val n = meus.size; val quantos = if (n > 1) " · $n agentes" else ""
    val linha = when {
        energia == Energia.VAZIA -> "Parado: sem tokens no orçamento"
        humor == Humor.TRABALHANDO -> { val k = sits.count { it.second.humor == Humor.TRABALHANDO }; "Trabalhando" + if (n > 1) " · $k de $n agentes" else "" }
        humor == Humor.FILA -> "Na fila · ${meus.sumOf { it.na_fila }.coerceAtLeast(1)} tarefa(s)"
        humor == Humor.AGUARDANDO -> "Aguardando ordens$quantos"
        else -> "Descansando$quantos"
    }
    return Situacao(humor, energia, txt, linha)
}

@Composable
fun PilulaTokens(e: Energia, texto: String, modifier: Modifier = Modifier) {
    val cor = when (e) { Energia.CHEIA -> Color(0xFF3DDC97); Energia.BAIXA -> Color(0xFFFFB547); Energia.VAZIA -> Color(0xFFFF6B6B)
        Energia.SEM_ORCAMENTO -> Color(0xFF8A9BFF); Energia.DESCONHECIDA -> SUAVE }
    Row(modifier.clip(CircleShape).background(cor.copy(alpha = 0.14f)).border(1.dp, cor.copy(alpha = 0.4f), CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(cor)); Spacer(Modifier.width(6.dp))
        Text(texto, color = cor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Palco com os quatro agentes originais do OpenBot, dois por linha. Tocar num deles filtra a lista abaixo. */
@Composable
fun PalcoEquipe(agentes: List<Agente>, tokens: Map<String, TokensAgente>, agora: Long, openbot: OpenbotAgentes?, filtro: String?, onFiltro: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Original.entries.chunked(2).forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                linha.forEach { q ->
                    val s = situacaoOriginal(q, agentes, tokens, agora, openbot?.agentes.orEmpty())
                    CartaoOriginal(q, s, filtro == q.motor, Modifier.weight(1f)) { onFiltro(if (filtro == q.motor) null else q.motor) }
                }
            }
        }
        Text(if (openbot == null) "OpenBot: a ponte do PC ainda não enviou o estado dos agentes."
            else "OpenBot lido pela ponte ${Fmt.idade(openbot.visto, agora)} · tokens = uso observado, não a cota do provedor.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CartaoOriginal(q: Original, s: Situacao, marcado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(22.dp)
    val tom = if (q == Original.CODEX) q.cor2 else q.cor
    val apagado = s.humor == Humor.SEM_DADOS
    Column(modifier.clip(forma)
        .background(Brush.verticalGradient(listOf(lerp(FUNDO, tom, if (apagado) 0.08f else 0.26f), FUNDO)))
        .border(if (marcado) 2.dp else 1.dp, if (marcado) tom else lerp(Color(0xFF26325A), tom, 0.3f), forma)
        .clickable(role = Role.Button, onClickLabel = "Filtrar agentes do ${q.nome}", onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        PilulaTokens(s.energia, s.tokens)
        Personagem(q, s.humor, s.energia, Modifier.fillMaxWidth().aspectRatio(1.08f))
        Text(q.nome, color = TEXTO, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(s.linha, color = SUAVE, fontSize = 11.5.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 14.sp)
    }
}

/** Cartão de um agente cadastrado: o personagem do seu motor reage ao estado e aos tokens DESTE agente. */
@Composable
fun CartaoAgenteVivo(a: Agente, tk: TokensAgente?, agora: Long, onClick: () -> Unit) {
    val q = Original.doMotor(a.motor) ?: Original.CODEX
    val s = situacaoAgente(a, tk, agora)
    val forma = RoundedCornerShape(20.dp)
    val tom = if (q == Original.CODEX) q.cor2 else q.cor
    Column(Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(forma)
        .background(Brush.horizontalGradient(listOf(lerp(FUNDO, tom, 0.2f), FUNDO)))
        .border(1.dp, lerp(Color(0xFF26325A), tom, 0.25f), forma)
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Personagem(q, s.humor, s.energia, Modifier.size(92.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.nome, color = TEXTO, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${q.nome} · ${a.pasta.ifBlank { "sem projeto (só leitura)" }}", color = SUAVE, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                PilulaTokens(s.energia, s.tokens)
                Text(s.linha, color = TEXTO, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (a.funcao.isNotBlank()) Text(a.funcao, color = SUAVE, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (tk?.orcamento_local?.configurado == true) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides TEXTO) {
                BarraConsumo(tk.orcamento_local.percentual_consumido, "Orçamento local (${Fmt.periodo(tk.orcamento_local.periodo)})", tk.orcamento_local.alertas)
            }
        }
    }
}

@Composable
fun TituloSecao(texto: String, detalhe: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(texto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (detalhe != null) { Spacer(Modifier.width(8.dp)); Text(detalhe, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
