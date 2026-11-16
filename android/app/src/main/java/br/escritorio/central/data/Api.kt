package br.escritorio.central.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Erro com ação possível para o usuário. Nunca carrega token, cookie ou cabeçalho. */
class ApiErro(val tipo: Tipo, val mensagem: String, val http: Int? = null, val codigo: String? = null) : Exception(mensagem) {
    enum class Tipo { SEM_REDE, TIMEOUT, TLS, NAO_AUTORIZADO, PROIBIDO, CONFLITO, NAO_ENCONTRADO, SERVIDOR, RESPOSTA_INVALIDA, CONFIG }
}

data class AmostraLatencia(val rota: String, val ms: Double, val ok: Boolean, val em: Long)

/**
 * Cliente HTTP da Central. Uma conexão reaproveitada (pool), timeouts explícitos, limite de payload e
 * medição de latência com relógio monotônico (SystemClock.elapsedRealtimeNanos) no próprio aparelho.
 * Se o endereço principal falhar por REDE (não por HTTP), tenta o alternativo (ex.: túnel HTTPS).
 */
class Api(private val sessao: () -> Pair<List<String>, String?>) {
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(false) // POST nunca é repetido às cegas; idempotência é explícita
        .build()
    private val tipoJson = "application/json; charset=utf-8".toMediaType()

    @Volatile var enderecoAtivo: String? = null; private set
    private val amostras = ArrayDeque<AmostraLatencia>()
    private val trava = Any()

    fun latencias(): List<AmostraLatencia> = synchronized(trava) { amostras.toList() }
    fun tirarLatencias(max: Int = 50): List<AmostraLatencia> = synchronized(trava) {
        val l = amostras.take(max); repeat(l.size) { amostras.removeFirst() }; l
    }

    private fun registrar(a: AmostraLatencia) = synchronized(trava) {
        amostras.addLast(a); while (amostras.size > 300) amostras.removeFirst()
    }

    suspend fun <T> get(caminho: String, ser: KSerializer<T>, base: String? = null, token: String? = null,
                        etag: String? = null): Resposta<T> = chamar("GET", caminho, null, ser, base, token, etag)

    suspend fun <T> post(caminho: String, corpo: JsonElement, ser: KSerializer<T>, base: String? = null, token: String? = null): Resposta<T> =
        chamar("POST", caminho, corpo, ser, base, token, null)

    data class Resposta<T>(val valor: T?, val http: Int, val etag: String?, val ms: Double, val endereco: String)

    private suspend fun <T> chamar(metodo: String, caminho: String, corpo: JsonElement?, ser: KSerializer<T>,
                                   baseFixa: String?, tokenFixo: String?, etag: String?): Resposta<T> = withContext(Dispatchers.IO) {
        val (bases, tokenSessao) = sessao()
        val lista = (if (baseFixa != null) listOf(baseFixa) else listOfNotNull(enderecoAtivo) + bases).distinct().filter { it.isNotBlank() }
        if (lista.isEmpty()) throw ApiErro(ApiErro.Tipo.CONFIG, "Configure o endereço da Central.")
        val token = tokenFixo ?: tokenSessao
        var ultimo: ApiErro? = null
        for (base in lista) {
            try {
                val r = umaVez(base, metodo, caminho, corpo, ser, token, etag)
                if (baseFixa == null) enderecoAtivo = base
                return@withContext r
            } catch (e: ApiErro) {
                ultimo = e
                // só troca de endereço em falha de rede/TLS; erro HTTP é resposta legítima da Central
                if (e.tipo !in setOf(ApiErro.Tipo.SEM_REDE, ApiErro.Tipo.TIMEOUT, ApiErro.Tipo.TLS)) throw e
                // POST: se o tempo esgotou depois de enviar, NÃO tenta em outro endereço (o servidor pode ter recebido)
                if (metodo == "POST" && e.tipo == ApiErro.Tipo.TIMEOUT) throw e
            }
        }
        throw ultimo ?: ApiErro(ApiErro.Tipo.SEM_REDE, "Sem conexão com a Central.")
    }

