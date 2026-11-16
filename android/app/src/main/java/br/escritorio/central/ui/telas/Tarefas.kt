package br.escritorio.central.ui.telas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.data.Dado
import br.escritorio.central.data.EnvioPendente
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.LogsResp
import br.escritorio.central.data.TarefaDetalhe
import br.escritorio.central.data.TotalUso
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.LocalCores
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.Idade
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import br.escritorio.central.ui.comp.Titulo
import br.escritorio.central.ui.comp.Vazio
import br.escritorio.central.ui.comp.corEstado
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Composable
fun TelaTarefas(vm: AppVM, nav: NavController) {
    val dado by vm.estado.collectAsState()
    val envios by vm.repo.envios.collectAsState(initial = emptyList())
    val e = dado?.valor
    var filtro by rememberSaveable { mutableStateOf("todas") }
    var busca by rememberSaveable { mutableStateOf("") }
    val escopo = rememberCoroutineScope()
    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { nav.navigate("nova_tarefa") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Nova tarefa") })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            item { Idade(dado) }
            val pend = envios.filter { it.estado != "confirmado" }
            if (pend.isNotEmpty()) {
                item { Titulo("Envios não confirmados") }
                items(pend, key = { "env" + it.idem }) { p -> CartaoEnvio(p, onReenviar = {
                    escopo.launch { runCatching { vm.repo.enviarTarefa(p.agente, p.texto, p.registrarNota, p.idem) } }
                }, onDescartar = { escopo.launch { vm.repo.descartarEnvio(p.idem) } }) }
            }
            item {
                OutlinedTextField(busca, { busca = it }, label = { Text("Buscar no histórico") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(listOf("todas", "fila", "rodando", "concluida", "falhou", "cancelada")) { f ->
                        FilterChip(filtro == f, { filtro = f }, label = { Text(if (f == "todas") "todas" else Fmt.status(f)) })
                    }
                }
            }
            if (e == null) { item { Carregando() }; return@LazyColumn }
            val nomes = e.agentes.associate { it.id to it.nome }
            val lista = e.tarefas.filter { (filtro == "todas" || it.status == filtro) &&
                (busca.isBlank() || it.texto.contains(busca, true) || (nomes[it.agente] ?: "").contains(busca, true) || it.id.contains(busca)) }
            if (lista.isEmpty()) item { Vazio("Nenhuma tarefa com esse filtro.") }
            items(lista, key = { it.id }) { t ->
                Cartao(onClick = { nav.navigate("tarefa/${t.id}") }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nomes[t.agente] ?: "agente removido", fontWeight = FontWeight.Medium)
                            Text("${Fmt.hora(t.criado)} · ${t.origem ?: "web"}" + if (t.tentativa > 1) " · tentativa ${t.tentativa}" else "",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Pilula(Fmt.status(t.status) + if (t.cancelar == true) " (cancelando)" else "", t.status)
                    }
                    Text(t.texto, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (t.status == "rodando" && !t.atividade.isNullOrBlank()) Text("▶ ${t.atividade}", style = MaterialTheme.typography.labelSmall)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun CartaoEnvio(p: EnvioPendente, onReenviar: () -> Unit, onDescartar: () -> Unit) {
    Cartao {
        Text(p.texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when (p.estado) {
            "enviando" -> "Enviando…"
            "nao_confirmado" -> "Sem confirmação da Central (${p.erro ?: "rede"}). Reenviar usa o MESMO identificador: se a Central já recebeu, não duplica."
            "recusado" -> "Recusado pela Central: ${p.erro}"
            else -> p.estado
        }, style = MaterialTheme.typography.bodySmall, color = corEstado(if (p.estado == "recusado") "erro" else "pendente"))
        Text("id de envio: ${p.idem.take(8)}… · tentativas: ${p.tentativas}", style = MaterialTheme.typography.labelSmall)
        Row {
            if (p.estado == "nao_confirmado") TextButton(onClick = onReenviar) { Text("Confirmar envio") }
            TextButton(onClick = onDescartar) { Text("Descartar") }
        }
    }
}

@Composable
fun TelaNovaTarefa(vm: AppVM, nav: NavController, agenteInicial: String?) {
    val dado by vm.estado.collectAsState()
    val e = dado?.valor
    val escopo = rememberCoroutineScope()
    var agente by rememberSaveable { mutableStateOf(agenteInicial) }
    var texto by rememberSaveable { mutableStateOf("") }
    var nota by rememberSaveable { mutableStateOf(true) }
    var carregouRascunho by rememberSaveable { mutableStateOf(false) }
    var enviando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    // UM identificador por rascunho (persistido): repetir o toque após falha reutiliza o mesmo idem,
    // então a Central nunca cria duas tarefas para o mesmo pedido. Só muda depois de envio confirmado.
    var idem by rememberSaveable { mutableStateOf<String?>(null) }
    // rascunho persiste em Room: sobrevive a rotação, suspensão e morte do processo
    LaunchedEffect(Unit) {
        if (!carregouRascunho) {
            vm.repo.rascunho("nova_tarefa")?.let { r -> if (texto.isBlank()) texto = r.texto; if (agente == null && r.extra.isNotBlank()) agente = r.extra }
            if (idem == null) idem = vm.repo.rascunho("nova_tarefa_idem")?.texto ?: UUID.randomUUID().toString().also { vm.repo.salvarRascunho("nova_tarefa_idem", it) }
            carregouRascunho = true
        }
    }
    LaunchedEffect(texto, agente) { if (carregouRascunho) { delay(400); vm.repo.salvarRascunho("nova_tarefa", texto, agente ?: "") } }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Agente", style = MaterialTheme.typography.labelLarge)
        if (e == null) Carregando()
        e?.agentes?.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                FilterChip(agente == a.id, { agente = a.id }, label = { Text("${a.nome} · ${a.motor} · ${a.pasta.ifBlank { "sem projeto" }}") })
            }
        }
        if (e != null && e.agentes.isEmpty()) Vazio("Crie um agente antes.", "Ir para Agentes") { nav.navigate("agentes") }
        val esc = e?.escopo_pastas?.firstOrNull { it.nome == e.agentes.firstOrNull { a -> a.id == agente }?.pasta }
        esc?.let { Text("Escopo: " + if (it.escrita) "leitura e ESCRITA em ${it.nome}" else "somente leitura em ${it.nome}",
            style = MaterialTheme.typography.bodySmall, color = if (it.escrita) LocalCores.current.aviso else MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(texto, { texto = it.take(6000) }, label = { Text("Tarefa") }, minLines = 5,
            supportingText = { Text("${texto.length}/6000 · rascunho salvo automaticamente") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(nota, { nota = it }); Text("Registrar o resultado como nota em 00-Inbox")
        }
        if (e?.componentes?.ponte?.estado !in setOf("online", "degradado"))
            Text("A ponte do PC não está online: a tarefa fica na fila até ela voltar.", color = LocalCores.current.aviso, style = MaterialTheme.typography.bodySmall)
        Button(enabled = !enviando && agente != null && texto.isNotBlank() && idem != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = {
            enviando = true; erro = null  // desabilita na hora: toques repetidos não criam outra tarefa
            escopo.launch {
                runCatching { vm.repo.enviarTarefa(agente!!, texto.trim(), nota, idem) }
                    .onSuccess { t -> vm.repo.apagarRascunho("nova_tarefa"); vm.repo.apagarRascunho("nova_tarefa_idem"); idem = null; texto = ""; vm.atualizarAgora()
                        nav.navigate("tarefa/${t.id}") { popUpTo("nova_tarefa") { inclusive = true } } }
                    .onFailure { erro = it }
                enviando = false
            }
        }) { Text(if (enviando) "Enviando…" else "Enviar") }
        erro?.let {
            ErroCartao(it)
            Text("Se a Central chegou a receber, a tarefa aparecerá uma única vez; o envio ficou em “Envios não confirmados” para reconfirmar com o mesmo identificador.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copiar(ctx: Context, rotulo: String, texto: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(rotulo, texto))
}

private fun compartilhar(ctx: Context, texto: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartilhar"))
}

@Composable
fun TelaTarefaDetalhe(vm: AppVM, nav: NavController, id: String) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val est by vm.estado.collectAsState()
    var d by remember { mutableStateOf<Dado<TarefaDetalhe>?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var acaoErro by remember { mutableStateOf<Throwable?>(null) }
    var confirmarCancel by remember { mutableStateOf(false) }
    var confirmarRepetir by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<LogsResp?>(null) }
    var verLogs by rememberSaveable { mutableStateOf(false) }
    var recarga by remember { mutableStateOf(0) }
    val idemRepetir = rememberSaveable(id) { UUID.randomUUID().toString() }  // estável: toque duplo não cria 2 tentativas
    // atualiza enquanto não finaliza; o estado final é sempre o da Central
    LaunchedEffect(id, recarga, est?.valor?.revisao) {
        runCatching { vm.repo.tarefa(id) }.onSuccess { d = it; erro = null }.onFailure { erro = it }
    }
    LaunchedEffect(verLogs, recarga) { if (verLogs) logs = runCatching { vm.repo.logs(mapOf("tarefa" to id, "limite" to "100")) }.getOrNull() }
    val t = d?.valor
    if (t == null) { Column(Modifier.padding(16.dp)) { if (erro != null) ErroCartao(erro!!) { recarga++ } else Carregando() }; return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Idade(d)
            Cartao {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(t.agente_nome ?: "agente", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pilula(Fmt.status(t.status) + if (t.cancelar == true) " (cancelamento pedido)" else "", t.status)
                }
                LinhaValor("Tarefa", t.id)
                LinhaValor("Trace", t.trace_id ?: Fmt.ND)
                LinhaValor("Tentativa", "${t.tentativa}" + (t.tentativa_de?.let { " (repete $it)" } ?: ""))
                LinhaValor("Origem", t.origem ?: "web")
                LinhaValor("Projeto", t.projeto ?: Fmt.ND)
                LinhaValor("Criada (Central)", Fmt.horaComFuso(t.criado))
                LinhaValor("Modelo usado", t.modelo ?: "${Fmt.ND} (não informado pelo executor)")
                Fmt.motivo(t.motivo)?.let { LinhaValor("Motivo", it, destaque = true) }
                t.codigo_saida?.let { LinhaValor("Código de saída", "$it") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (t.status in setOf("fila", "rodando") && t.cancelar != true)
                        OutlinedButton(onClick = { confirmarCancel = true }) { Text("Cancelar") }
                    if (t.status in setOf("concluida", "falhou", "cancelada"))
                        OutlinedButton(onClick = { confirmarRepetir = true }) { Text("Repetir (nova tentativa)") }
                }
                acaoErro?.let { ErroCartao(it) }
            }
        }
        item {
            Titulo("Percurso")
            Cartao {
                t.trilha.forEachIndexed { i, et ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.labelMedium)
                        Column(Modifier.weight(1f)) {
                            Text(et.rotulo)
                            Text(et.t?.let { Fmt.hora(it) + " (Central)" } ?: "—", style = MaterialTheme.typography.labelSmall)
                        }
                        Pilula(rotuloEtapa(et.estado), et.estado)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("${t.trilha.size + 1}.", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Atualização no app", Modifier.weight(1f))
                    Pilula(if (d!!.doCache) "dados salvos" else "recebida ${Fmt.idade(d!!.atualizadoEm)}", if (d!!.doCache) "sem_dados_recentes" else "ok")
                }
                if (t.status == "concluida" && t.nota_status != "gravada" && t.nota_status != "nao_solicitada")
                    Text("O executor terminou, mas a gravação da nota no vault ainda não foi confirmada pela ponte.",
                        color = LocalCores.current.aviso, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Titulo("Tempos")
            Cartao {
                val tp = t.tempos
                LinhaValor("Espera na fila", Fmt.duracao(tp?.espera_fila_ms))
                LinhaValor("Execução (relógio monotônico da ponte)", Fmt.duracao(tp?.execucao_ms))
                LinhaValor("Até a primeira saída do executor", Fmt.duracao(tp?.primeira_saida_ms))
                LinhaValor("Total observado na Central", Fmt.duracao(tp?.total_tarefa_ms))
                Text("Espera = (entrega − criação, relógio da Central) + espera local na ponte. Total inclui até um ciclo de sincronização (~2,5 s). " +
                    "Não é latência de rede.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Titulo("Tokens")
            Cartao {
                if (t.uso.isEmpty()) Text("Uso de tokens não informado para esta tarefa (${Fmt.ND}).", style = MaterialTheme.typography.bodySmall)
                t.uso.forEach { u ->
                    LinhaValor("Chamada", u.chamada_id.takeLast(28))
                    LinhaValor("Modelo / provedor", "${u.modelo ?: Fmt.ND} / ${u.provedor ?: Fmt.ND}")
                    LinhaValor("Entrada", Fmt.inteiro(u.entrada) + if (u.entrada_inclui_cache) " (inclui cache)" else "")
                    LinhaValor("Saída", Fmt.inteiro(u.saida))
                    LinhaValor("Cache leitura / criação", "${Fmt.inteiro(u.cache_leitura)} / ${Fmt.inteiro(u.cache_criacao)}")
                    LinhaValor("Total sem dupla contagem", Fmt.inteiro(TotalUso.total(u)), destaque = true)
                    u.custo_usd?.let { LinhaValor("Custo", "US$ %.4f (%s)".format(it, u.custo_fonte ?: "fonte não informada")) }
                    Text("Fonte: ${u.fonte ?: "?"}${if (!u.final) " · parcial" else ""}", style = MaterialTheme.typography.labelSmall)
                }
                Text("Tokens/s e TTFT não são exibidos: a ponte não mede streaming de tokens.", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Titulo("Pedido")
            Cartao { SelectionContainer { Text(t.texto) } }
            Titulo("Resultado")
            Cartao {
                if (t.resultado.isNullOrBlank()) Text(if (t.status in setOf("fila", "rodando")) "Aguardando o executor…" else "Sem resultado.")
                else SelectionContainer { Text(t.resultado, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                t.nota?.takeIf { it.isNotBlank() }?.let { LinhaValor("Nota no vault", it) }
                Row {
                    TextButton(enabled = !t.resultado.isNullOrBlank(), onClick = { copiar(ctx, "Resultado", t.resultado.orEmpty()) }) { Text("Copiar") }
                    TextButton(enabled = !t.resultado.isNullOrBlank(), onClick = { compartilhar(ctx, t.resultado.orEmpty()) }) { Text("Compartilhar") }
                }
            }
        }
        if (t.tentativas.size > 1) item {
            Titulo("Tentativas deste trace")
            t.tentativas.forEach { r -> TextButton(onClick = { nav.navigate("tarefa/${r.id}") }) { Text("#${r.tentativa} · ${r.id} · ${Fmt.status(r.status)}") } }
        }
        item {
            Titulo("Eventos")
            Cartao {
                t.eventos.forEach { ev ->
                    Text("${Fmt.hora(ev.t)} · ${ev.comp} · ${ev.ev}" + listOfNotNull(ev.status, ev.motivo, ev.obs).joinToString(" · ", prefix = if (ev.status != null || ev.motivo != null || ev.obs != null) " · " else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (t.eventos.isEmpty()) Text("Tarefa anterior à instrumentação: sem eventos registrados.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { verLogs = !verLogs }) { Text(if (verLogs) "Ocultar detalhes técnicos" else "Detalhes técnicos (logs)") }
                if (verLogs) logs?.logs?.forEach { l ->
                    Text(l.entries.joinToString(" ") { "${it.key}=${runCatching { it.value.jsonPrimitive.content }.getOrDefault(it.value.toString())}" },
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirmarCancel) AlertDialog(onDismissRequest = { confirmarCancel = false }, title = { Text("Cancelar tarefa?") },
        text = { Text(if (t.status == "fila") "Ela sai da fila imediatamente." else "A Central pede à ponte para interromper; o estado final é confirmado pela ponte.") },
        confirmButton = { Button(onClick = {
            confirmarCancel = false
            escopo.launch { runCatching { vm.repo.cancelar(t.id) }.onSuccess { acaoErro = null; recarga++ }.onFailure { acaoErro = it } }
        }) { Text("Cancelar tarefa") } }, dismissButton = { TextButton(onClick = { confirmarCancel = false }) { Text("Voltar") } })
    if (confirmarRepetir) AlertDialog(onDismissRequest = { confirmarRepetir = false }, title = { Text("Repetir tarefa?") },
        text = { Text("Cria a tentativa ${t.tentativa + 1}, identificável no mesmo trace. A anterior é preservada.") },
        confirmButton = { Button(onClick = {
            confirmarRepetir = false
            escopo.launch { runCatching { vm.repo.repetir(t.id, idemRepetir) }.onSuccess { n -> nav.navigate("tarefa/${n.id}") }.onFailure { acaoErro = it } }
        }) { Text("Repetir") } }, dismissButton = { TextButton(onClick = { confirmarRepetir = false }) { Text("Voltar") } })
}

private fun rotuloEtapa(e: String) = when (e) {
    "ok" -> "ok"; "erro" -> "erro"; "aguardando" -> "aguardando"; "executando" -> "executando"; "cancelada" -> "cancelada"
    "nao_alcancado" -> "não alcançada"; "nao_solicitada" -> "não solicitada"; "pendente" -> "pendente"; "nao_gravada" -> "não gravada"
    "vazio" -> "vazio"; else -> e
}
