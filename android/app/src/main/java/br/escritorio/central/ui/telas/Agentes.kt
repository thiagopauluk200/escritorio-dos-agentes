package br.escritorio.central.ui.telas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.data.Agente
import br.escritorio.central.data.Dado
import br.escritorio.central.data.EscopoPasta
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.TokensAgente
import br.escritorio.central.data.TokensVisao
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.LocalCores
import br.escritorio.central.ui.comp.BarraConsumo
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.Idade
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import br.escritorio.central.ui.comp.Titulo
import br.escritorio.central.ui.comp.Vazio
import kotlinx.coroutines.launch

@Composable
fun TelaAgentes(vm: AppVM, nav: NavController) {
    val dado by vm.estado.collectAsState()
    val e = dado?.valor
    var editar by remember { mutableStateOf<Agente?>(null) }
    var novo by rememberSaveable { mutableStateOf(false) }
    var filtro by rememberSaveable { mutableStateOf<String?>(null) }
    var tokens by remember { mutableStateOf<Dado<TokensVisao>?>(null) }
    LaunchedEffect(e?.revisao) { tokens = runCatching { vm.repo.tokens(null, null, null, null) }.getOrNull() ?: tokens }

    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { novo = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Novo agente") })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item { Idade(dado) }
            if (e == null) { item { Carregando() }; return@LazyColumn }
            val porAgente = tokens?.valor?.agentes.orEmpty().associateBy { it.agente }
            item { TituloSecao("Equipe", "agentes originais do OpenBot"); PalcoEquipe(e.agentes, porAgente, e.agora, e.openbot_agentes, filtro) { filtro = it } }
            val lista = e.agentes.filter { filtro == null || it.motor == filtro }
            item {
                val nomeFiltro = br.escritorio.central.ui.comp.Original.doMotor(filtro)?.nome
                TituloSecao("Seus agentes", if (nomeFiltro != null) "só $nomeFiltro · toque de novo no personagem para ver todos" else "${e.agentes.size} cadastrados")
            }
            if (e.agentes.isEmpty()) item { Vazio("Nenhum agente cadastrado.", "Criar agente") { novo = true } }
            else if (lista.isEmpty()) item { Vazio("Nenhum agente deste tipo na Central.") }
            items(lista, key = { it.id }) { a ->
                CartaoAgenteVivo(a, porAgente[a.id], e.agora) { nav.navigate("agente/${a.id}") }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (novo || editar != null) {
        DialogoAgente(vm, editar, e?.projetos.orEmpty(), e?.escopo_pastas.orEmpty()) { novo = false; editar = null }
    }
}

@Composable
fun DialogoAgente(vm: AppVM, a: Agente?, projetos: List<String>, escopo: List<EscopoPasta>, fechar: () -> Unit) {
    val escopoCo = rememberCoroutineScope()
    var nome by rememberSaveable { mutableStateOf(a?.nome ?: "") }
    var motor by rememberSaveable { mutableStateOf(a?.motor ?: "codex") }
    var funcao by rememberSaveable { mutableStateOf(a?.funcao ?: "") }
    var pasta by rememberSaveable { mutableStateOf(a?.pasta ?: "") }
    var menu by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val idem = rememberSaveable { java.util.UUID.randomUUID().toString() }  // uma chave por formulário aberto
    AlertDialog(
        onDismissRequest = fechar,
        title = { Text(if (a == null) "Novo agente" else "Editar agente") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(nome, { nome = it.take(40) }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = motor == "codex", onClick = { motor = "codex" }, label = { Text("Codex") })
                    FilterChip(selected = motor == "claude", onClick = { motor = "claude" }, label = { Text("Claude Code") })
                }
                OutlinedTextField(funcao, { funcao = it.take(1500) }, label = { Text("Função") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Projeto: " + pasta.ifBlank { "nenhum" })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("nenhum (pasta vazia, só leitura)") }, onClick = { pasta = ""; menu = false })
                    projetos.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { pasta = p; menu = false }) }
                }
                escopo.firstOrNull { it.nome == pasta }?.let { s ->
                    Text("Escopo declarado pela ponte: " + (if (s.escrita) "leitura e ESCRITA" else "somente leitura") +
                        " · codex: ${s.sandbox_codex ?: "?"}", style = MaterialTheme.typography.bodySmall,
                        color = if (s.escrita) LocalCores.current.aviso else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (projetos.isEmpty()) Text("A ponte ainda não publicou projetos.", style = MaterialTheme.typography.bodySmall)
                erro?.let { ErroCartao(it) }
            }
        },
        confirmButton = {
            Button(enabled = nome.isNotBlank() && !ocupado, onClick = {
                ocupado = true
                escopoCo.launch {
                    runCatching { vm.repo.salvarAgente(a?.id, nome, motor, funcao, pasta, idem); vm.repo.atualizarEstado() }
                        .onSuccess { fechar() }.onFailure { erro = it }
                    ocupado = false
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = fechar) { Text("Cancelar") } },
    )
}

/** Cartão de tokens: separa orçamento local, cota do provedor, contexto e vazão (unidades e denominadores distintos). */
@Composable
fun CartaoTokens(t: TokensAgente, detalhado: Boolean, onEditar: (() -> Unit)? = null) {
    val o = t.orcamento_local
    Cartao {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t.nome ?: t.agente, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (o.alerta_atingido != null) Pilula("alerta ${o.alerta_atingido}%", if (o.alerta_atingido >= 100) "excedido" else "degradado")
        }
        Text("Orçamento de produção (local)", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        if (!o.configurado) {
            Text("Sem orçamento configurado. Consumo observado: ${t.consumo_periodo?.let { Fmt.inteiro(it) + " tokens" } ?: Fmt.ND}" +
                " (${Fmt.periodo(o.periodo)}).", style = MaterialTheme.typography.bodySmall)
        } else {
            when (o.estado) {
                "limite_zero" -> Text("Limite configurado como 0 tokens: qualquer uso excede. Consumo: ${Fmt.inteiro(o.consumo)}.",
                    color = LocalCores.current.erro, style = MaterialTheme.typography.bodySmall)
                "sem_dados" -> Text("Nenhum uso registrado no período (não é 0%: não há dados de uso).", style = MaterialTheme.typography.bodySmall)
                else -> BarraConsumo(o.percentual_consumido, "Consumido do orçamento", o.alertas)
            }
            LinhaValor("Consumido", Fmt.inteiro(o.consumo) + " tokens")
            LinhaValor("Limite (orçamento local)", Fmt.inteiro(o.limite_tokens) + " tokens / " + Fmt.periodo(o.periodo))
            LinhaValor("Saldo", o.saldo_tokens?.let { Fmt.inteiro(it) + " tokens (" + Fmt.pct(o.percentual_disponivel) + ")" } ?: Fmt.ND)
            if ((o.excesso_tokens ?: 0) > 0) LinhaValor("Excesso", Fmt.inteiro(o.excesso_tokens) + " tokens", destaque = true)
            LinhaValor("Período", "desde ${Fmt.hora(o.inicio_periodo)} · renova ${Fmt.hora(o.renovacao)} · ${o.fuso} · ${o.tipo_janela ?: ""}")
            LinhaValor("Bloqueio de novas tarefas", if (o.bloquear) "ativado (na Central)" else "desligado (só alertas)")
            o.projecao?.let { p ->
                Text("Estimativa de esgotamento: ${Fmt.hora(p.esgota_em_ms)} (${p.metodo}; n=${p.amostras}, ${"%.1f".format(p.intervalo_horas)} h)",
                    style = MaterialTheme.typography.bodySmall, color = LocalCores.current.aviso)
            } ?: Text("Sem projeção: histórico insuficiente.", style = MaterialTheme.typography.labelSmall)
        }
        Text("Origem: ${o.origem.ifBlank { "orçamento local da Central" }}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinhaValor("Última atualização do uso", Fmt.hora(t.ultima_atualizacao))
        if (detalhado) {
            Text("Cota do provedor", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Text(if (t.cota_provedor.disponivel) "disponível" else "Limite não disponibilizado. ${t.cota_provedor.motivo}", style = MaterialTheme.typography.bodySmall)
            Text("Ocupação do contexto", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            Text(if (t.contexto.disponivel) "disponível" else "${Fmt.ND}. ${t.contexto.motivo}", style = MaterialTheme.typography.bodySmall)
            Text("Limites de vazão / assinatura", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            Text(if (t.vazao.disponivel) "disponível" else "${Fmt.ND}. ${t.vazao.motivo}", style = MaterialTheme.typography.bodySmall)
            Text("Cobertura: ${t.cobertura}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            if (t.por_modelo.isNotEmpty()) {
                Text("Por modelo (informado pelo executor)", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                t.por_modelo.forEach { LinhaValor(it.modelo, Fmt.inteiro(it.tokens)) }
            }
            if (t.historico_dia.isNotEmpty()) {
                Text("Histórico por dia (30 dias)", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                t.historico_dia.takeLast(10).forEach { LinhaValor(it.dia, Fmt.inteiro(it.tokens)) }
            }
            if (t.por_tarefa.isNotEmpty()) {
                Text("Por tarefa no período", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                t.por_tarefa.takeLast(15).forEach {
                    LinhaValor("${it.tarefa} (tent. ${it.tentativa ?: 1}, ${it.status ?: "?"})",
                        "${Fmt.inteiro(it.tokens)} · ent ${Fmt.inteiro(it.entrada)} / saí ${Fmt.inteiro(it.saida)}" +
                            (it.custo_usd?.let { c -> " · US$ %.4f".format(c) } ?: ""))
                }
            }
        }
        if (onEditar != null) TextButton(onClick = onEditar) { Text(if (o.configurado) "Editar orçamento" else "Definir orçamento") }
    }
}

@Composable
fun DialogoOrcamento(vm: AppVM, t: TokensAgente, fechar: (Boolean) -> Unit) {
    val escopo = rememberCoroutineScope()
    val o = t.orcamento_local
    var limite by rememberSaveable { mutableStateOf(o.limite_tokens?.toString() ?: "") }
    var periodo by rememberSaveable { mutableStateOf(o.periodo) }
    var alertas by rememberSaveable { mutableStateOf(o.alertas.joinToString(", ")) }
    var bloquear by rememberSaveable { mutableStateOf(o.bloquear) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    AlertDialog(onDismissRequest = { fechar(false) }, title = { Text("Orçamento de ${t.nome}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Orçamento LOCAL de produção: não é o limite do provedor.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(limite, { limite = it.filter(Char::isDigit).take(12) }, label = { Text("Limite (tokens)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("dia", "semana", "mes", "total").forEach { p -> FilterChip(periodo == p, { periodo = p }, label = { Text(Fmt.periodo(p)) }) }
            }
            OutlinedTextField(alertas, { alertas = it }, label = { Text("Alertas (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(bloquear, { bloquear = it })
                Text("Bloquear NOVAS tarefas ao atingir o limite")
            }
            Text("O bloqueio é aplicado pela Central, considerando tarefas na fila/execução (reserva pela média). Não controla chamadas fora da Central, " +
                "não cancela tarefas em execução e não garante evitar todo excedente de tarefas em andamento.", style = MaterialTheme.typography.labelSmall)
            erro?.let { ErroCartao(it) }
        }
    }, confirmButton = {
        Button(onClick = {
            escopo.launch {
                runCatching {
                    val als = alertas.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..100 }
                    vm.repo.salvarOrcamento(t.agente, limite.toLongOrNull(), periodo, als.ifEmpty { listOf(70, 85, 95) }, bloquear)
                }.onSuccess { fechar(true) }.onFailure { erro = it }
            }
        }) { Text("Salvar") }
    }, dismissButton = {
        Row {
            if (o.configurado) TextButton(onClick = {
                escopo.launch { runCatching { vm.repo.salvarOrcamento(t.agente, null, periodo, listOf(70, 85, 95), false, remover = true) }
                    .onSuccess { fechar(true) }.onFailure { erro = it } }
            }) { Text("Remover") }
            TextButton(onClick = { fechar(false) }) { Text("Cancelar") }
        }
    })
}

@Composable
fun TelaAgenteDetalhe(vm: AppVM, nav: NavController, id: String) {
    val dado by vm.estado.collectAsState()
    val e = dado?.valor
    val a = e?.agentes?.firstOrNull { it.id == id }
    val escopoCo = rememberCoroutineScope()
    var tokens by remember { mutableStateOf<Dado<TokensVisao>?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var editar by remember { mutableStateOf(false) }
    var orc by remember { mutableStateOf(false) }
    var excluir by remember { mutableStateOf(false) }
    var recarga by remember { mutableStateOf(0) }
    LaunchedEffect(id, recarga, e?.revisao) {
        runCatching { vm.repo.tokens(null, id, null, null) }.onSuccess { tokens = it; erro = null }.onFailure { erro = it }
    }
    if (a == null) { Vazio(if (e == null) "Carregando…" else "Agente não encontrado (talvez excluído)."); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Cartao {
                Text(a.nome, style = MaterialTheme.typography.titleLarge)
                LinhaValor("Motor", a.motor)
                LinhaValor("Projeto", a.pasta.ifBlank { "nenhum" })
                e.escopo_pastas.firstOrNull { it.nome == a.pasta }?.let {
                    LinhaValor("Escopo (declarado pela ponte)", if (it.escrita) "leitura e escrita" else "somente leitura")
                    LinhaValor("Pasta existe no PC", if (it.existe) "sim" else "não")
                }
                LinhaValor("Estado", Fmt.status(a.status))
                if (a.funcao.isNotBlank()) Text(a.funcao, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { nav.navigate("nova_tarefa?agente=${a.id}") }) { Text("Enviar tarefa") }
                    OutlinedButton(onClick = { editar = true }) { Text("Editar") }
                    TextButton(onClick = { excluir = true }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { Titulo("Tokens"); Idade(tokens) }
        erro?.let { item { ErroCartao(it) { recarga++ } } }
        tokens?.valor?.agentes?.firstOrNull()?.let { t -> item { CartaoTokens(t, detalhado = true) { orc = true } } }
        item { Titulo("Tarefas deste agente") }
        val ts = e.tarefas.filter { it.agente == id }
        if (ts.isEmpty()) item { Vazio("Sem tarefas.") }
        items(ts.take(20), key = { it.id }) { t ->
            Cartao(onClick = { nav.navigate("tarefa/${t.id}") }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.hora(t.criado), style = MaterialTheme.typography.bodySmall)
                    Pilula(Fmt.status(t.status), t.status)
                }
                Text(t.texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (editar) DialogoAgente(vm, a, e.projetos, e.escopo_pastas) { editar = false }
    if (orc) tokens?.valor?.agentes?.firstOrNull()?.let { t -> DialogoOrcamento(vm, t) { mudou -> orc = false; if (mudou) recarga++ } }
    if (excluir) AlertDialog(onDismissRequest = { excluir = false }, title = { Text("Excluir ${a.nome}?") },
        text = { Text("Tarefas na fila serão canceladas; em execução recebem pedido de cancelamento. Notas no vault não são apagadas.") },
        confirmButton = {
            Button(onClick = {
                escopoCo.launch { runCatching { vm.repo.excluirAgente(a.id); vm.repo.atualizarEstado() }; excluir = false; nav.popBackStack() }
            }) { Text("Excluir") }
        }, dismissButton = { TextButton(onClick = { excluir = false }) { Text("Cancelar") } })
}

@Composable
fun TelaTokens(vm: AppVM, nav: NavController) {
    val dado by vm.estado.collectAsState()
    val e = dado?.valor
    var periodo by rememberSaveable { mutableStateOf<String?>(null) }
    var agente by rememberSaveable { mutableStateOf<String?>(null) }
    var projeto by rememberSaveable { mutableStateOf<String?>(null) }
    var modelo by rememberSaveable { mutableStateOf<String?>(null) }
    var tokens by remember { mutableStateOf<Dado<TokensVisao>?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var orcDe by remember { mutableStateOf<TokensAgente?>(null) }
    var recarga by remember { mutableStateOf(0) }
    LaunchedEffect(periodo, agente, projeto, modelo, recarga) {
        runCatching { vm.repo.tokens(periodo, agente, projeto, modelo) }.onSuccess { tokens = it; erro = null }.onFailure { erro = it }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Período", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(periodo == null, { periodo = null }, label = { Text("do orçamento") })
                listOf("dia", "semana", "mes").forEach { p -> FilterChip(periodo == p, { periodo = p }, label = { Text(Fmt.periodo(p)) }) }
            }
            FiltroMenu("Agente", agente, e?.agentes?.map { it.id to it.nome }.orEmpty()) { agente = it }
            FiltroMenu("Projeto", projeto, e?.projetos?.map { it to it }.orEmpty()) { projeto = it }
            val modelos = tokens?.valor?.agentes?.flatMap { a -> a.por_modelo.map { it.modelo } }?.distinct().orEmpty()
            FiltroMenu("Modelo", modelo, modelos.map { it to it }) { modelo = it }
            Idade(tokens)
            tokens?.valor?.definicao_total?.let { Text("Total = $it", style = MaterialTheme.typography.labelSmall) }
        }
        erro?.let { item { ErroCartao(it) { recarga++ } } }
        val lista = tokens?.valor?.agentes.orEmpty()
        if (tokens != null && lista.isEmpty()) item { Vazio("Nenhum agente para os filtros.") }
        if (tokens == null && erro == null) item { Carregando() }
        items(lista, key = { it.agente }) { t -> CartaoTokens(t, detalhado = agente != null) { orcDe = t } }
        item { Spacer(Modifier.height(24.dp)) }
    }
    orcDe?.let { t -> DialogoOrcamento(vm, t) { mudou -> orcDe = null; if (mudou) recarga++ } }
}

@Composable
fun FiltroMenu(rotulo: String, valor: String?, opcoes: List<Pair<String, String>>, onEscolher: (String?) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { aberto = true }) {
            Text("$rotulo: " + (opcoes.firstOrNull { it.first == valor }?.second ?: valor ?: "todos"))
        }
        DropdownMenu(aberto, { aberto = false }) {
            DropdownMenuItem(text = { Text("todos") }, onClick = { onEscolher(null); aberto = false })
            opcoes.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { onEscolher(k); aberto = false }) }
        }
        Spacer(Modifier.width(4.dp))
    }
}
