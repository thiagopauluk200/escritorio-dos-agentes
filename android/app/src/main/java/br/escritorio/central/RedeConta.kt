package br.escritorio.central

import android.content.Context
import android.util.Base64
import br.escritorio.central.data.Cofre
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Computador(val ip: String, val porta: Int, val pin: String) {
    val endereco get() = "$ip:$porta"

    companion object {
        fun validar(ip: String, porta: Int, pin: String): Computador {
            val octetos = ip.split('.').mapNotNull { it.toIntOrNull() }
            val formato = octetos.size == 4 && octetos.joinToString(".") == ip && octetos.all { it in 0..255 }
            val privado = formato && (octetos[0] == 10 || octetos[0] == 172 && octetos[1] in 16..31 ||
                octetos[0] == 192 && octetos[1] == 168 || ip == "127.0.0.1")
            require(privado && porta in 1..65535 && pin.matches(Regex("[a-f0-9]{64}"))) { "Código do computador inválido." }
            return Computador(ip, porta, pin)
        }
    }
}

data class Convite(val pc: Computador, val codigoCadastro: String?) {
    companion object {
        fun ler(valor: String): Convite {
            val codigo = valor.trim()
            require(codigo.length in 40..600 && codigo.startsWith("RN1.")) { "Cole o código que começa com RN1." }
            return try {
                val dados = JSONObject(String(Base64.decode(codigo.removePrefix("RN1."), Base64.URL_SAFE), Charsets.UTF_8))
                val pc = Computador.validar(dados.getString("h"), dados.getInt("p"), dados.getString("f"))
                val cadastro = dados.optString("c").takeIf { it.matches(Regex("[A-Za-z0-9_-]{32}")) }
                Convite(pc, cadastro)
            } catch (_: Exception) {
                throw IllegalArgumentException("Código do computador inválido. Copie o código completo exibido no PC.")
            }
        }
    }
}

data class SessaoRede(val usuario: String, val token: String)
data class RespostaRede(val status: Int, val corpo: ByteArray)

class AcessoLocal(context: Context) {
    private val prefs = context.getSharedPreferences("rede_acesso_v3", Context.MODE_PRIVATE)
    private val cofre = Cofre()

    fun computador(): Computador? = runCatching {
        Computador.validar(prefs.getString("ip", "").orEmpty(), prefs.getInt("porta", 0), prefs.getString("pin", "").orEmpty())
    }.getOrNull()

    fun sessao(): SessaoRede? {
        val usuario = prefs.getString("usuario", null) ?: return null
        val cifra = prefs.getString("cifra", null) ?: return null
        val token = cofre.decifrar(cifra) ?: return null
        return SessaoRede(usuario, token)
    }

    fun salvar(pc: Computador, sessao: SessaoRede) {
        val salvo = prefs.edit().putString("ip", pc.ip).putInt("porta", pc.porta).putString("pin", pc.pin)
            .putString("usuario", sessao.usuario).putString("cifra", cofre.cifrar(sessao.token)).commit()
        check(salvo) { "Não foi possível salvar a sessão no aparelho." }
    }

    fun sair(esquecer: Boolean = false) {
        val editor = prefs.edit().remove("cifra")
        if (esquecer) editor.clear()
        check(editor.commit()) { "Não foi possível limpar a sessão no aparelho." }
    }
}

class ConexaoPc(val pc: Computador) {
    private val cliente: OkHttpClient

    init {
        // Confie na identidade recebida no código, antes de enviar senha ou sessão.
        val certificado = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Autenticação do cliente indisponível.")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val folha = chain.firstOrNull() ?: throw CertificateException("Certificado ausente.")
                folha.checkValidity()
                val hash = MessageDigest.getInstance("SHA-256").digest(folha.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(hash.toByteArray(), pc.pin.toByteArray())) {
                    throw CertificateException("A identidade do computador mudou.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val contexto = SSLContext.getInstance("TLS").apply { init(null, arrayOf(certificado), null) }
        cliente = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .sslSocketFactory(contexto.socketFactory, certificado)
            .hostnameVerifier { host, _ -> host == pc.ip } // A identidade é o certificado fixado no código.
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun entrar(usuario: String, senha: String, cadastro: Boolean, convite: String?): SessaoRede {
        val dados = JSONObject().put("usuario", usuario).put("senha", senha)
        if (cadastro) dados.put("codigo", convite)
        val rota = if (cadastro) "/api/app/cadastro" else "/api/app/login"
        val resposta = pedir(rota, dados = dados)
        val json = JSONObject(String(resposta.corpo, Charsets.UTF_8))
        if (resposta.status != 200) throw IllegalArgumentException(json.optString("erro", "Falha ao entrar."))
        val token = json.getString("token")
        require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Sessão inválida enviada pelo computador." }
        return SessaoRede(json.getString("usuario"), token)
    }

    fun verificar() {
        val resposta = pedir("/api/saude")
        check(resposta.status == 200 && JSONObject(String(resposta.corpo, Charsets.UTF_8))
            .optString("servico") == "rede-neural") { "O computador não respondeu como Rede Neural." }
    }

    fun sair(token: String) {
        check(pedir("/api/app/sair", token = token, dados = JSONObject()).status == 200) {
            "Não foi possível encerrar a sessão no computador."
        }
    }

    fun pedir(caminho: String, query: String? = null, token: String? = null, dados: JSONObject? = null): RespostaRede {
        val url = HttpUrl.Builder().scheme("https").host(pc.ip).port(pc.porta)
            .encodedPath(caminho).encodedQuery(query).build()
        val pedido = Request.Builder().url(url).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (dados != null) post(dados.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return cliente.newCall(pedido).execute().use { resposta ->
            val fonte = resposta.body.source()
            val limite = if (caminho == "/api/grafo") 16L * 1024 * 1024 else 1024L * 1024
            if (fonte.request(limite + 1)) throw IOException("Resposta maior que o limite do painel.")
            RespostaRede(resposta.code, fonte.readByteArray())
        }
    }
}
