package br.escritorio.central.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.escritorio.central.Container
import br.escritorio.central.data.ApiErro
import br.escritorio.central.ui.comp.Carregando
import br.escritorio.central.ui.telas.TelaAgenteDetalhe
import br.escritorio.central.ui.telas.TelaAgentes
import br.escritorio.central.ui.telas.TelaAjustes
import br.escritorio.central.ui.telas.TelaConexao
import br.escritorio.central.ui.telas.TelaConhecimento
import br.escritorio.central.ui.telas.TelaDiagnostico
import br.escritorio.central.ui.telas.TelaInicio
import br.escritorio.central.ui.telas.TelaNota
import br.escritorio.central.ui.telas.TelaNovaTarefa
import br.escritorio.central.ui.telas.TelaTarefaDetalhe
import br.escritorio.central.ui.telas.TelaTarefas
import br.escritorio.central.ui.telas.TelaTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/** Estado global: polling adaptativo SÓ em primeiro plano (parado com o app em segundo plano). */
class AppVM(val c: Container) : ViewModel() {
    val repo = c.repo
    val estado = repo.estado
    val conexao = repo.conexao
    val sessao = repo.sessaoValida
    val config = c.prefs.fluxo

    /**
     * Orçamento de bateria: 3 s enquanto há tarefa/nota em andamento, 15 s ocioso, backoff exponencial com
     * jitter (até 60 s) em falhas. Nenhuma requisição com o app em segundo plano (repeatOnLifecycle STARTED).
     */
    suspend fun cicloPrimeiroPlano() {
        var falhas = 0
        var ciclo = 0
        var reconfirmou = false
        while (true) {
            ciclo++
            val intervalo = try {
                val e = repo.atualizarEstado()
                falhas = 0
                if (!reconfirmou) { reconfirmou = true; repo.reconfirmarPendentes() }
                if (ciclo % 8 == 0) repo.relatarLatencias()
                val ativo = e.tarefas.any { it.status in setOf("fila", "rodando") } || e.ops.any { it.status in setOf("pendente", "entregue") }
                if (ativo) 3_000L else 15_000L
            } catch (e: ApiErro) {
                falhas++
                if (e.tipo == ApiErro.Tipo.NAO_AUTORIZADO) return
                min(60_000L, 2_000L shl min(falhas, 5)) + Random.nextLong(0, 1_000)
            }
            delay(intervalo)
        }
    }

    fun atualizarAgora() = viewModelScope.launch { runCatching { repo.atualizarEstado() } }
    fun lembrarRota(r: String) = viewModelScope.launch { c.prefs.rota(r) }
}

private data class Aba(val rota: String, val rotulo: String, val icone: ImageVector)

private val abas = listOf(
    Aba("inicio", "Início", Icons.Default.Home),
    Aba("agentes", "Agentes", Icons.Default.Person),
    Aba("tarefas", "Tarefas", Icons.AutoMirrored.Filled.List),
    Aba("conhecimento", "Memória", Icons.Default.Share),
    Aba("diagnostico", "Diagnóstico", Icons.Default.Build),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRaiz(c: Container) {
    val vm: AppVM = viewModel(factory = viewModelFactory { initializer { AppVM(c) } })
    val cfg by vm.config.collectAsState(initial = null)
    TemaCentral(cfg?.tema ?: "sistema") {
        val sessao by vm.sessao.collectAsState()
        when {
            sessao == null || cfg == null -> Box(Modifier.fillMaxSize().safeDrawingPadding()) { Carregando("Abrindo…") }
            sessao == false -> TelaConexao(vm)
            else -> Principal(vm, cfg!!.ultimaRota)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Principal(vm: AppVM, rotaInicial: String) {
    val nav = rememberNavController()
    val ciclo = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { ciclo.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.cicloPrimeiroPlano() } }
    val entrada by nav.currentBackStackEntryAsState()
    val rotaAtual = entrada?.destination?.route ?: ""
    val topo = abas.firstOrNull { it.rota == rotaAtual }
    LaunchedEffect(rotaAtual) { if (topo != null) vm.lembrarRota(rotaAtual) }
    val inicio = remember { if (abas.any { it.rota == rotaInicial }) rotaInicial else "inicio" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topo?.rotulo ?: titulo(rotaAtual)) },
                navigationIcon = {
                    if (topo == null) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("ajustes") }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes e conexão") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                abas.forEach { a ->
                    NavigationBarItem(
                        selected = a.rota == rotaAtual,
                        onClick = {
                            nav.navigate(a.rota) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(a.icone, contentDescription = null) },
                        label = { Text(a.rotulo, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = inicio, modifier = Modifier.padding(pad)) {
            composable("inicio") { TelaInicio(vm, nav) }
            composable("agentes") { TelaAgentes(vm, nav) }
            composable("agente/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                TelaAgenteDetalhe(vm, nav, it.arguments?.getString("id") ?: "")
            }
            composable("tokens") { TelaTokens(vm, nav) }
            composable("tarefas") { TelaTarefas(vm, nav) }
            composable("nova_tarefa?agente={agente}", arguments = listOf(navArgument("agente") { type = NavType.StringType; nullable = true })) {
                TelaNovaTarefa(vm, nav, it.arguments?.getString("agente"))
            }
            composable("tarefa/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                TelaTarefaDetalhe(vm, nav, it.arguments?.getString("id") ?: "")
            }
            composable("conhecimento") { TelaConhecimento(vm, nav) }
            composable("nota?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true })) {
                TelaNota(vm, nav, it.arguments?.getString("id"))
            }
            composable("diagnostico") { TelaDiagnostico(vm, nav) }
            composable("ajustes") { TelaAjustes(vm, nav) }
        }
    }
}

private fun titulo(rota: String) = when {
    rota.startsWith("agente/") -> "Agente"
    rota.startsWith("tarefa/") -> "Tarefa"
    rota.startsWith("nova_tarefa") -> "Nova tarefa"
    rota.startsWith("nota") -> "Nota"
    rota == "tokens" -> "Tokens e orçamento"
    rota == "ajustes" -> "Ajustes e conexão"
    else -> "Central dos Agentes"
}
