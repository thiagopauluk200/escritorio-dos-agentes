package br.escritorio.central

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val ORIGEM = "appassets.androidplatform.net"
private const val SERVIDOR = "http://127.0.0.1:8770"
private const val CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; " +
    "script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

class MainActivity : ComponentActivity() {
    private lateinit var conteudo: FrameLayout
    private lateinit var acesso: AcessoLocal
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkManager.getInstance(this).cancelUniqueWork(AppCentral.TRABALHO)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        acesso = AcessoLocal(this)
        conteudo = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(conteudo) { view, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
        setContentView(conteudo)
        ViewCompat.requestApplyInsets(conteudo)

        val pc = acesso.computador()
        val sessao = acesso.sessao()
        val legado = if (BuildConfig.DEBUG) runCatching {
            filesDir.resolve("rede-token").readText(Charsets.US_ASCII).trim()
        }.getOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{40,128}")) } else null
        when {
            pc != null && sessao != null -> abrirPainel(pc, sessao)
            legado != null -> abrirPainel(legado = legado)
            else -> mostrarConta(codigo = codigoRecebido(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val codigo = codigoRecebido(intent)
        if (web == null && acesso.sessao() == null && codigo.isNotEmpty()) mostrarConta(codigo = codigo)
    }

    private fun codigoRecebido(intent: Intent): String =
        if (intent.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            ?.takeIf { it.length in 40..600 && it.startsWith("RN1.") }.orEmpty() else ""

    private fun mostrarConta(mensagem: String = "", codigo: String = "") {
        fecharPainel()
        conteudo.removeAllViews()
        val pc = acesso.computador()
        val sessao = acesso.sessao()
        conteudo.addView(ComposeView(this).apply {
            setContent {
                TelaAcesso(pc, sessao, codigo, mensagem, acesso::salvar, acesso::sair,
                    abrir = { computador, ativa -> abrirPainel(computador, ativa) })
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun abrirPainel(pc: Computador? = null, sessao: SessaoRede? = null, legado: String? = null) {
        fecharPainel()
        conteudo.removeAllViews()
        val conexao = pc?.let(::ConexaoPc)
        val painel = WebView(this).apply {
            setBackgroundColor(0xff090e18.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            webViewClient = RedeLocal(
                abrir = { assets.open(it) },
                legado = legado,
                conexao = conexao,
                token = sessao?.token,
                conta = { origem, expirada ->
                    runOnUiThread {
                        if (web === origem && !isFinishing) {
                            if (expirada) acesso.sair()
                            mostrarConta(if (expirada) "Sua sessão expirou. Entre novamente." else "")
                        }
                    }
                }
            )
        }
        web = painel
        conteudo.addView(painel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        painel.loadUrl("https://$ORIGEM/index.html")
    }

    private fun fecharPainel() {
        web?.let {
            conteudo.removeView(it)
            it.destroy()
        }
        web = null
    }

    override fun onDestroy() {
        fecharPainel()
        super.onDestroy()
    }
}

private class RedeLocal(
    private val abrir: (String) -> InputStream,
    private val legado: String?,
    private val conexao: ConexaoPc?,
    private val token: String?,
    private val conta: (WebView, Boolean) -> Unit
) : WebViewClient() {
    private val cliente = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val arquivos = mapOf(
        "/index.html" to "text/html",
        "/estilo.css" to "text/css",
        "/app.js" to "application/javascript",
        "/motor3d.js" to "application/javascript",
        "/personagens.js" to "application/javascript"
    )
    private val rotas = setOf("/api/grafo", "/api/vivo", "/api/nota")

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        if (request.method != "GET" || uri.scheme != "https" || uri.encodedAuthority != ORIGEM) {
            return erro(403, "Forbidden")
        }
        val caminho = uri.encodedPath ?: return erro(404, "Not Found")
        val mime = arquivos[caminho]
        if (mime != null) {
            return try {
                resposta(200, "OK", mime, abrir(caminho.removePrefix("/")))
            } catch (_: IOException) {
                erro(404, "Not Found")
            }
        }
        if (caminho == "/api/app-modo" && !request.isForMainFrame) {
            return resposta(200, "OK", "application/json",
                ByteArrayInputStream("""{"conta":${conexao != null}}""".toByteArray()))
        }
        if (caminho == "/api/app-ajustes" && !request.isForMainFrame && conexao != null) {
            conta(view, false)
            return resposta(200, "OK", "application/json", ByteArrayInputStream("{}".toByteArray()))
        }
        if (caminho !in rotas || request.isForMainFrame) return erro(404, "Not Found")
        if (conexao != null) {
            return try {
                val dados = conexao.pedir(caminho, uri.encodedQuery, token)
                if (dados.status == 401) conta(view, true)
                resposta(dados.status, "Resposta", "application/json", ByteArrayInputStream(dados.corpo))
            } catch (_: IOException) {
                erro(503, "Service Unavailable")
            } catch (_: IllegalArgumentException) {
                erro(400, "Bad Request")
            }
        }
        return consultar(uri, caminho)
    }

    private fun consultar(uri: Uri, caminho: String): WebResourceResponse {
        val token = legado ?: return erro(401, "Unauthorized")
        val ts = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val rota = caminho + (uri.encodedQuery?.let { "?$it" } ?: "")
        return try {
            val base = Request.Builder().url(SERVIDOR + rota).build()
            val alvo = base.url.encodedPath + (base.url.encodedQuery?.let { "?$it" } ?: "")
            val pedido = base.newBuilder()
                .header("X-RN-Auth", "$ts.$nonce.${hmac(token, "GET\n$alvo\n$ts\n$nonce")}")
                .header("Accept-Encoding", "identity")
                .build()
            cliente.newCall(pedido).execute().use { r ->
                if (r.code == 401) return erro(401, "Unauthorized")
                if (r.code !in setOf(200, 404, 500)) return erro(502, "Bad Gateway")
                val fonte = r.body.source()
                if (fonte.request(16L * 1024 * 1024 + 1)) return erro(502, "Bad Gateway")
                val corpo = fonte.readByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(corpo).hex()
                val esperado = hmac(token, "$nonce\n$digest")
                val assinatura = r.header("X-RN-Sig").orEmpty()
                if (!MessageDigest.isEqual(esperado.toByteArray(), assinatura.toByteArray())) {
                    return erro(502, "Bad Gateway")
                }
                resposta(r.code, r.message.ifBlank { "Resposta" }, "application/json", ByteArrayInputStream(corpo))
            }
        } catch (_: IOException) {
            erro(503, "Service Unavailable")
        } catch (_: IllegalArgumentException) {
            erro(400, "Bad Request")
        }
    }

    private fun hmac(token: String, mensagem: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        doFinal(mensagem.toByteArray()).hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun erro(codigo: Int, motivo: String) =
        resposta(codigo, motivo, "application/json", ByteArrayInputStream("{}".toByteArray()))

    private fun resposta(codigo: Int, motivo: String, mime: String, corpo: InputStream) = WebResourceResponse(
        mime, "UTF-8", codigo, motivo,
        mapOf("Content-Security-Policy" to CSP, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        corpo
    )
}
