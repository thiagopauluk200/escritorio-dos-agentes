package br.escritorio.central.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.util.UUID

/** Valor com a idade da informação e a origem (rede ou cache local). */
data class Dado<T>(val valor: T, val atualizadoEm: Long, val doCache: Boolean)

enum class Conexao { DESCONHECIDA, CONECTANDO, ONLINE, OFFLINE }

data class EstadoConexao(
    val conexao: Conexao = Conexao.DESCONHECIDA,
    val endereco: String? = null,
    val erro: ApiErro? = null,
    val ultimoSucesso: Long? = null,
    val falhasSeguidas: Int = 0,
)

class Repositorio(private val api: Api, private val db: BancoLocal, private val prefs: Preferencias, private val cofre: Cofre) {
    @Volatile private var token: String? = null
    @Volatile private var bases: List<String> = emptyList()

    private val _conexao = MutableStateFlow(EstadoConexao())
    val conexao: StateFlow<EstadoConexao> = _conexao.asStateFlow()
    private val _estado = MutableStateFlow<Dado<EstadoV1>?>(null)
    val estado: StateFlow<Dado<EstadoV1>?> = _estado.asStateFlow()
    private val _sessaoValida = MutableStateFlow<Boolean?>(null)
    val sessaoValida: StateFlow<Boolean?> = _sessaoValida.asStateFlow()

    val envios: Flow<List<EnvioPendente>> = db.envios().pendentes()

    fun sessao(): Pair<List<String>, String?> = bases to token
    fun enderecoAtivo(): String? = api.enderecoAtivo
    fun latenciasLocais() = api.latencias()

    suspend fun iniciar() {
        val c = prefs.atual()
        bases = listOf(c.principal, c.alternativo).filter { it.isNotBlank() }
        token = c.tokenCifrado.takeIf { it.isNotBlank() }?.let { cofre.decifrar(it) }
        _sessaoValida.value = token != null && bases.isNotEmpty()
        db.cache().obter("estado")?.let { e ->
            runCatching { JsonApi.decodeFromString(EstadoV1.serializer(), e.json) }.getOrNull()?.let {
                _estado.value = Dado(it, e.atualizadoEm, doCache = true)
            }
        }
    }

    private fun <T> marcarOk(r: Api.Resposta<T>) {
        _conexao.value = EstadoConexao(Conexao.ONLINE, r.endereco, null, System.currentTimeMillis(), 0)
    }

    private suspend fun marcarErro(e: ApiErro) {
        val ant = _conexao.value
        val offline = e.tipo in setOf(ApiErro.Tipo.SEM_REDE, ApiErro.Tipo.TIMEOUT, ApiErro.Tipo.TLS, ApiErro.Tipo.CONFIG)
        _conexao.value = ant.copy(conexao = if (offline) Conexao.OFFLINE else ant.conexao, erro = e, falhasSeguidas = ant.falhasSeguidas + 1)
        if (e.tipo == ApiErro.Tipo.NAO_AUTORIZADO) {
            // sessão expirada ou revogada: exige novo pareamento (não tenta outra credencial)
            token = null
            prefs.limparSessao()
            _sessaoValida.value = false
        }
    }

    private suspend fun <T> rede(bloco: suspend () -> Api.Resposta<T>): Api.Resposta<T> {
        if (_conexao.value.conexao == Conexao.DESCONHECIDA) _conexao.value = _conexao.value.copy(conexao = Conexao.CONECTANDO)
        return try { bloco().also { marcarOk(it) } } catch (e: ApiErro) { marcarErro(e); throw e }
    }

    // ------------------------------------------------------------ sessão
    suspend fun testarServidor(url: String): InfoResp =
        api.get("/api/v1/info", InfoResp.serializer(), base = normalizar(url)).valor
            ?: throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta vazia")

