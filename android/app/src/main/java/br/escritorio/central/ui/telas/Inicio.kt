package br.escritorio.central.ui.telas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.data.Componente
import br.escritorio.central.data.Conexao
import br.escritorio.central.data.EstadoConexao
import br.escritorio.central.data.Fmt
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.Idade
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import br.escritorio.central.ui.comp.Titulo
import br.escritorio.central.ui.comp.Vazio

@Composable
fun FaixaConexao(cx: EstadoConexao) {
    val (txt, est) = when (cx.conexao) {
        Conexao.ONLINE -> "Central: ${cx.endereco ?: ""}" to "online"
        Conexao.CONECTANDO, Conexao.DESCONHECIDA -> "Conectando à Central…" to "conectando"
        Conexao.OFFLINE -> "Sem conexão com a Central" + (cx.ultimoSucesso?.let { " · último contato ${Fmt.idade(it)}" } ?: "") to "offline"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pilula(txt, est)
    }
}

/**
 * Com dados de cache (offline), o estado mostrado é o ÚLTIMO CONHECIDO, não o atual: a pílula fica neutra
 * e a idade avança pelo tempo decorrido no aparelho desde o recebimento (sem misturar relógios).
 */
@Composable
fun LinhaComponente(nome: String, c: Componente, agora: Long, velho: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(nome, fontWeight = FontWeight.Medium)
            Text("último contato: " + (c.visto?.let { Fmt.idade(it, agora) } ?: "nunca"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (velho) Pilula("último conhecido: " + Fmt.status(c.estado), "sem_dados_recentes")
        else Pilula(Fmt.status(c.estado), c.estado)
    }
    c.avisos.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun TelaInicio(vm: AppVM, nav: NavController) {
    val dado by vm.estado.collectAsState()
    val cx by vm.conexao.collectAsState()
    val e = dado?.valor
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { FaixaConexao(cx); Idade(dado) }
        if (e == null) {
            item { if (cx.erro != null) ErroCartao(cx.erro!!) { vm.atualizarAgora() } else Carregando() }
            return@LazyColumn
        }
        item {
            Titulo("Componentes")
            // horários da Central comparados ao relógio da Central (e.agora) + tempo decorrido no aparelho desde o recebimento
            val velho = dado!!.doCache || cx.conexao == Conexao.OFFLINE
            val agora = e.agora + (System.currentTimeMillis() - dado!!.atualizadoEm).coerceAtLeast(0)
            Cartao(onClick = { nav.navigate("diagnostico") }) {
                if (velho) LinhaComponente("Central (J7)", e.componentes.central.copy(estado = "offline"), agora)
                else LinhaComponente("Central (J7)", e.componentes.central, agora)
                LinhaComponente("Ponte do PC", e.componentes.ponte, agora, velho)
                LinhaComponente("OpenBot (MCP)", e.componentes.openbot, agora, velho)
                e.componentes.executores.forEach { (m, x) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Executor $m", fontWeight = FontWeight.Medium)
                            Text("último sucesso: " + (x.ultimo_sucesso?.let { Fmt.idade(it, agora) } ?: "nenhum registrado"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (velho) Pilula("último conhecido: " + Fmt.status(x.estado), "sem_dados_recentes") else Pilula(Fmt.status(x.estado), x.estado)
                    }
                }
                LinhaComponente("Obsidian / MCP (porta no PC)", e.componentes.obsidian_mcp.copy(visto = e.componentes.obsidian_mcp.observado_em), agora, velho)
                e.componentes.nota?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Titulo("Tokens e orçamento")
            Cartao(onClick = { nav.navigate("tokens") }) {
                val r = e.tokens_resumo
                LinhaValor("Consumo observado no período", r.consumo_total_periodo?.let { Fmt.inteiro(it) + " tokens" } ?: Fmt.ND, destaque = true)
                LinhaValor("Agentes com orçamento local", "${r.com_orcamento}")
                if (r.agentes_perto_limite.isEmpty()) Text("Nenhum agente perto do limite do orçamento local.", style = MaterialTheme.typography.bodySmall)
                r.agentes_perto_limite.forEach { LinhaValor(it.nome ?: it.agente, Fmt.pct(it.percentual), destaque = true) }
                Text("Cobertura: ${r.cobertura ?: "parcial"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("nova_tarefa") }, modifier = Modifier.weight(1f)) { Text("Nova tarefa") }
                OutlinedButton(onClick = { nav.navigate("nota") }, modifier = Modifier.weight(1f)) { Text("Nova nota") }
            }
        }
        item { Titulo("Tarefas recentes") }
        val recentes = e.tarefas.take(5)
        if (recentes.isEmpty()) item { Vazio("Nenhuma tarefa ainda.", "Enviar a primeira") { nav.navigate("nova_tarefa") } }
        items(recentes, key = { it.id }) { t ->
            val nome = e.agentes.firstOrNull { it.id == t.agente }?.nome ?: "agente removido"
            Cartao(onClick = { nav.navigate("tarefa/${t.id}") }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(nome, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Pilula(Fmt.status(t.status), t.status)
                }
                Text(t.texto, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { TextButton(onClick = { nav.navigate("tarefas") }) { Text("Ver todas as tarefas") } }
        item { Titulo("Sessões observadas no PC") }
        if (e.sessoes.isEmpty()) item { Vazio("Nenhuma sessão recente do Codex/Claude no PC.") }
        items(e.sessoes.take(8), key = { "s" + it.id }) { s ->
            Cartao {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((s.tipo ?: "") + " · " + (s.titulo ?: "sessão"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Pilula(s.estado ?: "?", if (s.estado == "trabalhando") "online" else "inativo")
                }
                Text(listOfNotNull(s.atividade, s.detalhe).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(Fmt.idade(s.atualizado, e.agora), style = MaterialTheme.typography.labelSmall)
            }
        }
        item { Titulo("Atividade recente") }
        items(e.feed.take(12), key = { "f${it.t}${it.texto.hashCode()}" }) { f ->
            Text("${Fmt.hora(f.t)} · ${f.agente}: ${f.texto}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp)) }
    }
}
