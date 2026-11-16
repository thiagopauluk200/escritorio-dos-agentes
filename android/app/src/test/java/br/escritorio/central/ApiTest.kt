package br.escritorio.central

import br.escritorio.central.data.Api
import br.escritorio.central.data.ApiErro
import br.escritorio.central.data.InfoResp
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Contrato do cliente HTTP com servidor falso (respostas SINTÉTICAS). */
class ApiTest {
    private lateinit var srv: MockWebServer

    @Before fun subir() { srv = MockWebServer(); srv.start() }
    @After fun descer() { srv.close() }

    private fun base() = srv.url("/").toString().trimEnd('/')

    @Test fun naoAutorizadoViraErroTipado() = runTest {
        srv.enqueue(MockResponse.Builder().code(401).body("{\"erro\":\"Sessão inválida\",\"codigo\":\"sessao\"}").build())
        val api = Api { listOf(base()) to "tok-sintetico" }
        try { api.get("/api/v1/estado", InfoResp.serializer()); fail() } catch (e: ApiErro) {
            assertEquals(ApiErro.Tipo.NAO_AUTORIZADO, e.tipo); assertEquals("sessao", e.codigo)
        }
        assertEquals("Bearer tok-sintetico", srv.takeRequest().headers["Authorization"])
    }

    @Test fun getTrocaParaAlternativoQuandoPrincipalCai() = runTest {
        srv.enqueue(MockResponse.Builder().body("{\"versao_api\":1}").build())
        val api = Api { listOf("http://127.0.0.1:9", base()) to null }  // porta 9: recusa conexão
        val r = api.get("/api/v1/info", InfoResp.serializer())
        assertEquals(1, r.valor?.versao_api)
        assertEquals(base(), api.enderecoAtivo)
    }

    @Test fun conflitoDeOrcamento() = runTest {
        srv.enqueue(MockResponse.Builder().code(409).body("{\"erro\":\"Orçamento\",\"codigo\":\"orcamento_esgotado\"}").build())
        val api = Api { listOf(base()) to "t" }
        try { api.post("/api/v1/tarefas", Api.corpo("idem" to "x"), InfoResp.serializer()); fail() } catch (e: ApiErro) {
            assertEquals(ApiErro.Tipo.CONFLITO, e.tipo); assertEquals("orcamento_esgotado", e.codigo)
        }
        assertEquals("escritorio-android", srv.takeRequest().headers["X-Requested-With"])
    }

    @Test fun respostaInesperadaNaoQuebra() = runTest {
        srv.enqueue(MockResponse.Builder().body("<html>proxy</html>").build())
        val api = Api { listOf(base()) to null }
        try { api.get("/api/v1/info", InfoResp.serializer()); fail() } catch (e: ApiErro) { assertEquals(ApiErro.Tipo.RESPOSTA_INVALIDA, e.tipo) }
    }
}
