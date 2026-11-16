package br.escritorio.central.ui.telas

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.BuildConfig
import br.escritorio.central.data.ApiErro
import br.escritorio.central.data.DiagResp
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.LogsResp
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import br.escritorio.central.ui.comp.Titulo
import br.escritorio.central.ui.comp.Vazio
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Passo(val nome: String, val estado: String, val detalhe: String)

private fun campo(o: JsonObject, k: String) = o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/**
 * Teste de conectividade em etapas: identifica QUAL componente falhou, sem executar comandos
 * arbitrários e sem varrer a rede (só os endereços configurados).
 */
@Composable
fun TelaDiagnostico(vm: AppVM, nav: NavController) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val cx by vm.conexao.collectAsState()
    val cfg by vm.config.collectAsState(initial = null)
    val passos = remember { mutableStateListOf<Passo>() }
    var diag by remember { mutableStateOf<DiagResp?>(null) }
    var testando by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<LogsResp?>(null) }
    var logsErro by remember { mutableStateOf<Throwable?>(null) }
    var nivel by rememberSaveable { mutableStateOf("info") }
    var comp by rememberSaveable { mutableStateOf<String?>(null) }
    var filtroTarefa by rememberSaveable { mutableStateOf("") }
    var periodoH by rememberSaveable { mutableStateOf(24) }
    var antes by remember { mutableStateOf<Long?>(null) }
    var previa by remember { mutableStateOf<String?>(null) }

    fun testar() {
        testando = true; passos.clear()
        escopo.launch {
            val c = vm.c.prefs.atual()
            val enderecos = listOf("principal" to c.principal, "alternativo" to c.alternativo).filter { it.second.isNotBlank() }
            if (enderecos.isEmpty()) passos += Passo("Configuração", "erro", "Nenhum endereço configurado.")
            var algum = false
            for ((nome, url) in enderecos) {
                passos += Passo("Endereço $nome", if (url.startsWith("https://")) "ok" else "degradado",
                    url + if (url.startsWith("http://")) " (sem criptografia: só no build de teste)" else " (HTTPS)")
                runCatching { vm.repo.info(url) }
                    .onSuccess { r -> algum = true; passos += Passo("Central em $nome responde", "ok", "API ${r.valor?.versao_api} · backend ${r.valor?.versao_backend} · ${"%.0f".format(r.ms)} ms (medido no aparelho)") }
                    .onFailure { e -> passos += Passo("Central em $nome responde", "erro", (e as? ApiErro)?.mensagem ?: e.toString()) }
            }
            if (algum) runCatching { vm.repo.diagnostico() }
                .onSuccess { r ->
                    val d = r.valor!!; diag = d
                    passos += Passo("Sessão deste aparelho", "ok", "dispositivo ${d.sessao.dispositivo} · expira ${Fmt.hora(d.sessao.expira?.times(1000))}")
                    val p = d.componentes.ponte
                    passos += Passo("Ponte do PC", p.estado, "último contato ${Fmt.idade(p.visto, d.agora)}; ${p.ativas.size} ponte(s) ativa(s)" +
                        p.ativas.joinToString("") { "; ${it.id} v${it.versao} rtt ${Fmt.duracao(it.rtt_ms)}" })
                    p.avisos.forEach { passos += Passo("Aviso da ponte", "degradado", it) }
                    d.componentes.executores.forEach { (m, x) -> passos += Passo("Executor $m", x.estado,
                        "último sucesso: " + (x.ultimo_sucesso?.let { Fmt.idade(it, d.agora) } ?: "nenhum")) }
                    passos += Passo("Obsidian / MCP", d.componentes.obsidian_mcp.estado, d.componentes.obsidian_mcp.nota ?: "")
                    passos += Passo("OpenBot", d.componentes.openbot.estado, "último contato ${Fmt.idade(d.componentes.openbot.visto, d.agora)}")
                }
                .onFailure { e -> passos += Passo("Autenticação / diagnóstico", "erro", (e as? ApiErro)?.mensagem ?: e.toString()) }
            testando = false
        }
    }

    fun carregarLogs(mais: Boolean = false) {
        escopo.launch {
            val desde = System.currentTimeMillis() - periodoH * 3_600_000L
            runCatching {
                vm.repo.logs(mapOf("nivel" to nivel, "comp" to comp, "tarefa" to filtroTarefa.ifBlank { null }, "limite" to "60",
                    "desde" to desde.toString(), "antes" to (if (mais) antes?.toString() else null)))
            }.onSuccess { r -> logs = if (mais && logs != null) r.copy(logs = logs!!.logs + r.logs) else r; antes = r.proximo; logsErro = null }
                .onFailure { logsErro = it }
        }
    }
    LaunchedEffect(Unit) { testar() }
    LaunchedEffect(nivel, comp, periodoH) { carregarLogs() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Titulo("Conectividade por componente")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !testando, onClick = { testar() }) { Text(if (testando) "Testando…" else "Testar de novo") }
                OutlinedButton(onClick = { previa = textoDiagnostico(vm, diag, logs) }) { Text("Exportar…") }
            }
        }
        items(passos) { p ->
            Cartao {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.nome, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Pilula(when (p.estado) { "ok" -> "ok"; "erro" -> "falhou"; else -> Fmt.status(p.estado) }, p.estado)
                }
                if (p.detalhe.isNotBlank()) Text(p.detalhe, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Titulo("Versões e sessão")
            Cartao {
                LinhaValor("App", "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                LinhaValor("Backend", diag?.versao_backend ?: Fmt.ND)
                LinhaValor("Central desde", Fmt.horaComFuso(diag?.central_desde))
                LinhaValor("Central PID", diag?.pid?.toString() ?: Fmt.ND)
                LinhaValor("Última sincronização da ponte", Fmt.horaComFuso(diag?.ultima_sincronizacao_ponte))
                LinhaValor("Endereço em uso", cx.endereco ?: vm.repo.enderecoAtivo() ?: Fmt.ND)
                LinhaValor("Túnel informado pela Central", diag?.url_tunel ?: Fmt.ND)
                LinhaValor("Sessão (dispositivo)", cfg?.dispositivoId ?: Fmt.ND)
                LinhaValor("Sessão remota no PC", diag?.sessao_remota_pc?.motivo?.ifBlank { null } ?: "não verificada")
                diag?.contagens?.forEach { (k, v) -> LinhaValor(k.replace('_', ' '), "$v") }
                val lat = vm.repo.latenciasLocais().filter { it.ok }
                LinhaValor("Latência app→Central (últimas ${lat.size})",
                    if (lat.isEmpty()) Fmt.ND else "mediana ${Fmt.duracao(lat.map { it.ms }.sorted()[lat.size / 2])}")
            }
        }
        item {
            Titulo("Logs estruturados")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("debug", "info", "aviso", "erro").forEach { n -> FilterChip(nivel == n, { nivel = n }, label = { Text("≥ $n") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null, "central", "ponte").forEach { c -> FilterChip(comp == c, { comp = c }, label = { Text(c ?: "todos") }) }
                listOf(1, 24, 168).forEach { h -> FilterChip(periodoH == h, { periodoH = h }, label = { Text(if (h == 168) "7 d" else "$h h") }) }
            }
            Row {
                OutlinedTextField(filtroTarefa, { filtroTarefa = it.trim() }, label = { Text("ID da tarefa") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { carregarLogs() }) { Text("Filtrar") }
            }
            logs?.retencao?.let { Text("Retenção: $it. Segredos e textos integrais não são registrados.", style = MaterialTheme.typography.labelSmall) }
            logsErro?.let { ErroCartao(it) { carregarLogs() } }
        }
        val l = logs?.logs.orEmpty()
        if (logs != null && l.isEmpty()) item { Vazio("Nenhum log com esses filtros.") }
        items(l.size) { i ->
            val o = l[i]
            val extra = o.filterKeys { it !in setOf("t", "nivel", "comp", "ev", "msg") }.entries.joinToString(" ") { "${it.key}=${runCatching { it.value.jsonPrimitive.content }.getOrDefault("…")}" }
            Column(Modifier.padding(vertical = 3.dp)) {
                Text("${Fmt.hora(campo(o, "t")?.toLongOrNull())} [${campo(o, "nivel")}] ${campo(o, "comp")} · ${campo(o, "ev")}",
                    style = MaterialTheme.typography.labelMedium, color = br.escritorio.central.ui.comp.corEstado(
                        when (campo(o, "nivel")) { "erro" -> "erro"; "aviso" -> "degradado"; else -> "inativo" }))
                val msg = campo(o, "msg").orEmpty()
                if (msg.isNotBlank() || extra.isNotBlank()) Text((msg + " " + extra).trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (antes != null) item { TextButton(onClick = { carregarLogs(mais = true) }) { Text("Carregar mais antigos") } }
        item { Spacer(Modifier.height(24.dp)) }
    }

    previa?.let { txt ->
        AlertDialog(onDismissRequest = { previa = null }, title = { Text("Prévia do diagnóstico") }, text = {
            Column {
                Text("Isto será compartilhado somente se você tocar em Compartilhar. Não inclui token, senha nem conteúdo de notas/prompts.",
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(txt, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }, confirmButton = {
            Button(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Compartilhar diagnóstico"))
                previa = null
            }) { Text("Compartilhar") }
        }, dismissButton = { TextButton(onClick = { previa = null }) { Text("Cancelar") } })
    }
}

private fun textoDiagnostico(vm: AppVM, d: DiagResp?, logs: LogsResp?): String = buildString {
    appendLine("Diagnóstico — Central dos Agentes (Android)")
    appendLine("Gerado: ${Fmt.horaComFuso(System.currentTimeMillis())}")
    appendLine("App: ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}")
    appendLine("Backend: ${d?.versao_backend ?: "?"} · API ${d?.versao_api ?: "?"} · PID ${d?.pid ?: "?"} · uptime ${d?.uptime_s ?: "?"} s")
    appendLine("Endereço em uso: ${vm.repo.enderecoAtivo() ?: "?"}")
    d?.componentes?.let { c ->
        appendLine("Ponte: ${c.ponte.estado} (último contato ${Fmt.hora(c.ponte.visto)}); ativas: ${c.ponte.ativas.joinToString { it.id + " v" + it.versao }}")
        c.ponte.avisos.forEach { appendLine("  aviso: $it") }
        appendLine("OpenBot: ${c.openbot.estado} (${Fmt.hora(c.openbot.visto)})")
        c.executores.forEach { (m, x) -> appendLine("Executor $m: ${x.estado}; último sucesso ${Fmt.hora(x.ultimo_sucesso)}") }
        appendLine("Obsidian/MCP: ${c.obsidian_mcp.estado}")
    }
    d?.contagens?.let { appendLine("Contagens: $it") }
    val lat = vm.repo.latenciasLocais()
    appendLine("Latências locais (app→Central): n=${lat.size}, falhas=${lat.count { !it.ok }}")
    appendLine("--- últimos logs (já sem segredos) ---")
    logs?.logs?.take(40)?.forEach { o ->
        appendLine("${Fmt.hora(campo(o, "t")?.toLongOrNull())} ${campo(o, "nivel")} ${campo(o, "comp")} ${campo(o, "ev")} ${campo(o, "msg").orEmpty()}")
    }
}
