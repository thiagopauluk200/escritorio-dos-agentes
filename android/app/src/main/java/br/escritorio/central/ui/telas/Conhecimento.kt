package br.escritorio.central.ui.telas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.data.Dado
import br.escritorio.central.data.Eixos
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.GrafoResp
import br.escritorio.central.data.MetricasVisao
import br.escritorio.central.data.No
import br.escritorio.central.data.NotasBusca
import br.escritorio.central.data.Resumo
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.LocalCores
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.Idade
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Titulo
import br.escritorio.central.ui.comp.Vazio
import br.escritorio.central.ui.graficos.BarrasH
import br.escritorio.central.ui.graficos.Dispersao
import br.escritorio.central.ui.graficos.Linhas
import br.escritorio.central.ui.graficos.PontoXY
import br.escritorio.central.ui.graficos.Rede
import br.escritorio.central.ui.graficos.RedePreparada
import br.escritorio.central.ui.graficos.Serie
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val VISOES = listOf("rede" to "Rede", "barras" to "Barras", "linhas" to "Linhas", "dispersao" to "Dispersão",
    "comunicacao" to "Comunicação", "resposta" to "Resposta", "resumo" to "Resumo")

@Composable
fun TelaConhecimento(vm: AppVM, nav: NavController) {
    var aba by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = aba) {
            Tab(aba == 0, { aba = 0 }, text = { Text("Visualizações") })
            Tab(aba == 1, { aba = 1 }, text = { Text("Notas") })
        }
        if (aba == 0) Visualizacoes(vm, nav) else ListaNotas(vm, nav)
    }
}

