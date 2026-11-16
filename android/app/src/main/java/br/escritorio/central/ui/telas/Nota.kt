package br.escritorio.central.ui.telas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.data.Dado
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.NotaConteudo
import br.escritorio.central.data.OpResumo
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.LocalCores
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.Idade
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Ver, criar (00-Inbox) e editar nota. A edição leva a versão base (mtime); a ponte recusa se a nota mudou
 * em outro dispositivo (conflito) e guarda a versão anterior. "Salvo" só aparece após a ponte confirmar.
 */
@Composable
fun TelaNota(vm: AppVM, nav: NavController, idCod: String?) {
    val id = idCod  // o Navigation já decodifica o argumento
    val escopo = rememberCoroutineScope()
    val chaveRascunho = "nota:" + (id ?: "nova")
    var nota by remember { mutableStateOf<Dado<NotaConteudo>?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var editando by rememberSaveable { mutableStateOf(id == null) }
    var titulo by rememberSaveable { mutableStateOf("") }
    var texto by rememberSaveable { mutableStateOf("") }
    var baseMod by rememberSaveable { mutableStateOf<Long?>(null) }
    var opId by rememberSaveable { mutableStateOf<String?>(null) }
    var idem by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var op by remember { mutableStateOf<OpResumo?>(null) }
    var carregou by rememberSaveable { mutableStateOf(false) }
    var enviando by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null) runCatching { vm.repo.nota(id) }.onSuccess { nota = it; erro = null }.onFailure { erro = it }
        if (!carregou) {
            vm.repo.rascunho(chaveRascunho)?.let { r ->
                texto = r.texto; titulo = r.extra.substringAfter("|", ""); baseMod = r.extra.substringBefore("|").toLongOrNull(); editando = true
            }
            carregou = true
        }
    }
    LaunchedEffect(texto, titulo, editando) {
        if (carregou && editando && opId == null) { delay(500); vm.repo.salvarRascunho(chaveRascunho, texto, "${baseMod ?: ""}|$titulo") }
    }
    // acompanha a gravação até a ponte confirmar (ou recusar)
    LaunchedEffect(opId) {
        val o = opId ?: return@LaunchedEffect
        while (true) {
            runCatching { vm.repo.op(o) }.onSuccess { op = it }
            if (op?.status in setOf("ok", "erro", "conflito")) {
                if (op?.status == "ok") vm.repo.apagarRascunho(chaveRascunho)
                break
            }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (id != null) {
            Idade(nota)
            Text(id.removePrefix("nota:"), style = MaterialTheme.typography.titleSmall)
            nota?.valor?.let { LinhaValor("Versão (mtime no PC)", Fmt.hora(it.mod)) }
            erro?.let { ErroCartao(it) }
            if (nota == null && erro == null) Carregando()
        } else {
            Text("Nova nota em 00-Inbox", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(titulo, { titulo = it.take(90) }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        val conteudoAtual = nota?.valor?.conteudo.orEmpty()
        if (!editando) {
            Row {
                Button(enabled = nota != null && !nota!!.doCache, onClick = {
                    texto = conteudoAtual; baseMod = nota!!.valor.mod; editando = true; idem = UUID.randomUUID().toString(); opId = null; op = null
                }) { Text("Editar") }
            }
            if (nota?.doCache == true) Text("Offline: a edição exige a versão atual da Central.", style = MaterialTheme.typography.labelSmall)
            Cartao { SelectionContainer { Text(conteudoAtual, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        } else {
            if (id != null && baseMod != null && nota != null && nota!!.valor.mod != baseMod)
                Text("Atenção: a nota mudou desde que você começou a editar (versão ${Fmt.hora(nota!!.valor.mod)}). Salvar resultará em conflito; " +
                    "copie seu texto e reabra.", color = LocalCores.current.erro, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(texto, { texto = it }, label = { Text("Conteúdo (Markdown)") }, minLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text("Rascunho salvo neste aparelho") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row {
                Button(enabled = !enviando && opId == null && (id != null || titulo.isNotBlank()), onClick = {
                    enviando = true
                    escopo.launch {
                        runCatching { vm.repo.salvarNota(idem, if (id == null) titulo else null, id, if (id != null) baseMod else null, texto) }
                            .onSuccess { opId = it.op; erro = null }.onFailure { erro = it }
                        enviando = false
                    }
                }) { Text(if (enviando) "Enviando…" else "Salvar no vault") }
                if (id != null) OutlinedButton(onClick = { editando = false; escopo.launch { vm.repo.apagarRascunho(chaveRascunho) } },
                    modifier = Modifier.padding(start = 8.dp)) { Text("Descartar") }
            }
            erro?.let { ErroCartao(it) }
        }
        opId?.let {
            Cartao {
                val st = op?.status ?: "pendente"
                Pilula(when (st) {
                    "ok" -> "Salva no vault (confirmado pela ponte)"
                    "pendente" -> "Pendente: aguardando a ponte"
                    "entregue" -> "Com a ponte: gravando…"
                    "conflito" -> "Conflito: não salva"
                    else -> "Erro: não salva"
                }, st)
                op?.msg?.let { m -> Text(m, style = MaterialTheme.typography.bodySmall) }
                if (st == "conflito") Text("Seu texto continua no rascunho. Reabra a nota para ver a versão atual e reaplicar suas mudanças.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
