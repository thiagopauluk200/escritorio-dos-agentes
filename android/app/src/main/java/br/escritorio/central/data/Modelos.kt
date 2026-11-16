package br.escritorio.central.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Contratos da API v1 da Central (ver docs/API.md). Campos ausentes chegam como null:
 * a interface mostra "não disponível" e nunca converte ausência em zero.
 */
val JsonApi = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable data class InfoResp(val servico: String? = null, val versao_api: Int? = null, val versao_backend: String? = null,
                                  val agora: Long? = null, val https: Boolean = false)

@Serializable data class DispositivoInfo(val id: String, val nome: String? = null, val criado: Long? = null, val expira: Long? = null,
                                         val visto: Long? = null, val revogado: Long? = null, val escopos: List<String> = emptyList())
@Serializable data class PareamentoResp(val token: String, val dispositivo: DispositivoInfo)
@Serializable data class DispositivosResp(val dispositivos: List<DispositivoInfo> = emptyList(), val atual: String? = null)

@Serializable data class PonteAtiva(val id: String, val visto: Long? = null, val versao: String? = null, val rtt_ms: Double? = null)
@Serializable data class Componente(
    val estado: String = "desconhecido", val visto: Long? = null, val idade_ms: Long? = null, val versao: String? = null,
    val avisos: List<String> = emptyList(), val nota: String? = null, val ativas: List<PonteAtiva> = emptyList(),
    val porta: Int? = null, val observado_em: Long? = null, val desde: Long? = null,
)
@Serializable data class Executor(val declarado: Boolean = false, val ultimo_sucesso: Long? = null, val estado: String = "desconhecido")
@Serializable data class Componentes(
    val central: Componente = Componente(), val ponte: Componente = Componente(), val openbot: Componente = Componente(),
    val executores: Map<String, Executor> = emptyMap(), val obsidian_mcp: Componente = Componente(), val nota: String? = null,
)

@Serializable data class Agente(
    val id: String, val nome: String = "", val motor: String = "codex", val funcao: String = "", val pasta: String = "",
    val criado: Long? = null, val status: String? = null, val atividade: String? = null, val na_fila: Int = 0, val atualizado: Long? = null,
    val memoria: Boolean = false,
)
@Serializable data class EscopoPasta(val nome: String, val escrita: Boolean = false, val existe: Boolean = false,
                                     val sandbox_codex: String? = null, val ferramentas_claude: String? = null, val fonte: String? = null)

@Serializable data class Tempos(val espera_fila_ms: Double? = null, val execucao_ms: Double? = null,
                                val primeira_saida_ms: Double? = null, val total_tarefa_ms: Double? = null)

@Serializable data class TarefaResumo(
    val id: String, val agente: String = "", val status: String = "", val criado: Long? = null, val inicio: Long? = null,
    val fim: Long? = null, val origem: String? = null, val trace_id: String? = null, val tentativa: Int = 1,
    val tentativa_de: String? = null, val projeto: String? = null, val nota: String? = null, val nota_status: String? = null,
    val atividade: String? = null, val motivo: String? = null, val modelo: String? = null, val cancelar: Boolean? = null,
    val texto: String = "", val tempos: Tempos? = null, val tokens: Long? = null, val repetida: Boolean = false,
)
@Serializable data class Evento(val t: Long = 0, val ev: String = "", val comp: String = "", val ponte: String? = null,
                                val status: String? = null, val motivo: String? = null, val obs: String? = null, val origem: String? = null)
@Serializable data class EtapaTrilha(val etapa: String, val rotulo: String, val estado: String, val t: Long? = null)
@Serializable data class UsoReg(
    val chamada_id: String = "", val provedor: String? = null, val modelo: String? = null, val entrada: Long? = null,
    val saida: Long? = null, val cache_leitura: Long? = null, val cache_criacao: Long? = null,
    val entrada_inclui_cache: Boolean = false, val custo_usd: Double? = null, val custo_fonte: String? = null,
    val fonte: String? = null, val final: Boolean = false, val t: Long = 0,
)
@Serializable data class Medidas(val espera_local_ms: Double? = null, val execucao_ms: Double? = null, val primeira_saida_ms: Double? = null,
                                 val relogio: String? = null, val primeira_saida_def: String? = null)