    private fun <T> umaVez(base: String, metodo: String, caminho: String, corpo: JsonElement?, ser: KSerializer<T>,
                           token: String?, etag: String?): Resposta<T> {
        val url = (base.trimEnd('/') + caminho).toHttpUrlOrNull()
            ?: throw ApiErro(ApiErro.Tipo.CONFIG, "Endereço inválido: use http(s)://host:porta")
        val rb = Request.Builder().url(url)
            .header("X-Requested-With", "escritorio-android")
            .header("Accept", "application/json")
            .header("User-Agent", "CentralAndroid/1.0")
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        etag?.let { rb.header("If-None-Match", it) }
        if (metodo == "POST") rb.post((corpo?.toString() ?: "{}").toRequestBody(tipoJson)) else rb.get()
        val rotulo = caminho.substringBefore('?').replace(Regex("/[a-z]{1,3}[0-9a-f]{8,}"), "/{id}")
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            cliente.newCall(rb.build()).execute().use { resp ->
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val tam = resp.body.contentLength()
                if (tam > LIMITE_BYTES) throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta grande demais ($tam bytes).")
                val texto = resp.body.source().let { src ->
                    src.request(LIMITE_BYTES + 1)
                    if (src.buffer.size > LIMITE_BYTES) throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta grande demais.")
                    src.buffer.readUtf8()
                }
                registrar(AmostraLatencia(rotulo, ms, resp.isSuccessful || resp.code == 304, System.currentTimeMillis()))
                if (resp.code == 304) return Resposta(null, 304, resp.header("ETag"), ms, base)
                if (!resp.isSuccessful) {
                    val err = runCatching { JsonApi.decodeFromString(ErroResp.serializer(), texto) }.getOrNull()
                    val msg = err?.erro ?: "A Central respondeu HTTP ${resp.code}."
                    val tipo = when (resp.code) {
                        401 -> ApiErro.Tipo.NAO_AUTORIZADO; 403 -> ApiErro.Tipo.PROIBIDO; 404 -> ApiErro.Tipo.NAO_ENCONTRADO
                        409 -> ApiErro.Tipo.CONFLITO; 429 -> ApiErro.Tipo.PROIBIDO; in 500..599 -> ApiErro.Tipo.SERVIDOR
                        else -> ApiErro.Tipo.SERVIDOR
                    }
                    throw ApiErro(tipo, msg, resp.code, err?.codigo)
                }
                val valor = try { JsonApi.decodeFromString(ser, texto) } catch (e: Exception) {
                    throw ApiErro(ApiErro.Tipo.RESPOSTA_INVALIDA, "Resposta inesperada da Central (versão incompatível?).", resp.code)
                }
                return Resposta(valor, resp.code, resp.header("ETag"), ms, base)
            }
        } catch (e: ApiErro) {
            throw e
        } catch (e: SSLException) {
            registrar(AmostraLatencia(rotulo, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, false, System.currentTimeMillis()))
            throw ApiErro(ApiErro.Tipo.TLS, "Falha na conexão segura (certificado ou TLS) com $base.")
        } catch (e: InterruptedIOException) {
            registrar(AmostraLatencia(rotulo, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, false, System.currentTimeMillis()))
            throw ApiErro(ApiErro.Tipo.TIMEOUT, "A Central não respondeu a tempo ($base).")
        } catch (e: UnknownHostException) {
            throw ApiErro(ApiErro.Tipo.SEM_REDE, "Endereço não encontrado: $base. Confira a rede ou o túnel.")
        } catch (e: IOException) {
            if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true)
                throw ApiErro(ApiErro.Tipo.CONFIG, "Este build só aceita HTTPS. Use o endereço do túnel (https://...).")
            throw ApiErro(ApiErro.Tipo.SEM_REDE, "Sem conexão com $base (${e.message?.take(80) ?: e.javaClass.simpleName}).")
        }
    }

    companion object {
        const val LIMITE_BYTES = 6L * 1024 * 1024
        fun corpo(vararg pares: Pair<String, Any?>): JsonObject = buildJsonObject {
            for ((k, v) in pares) when (v) {
                null -> {}
                is String -> put(k, v); is Number -> put(k, v); is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                is List<*> -> put(k, kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it as Number) }))
                else -> put(k, v.toString())
            }
        }
    }
}
