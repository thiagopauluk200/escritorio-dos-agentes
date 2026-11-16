package br.escritorio.central.ui.telas

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import br.escritorio.central.AppCentral
import br.escritorio.central.data.DispositivosResp
import br.escritorio.central.data.Fmt
import br.escritorio.central.ui.AppVM
import br.escritorio.central.ui.comp.Cartao
import br.escritorio.central.ui.comp.ErroCartao
import br.escritorio.central.ui.comp.LinhaValor
import br.escritorio.central.ui.comp.Pilula
import br.escritorio.central.ui.comp.Titulo
import kotlinx.coroutines.launch

@Composable
fun TelaAjustes(vm: AppVM, nav: NavController) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val cfg by vm.config.collectAsState(initial = null)
    val cx by vm.conexao.collectAsState()
    var alternativo by rememberSaveable { mutableStateOf<String?>(null) }
    var disp by remember { mutableStateOf<DispositivosResp?>(null) }
    var erro by remember { mutableStateOf<Throwable?>(null) }
    var sair by remember { mutableStateOf(false) }
    var tunel by remember { mutableStateOf<String?>(null) }
    val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        escopo.launch { vm.c.prefs.notificacoes(ok); AppCentral.agendar(ctx, ok) }
    }
    LaunchedEffect(Unit) {
        runCatching { vm.repo.dispositivos() }.onSuccess { disp = it }.onFailure { erro = it }
        runCatching { vm.repo.diagnostico().valor?.url_tunel }.onSuccess { tunel = it }
    }
    val c = cfg ?: return
    if (alternativo == null) alternativo = c.alternativo

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Titulo("Central selecionada")
        Cartao {
            LinhaValor("Principal", c.principal.ifBlank { Fmt.ND })
            LinhaValor("Alternativo", c.alternativo.ifBlank { "nenhum" })
            LinhaValor("Em uso agora", cx.endereco ?: Fmt.ND)
            Pilula(Fmt.status(cx.conexao.name.lowercase()), cx.conexao.name.lowercase())
            if (c.principal.startsWith("http://")) Text("O endereço principal não é criptografado (build de teste, rede local).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(alternativo ?: "", { alternativo = it.trim() }, label = { Text("Endereço alternativo (ex.: túnel https)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { escopo.launch { vm.repo.definirAlternativo(alternativo ?: "") } }) { Text("Salvar alternativo") }
            tunel?.takeIf { it != c.alternativo }?.let { u ->
                OutlinedButton(onClick = { alternativo = u; escopo.launch { vm.repo.definirAlternativo(u) } }) { Text("Usar túnel atual") }
            }
        }
        tunel?.let { Text("Túnel vigente informado pela Central: $it (muda quando o J7 reinicia o túnel temporário).", style = MaterialTheme.typography.labelSmall) }

        Titulo("Preferências")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notificações de conclusão/falha e alertas de tokens")
                Text("Verificação periódica (~15 min, conforme o Android permitir). Não é instantâneo. Detalhes ocultos na tela bloqueada.",
                    style = MaterialTheme.typography.labelSmall)
            }
            Switch(c.notificacoes, { liga ->
                if (liga && Build.VERSION.SDK_INT >= 33) permissao.launch(Manifest.permission.POST_NOTIFICATIONS)
                else escopo.launch { vm.c.prefs.notificacoes(liga); AppCentral.agendar(ctx, liga) }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Reconfirmar envios não confirmados ao reconectar")
                Text("Usa o MESMO identificador; a Central não duplica. Desligado: você confirma manualmente.", style = MaterialTheme.typography.labelSmall)
            }
            Switch(c.reenvioAutomatico, { v -> escopo.launch { vm.c.prefs.reenvio(v) } })
        }
        Text("Tema", modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("sistema", "claro", "escuro").forEach { t -> FilterChip(c.tema == t, { escopo.launch { vm.c.prefs.tema(t) } }, label = { Text(t) }) }
        }

        Titulo("Aparelhos com acesso")
        erro?.let { ErroCartao(it) }
        disp?.dispositivos?.forEach { d ->
            Cartao {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((d.nome ?: d.id) + if (d.id == disp?.atual) " (este)" else "", modifier = Modifier.weight(1f))
                    Pilula(if (d.revogado != null) "revogado" else "ativo", if (d.revogado != null) "inativo" else "ok")
                }
                LinhaValor("Último uso", Fmt.hora(d.visto))
                LinhaValor("Expira", Fmt.hora(d.expira?.times(1000)))
                if (d.revogado == null && d.id != disp?.atual) TextButton(onClick = {
                    escopo.launch { runCatching { vm.repo.revogarDispositivo(d.id); disp = vm.repo.dispositivos() }.onFailure { erro = it } }
                }) { Text("Revogar") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { sair = true }, modifier = Modifier.fillMaxWidth()) { Text("Sair e revogar este aparelho") }
        Spacer(Modifier.height(24.dp))
    }
    if (sair) AlertDialog(onDismissRequest = { sair = false }, title = { Text("Sair?") },
        text = { Text("O token deste aparelho é revogado na Central e o cache local é apagado. Para voltar, será preciso a senha do painel.") },
        confirmButton = { Button(onClick = { sair = false; escopo.launch { AppCentral.agendar(ctx, false); vm.repo.sair() } }) { Text("Sair") } },
        dismissButton = { TextButton(onClick = { sair = false }) { Text("Cancelar") } })
}