    suspend fun parear(url: String, senha: String, nomeAparelho: String): DispositivoInfo {
        val base = normalizar(url)
        val r = api.post("/api/v1/dispositivos/parear", Api.corpo("senha" to senha, "nome" to nomeAparelho),
            PareamentoResp.serializer(), base = base).valor ?: throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta vazia")
        prefs.servidores(base, prefs.atual().alternativo.takeIf { it != base } ?: "")
        prefs.sessao(cofre.cifrar(r.token), r.dispositivo.id)
        token = r.token
        bases = listOf(base) + listOfNotNull(prefs.atual().alternativo.takeIf { it.isNotBlank() && it != base })
        _sessaoValida.value = true
        _conexao.value = EstadoConexao(Conexao.CONECTANDO, base)
        return r.dispositivo
    }

    /** Alternativa ao pareamento por senha: token criado no próprio servidor (python hub.py --novo-dispositivo NOME). */
    suspend fun entrarComToken(url: String, tokenAparelho: String): DiagResp {
        val base = normalizar(url)
        val d = api.get("/api/v1/diagnostico", DiagResp.serializer(), base = base, token = tokenAparelho.trim()).valor
            ?: throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta vazia")
        prefs.servidores(base, prefs.atual().alternativo.takeIf { it != base } ?: "")
        prefs.sessao(cofre.cifrar(tokenAparelho.trim()), d.sessao.dispositivo ?: "")
        token = tokenAparelho.trim()
        bases = listOf(base) + listOfNotNull(prefs.atual().alternativo.takeIf { it.isNotBlank() && it != base })
        _sessaoValida.value = true
        return d
    }

    suspend fun definirAlternativo(url: String) {
        val n = if (url.isBlank()) "" else normalizar(url)
        prefs.alternativo(n)
        bases = listOf(prefs.atual().principal, n).filter { it.isNotBlank() }.distinct()
    }

    suspend fun sair(revogarNoServidor: Boolean = true) {
        if (revogarNoServidor) runCatching { api.post("/api/v1/sessao/sair", JsonObject(emptyMap()), JsonObject.serializer()) }
        token = null
        prefs.limparSessao()
        db.cache().limpar()
        db.envios().limpar()
        _estado.value = null
        _sessaoValida.value = false
    }

    // ------------------------------------------------------------ leitura
    suspend fun atualizarEstado(): EstadoV1 {
        val r = rede { api.get("/api/v1/estado", EstadoV1.serializer()) }
        val v = r.valor!!
        val agora = System.currentTimeMillis()
        _estado.value = Dado(v, agora, doCache = false)
        db.cache().salvar(CacheEntrada("estado", JsonApi.encodeToString(EstadoV1.serializer(), v), agora))
        return v
    }

    /** Busca com cache offline: devolve a rede quando há; senão, o último valor salvo com a sua idade. */
    suspend fun <T> comCache(chave: String, caminho: String, ser: KSerializer<T>): Dado<T> {
        return try {
            val r = rede { api.get(caminho, ser) }
            val agora = System.currentTimeMillis()
            db.cache().salvar(CacheEntrada(chave, JsonApi.encodeToString(ser, r.valor!!), agora))
            Dado(r.valor, agora, false)
        } catch (e: ApiErro) {
            val c = db.cache().obter(chave) ?: throw e
            Dado(JsonApi.decodeFromString(ser, c.json), c.atualizadoEm, true)
        }
    }

    private var grafoEtag: String? = null
    suspend fun grafo(): Dado<GrafoResp> {
        val cache = db.cache().obter("grafo")
        return try {
            val r = rede { api.get("/api/v1/grafo", GrafoResp.serializer(), etag = if (cache != null) grafoEtag else null) }
            if (r.http == 304 && cache != null) {
                Dado(JsonApi.decodeFromString(GrafoResp.serializer(), cache.json), System.currentTimeMillis(), false)
            } else {
                grafoEtag = r.etag
                val agora = System.currentTimeMillis()
                db.cache().salvar(CacheEntrada("grafo", JsonApi.encodeToString(GrafoResp.serializer(), r.valor!!), agora))
                Dado(r.valor, agora, false)
            }
        } catch (e: ApiErro) {
            cache ?: throw e
            Dado(JsonApi.decodeFromString(GrafoResp.serializer(), cache.json), cache.atualizadoEm, true)
        }
    }

