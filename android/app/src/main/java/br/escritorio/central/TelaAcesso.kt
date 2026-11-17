package br.escritorio.central

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var mostrarCodigo by remember { mutableStateOf(codigoInicial.isNotBlank()) }
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
        val cores = MaterialTheme.colorScheme
        Box(
            Modifier.fillMaxSize()
                .background(cores.background)
                .background(Brush.radialGradient(
                    colors = listOf(Color(0xff203655), Color(0xff101a2b), Color.Transparent),
                    center = Offset.Zero
                ))
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(Modifier.widthIn(max = 472.dp).fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                MarcaAcesso()
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
                ) {
                    val acesso = ativo
                    val computador = pc
                    when {
                        acesso != null && computador != null -> {
                            CabecalhoAcesso("Conta e computador", "Conexão HTTPS com ${computador.endereco}")
                            CartaoAcesso {
                                Text(acesso.usuario, color = cores.onSurface, style = MaterialTheme.typography.titleMedium)
                                BotaoAcesso("Voltar ao grafo", !ocupado) { abrir(computador, acesso) }
                                BotaoAcesso("Testar QR do Conector", !ocupado && !cameraAberta, destaque = false, acao = ::abrirCamera)
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
                        }
                        computador == null -> {
                            CabecalhoAcesso("Conectar computador",
                                "Abra o Conector Rede Neural no PC e conecte os dois aparelhos à mesma rede.")
                            CartaoAcesso {
                                SimboloQr(Modifier.align(Alignment.CenterHorizontally))
                                Text(
                                    "Aponte para o QR exibido no Conector.",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cores.onSurfaceVariant
                                )
                                BotaoAcesso("Ler QR com a câmera", !ocupado && !cameraAberta, acao = ::abrirCamera)
                                if (mostrarCodigo) {
                                    CampoAcesso("Código do computador", codigo, { codigo = it.take(600) },
                                        !ocupado, proteger = true)
                                    BotaoAcesso(if (ocupado) "Conectando…" else "Verificar conexão",
                                        codigo.isNotBlank() && !ocupado, destaque = false) { executar { verificar(codigo) } }
                                } else {
                                    BotaoAcesso("Usar código manual", !ocupado, destaque = false) { mostrarCodigo = true }
                                }
                            }
                        }
                        else -> {
                            CabecalhoAcesso(if (criar) "Criar conta local" else "Entrar",
                                "Computador conectado · ${computador.endereco}")
                            CartaoAcesso {
                                if (criar) {
                                    Text("Escolha um usuário e uma senha com pelo menos 12 caracteres.",
                                        color = cores.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                                CampoAcesso("Usuário", usuario, { usuario = it.take(40) }, !ocupado)
                                CampoAcesso("Senha", senha, { senha = it.take(128) }, !ocupado, proteger = true)
                                BotaoAcesso(
                                    if (ocupado) "Aguarde…" else if (criar) "Criar conta" else "Entrar",
                                    usuario.isNotBlank() && senha.length >= 12 && !ocupado
                                ) { executar {
                                    val nova = withContext(Dispatchers.IO) {
                                        ConexaoPc(computador).entrar(usuario.trim(), senha, criar, convite)
                                            .also { salvar(computador, it) }
                                    }
                                    senha = ""
                                    convite = null
                                    abrir(computador, nova)
                                } }
                                if (convite != null) {
                                    BotaoAcesso(if (criar) "Já tenho conta" else "Criar conta local",
                                        !ocupado, destaque = false) { criar = !criar; aviso = "" }
                                }
                                TextButton(
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                    enabled = !ocupado,
                                    onClick = { pc = null; convite = null; senha = ""; aviso = "" }
                                ) { Text("Usar outro código") }
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
}

@Composable
private fun MarcaAcesso() {
    val cores = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Canvas(
            Modifier.size(40.dp)
                .background(Brush.verticalGradient(listOf(Color(0xff344c71), Color(0xff1e2d46))), CircleShape)
                .border(1.dp, Color(0xff758fb9).copy(alpha = .62f), CircleShape)
        ) {
            val cima = Offset(size.width * .41f, size.height * .28f)
            val direita = Offset(size.width * .72f, size.height * .43f)
            val esquerda = Offset(size.width * .29f, size.height * .69f)
            drawLine(cores.primary, cima, direita, strokeWidth = 1.dp.toPx())
            drawLine(cores.primary, cima, esquerda, strokeWidth = 1.dp.toPx())
            for (ponto in listOf(cima, direita, esquerda)) drawCircle(cores.primary, 2.5.dp.toPx(), ponto)
        }
        Column {
            Text("Rede Neural", color = cores.onSurface,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Memória compartilhada", color = cores.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CabecalhoAcesso(titulo: String, subtitulo: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.85).sp),
            fontWeight = FontWeight.SemiBold)
        Text(subtitulo, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CartaoAcesso(conteudo: @Composable ColumnScope.() -> Unit) {
    val forma = RoundedCornerShape(32.dp)
    val superficie = Brush.linearGradient(listOf(Color(0xdc263b5b), Color(0xf0101c30)))
    val contorno = Brush.verticalGradient(listOf(Color(0x997f9cc8), Color(0x403c5276)))
    Column(
        Modifier.fillMaxWidth().background(superficie, forma).border(1.dp, contorno, forma).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = conteudo
    )
}

@Composable
private fun BotaoAcesso(rotulo: String, habilitado: Boolean, destaque: Boolean = true, acao: () -> Unit) {
    val cores = MaterialTheme.colorScheme
    val fundo = when {
        !habilitado -> Brush.verticalGradient(listOf(Color(0xff26354c), Color(0xff1b293e)))
        destaque -> Brush.verticalGradient(listOf(Color(0xffd4e5ff), Color(0xff9bbdff)))
        else -> Brush.verticalGradient(listOf(Color(0xca3c5884), Color(0x9c1a2a45)))
    }
    val borda = if (destaque && habilitado) Color(0xffdeebff) else Color(0x7a8da9d6)
    Button(
        onClick = acao,
        enabled = habilitado,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(CircleShape)
            .background(fundo).border(1.dp, borda, CircleShape),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = if (destaque) cores.onPrimary else cores.onSurface,
            disabledContentColor = cores.onSurfaceVariant
        )
    ) {
        Text(rotulo, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CampoAcesso(
    rotulo: String,
    valor: String,
    alterar: (String) -> Unit,
    habilitado: Boolean,
    proteger: Boolean = false
) {
    OutlinedTextField(
        value = valor,
        onValueChange = alterar,
        label = { Text(rotulo) },
        enabled = habilitado,
        singleLine = true,
        shape = CircleShape,
        visualTransformation = if (proteger) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (proteger) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xff111b2e),
            unfocusedContainerColor = Color(0xd9111b2e),
            unfocusedBorderColor = Color(0xff506a96)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SimboloQr(modifier: Modifier = Modifier) {
    val azul = MaterialTheme.colorScheme.primary
    val forma = RoundedCornerShape(24.dp)
    Box(
        modifier.size(78.dp)
            .background(Brush.verticalGradient(listOf(Color(0xff365379), Color(0xff1b2a43))), forma)
            .border(1.dp, Color(0xff6888b9), forma),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(41.dp)) {
            val tamanho = size.minDimension
            val inicio = tamanho * .16f
            val fim = tamanho - inicio
            val canto = tamanho * .17f
            val traço = 2.dp.toPx()
            for (x in listOf(inicio, fim)) {
                for (y in listOf(inicio, fim)) {
                    val origem = Offset(x, y)
                    val dx = if (x == inicio) canto else -canto
                    val dy = if (y == inicio) canto else -canto
                    drawLine(azul, origem, Offset(x + dx, y), traço, StrokeCap.Round)
                    drawLine(azul, origem, Offset(x, y + dy), traço, StrokeCap.Round)
                }
            }
            drawLine(azul, Offset(inicio, center.y), Offset(fim, center.y), traço, StrokeCap.Round)
        }
    }
}

private fun mensagemAcesso(e: Exception): String = when (e) {
    is SSLException -> "A identidade do computador não confere. Confira o código exibido no PC."
    is IOException -> "Não foi possível alcançar o computador. Confira a rede, o firewall e se o conector está aberto."
    is IllegalArgumentException, is IllegalStateException -> e.message ?: "Falha ao verificar a conexão."
    else -> "Não foi possível concluir. Tente novamente."
}