@Composable
private fun Visualizacoes(vm: AppVM, nav: NavController) {
    val est by vm.estado.collectAsState()
    var visao by rememberSaveable { mutableStateOf("rede") }
    var periodo by rememberSaveable { mutableStateOf("7d") }
    var agente by rememberSaveable { mutableStateOf<String?>(null) }
    var grafo by remember { mutableStateOf<Dado<GrafoResp>?>(null) }
    var met by remember { mutableStateOf<Dado<MetricasVisao>?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var selecionado by rememberSaveable { mutableStateOf<String?>(null) }
    var buscaRede by rememberSaveable { mutableStateOf("") }
    var notaEmFoco by rememberSaveable { mutableStateOf<String?>(null) }
    var resultadosRede by remember { mutableStateOf<NotasBusca?>(null) }
    var erroBuscaRede by remember { mutableStateOf<Throwable?>(null) }
    var recarga by remember { mutableStateOf(0) }
    val revisao = est?.valor?.revisao
    LaunchedEffect(visao, recarga, revisao) {
        if (visao == "rede") runCatching { vm.repo.grafo() }.onSuccess { grafo = it; erro = null }.onFailure { erro = it }
    }
    LaunchedEffect(visao, buscaRede) {
        if (visao != "rede") return@LaunchedEffect
        val consulta = buscaRede.trim()
        if (consulta.length < 2) {
            resultadosRede = null
            erroBuscaRede = null
            return@LaunchedEffect
        }
        delay(300)
        runCatching { vm.repo.notas(consulta, null) }
            .onSuccess { resultadosRede = it; erroBuscaRede = null }
            .onFailure { erroBuscaRede = it }
    }
    LaunchedEffect(visao, periodo, agente, recarga, revisao) {
        if (visao != "rede") runCatching { vm.repo.metricas(periodo, agente) }.onSuccess { met = it; erro = null }.onFailure { erro = it }
    }
    val comOpenbot = est?.valor?.componentes?.openbot?.visto != null
    val preparada = remember(grafo?.valor?.versao, comOpenbot, notaEmFoco) {
        grafo?.valor?.let { RedePreparada(it.grafo, comOpenbot, notaEmFoco = notaEmFoco) }
    }
    // Com a rede aberta, o estado é consultado a cada 2,5 s para os pulsos de tráfego acompanharem o tempo real
    // (o ciclo geral do app usa 15 s quando ocioso). Para ao sair desta visão.
    LaunchedEffect(visao) {
        if (visao == "rede") while (true) { delay(2_500); runCatching { vm.repo.atualizarEstado() } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VISOES.forEach { (k, v) -> FilterChip(visao == k, { visao = k }, label = { Text(v) }) }
            }
            if (visao in setOf("comunicacao", "resposta", "resumo")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("24h", "7d", "30d", "tudo").forEach { p -> FilterChip(periodo == p, { periodo = p }, label = { Text(p) }) }
                }
                FiltroMenu("Agente", agente, est?.valor?.agentes?.map { it.id to it.nome }.orEmpty()) { agente = it }
            }
            erro?.let { ErroCartao(it) { recarga++ } }
        }
        when (visao) {
            "rede" -> item {
                OutlinedTextField(
                    value = buscaRede,
                    onValueChange = { buscaRede = it },
                    label = { Text("Encontrar nota na rede") },
                    supportingText = { Text("Pesquise pelo título ou conteúdo e destaque a nota no grafo.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                )
                erroBuscaRede?.let { ErroCartao(it) }
                resultadosRede?.notas?.take(5)?.forEach { nota ->
                    Cartao(onClick = {
                        notaEmFoco = nota.id
                        selecionado = nota.id
                        buscaRede = ""
                        resultadosRede = null
                    }) {
                        Text(nota.rotulo ?: nota.id, fontWeight = FontWeight.Medium)
                        Text("${nota.pasta} · ${nota.ligacoes} ligações · tocar para localizar",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (buscaRede.trim().length >= 2 && resultadosRede?.notas?.isEmpty() == true) {
                    Text("Nenhuma nota encontrada para “${buscaRede.trim()}”.", style = MaterialTheme.typography.labelSmall)
                }
                Idade(grafo)
                if (preparada == null) Carregando()
                else if (preparada.nos.isEmpty()) Vazio("A ponte ainda não enviou a rede de notas.")
                else {
                    val memoria = remember(est?.valor?.agentes) {
                        est?.valor?.agentes.orEmpty().filter { it.memoria && it.status == "trabalhando" }.map { it.motor }.toSet()
                    }
                    Rede(preparada, selecionado, est?.valor?.fluxo, est?.valor?.agora ?: 0L, memoria) { selecionado = it?.id }
                    val no = selecionado?.let { id -> preparada.nos.firstOrNull { it.id == id } }
                    if (no != null) DetalheNo(no, preparada) { nav.navigate("nota?id=" + android.net.Uri.encode(no.id)) }
                    grafo?.valor?.observado_em?.let { Text("Rede observada pela ponte em ${Fmt.hora(it)}", style = MaterialTheme.typography.labelSmall) }
                }
            }
            else -> item {
                Idade(met)
                val m = met?.valor
                if (m == null) Carregando() else GraficoMetricas(visao, m)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DetalheNo(n: No, r: RedePreparada, abrir: () -> Unit) {
    val viz = r.indice[n.id]?.let { r.vizinhos[it] }.orEmpty().map { r.nos[it] }
    Cartao {
        Text(n.rotulo, style = MaterialTheme.typography.titleSmall)
        LinhaValor("Tipo", if (n.tipo == "canal") "canal de dados" else n.tipo)
        n.grupo?.let { LinhaValor("Pasta / grupo", it) }
        LinhaValor("Ligações", "${n.conexoes}")
        n.mod?.let { LinhaValor("Última alteração (mtime)", Fmt.hora(it)) }
        if (viz.isNotEmpty()) Text("Relacionadas: " + viz.take(12).joinToString(", ") { it.rotulo }, style = MaterialTheme.typography.bodySmall)
        if (n.tipo == "nota") TextButton(onClick = abrir) { Text("Abrir nota") }
    }
}

private val diaFmt = DateTimeFormatter.ofPattern("dd/MM")

@Composable
private fun GraficoMetricas(visao: String, m: MetricasVisao) {
    val c = LocalCores.current
    when (visao) {
        "barras" -> {
            Titulo("Notas por pasta")
            val d = m.barras_pastas.dados
            if (d.isEmpty()) Vazio("Nenhuma nota sincronizada.") else {
                BarrasH(d.map { it.pasta to it.n.toDouble() }, c.info, "notas", "Quantidade")
                Text("Total: ${d.sumOf { it.n }} notas · ordenado por quantidade · fonte: ${m.barras_pastas.fonte ?: "?"}", style = MaterialTheme.typography.labelSmall)
            }
        }
        "linhas" -> {
            Titulo(m.linhas_mtime.rotulo)
            Text(m.linhas_mtime.aviso, style = MaterialTheme.typography.labelSmall, color = c.aviso)
            val d = m.linhas_mtime.dados
            val ini = LocalDate.now().minusDays(29)
            val serie = d.mapNotNull { runCatching { LocalDate.parse(it.dia) }.getOrNull()?.let { dt -> dt to it.n } }.filter { !it.first.isBefore(ini) }
            if (serie.isEmpty()) Vazio("Nenhuma nota com última alteração nos últimos 30 dias.")
            else Linhas(listOf(Serie("notas", c.info, serie.map { it.first.toEpochDay().toDouble() to it.second.toDouble() })),
                "Dia da última alteração (30 dias)", "Notas (contagem)", { LocalDate.ofEpochDay(it.toLong()).format(diaFmt) })
            Titulo(m.linhas_eventos.rotulo)
            Text(m.linhas_eventos.aviso + (m.linhas_eventos.desde?.let { " Coleta desde ${Fmt.hora(it)}." } ?: ""), style = MaterialTheme.typography.labelSmall)
            val ev = m.linhas_eventos.dados.mapNotNull { runCatching { LocalDate.parse(it.dia) }.getOrNull()?.let { dt -> dt.toEpochDay().toDouble() to it.n.toDouble() } }
            if (ev.isEmpty()) Vazio("Ainda não há alterações observadas desde o início da coleta.")
            else Linhas(listOf(Serie("alterações", c.ok, ev)), "Dia", "Alterações observadas", { LocalDate.ofEpochDay(it.toLong()).format(diaFmt) })
        }
        "dispersao" -> {
            Titulo("Idade × ligações das notas")
            val pts = m.dispersao.dados.map { PontoXY(it.id, it.rotulo ?: it.id, it.pasta, it.dias, it.ligacoes.toDouble()) }
            if (pts.isEmpty()) Vazio("Nenhuma nota sincronizada.") else {
                val grupos = pts.map { it.grupo }.distinct().sorted()
                Dispersao(pts, grupos.withIndex().associate { it.value to c.serie[it.index % c.serie.size] },
                    "Dias desde a última alteração", "Ligações", "dias", "nº")
                Text("X: ${m.dispersao.x}. Y: ${m.dispersao.y}. Cor: ${m.dispersao.cor}.", style = MaterialTheme.typography.labelSmall)
            }
        }
        "comunicacao" -> {
            Titulo("Latência de requisição/resposta")
            Text(m.comunicacao.definicao, style = MaterialTheme.typography.labelSmall)
            if (m.comunicacao.componentes.isEmpty()) Vazio("Sem amostras de latência no período.")
            m.comunicacao.componentes.forEach { (k, r) -> ResumoCartao(k, r, ms = true) }
            val porComp = m.comunicacao.serie.groupBy { it.comp }
            if (porComp.isNotEmpty()) {
                val maxMs = m.comunicacao.serie.maxOf { it.ms }
                val (un, div) = Eixos.unidadeTempo(maxMs)
                Linhas(porComp.entries.withIndex().map { (i, e) -> Serie(e.key, c.serie[i % c.serie.size], e.value.map { it.t.toDouble() to it.ms / div }) },
                    "Horário (local)", "Latência ($un)", { Fmt.hora(it.toLong()).substring(6, 11) })
            }
            Titulo("Espera na fila e execução por agente (mediana)")
            Text("Espera e execução são durações da TAREFA, não latência de rede.", style = MaterialTheme.typography.labelSmall, color = c.aviso)
            val linhas = m.resumo_agentes.filter { it.espera_fila_ms.mediana != null || it.execucao_ms.mediana != null }
            if (linhas.isEmpty()) Vazio("Sem tarefas instrumentadas com tempos completos no período.")
            else {
                val maxMs = linhas.maxOf { (it.espera_fila_ms.mediana ?: 0.0) + (it.execucao_ms.mediana ?: 0.0) }
                val (un, div) = Eixos.unidadeTempo(maxMs)
                BarrasH(emptyList(), c.info, un, "Mediana", empilhada = linhas.map {
                    "${it.nome ?: it.agente} (n=${it.execucao_ms.n})" to listOf((it.espera_fila_ms.mediana ?: 0.0) / div, (it.execucao_ms.mediana ?: 0.0) / div)
                }, cores = listOf(c.info, c.erro))
                br.escritorio.central.ui.graficos.Legenda(listOf("Espera na fila" to c.info, "Execução" to c.erro))
            }
        }
        "resposta" -> {
            Titulo("Tempo de resposta por tarefa")
            val d = m.tarefas.dados.filter { it.fim_central != null && it.total_tarefa_ms != null }
            val legado = m.tarefas.dados.count { it.legado }
            if (d.isEmpty()) Vazio("Ainda não há tarefas instrumentadas concluídas no período.")
            else {
                val maxMs = d.maxOf { it.total_tarefa_ms ?: 0.0 }
                val (un, div) = Eixos.unidadeTempo(maxMs)
                val x = { t: br.escritorio.central.data.TarefaTempo -> t.fim_central!!.toDouble() }
                Linhas(listOf(
                    Serie("Envio → conclusão (Central)", c.info, d.map { x(it) to it.total_tarefa_ms!! / div }),
                    Serie("Espera na fila", c.aviso, d.filter { it.espera_fila_ms != null }.map { x(it) to it.espera_fila_ms!! / div }),
                    Serie("Execução", c.ok, d.filter { it.execucao_ms != null }.map { x(it) to it.execucao_ms!! / div }),
                    Serie("Primeira saída", c.serie[3], d.filter { it.primeira_saida_ms != null }.map { x(it) to it.primeira_saida_ms!! / div }),
                ).filter { it.pontos.isNotEmpty() }, "Conclusão (horário local)", "Duração ($un)",
                    { Instant.ofEpochMilli(it.toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) })
                Text("n=${d.size}. Confirmação do envio é medida no app (latência app→Central).", style = MaterialTheme.typography.labelSmall)
            }
            if (legado > 0) Text("$legado tarefa(s) anteriores à instrumentação foram excluídas: seus horários vêm de relógios diferentes (J7 e PC).",
                style = MaterialTheme.typography.labelSmall, color = c.aviso)
        }
        "resumo" -> {
            Titulo("Resumo por agente")
            Text("Percentil: ${m.percentil}.", style = MaterialTheme.typography.labelSmall)
            val s = m.sucesso_geral
            Cartao {
                Text("Pontuação operacional (taxa de sucesso)", fontWeight = FontWeight.SemiBold)
                LinhaValor("Sucesso", s.sucesso_percentual?.let { Fmt.pct(it) + " de 100" } ?: Fmt.ND, destaque = true)
                LinhaValor("n (finalizadas elegíveis)", "${s.denominador}")
                s.contagens.forEach { (k, v) -> LinhaValor(k.replace('_', ' '), "$v") }
                Text("${s.definicao}. Período: ${m.periodo}. Velocidade não é qualidade.", style = MaterialTheme.typography.labelSmall)
            }
            if (m.resumo_agentes.isEmpty()) Vazio("Sem tarefas no período.")
            m.resumo_agentes.forEach { a ->
                Cartao {
                    Text(a.nome ?: a.agente, style = MaterialTheme.typography.titleSmall)
                    LinhaValor("Sucesso", (a.sucesso.sucesso_percentual?.let { Fmt.pct(it) } ?: Fmt.ND) + " (n=${a.sucesso.denominador})")
                    LinhaValor("Taxa de erro", a.sucesso.sucesso_percentual?.let { Fmt.pct(100 - it) } ?: Fmt.ND)
                    ResumoLinhas("Total", a.total_tarefa_ms)
                    ResumoLinhas("Espera na fila", a.espera_fila_ms)
                    ResumoLinhas("Execução", a.execucao_ms)
                    ResumoLinhas("Primeira saída", a.primeira_saida_ms)
                }
            }
        }
    }
}

@Composable
private fun ResumoLinhas(nome: String, r: Resumo) {
    LinhaValor("$nome (n=${r.n})", if (r.n == 0) Fmt.ND else
        "média ${Fmt.duracao(r.media)} · mediana ${Fmt.duracao(r.mediana)} · p95 ${Fmt.duracao(r.p95)}")
    if (r.aviso.isNotBlank() && r.n > 0) Text(r.aviso, style = MaterialTheme.typography.labelSmall, color = LocalCores.current.aviso)
}

@Composable
private fun ResumoCartao(nome: String, r: Resumo, ms: Boolean) {
    Cartao {
        Text(nome, fontWeight = FontWeight.SemiBold)
        ResumoLinhas("Latência", r)
        LinhaValor("mín / máx", "${Fmt.duracao(r.min)} / ${Fmt.duracao(r.max)}")
    }
}

@Composable
private fun ListaNotas(vm: AppVM, nav: NavController) {
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<NotasBusca?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    val ops = vm.estado.collectAsState().value?.valor?.ops.orEmpty()
    LaunchedEffect(q) {
        delay(300)
        runCatching { vm.repo.notas(q, null) }.onSuccess { res = it; erro = null }.onFailure { erro = it }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, label = { Text("Pesquisar notas (título e conteúdo)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = { nav.navigate("nota") }) { Text("Nova nota em 00-Inbox") }
            erro?.let { ErroCartao(it) }
        }
        val pend = ops.filter { it.status in setOf("pendente", "entregue", "erro", "conflito") }
        if (pend.isNotEmpty()) {
            item { Titulo("Gravações no vault") }
            items(pend, key = { "op" + it.id }) { o ->
                Cartao {
                    Text((o.titulo ?: o.nota ?: o.id) + " · " + (if (o.tipo == "criar") "criação" else "edição"))
                    Text(when (o.status) {
                        "pendente" -> "Pendente: aguardando a ponte do PC."
                        "entregue" -> "Entregue à ponte; aguardando confirmação da gravação."
                        "conflito" -> "Conflito: a nota mudou em outro lugar. Reabra e edite de novo. ${o.msg ?: ""}"
                        else -> "Erro: ${o.msg ?: "?"}"
                    }, style = MaterialTheme.typography.bodySmall, color = br.escritorio.central.ui.comp.corEstado(o.status))
                }
            }
        }
        val l = res?.notas
        if (l == null && erro == null) item { Carregando() }
        if (l != null && l.isEmpty()) item { Vazio(if (q.isBlank()) "Nenhuma nota sincronizada." else "Nada encontrado para “$q”.") }
        items(l.orEmpty(), key = { it.id }) { n ->
            Cartao(onClick = { nav.navigate("nota?id=" + android.net.Uri.encode(n.id)) }) {
                Text(n.rotulo ?: n.id, fontWeight = FontWeight.Medium)
                Text("${n.pasta} · ${n.ligacoes} ligações · alterada ${Fmt.hora(n.mod)}" + if (!n.sincronizada) " · conteúdo ainda não sincronizado" else "",
                    style = MaterialTheme.typography.labelSmall)
                n.trecho?.takeIf { it.isNotBlank() }?.let { Text("…$it…", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