    suspend fun tarefa(id: String) = comCache("tarefa:$id", "/api/v1/tarefas/$id", TarefaDetalhe.serializer())
    suspend fun metricas(periodo: String, agente: String?) =
        comCache("metricas:$periodo:${agente.orEmpty()}", "/api/v1/metricas?periodo=$periodo" + (agente?.let { "&agente=$it" } ?: ""), MetricasVisao.serializer())
    suspend fun tokens(periodo: String?, agente: String?, projeto: String?, modelo: String?): Dado<TokensVisao> {
        val q = listOfNotNull(periodo?.let { "periodo=$it" }, agente?.let { "agente=$it" }, projeto?.let { "projeto=${enc(it)}" },
            modelo?.let { "modelo=${enc(it)}" }).joinToString("&")
        return comCache("tokens:$q", "/api/v1/tokens" + (if (q.isNotEmpty()) "?$q" else ""), TokensVisao.serializer())
    }
    suspend fun notas(q: String, pasta: String?) =
        rede { api.get("/api/v1/notas?q=${enc(q)}" + (pasta?.let { "&pasta=${enc(it)}" } ?: ""), NotasBusca.serializer()) }.valor!!
    suspend fun nota(id: String) = comCache("nota:$id", "/api/v1/nota?id=${enc(id)}", NotaConteudo.serializer())
    suspend fun op(id: String) = rede { api.get("/api/v1/ops/$id", OpResumo.serializer()) }.valor!!
    suspend fun logs(filtros: Map<String, String?>): LogsResp {
        val q = filtros.filterValues { !it.isNullOrBlank() }.map { "${it.key}=${enc(it.value!!)}" }.joinToString("&")
        return rede { api.get("/api/v1/logs?$q", LogsResp.serializer()) }.valor!!
    }
    suspend fun diagnostico() = rede { api.get("/api/v1/diagnostico", DiagResp.serializer()) }
    suspend fun dispositivos() = rede { api.get("/api/v1/dispositivos", DispositivosResp.serializer()) }.valor!!
    suspend fun info(base: String) = api.get("/api/v1/info", InfoResp.serializer(), base = base, token = "")

    // ------------------------------------------------------------- escrita
    /** Grava o envio com idem ANTES da requisição; resposta perdida -> 'nao_confirmado', reenvio seguro com o mesmo idem. */
    suspend fun enviarTarefa(agente: String, texto: String, registrarNota: Boolean, idemExistente: String? = null): TarefaResumo {
        val idem = idemExistente ?: UUID.randomUUID().toString()
        val ant = db.envios().obter(idem)
        db.envios().salvar(EnvioPendente(idem, agente, texto, registrarNota, ant?.criadoEm ?: System.currentTimeMillis(),
            "enviando", tentativas = (ant?.tentativas ?: 0) + 1))
        return try {
            val r = rede {
                api.post("/api/v1/tarefas", Api.corpo("agente" to agente, "texto" to texto, "idem" to idem,
                    "registrar_nota" to registrarNota, "origem" to "android"), TarefaResumo.serializer())
            }.valor!!
            db.envios().salvar(db.envios().obter(idem)!!.copy(estado = "confirmado", tarefaId = r.id, erro = null))
            db.envios().limparAntigos(System.currentTimeMillis() - 86_400_000)
            r
        } catch (e: ApiErro) {
            val recusado = e.http != null && e.http in 400..499
            db.envios().salvar(db.envios().obter(idem)!!.copy(estado = if (recusado) "recusado" else "nao_confirmado", erro = e.mensagem))
            throw e
        }
    }