@Serializable data class TentativaRef(val id: String, val tentativa: Int = 1, val status: String = "")
@Serializable data class TarefaDetalhe(
    val id: String, val agente: String = "", val agente_nome: String? = null, val status: String = "", val texto: String = "",
    val resultado: String? = null, val criado: Long? = null, val origem: String? = null, val trace_id: String? = null,
    val tentativa: Int = 1, val tentativa_de: String? = null, val projeto: String? = null, val nota: String? = null,
    val nota_status: String? = null, val motivo: String? = null, val modelo: String? = null, val motor: String? = null,
    val cancelar: Boolean? = null, val codigo_saida: Int? = null, val fim_central: Long? = null,
    val eventos: List<Evento> = emptyList(), val trilha: List<EtapaTrilha> = emptyList(), val uso: List<UsoReg> = emptyList(),
    val tentativas: List<TentativaRef> = emptyList(), val tempos: Tempos? = null, val medidas: Medidas? = null,
)
@Serializable data class OpResumo(val id: String, val status: String = "", val tipo: String? = null, val titulo: String? = null,
                                  val nota: String? = null, val msg: String? = null, val criado: Long? = null,
                                  val concluido: Long? = null, val arquivo: String? = null, val tarefa: String? = null)
@Serializable data class Sessao(val id: String = "", val tipo: String? = null, val titulo: String? = null, val estado: String? = null,
                                val atividade: String? = null, val detalhe: String? = null, val atualizado: Long? = null, val agente: String? = null)
@Serializable data class FeedItem(val t: Long = 0, val agente: String = "", val tipo: String = "", val texto: String = "", val sessao: String? = null)
@Serializable data class PertoLimite(val agente: String, val nome: String? = null, val percentual: Double = 0.0)
@Serializable data class TokensResumo(val consumo_total_periodo: Long? = null, val agentes_perto_limite: List<PertoLimite> = emptyList(),
                                      val com_orcamento: Int = 0, val cobertura: String? = null)

/** Tráfego HTTP medido na Central (bytes do corpo das requisições); ver metricas.fluxo_trafego. */
@Serializable data class Taxa(val entrada: Double = 0.0, val saida: Double = 0.0)
@Serializable data class Transferencia(val t: Long = 0, val canal: String? = null, val sentido: String? = null, val bytes: Long = 0)
@Serializable data class Fluxo(
    val janela_s: Int = 60, val serie_entrada: List<Long> = emptyList(), val serie_saida: List<Long> = emptyList(),
    val taxa: Taxa = Taxa(), val canais: Map<String, Taxa> = emptyMap(), val req_min: Int = 0, val total_bytes: Long = 0,
    val recentes: List<Transferencia> = emptyList(),
)

/** Agentes do OpenBot, lidos pela ponte no banco local dele (só metadados). Ausente = a ponte não informou. */
@Serializable data class OpenbotAgente(
    val id: String = "", val nome: String = "", val titulo: String? = null, val provedor: String = "", val provedor_openbot: String? = null,
    val modelo: String? = null, val trabalhando: Boolean = false, val atualizado: Long? = null, val ultimo_status: String? = null,
    val falhou_ultimo: Boolean = false, val sem_cota: Boolean = false, val tokens_24h: Long? = null, val tokens_total: Long? = null,
    val chamadas_24h: Int = 0, val custo_24h_usd: Double? = null, val ultima_chamada: Long? = null,
)
@Serializable data class OpenbotAgentes(val visto: Long? = null, val lido_em: Long? = null, val agentes: List<OpenbotAgente> = emptyList())

@Serializable data class EstadoV1(
    val agora: Long = 0, val revisao: Long = 0, val versao_backend: String? = null,
    val componentes: Componentes = Componentes(), val agentes: List<Agente> = emptyList(), val projetos: List<String> = emptyList(),
    val escopo_pastas: List<EscopoPasta> = emptyList(), val tarefas: List<TarefaResumo> = emptyList(),
    val ops: List<OpResumo> = emptyList(), val sessoes: List<Sessao> = emptyList(), val feed: List<FeedItem> = emptyList(),
    val estado_pc_em: Long? = null, val tokens_resumo: TokensResumo = TokensResumo(), val fluxo: Fluxo? = null,
    val openbot_agentes: OpenbotAgentes? = null,
)

