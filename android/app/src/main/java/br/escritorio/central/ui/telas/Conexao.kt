package br.escritorio.central.ui.telas

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.escritorio.central.data.ApiErro
import br.escritorio.central.data.Repositorio
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.LocalCores
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.LinhaValor
import kotlinx.coroutines.launch

/**
 * Pareamento do aparelho: a senha do painel é digitada aqui, vai uma única vez à Central e NÃO é guardada.
 * A Central devolve um token próprio deste aparelho (expira em 30 dias, revogável), cifrado no Keystore.
 */
@Composable
fun TelaConexao(vm: AppVM) {
    val escopo = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var senha by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var erro by androidx.compose.runtime.remember { mutableStateOf<Throwable?>(null) }
    var ocupado by rememberSaveable { mutableStateOf(false) }
    var comToken by rememberSaveable { mutableStateOf(false) }
    var tokenAp by androidx.compose.runtime.remember { mutableStateOf("") }  // não vai para o estado salvo

    LaunchedEffect(Unit) { if (url.isBlank()) url = vm.c.prefs.atual().principal }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Central dos Agentes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Conecte este aparelho à Central do Escritório (servidor no J7).", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Endereço da Central") },
            placeholder = { Text("https://….trycloudflare.com ou http://IP:8765") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("campo_url"))
        if (url.startsWith("http://")) {
            Text("Endereço sem criptografia: aceito apenas neste build de teste e na rede local. Prefira o https do túnel.",
                color = LocalCores.current.aviso, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = url.isNotBlank() && !ocupado, onClick = {
                ocupado = true; erro = null; info = null
                escopo.launch {
                    runCatching { vm.repo.testarServidor(url) }
                        .onSuccess { info = "Central v${it.versao_backend ?: "?"} (API ${it.versao_api ?: "?"}) respondeu" + if (it.https) " via HTTPS." else "." }
                        .onFailure { erro = it }
                    ocupado = false
                }
            }) { Text("Testar endereço") }
        }
        info?.let { Text(it, color = LocalCores.current.ok, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(senha, { senha = it }, label = { Text("Senha do painel") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(nome, { nome = it.take(60) }, label = { Text("Nome deste aparelho") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Button(enabled = url.isNotBlank() && senha.isNotBlank() && !ocupado, modifier = Modifier.fillMaxWidth(), onClick = {
            ocupado = true; erro = null
            escopo.launch {
                runCatching { vm.repo.parear(url, senha, nome) }
                    .onSuccess { senha = "" }
                    .onFailure { erro = it; senha = "" }
                ocupado = false
            }
        }) { Text(if (ocupado) "Conectando…" else "Conectar este aparelho") }
        TextButton(onClick = { comToken = !comToken }) { Text(if (comToken) "Usar senha do painel" else "Já tenho um token de aparelho (gerado no servidor)") }
        if (comToken) {
            OutlinedTextField(tokenAp, { tokenAp = it.trim() }, label = { Text("Token de aparelho") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("campo_token"))
            Text("Gerado no J7 com: python hub.py --novo-dispositivo \"nome\". Não é a chave da ponte.", style = MaterialTheme.typography.labelSmall)
            Button(enabled = url.isNotBlank() && tokenAp.isNotBlank() && !ocupado, modifier = Modifier.fillMaxWidth(), onClick = {
                ocupado = true; erro = null
                escopo.launch {
                    runCatching { vm.repo.entrarComToken(url, tokenAp) }.onFailure { erro = it }
                    tokenAp = ""; ocupado = false
                }
            }) { Text("Entrar com token") }
        }
        erro?.let { ErroCartao(it) }
        if ((erro as? ApiErro)?.tipo == ApiErro.Tipo.CONFIG && url.startsWith("http://")) {
            Text("Este build só aceita HTTPS.", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Cartao {
            Text("Como funciona", style = MaterialTheme.typography.titleSmall)
            LinhaValor("Credencial", "token próprio deste aparelho (não é a chave da ponte)")
            LinhaValor("Senha", "usada só para parear; não fica salva")
            LinhaValor("Expiração", "30 dias; revogável em Ajustes")
            LinhaValor("Endereço normalizado", if (url.isBlank()) "—" else Repositorio.normalizar(url))
        }
    }
}