    suspend fun descartarEnvio(idem: String) = db.envios().apagar(idem)

    /** Política explícita: só reenvia automaticamente se o usuário ativou, e sempre com o MESMO idem. */
    suspend fun reconfirmarPendentes(): Int {
        if (!prefs.atual().reenvioAutomatico) return 0
        var n = 0
        for (e in db.envios().naoConfirmados()) {
            runCatching { enviarTarefa(e.agente, e.texto, e.registrarNota, e.idem) }.onSuccess { n++ }
        }
        return n
    }

    suspend fun cancelar(id: String) = rede { api.post("/api/v1/tarefas/$id/cancelar", JsonObject(emptyMap()), TarefaResumo.serializer()) }.valor!!
    suspend fun repetir(id: String, idem: String) =
        rede { api.post("/api/v1/tarefas/$id/repetir", Api.corpo("idem" to idem), TarefaResumo.serializer()) }.valor!!

    /** `idem`: chave gerada uma vez por abertura do formulário; reenviar com a mesma chave não cria outro agente. */
    suspend fun salvarAgente(id: String?, nome: String, motor: String, funcao: String, pasta: String, idem: String? = null) =
        rede { api.post("/api/v1/agentes", Api.corpo("id" to id, "nome" to nome, "motor" to motor, "funcao" to funcao, "pasta" to pasta, "idem" to idem), Agente.serializer()) }.valor!!
    suspend fun excluirAgente(id: String) = rede { api.post("/api/v1/agentes/$id/excluir", JsonObject(emptyMap()), JsonObject.serializer()) }

    suspend fun salvarNota(idem: String, titulo: String?, notaId: String?, baseMod: Long?, conteudo: String) =
        rede {
            api.post("/api/v1/notas", Api.corpo("idem" to idem, "titulo" to titulo, "nota" to notaId, "base_mod" to baseMod, "conteudo" to conteudo),
                OpCriada.serializer())
        }.valor!!

    suspend fun salvarOrcamento(agente: String, limite: Long?, periodo: String, alertas: List<Int>, bloquear: Boolean, remover: Boolean = false) {
        val corpo = buildJsonObject {
            put("agente", agente); put("periodo", periodo); put("bloquear", bloquear); put("remover", remover)
            put("fuso", java.util.TimeZone.getDefault().id)
            if (limite != null) put("limite_tokens", limite) else put("limite_tokens", kotlinx.serialization.json.JsonNull)
            putJsonArray("alertas") { alertas.forEach { add(it) } }
        }
        rede { api.post("/api/v1/orcamentos", corpo, JsonObject.serializer()) }
    }

    suspend fun revogarDispositivo(id: String) = rede { api.post("/api/v1/dispositivos/$id/revogar", JsonObject(emptyMap()), JsonObject.serializer()) }

    /** Envia à Central as latências medidas NO APARELHO (monotônico) para os gráficos de comunicação. */
    suspend fun relatarLatencias() {
        val l = api.tirarLatencias(50).filter { it.ok }
        if (l.isEmpty()) return
        val corpo = buildJsonObject {
            put("amostras", JsonArray(l.map { buildJsonObject { put("rota", it.rota); put("ms", it.ms) } }))
        }
        runCatching { api.post("/api/v1/latencias", corpo, JsonObject.serializer()) }
    }

    // ------------------------------------------------------------ rascunhos
    suspend fun rascunho(chave: String) = db.rascunhos().obter(chave)
    suspend fun salvarRascunho(chave: String, texto: String, extra: String = "") =
        if (texto.isBlank() && extra.isBlank()) db.rascunhos().apagar(chave)
        else db.rascunhos().salvar(Rascunho(chave, texto, extra, System.currentTimeMillis()))
    suspend fun apagarRascunho(chave: String) = db.rascunhos().apagar(chave)

    companion object {
        fun normalizar(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