// ----------------------------------------------------------------- tokens
@Serializable data class Projecao(val estimativa: Boolean = true, val metodo: String = "", val amostras: Int = 0,
                                  val intervalo_horas: Double = 0.0, val taxa_tokens_h: Double = 0.0,
                                  val horas_ate_esgotar: Double = 0.0, val esgota_em_ms: Long = 0)
@Serializable data class OrcamentoLocal(
    val configurado: Boolean = false, val limite_tokens: Long? = null, val periodo: String = "dia", val fuso: String = "",
    val inicio_periodo: Long? = null, val renovacao: Long? = null, val tipo_janela: String? = null,
    val alertas: List<Int> = listOf(70, 85, 95), val bloquear: Boolean = false, val origem: String = "",
    val consumo: Long? = null, val limite: Long? = null, val percentual_consumido: Double? = null,
    val percentual_disponivel: Double? = null, val saldo_tokens: Long? = null, val excesso_tokens: Long? = null,
    val estado: String = "sem_dados", val alerta_atingido: Int? = null, val projecao: Projecao? = null,
)
@Serializable data class Indisponivel(val disponivel: Boolean = false, val motivo: String = "")
@Serializable data class PorModelo(val modelo: String, val tokens: Long = 0)
@Serializable data class PorTarefa(val tarefa: String, val tentativa: Int? = null, val tokens: Long = 0, val entrada: Long = 0,
                                   val saida: Long = 0, val modelo: String? = null, val custo_usd: Double? = null, val status: String? = null)
@Serializable data class HistDia(val dia: String, val tokens: Long = 0)
@Serializable data class TokensAgente(
    val agente: String, val nome: String? = null, val motor: String? = null, val projeto: String? = null,
    val orcamento_local: OrcamentoLocal = OrcamentoLocal(), val consumo_periodo: Long? = null, val chamadas: Int = 0,
    val ultima_atualizacao: Long? = null, val por_modelo: List<PorModelo> = emptyList(), val por_tarefa: List<PorTarefa> = emptyList(),
    val historico_dia: List<HistDia> = emptyList(), val cota_provedor: Indisponivel = Indisponivel(),
    val contexto: Indisponivel = Indisponivel(), val vazao: Indisponivel = Indisponivel(), val cobertura: String = "",
)
@Serializable data class TokensVisao(val agora: Long = 0, val agentes: List<TokensAgente> = emptyList(),
                                     val resumo: TokensResumo = TokensResumo(), val definicao_total: String = "")

// --------------------------------------------------------------- métricas
@Serializable data class Resumo(val n: Int = 0, val media: Double? = null, val mediana: Double? = null, val p50: Double? = null,
                                val p95: Double? = null, val min: Double? = null, val max: Double? = null, val aviso: String = "")
@Serializable data class Sucesso(val sucesso_percentual: Double? = null, val denominador: Int = 0,
                                 val contagens: Map<String, Int> = emptyMap(), val definicao: String = "")
@Serializable data class PastaN(val pasta: String, val n: Int)
@Serializable data class DiaN(val dia: String, val n: Int)
@Serializable data class SeriePastas(val dados: List<PastaN> = emptyList(), val fonte: String? = null, val observado_em: Long? = null)
@Serializable data class SerieDias(val dados: List<DiaN> = emptyList(), val rotulo: String = "", val aviso: String = "", val desde: Long? = null)
@Serializable data class PontoDisp(val id: String, val rotulo: String? = null, val pasta: String = "", val dias: Double = 0.0, val ligacoes: Int = 0)
@Serializable data class Dispersao(val dados: List<PontoDisp> = emptyList(), val x: String = "", val y: String = "",
                                   val tamanho: String = "", val cor: String = "")
