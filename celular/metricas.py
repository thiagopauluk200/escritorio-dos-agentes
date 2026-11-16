#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cálculos puros da Central (sem E/S): percentis, resumos de tempo, orçamento de
tokens e janelas de período. Ficam separados para serem testados isoladamente
(ver tests/test_metricas.py) e documentados em android/docs/FORMULAS.md.

Convenções
- Valor ausente é None, nunca 0. Funções que recebem listas ignoram None.
- Percentil: interpolação linear entre ordens (método "linear"/tipo 7 de
  Hyndman-Fan, o padrão do numpy.percentile). p50 = mediana.
- Amostra pequena: n < AMOSTRA_MINIMA gera aviso explícito no resumo.
"""
import math
from datetime import datetime, timedelta, timezone

AMOSTRA_MINIMA = 20

try:  # Termux e Windows recentes trazem a base de fusos; senão usamos UTC-3 fixo (Brasil sem horário de verão desde 2019)
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None


def fuso(nome):
    """Retorna (tzinfo, descrição). Nunca falha: cai para UTC-03:00 fixo, identificado."""
    if ZoneInfo is not None:
        try:
            return ZoneInfo(nome), nome
        except Exception:
            pass
    return timezone(timedelta(hours=-3)), "UTC-03:00 (fixo; base de fusos indisponível)"


# ------------------------------------------------------------------ estatística
def percentil(valores, p):
    """Percentil p (0..100) por interpolação linear. Lista vazia -> None."""
    xs = sorted(v for v in valores if v is not None)
    if not xs:
        return None
    if len(xs) == 1:
        return float(xs[0])
    pos = (len(xs) - 1) * (p / 100.0)
    lo, hi = math.floor(pos), math.ceil(pos)
    if lo == hi:
        return float(xs[lo])
    return xs[lo] + (xs[hi] - xs[lo]) * (pos - lo)


def resumo(valores):
    """n, média, mediana, p50, p95, mín, máx; aviso se a amostra for pequena."""
    xs = [v for v in valores if v is not None]
    n = len(xs)
    if n == 0:
        return {"n": 0, "media": None, "mediana": None, "p50": None, "p95": None,
                "min": None, "max": None, "aviso": "sem amostras"}
    med = percentil(xs, 50)
    return {"n": n, "media": sum(xs) / n, "mediana": med, "p50": med, "p95": percentil(xs, 95),
            "min": min(xs), "max": max(xs),
            "aviso": ("amostra pequena (n=%d < %d): percentis instáveis" % (n, AMOSTRA_MINIMA)) if n < AMOSTRA_MINIMA else ""}


# --------------------------------------------------------------- tempos de tarefa
FINAIS = ("concluida", "falhou", "cancelada")


def tempos_tarefa(t):
    """
    Durações de uma tentativa. Cada valor é calculado no componente que observou
    os dois eventos (mesmo relógio); o que não foi observado fica None.

    - espera_fila_ms: (entregue_central - criada_central) [relógio da Central]
                      + espera_local_ms [monotônico da ponte: recebida -> processo iniciado].
                      Incerteza: até um trajeto de rede da sincronização (ver rtt da ponte).
    - execucao_ms:    monotônico da ponte (processo iniciado -> processo terminou).
    - primeira_saida_ms: monotônico da ponte (processo iniciado -> primeira linha de saída do executor).
    - total_tarefa_ms: fim_recebido_central - criada_central [relógio da Central]; inclui o atraso
                      de até um ciclo de sincronização da ponte (estimativa observada na Central).
    - confirmacao_ms: criada_central - recebido pelo servidor (resposta ao envio); medido no app.
    """
    ev = {}
    for e in t.get("eventos") or []:
        ev.setdefault(e.get("ev"), e.get("t"))
    m = t.get("medidas") or {}
    criada = ev.get("criada") or t.get("criado")
    entregue = ev.get("entregue_ponte")
    fim_rec = ev.get("fim_recebido")
    espera = None
    if criada is not None and entregue is not None and m.get("espera_local_ms") is not None and entregue >= criada:
        espera = (entregue - criada) + m["espera_local_ms"]
    total = (fim_rec - criada) if (criada is not None and fim_rec is not None and fim_rec >= criada) else None
    return {"espera_fila_ms": espera, "execucao_ms": m.get("execucao_ms"),
            "primeira_saida_ms": m.get("primeira_saida_ms"), "total_tarefa_ms": total}


def taxa_sucesso(tarefas):
    """
    sucesso_percentual = 100 x concluídas_com_sucesso / finalizadas_elegíveis
    Elegíveis: status 'concluida' ou 'falhou' (inclui tempo esgotado, que termina como falha).
    Canceladas ficam fora do denominador e são contadas à parte. Pendentes (fila/rodando) também.
    """
    c = {"concluida": 0, "falhou": 0, "cancelada": 0, "pendente": 0, "tempo_esgotado": 0}
    for t in tarefas:
        s = t.get("status")
        if s in ("fila", "rodando"):
            c["pendente"] += 1
        elif s in c:
            c[s] += 1
        if s == "falhou" and t.get("motivo") == "tempo_esgotado":
            c["tempo_esgotado"] += 1
    eleg = c["concluida"] + c["falhou"]
    return {"sucesso_percentual": (100.0 * c["concluida"] / eleg) if eleg else None,
            "denominador": eleg, "contagens": c,
            "definicao": "concluídas com código 0 / (concluídas + falhas); canceladas e pendentes fora do denominador"}


# ------------------------------------------------------------------ tokens
def total_tokens(uso):
    """
    Tokens processados de UMA chamada, sem dupla contagem:
    - OpenAI/Codex: input_tokens já inclui cached_input_tokens -> total = entrada + saida.
    - Anthropic/Claude: input_tokens NÃO inclui cache -> total = entrada + cache_criacao + cache_leitura + saida.
    O campo 'entrada_inclui_cache' (gravado pela ponte) decide qual regra vale. Retorna None se nada foi informado.
    """
    if not uso:
        return None
    campos = [uso.get(k) for k in ("entrada", "saida", "cache_leitura", "cache_criacao")]
    if all(v is None for v in campos):
        return None
    e, s = uso.get("entrada") or 0, uso.get("saida") or 0
    if uso.get("entrada_inclui_cache"):
        return e + s
    return e + s + (uso.get("cache_leitura") or 0) + (uso.get("cache_criacao") or 0)


def inicio_periodo(periodo, agora_ms, tz):
    """Início do período corrente (ms UTC) no fuso tz. periodo: dia | semana (segunda) | mes | total."""
    if periodo == "total":
        return 0
    dt = datetime.fromtimestamp(agora_ms / 1000, tz)
    base = dt.replace(hour=0, minute=0, second=0, microsecond=0)
    if periodo == "semana":
        base = base - timedelta(days=base.weekday())
    elif periodo == "mes":
        base = base.replace(day=1)
    elif periodo != "dia":
        raise ValueError("período inválido")
    return int(base.timestamp() * 1000)


def proxima_renovacao(periodo, agora_ms, tz):
    if periodo == "total":
        return None
    ini = datetime.fromtimestamp(inicio_periodo(periodo, agora_ms, tz) / 1000, tz)
    if periodo == "dia":
        prox = ini + timedelta(days=1)
    elif periodo == "semana":
        prox = ini + timedelta(days=7)
    else:
        prox = (ini.replace(day=28) + timedelta(days=4)).replace(day=1)
    # recria no fuso para respeitar mudanças de deslocamento
    prox = prox.replace(tzinfo=None).replace(tzinfo=tz)
    return int(prox.timestamp() * 1000)


def orcamento(consumo, limite):
    """
    Para C (consumo) e L (limite) do MESMO escopo, unidade e período:
      percentual_consumido = 100 x C / L
      saldo_tokens         = max(0, L - C)
      percentual_disponivel= max(0, 100 x (L - C) / L)
      excesso_tokens       = max(0, C - L)
    Limite desconhecido (None) -> sem percentual. Limite 0 -> estado 'limite_zero' (não divide).
    Consumo desconhecido (None) -> 'sem_dados' (nunca vira 0%).
    """
    r = {"consumo": consumo, "limite": limite, "percentual_consumido": None, "percentual_disponivel": None,
         "saldo_tokens": None, "excesso_tokens": None}
    if limite is None:
        r["estado"] = "limite_desconhecido"
        return r
    if consumo is None:
        r["estado"] = "sem_dados"
        return r
    if limite == 0:
        r["estado"] = "limite_zero"
        r["excesso_tokens"] = consumo
        return r
    if limite < 0:
        r["estado"] = "limite_invalido"
        return r
    r["percentual_consumido"] = 100.0 * consumo / limite
    r["saldo_tokens"] = max(0, limite - consumo)
    r["percentual_disponivel"] = max(0.0, 100.0 * (limite - consumo) / limite)
    r["excesso_tokens"] = max(0, consumo - limite)
    r["estado"] = "excedido" if consumo > limite else ("atingido" if consumo == limite else ("sem_uso" if consumo == 0 else "ok"))
    return r


def alertas_cruzados(pct, limiares):
    """Limiar mais alto já atingido (ou None). pct None -> None."""
    if pct is None:
        return None
    ok = [l for l in sorted(limiares or []) if pct >= l]
    return ok[-1] if ok else None


def projecao_esgotamento(registros, limite, inicio_ms, agora_ms, minimo_amostras=5, minimo_horas=6):
    """
    Estimativa linear simples: taxa = consumo no período / horas decorridas; horas_restantes = saldo / taxa.
    Só retorna valor com >= minimo_amostras chamadas e >= minimo_horas de período decorrido.
    registros: [(t_ms, tokens)]. Sempre marcada como estimativa.
    """
    if not limite or limite <= 0:
        return None
    pts = [(t, v) for t, v in registros if v is not None and t >= inicio_ms]
    horas = (agora_ms - inicio_ms) / 3_600_000
    if len(pts) < minimo_amostras or horas < minimo_horas:
        return None
    consumo = sum(v for _, v in pts)
    taxa = consumo / horas
    if taxa <= 0:
        return None
    saldo = limite - consumo
    return {"estimativa": True, "metodo": "linear: consumo do período / horas decorridas",
            "amostras": len(pts), "intervalo_horas": round(horas, 2), "taxa_tokens_h": taxa,
            "horas_ate_esgotar": max(0.0, saldo / taxa), "esgota_em_ms": int(agora_ms + max(0.0, saldo / taxa) * 3_600_000)}


# ------------------------------------------------------------------ tráfego em tempo real
def fluxo_trafego(registros, agora_ms, janela_s=60, recentes_s=12, max_recentes=40):
    """Resumo do tráfego HTTP medido na Central.

    - serie: bytes por segundo nos últimos `janela_s` segundos (entrada e saída), mais antigo primeiro.
    - taxa: média de bytes/s nos últimos 10 s, por sentido e por canal.
    - req_min: requisições com corpo no último minuto.
    - recentes: transferências dos últimos `recentes_s` s (para animar pulsos no app).
    Só usa o que foi medido; sem dados, tudo zero e `recentes` vazio.
    """
    inicio = agora_ms - janela_s * 1000
    ent = [0] * janela_s
    sai = [0] * janela_s
    canais = {}
    taxa = {"entrada": 0.0, "saida": 0.0}
    req = 0
    recentes = []
    for r in registros:
        t = r.get("t", 0)
        if t < inicio or t > agora_ms:
            continue
        req += 1
        i = min(janela_s - 1, int((t - inicio) // 1000))
        b = r.get("bytes", 0)
        (ent if r.get("sentido") == "entrada" else sai)[i] += b
        if t >= agora_ms - 10_000:
            taxa["entrada" if r.get("sentido") == "entrada" else "saida"] += b / 10.0
            c = canais.setdefault(r.get("canal", "app"), {"entrada": 0.0, "saida": 0.0})
            c["entrada" if r.get("sentido") == "entrada" else "saida"] += b / 10.0
        if t >= agora_ms - recentes_s * 1000:
            recentes.append({"t": t, "canal": r.get("canal"), "sentido": r.get("sentido"), "bytes": b})
    total = sum(ent) + sum(sai)
    return {"janela_s": janela_s, "serie_entrada": ent, "serie_saida": sai, "taxa": taxa,
            "canais": canais, "req_min": round(req * 60.0 / janela_s), "total_bytes": total,
            "recentes": recentes[-max_recentes:]}
