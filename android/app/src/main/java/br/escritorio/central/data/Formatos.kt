package br.escritorio.central.data

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatação para exibição. Transporte em UTC (ms); exibição no fuso local com o fuso indicado. */
object Fmt {
    private val br = Locale.forLanguageTag("pt-BR")
    private val zona get() = ZoneId.systemDefault()
    private val dataHora = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", br)
    private val dataHoraZona = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss xxx", br)

    const val ND = "não disponível"

    fun hora(ms: Long?): String = ms?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zona).format(dataHora) } ?: ND
    fun horaComFuso(ms: Long?): String = ms?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zona).format(dataHoraZona) + " (" + zona.id + ")" } ?: ND

    /** Idade relativa a 'agora'. Não usa relógios de outros aparelhos para intervalos curtos. */
    fun idade(ms: Long?, agora: Long = System.currentTimeMillis()): String {
        if (ms == null || ms <= 0) return "nunca"
        val s = (agora - ms) / 1000
        return when {
            s < 0 -> "agora (relógios divergem)"
            s < 5 -> "agora"
            s < 60 -> "há ${s} s"
            s < 3600 -> "há ${s / 60} min"
            s < 86400 -> "há ${s / 3600} h"
            else -> "há ${s / 86400} d"
        }
    }

    /** Duração com unidade legível; precisão compatível com a fonte (ms quando < 1 s). */
    fun duracao(ms: Double?): String {
        if (ms == null) return ND
        return when {
            ms < 1000 -> String.format(br, "%.0f ms", ms)
            ms < 60_000 -> String.format(br, "%.1f s", ms / 1000)
            ms < 3_600_000 -> String.format(br, "%.1f min", ms / 60_000)
            else -> String.format(br, "%.1f h", ms / 3_600_000)
        }
    }

    fun inteiro(v: Long?): String = v?.let { NumberFormat.getIntegerInstance(br).format(it) } ?: ND
    fun pct(v: Double?, casas: Int = 1): String = v?.let { String.format(br, "%.${casas}f%%", it) } ?: ND

    fun status(s: String?): String = when (s) {
        "fila" -> "na fila"; "rodando" -> "executando"; "concluida" -> "concluída"; "falhou" -> "falhou"
        "cancelada" -> "cancelada"; "trabalhando" -> "trabalhando"; "ocioso" -> "ocioso"
        "online" -> "online"; "degradado" -> "degradado"; "sem_dados_recentes" -> "sem dados recentes"
        "offline" -> "offline"; "nunca_visto" -> "nunca visto"; "conectando" -> "conectando"
        "declarado" -> "declarado pela ponte"; "ausente" -> "ausente"; "ativo" -> "ativo"; "inativo" -> "inativo"
        "sem_dados" -> "sem dados"; "pendente" -> "pendente"; "entregue" -> "entregue à ponte"; "ok" -> "gravada no vault"
        "erro" -> "erro"; "conflito" -> "conflito de versão"
        else -> s ?: ND
    }

    fun motivo(m: String?): String? = when (m) {
        null -> null
        "tempo_esgotado" -> "tempo máximo atingido"; "ponte_perdida" -> "a ponte responsável parou de responder"
        "ponte_reiniciada" -> "a ponte foi reiniciada durante a execução"; "codigo_saida" -> "o executor terminou com erro"
        "executor_ausente" -> "executor não encontrado no PC"; "cancelada" -> "cancelada pelo usuário"
        else -> m
    }

    fun periodo(p: String) = when (p) { "dia" -> "dia"; "semana" -> "semana"; "mes" -> "mês"; "total" -> "acumulado"; else -> p }
}