@Serializable data class TarefaTempo(
    val id: String, val agente: String = "", val nome: String? = null, val status: String = "", val criado: Long? = null,
    val fim_central: Long? = null, val tentativa: Int = 1, val origem: String? = null, val modelo: String? = null,
    val legado: Boolean = false, val espera_fila_ms: Double? = null, val execucao_ms: Double? = null,
    val primeira_saida_ms: Double? = null, val total_tarefa_ms: Double? = null,
)
@Serializable data class ListaTempos(val dados: List<TarefaTempo> = emptyList())
@Serializable data class ResumoAgente(val agente: String, val nome: String? = null, val espera_fila_ms: Resumo = Resumo(),
                                      val execucao_ms: Resumo = Resumo(), val primeira_saida_ms: Resumo = Resumo(),
                                      val total_tarefa_ms: Resumo = Resumo(), val sucesso: Sucesso = Sucesso())
@Serializable data class Lat(val t: Long = 0, val comp: String = "", val ms: Double = 0.0)
@Serializable data class Comunicacao(val componentes: Map<String, Resumo> = emptyMap(), val serie: List<Lat> = emptyList(), val definicao: String = "")
@Serializable data class MetricasVisao(
    val agora: Long = 0, val periodo: String = "7d", val fuso: String = "",
    val barras_pastas: SeriePastas = SeriePastas(), val linhas_mtime: SerieDias = SerieDias(),
    val linhas_eventos: SerieDias = SerieDias(), val dispersao: Dispersao = Dispersao(), val tarefas: ListaTempos = ListaTempos(),
    val resumo_agentes: List<ResumoAgente> = emptyList(), val sucesso_geral: Sucesso = Sucesso(),
    val comunicacao: Comunicacao = Comunicacao(), val percentil: String = "",
)

// ------------------------------------------------------------ grafo / notas
@Serializable data class No(val id: String, val rotulo: String = "", val grupo: String? = null, val tipo: String = "nota",
                            val x: Double = 0.0, val y: Double = 0.0, val raio: Double = 4.0, val conexoes: Int = 0, val mod: Long? = null)
@Serializable data class Grafo(val nos: List<No> = emptyList(), val arestas: List<List<String>> = emptyList(), val total_notas: Int? = null)
@Serializable data class GrafoResp(val versao: String = "", val grafo: Grafo = Grafo(), val observado_em: Long? = null)
@Serializable data class NotaItem(val id: String, val rotulo: String? = null, val pasta: String = "", val mod: Long? = null,
                                  val ligacoes: Int = 0, val trecho: String? = null, val sincronizada: Boolean = false)
@Serializable data class NotasBusca(val notas: List<NotaItem> = emptyList(), val total: Int = 0)
@Serializable data class NotaConteudo(val id: String, val mod: Long = 0, val conteudo: String = "")
@Serializable data class OpCriada(val ok: Boolean = false, val op: String = "", val status: String? = null, val repetida: Boolean = false)

// --------------------------------------------------------- logs / diagnóstico
@Serializable data class LogsResp(val logs: List<JsonObject> = emptyList(), val proximo: Long? = null, val retencao: String = "")
@Serializable data class SessaoDisp(val dispositivo: String? = null, val nome: String? = null, val expira: Long? = null)
@Serializable data class DiagResp(
    val agora: Long = 0, val versao_backend: String? = null, val versao_api: Int? = null, val pid: Int? = null,
    val central_desde: Long? = null, val uptime_s: Long? = null, val componentes: Componentes = Componentes(),
    val url_tunel: String? = null, val ultima_sincronizacao_ponte: Long? = null, val estado_pc_em: Long? = null,
    val sessao: SessaoDisp = SessaoDisp(), val contagens: Map<String, Int> = emptyMap(),
    val sessao_remota_pc: Indisponivel = Indisponivel(), val processamento_ms: Double? = null,
)
@Serializable data class AgentesResp(val agentes: List<Agente> = emptyList(), val projetos: List<String> = emptyList(),
                                     val escopo_pastas: List<EscopoPasta> = emptyList())
@Serializable data class ErroResp(val erro: String? = null, val codigo: String? = null)
