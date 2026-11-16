package br.escritorio.central.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Cálculos de exibição. As métricas oficiais vêm da Central (celular/metricas.py); aqui só há o que a
 * interface precisa para desenhar, com a mesma definição quando há sobreposição (ver docs/FORMULAS.md).
 */
object TotalUso {
    /** Igual a metricas.total_tokens: OpenAI inclui cache na entrada; Anthropic soma cache à parte. Nada informado -> null. */
    fun total(u: UsoReg): Long? {
        if (u.entrada == null && u.saida == null && u.cache_leitura == null && u.cache_criacao == null) return null
        val base = (u.entrada ?: 0) + (u.saida ?: 0)
        return if (u.entrada_inclui_cache) base else base + (u.cache_leitura ?: 0) + (u.cache_criacao ?: 0)
    }
}

object Eixos {
    /** Marcas "redondas" (1, 2, 5 × 10^n) cobrindo [min, max]. */
    fun marcas(min: Double, max: Double, alvo: Int = 5): List<Double> {
        if (max <= min) return listOf(min, min + 1)
        val bruto = (max - min) / alvo
        val mag = 10.0.pow(floor(log10(bruto)))
        val passo = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= bruto }
        val ini = floor(min / passo) * passo
        val fim = ceil(max / passo) * passo
        val n = ((fim - ini) / passo).toInt()
        return (0..n).map { ini + it * passo }
    }

    /** Escolhe a unidade de tempo legível para uma série em ms (sem alterar os valores reais). */
    fun unidadeTempo(maxMs: Double): Pair<String, Double> = when {
        maxMs < 2_000 -> "ms" to 1.0
        maxMs < 120_000 -> "s" to 1_000.0
        maxMs < 7_200_000 -> "min" to 60_000.0
        else -> "h" to 3_600_000.0
    }
}
