package br.escritorio.central

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.net.ssl.SSLException

@Composable
fun TelaAcesso(
    salvo: Computador?,
    sessao: SessaoRede?,
    codigoInicial: String,
    mensagem: String,
    salvar: (Computador, SessaoRede) -> Unit,
    limpar: (Boolean) -> Unit,
    abrir: (Computador, SessaoRede) -> Unit
) {
    var ativo by remember { mutableStateOf(sessao) }
    var pc by remember { mutableStateOf(salvo.takeIf { codigoInicial.isEmpty() }) }
    var convite by remember { mutableStateOf<String?>(null) }
    var codigo by remember { mutableStateOf(codigoInicial) }
    var criar by remember { mutableStateOf(false) }
    var usuario by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf(mensagem) }
    var qrValidado by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf(false) }
    var cameraAberta by remember { mutableStateOf(false) }
    val contexto = LocalContext.current
    val fotoQr = remember(contexto) { File(contexto.cacheDir, "pareamento-qr/foto.jpg") }
    val escopo = rememberCoroutineScope()

    fun executar(acao: suspend () -> Unit) {
        if (ocupado) return
        ocupado = true
        aviso = ""
        qrValidado = false
        escopo.launch {
            try {
                acao()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aviso = mensagemAcesso(e)
            } finally {
                ocupado = false
            }
        }
    }

    suspend fun verificar(valor: String) {
        val novo = Convite.ler(valor)
        withContext(Dispatchers.IO) { ConexaoPc(novo.pc).verificar() }
        pc = novo.pc
        convite = novo.codigoCadastro
        criar = convite != null
        codigo = ""
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { capturou ->
        cameraAberta = false
        if (!capturou) {
            fotoQr.delete()
        } else executar {
            val lido = withContext(Dispatchers.IO) {
                try {
                    LeitorQr.lerFoto(fotoQr)
                } finally {
                    fotoQr.delete()
                }
            }
            if (ativo != null) {
                val novo = Convite.ler(lido).pc
                require(novo == pc) { "O QR pertence a outro computador ou endereço." }
                withContext(Dispatchers.IO) { ConexaoPc(novo).verificar() }
                qrValidado = true
                aviso = "QR verificado. O Conector deste computador está respondendo."
            } else {
                verificar(lido)
            }
        }
    }

    fun abrirCamera() {
        try {
            fotoQr.parentFile?.mkdirs()
            fotoQr.delete()
            val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}.pareamento", fotoQr)
            cameraAberta = true
            camera.launch(uri)
        } catch (_: RuntimeException) {
            cameraAberta = false
            qrValidado = false
            aviso = "Não foi possível abrir a câmera. Use Copiar código no computador."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xffadcaff), onPrimary = Color(0xff10203d), background = Color(0xff090e18),
        surface = Color(0xff101925), onSurface = Color(0xffedf3ff),
        onSurfaceVariant = Color(0xffaebcd1), outline = Color(0xff667eaa)
    )) {
        val forma = RoundedCornerShape(32.dp)
        val vidro = Brush.verticalGradient(listOf(Color(0xff243957), Color(0xff131e31)))
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xff17243b), MaterialTheme.colorScheme.background)))
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier.widthIn(max = 500.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 22.dp)
                    .background(vidro, forma).border(1.dp, Color(0xff54739e), forma).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Rede Neural", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                val acesso = ativo
                val computador = pc
                when {
                    acesso != null && computador != null -> {
                        Text("Conta e computador", style = MaterialTheme.typography.headlineMedium)
                        Text("Conta local: ${acesso.usuario}", style = MaterialTheme.typography.bodyLarge)
                        Text("Conexão HTTPS com ${computador.endereco}", style = MaterialTheme.typography.bodyMedium)
                        Text("Obsidian, Claude Code e Codex usam as sessões deste computador.")
                        Button(onClick = { abrir(computador, acesso) }, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                            Text("Voltar ao grafo")
                        }
                        TextButton(onClick = ::abrirCamera, enabled = !ocupado && !cameraAberta) {
                            Text("Testar QR do Conector")
                        }
                        TextButton(onClick = { executar {
                            withContext(Dispatchers.IO) {
                                ConexaoPc(computador).sair(acesso.token)
                                limpar(false)
                            }
                            ativo = null
                        } }, enabled = !ocupado) { Text("Sair da conta") }
                        TextButton(onClick = { executar {
                            val revogada = withContext(Dispatchers.IO) {
                                val resultado = runCatching { ConexaoPc(computador).sair(acesso.token) }
                                limpar(true)
                                resultado.isSuccess
                            }
                            ativo = null
                            pc = null
                            convite = null
                            if (!revogada) aviso = "O aparelho foi desconectado. A sessão no PC ainda pode estar ativa até expirar."
                        } }, enabled = !ocupado) { Text("Esquecer computador") }
                    }
                    computador == null -> {
                        Text("Conectar computador", style = MaterialTheme.typography.headlineMedium)
                        Text("1. No PC, abra o Conector Rede Neural e escolha a pasta do seu vault.")
                        Text("2. Ligue o computador e este celular à mesma rede Wi-Fi ou à mesma rede local.")
                        Text("3. Leia o QR do Conector ou cole o código exibido no PC.")
                        Button(enabled = !ocupado && !cameraAberta, modifier = Modifier.fillMaxWidth(), onClick = ::abrirCamera) {
                            Text("Ler QR com a câmera")
                        }
                        OutlinedTextField(value = codigo, onValueChange = { codigo = it.take(600) },
                            label = { Text("Código do computador") }, enabled = !ocupado, singleLine = true,
                            shape = RoundedCornerShape(20.dp), visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = codigo.isNotBlank() && !ocupado, modifier = Modifier.fillMaxWidth(), onClick = { executar {
                            verificar(codigo)
                        } }) { Text(if (ocupado) "Conectando…" else "Verificar conexão") }
                    }
                    else -> {
                        Text(if (criar) "Criar conta local" else "Entrar", style = MaterialTheme.typography.headlineMedium)
                        Text("Computador: ${computador.endereco}")
                        Text(if (criar) "Escolha um usuário e uma senha de pelo menos 12 caracteres. A conta fica neste computador."
                            else "Use a conta da Rede Neural criada para este computador.")
                        OutlinedTextField(value = usuario, onValueChange = { usuario = it.take(40) },
                            label = { Text("Usuário") }, enabled = !ocupado, singleLine = true,
                            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = senha, onValueChange = { senha = it.take(128) },
                            label = { Text("Senha") }, enabled = !ocupado, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp), visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Button(enabled = usuario.isNotBlank() && senha.length >= 12 && !ocupado,
                            modifier = Modifier.fillMaxWidth(), onClick = { executar {
                                val nova = withContext(Dispatchers.IO) {
                                    ConexaoPc(computador).entrar(usuario.trim(), senha, criar, convite)
                                        .also { salvar(computador, it) }
                                }
                                senha = ""
                                convite = null
                                abrir(computador, nova)
                            } }) { Text(if (ocupado) "Aguarde…" else if (criar) "Criar conta" else "Entrar") }
                        if (convite != null) {
                            TextButton(enabled = !ocupado, onClick = { criar = !criar; aviso = "" }) {
                                Text(if (criar) "Já tenho conta" else "Criar conta local")
                            }
                        }
                        TextButton(enabled = !ocupado, onClick = { pc = null; convite = null; senha = ""; aviso = "" }) {
                            Text("Usar outro código")
                        }
                    }
                }
                if (aviso.isNotBlank()) {
                    Text(aviso, color = if (qrValidado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

private fun mensagemAcesso(e: Exception): String = when (e) {
    is SSLException -> "A identidade do computador não confere. Confira o código exibido no PC."
    is IOException -> "Não foi possível alcançar o computador. Confira a rede, o firewall e se o conector está aberto."
    is IllegalArgumentException, is IllegalStateException -> e.message ?: "Falha ao verificar a conexão."
    else -> "Não foi possível concluir. Tente novamente."
}
