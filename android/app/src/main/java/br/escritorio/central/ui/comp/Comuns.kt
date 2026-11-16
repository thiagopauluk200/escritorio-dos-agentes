package br.escritorio.central.ui.comp

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.escritorio.central.data.ApiErro
import br.escritorio.central.data.Dado
import br.escritorio.central.data.Fmt
import br.escritorio.central.ui.LocalCores

@Composable
fun corEstado(estado: String?): Color {
    val c = LocalCores.current
    return when (estado) {
        "online", "concluida", "ok", "gravada", "ativo", "declarado", "trabalhando", "sem_uso" -> c.ok
        "degradado", "fila", "pendente", "entregue", "aguardando", "executando", "rodando", "sem_dados_recentes", "conectando" -> c.aviso
        "offline", "falhou", "erro", "conflito", "ausente", "excedido", "atingido", "limite_zero", "nao_gravada" -> c.erro
        "cancelada", "nunca_visto", "inativo", "nao_solicitada", "nao_alcancado" -> c.neutro
        else -> c.neutro
    }
}

@Composable
fun Pilula(texto: String, estado: String?, modifier: Modifier = Modifier) {
    val cor = corEstado(estado)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(cor.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(cor))
        Spacer(Modifier.width(6.dp))
        Text(texto, style = MaterialTheme.typography.labelMedium, color = cor)
    }
}

@Composable
fun Titulo(texto: String, modifier: Modifier = Modifier) =
    Text(texto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp).semantics { heading() })

@Composable
fun Cartao(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, conteudo: @Composable () -> Unit) {
    val m = modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (onClick != null) Card(onClick = onClick, modifier = m, colors = CardDefaults.cardColors()) { Column(Modifier.padding(14.dp)) { conteudo() } }
    else Card(modifier = m) { Column(Modifier.padding(14.dp)) { conteudo() } }
}

@Composable
fun LinhaValor(rotulo: String, valor: String, destaque: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(valor, style = MaterialTheme.typography.bodyMedium, fontWeight = if (destaque) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1.2f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Vazio(mensagem: String, acao: String? = null, onAcao: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(mensagem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (acao != null && onAcao != null) TextButton(onClick = onAcao) { Text(acao) }
    }
}

@Composable
fun Carregando(texto: String = "Carregando…") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp)); Text(texto)
    }
}

/** Erro com a ação possível (tentar de novo, entrar de novo, conferir rede). */
@Composable
fun ErroCartao(erro: Throwable, onTentar: (() -> Unit)? = null) {
    val e = erro as? ApiErro
    val dica = when (e?.tipo) {
        ApiErro.Tipo.SEM_REDE -> "Confira o Wi-Fi/dados e se a Central (J7) está ligada. Em Diagnóstico há o teste por componente."
        ApiErro.Tipo.TIMEOUT -> "A Central demorou a responder. Tente de novo; se persistir, veja Diagnóstico."
        ApiErro.Tipo.TLS -> "O certificado do endereço não foi aceito. Use o endereço https do túnel válido."
        ApiErro.Tipo.NAO_AUTORIZADO -> "A sessão expirou ou foi revogada. Entre novamente."
        ApiErro.Tipo.PROIBIDO -> "Este aparelho não tem permissão para esta operação."
        ApiErro.Tipo.CONFLITO -> "A Central recusou por conflito de estado."
        ApiErro.Tipo.CONFIG -> "Revise o endereço da Central em Ajustes."
        else -> "Tente de novo em instantes."
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(e?.mensagem ?: (erro.message ?: "Erro"), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
            Text(dica, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (onTentar != null) TextButton(onClick = onTentar) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Tentar de novo") }
        }
    }
}

/** Faixa de origem/idade: deixa explícito quando o que se vê é cache offline. */
@Composable
fun Idade(d: Dado<*>?) {
    if (d == null) return
    val txt = if (d.doCache) "Sem conexão — exibindo dados salvos ${Fmt.idade(d.atualizadoEm)}" else "Atualizado ${Fmt.idade(d.atualizadoEm)}"
    Text(txt, style = MaterialTheme.typography.labelSmall,
        color = if (d.doCache) LocalCores.current.aviso else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * Barra de consumo acessível. A barra satura visualmente em 100%, mas o texto e a descrição
 * para leitores de tela preservam o valor real (ex.: 137%).
 */
@Composable
fun BarraConsumo(percentual: Double?, rotulo: String, alertas: List<Int> = listOf(70, 85, 95)) {
    val c = LocalCores.current
    if (percentual == null) {
        Text("$rotulo: percentual não calculado (limite ou consumo não disponibilizado)", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val cor = when {
        percentual >= 100 -> c.erro
        percentual >= (alertas.getOrNull(1) ?: 85) -> c.aviso
        else -> c.ok
    }
    val desc = "$rotulo: ${Fmt.pct(percentual)} consumido"
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = desc
        stateDescription = Fmt.pct(percentual)
        progressBarRangeInfo = ProgressBarRangeInfo(percentual.toFloat().coerceIn(0f, 100f), 0f..100f)
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(rotulo, style = MaterialTheme.typography.bodySmall)
            Text(Fmt.pct(percentual), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = cor)
        }
        LinearProgressIndicator(progress = { (percentual / 100).toFloat().coerceIn(0f, 1f) }, color = cor,
            modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp).clip(RoundedCornerShape(4.dp)))
    }
}
