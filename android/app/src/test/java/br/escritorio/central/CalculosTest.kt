package br.escritorio.central

import br.escritorio.central.data.Eixos
import br.escritorio.central.data.Fmt
import br.escritorio.central.data.Repositorio
import br.escritorio.central.data.TotalUso
import br.escritorio.central.data.UsoReg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amostras SINTÉTICAS, só para teste. Espelham tests/test_backend.py (mesmas fórmulas). */
class CalculosTest {
    @Test fun totalSemDuplaContagem() {
        assertEquals(1050L, TotalUso.total(UsoReg(entrada = 1000, saida = 50, cache_leitura = 800, entrada_inclui_cache = true)))
        assertEquals(1060L, TotalUso.total(UsoReg(entrada = 10, saida = 50, cache_leitura = 800, cache_criacao = 200)))
        assertNull(TotalUso.total(UsoReg()))  // ausência não vira zero
    }

    @Test fun marcasCobremIntervalo() {
        val m = Eixos.marcas(0.0, 137.0)
        assertTrue(m.first() <= 0.0 && m.last() >= 137.0)
        assertEquals(listOf(0.0, 1.0), Eixos.marcas(0.0, 0.0))
    }

    @Test fun unidadeTempoLegivel() {
        assertEquals("ms", Eixos.unidadeTempo(900.0).first)
        assertEquals("s", Eixos.unidadeTempo(30_000.0).first)
        assertEquals("min", Eixos.unidadeTempo(600_000.0).first)
    }

    @Test fun duracaoEAusente() {
        assertEquals(Fmt.ND, Fmt.duracao(null))
        assertTrue(Fmt.duracao(1500.0).endsWith(" s"))
        assertEquals(Fmt.ND, Fmt.pct(null))
        assertEquals("nunca", Fmt.idade(null))
    }

    @Test fun normalizaEndereco() {
        assertEquals("https://exemplo.trycloudflare.com", Repositorio.normalizar(" exemplo.trycloudflare.com/ "))
        assertEquals("http://192.168.0.9:8765", Repositorio.normalizar("http://192.168.0.9:8765/"))
    }
}
