#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Central do Escritorio dos agentes - roda no servidor (Termux no celular).

- Serve o painel (app.html) com login por senha.
- Guarda a equipe de agentes (nome, motor, funcao) e a fila de tarefas.
- Recebe da "ponte" (ponte.py, no PC) o estado das sessoes, a rede de notas
  e os resultados das tarefas; entrega a ponte as tarefas e edicoes pendentes.

So biblioteca padrao do Python. Dados em ./dados (JSON).
"""
import argparse
import copy
import hashlib
import hmac
import io
import json
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import armazenamento as A  # noqa: E402
import metricas as M  # noqa: E402

VERSAO_BACKEND = "2.0.0"
VERSAO_API = 1
INICIO_MONO = time.monotonic()
INICIO_MS = int(time.time() * 1000)
DADOS = os.path.join(AQUI, "dados")
NOTAS = os.path.join(DADOS, "notas")
CONFIG = os.path.join(DADOS, "config.json")
SESSAO_S = 14 * 24 * 3600
MAX_CORPO = 12 * 1024 * 1024
PONTE_ONLINE_S = 20
OPENBOT_ONLINE_S = 90
OPENBOT_REPETICAO_S = 120  # janela em que a mesma mensagem do OpenBot é tratada como reenvio
trava = threading.RLock()
grafico_trava = threading.Lock()


# ------------------------------------------------------------------ armazenamento
# O COMO gravar (atômico, uma gravação por vez, ordem por série) mora em armazenamento.py.
# Aqui ficam só os atalhos que já existiam, presos à pasta de dados da Central (DADOS).
trava_disco = A.trava_disco
_serie = A.serie


def ler(nome, padrao):
    return A.ler(DADOS, nome, padrao)


def gravar(nome, valor, serie=None):
    """Grava em DADOS. `serie` = quando o retrato foi tirado (ver armazenamento.gravar)."""
    return A.gravar(DADOS, nome, valor, serie)


def hash_senha(senha, sal):
    return hashlib.pbkdf2_hmac("sha256", senha.encode(), bytes.fromhex(sal), 200_000).hex()


def criar_config(senha=None):
    os.makedirs(NOTAS, exist_ok=True)
    try:
        os.chmod(DADOS, 0o700)
    except OSError:
        pass
    senha = senha or secrets.token_urlsafe(12)
    sal = secrets.token_hex(16)
    cfg = {"sal": sal, "senha_hash": hash_senha(senha, sal),
           "segredo": secrets.token_hex(32), "chave_ponte": secrets.token_urlsafe(32),
           "chave_openbot": secrets.token_urlsafe(32)}
    gravar("config.json", cfg)
    try:
        os.chmod(CONFIG, 0o600)
    except OSError:
        pass
    return cfg, senha


class Estado:
    cfg = {}
    agentes = []
    tarefas = []
    ops = []            # edicoes de notas pendentes / recentes
    ponte = {"visto": 0, "executores": {}, "pastas": [], "estado": None}
    openbot_visto = 0
    falhas_login = 0
    bloqueado_ate = 0
    # --- v2 (API do app Android) ---
    dispositivos = []   # [{id, nome, token_hash, criado, expira, revogado, visto, escopos, projetos}]
    orcamentos = {}     # agente_id -> {limite_tokens, periodo, fuso, alertas, bloquear}
    uso = []            # registro de uso de tokens (deduplicado por tarefa+chamada)
    logs = []           # logs estruturados (já sem segredos)
    latencias = []      # amostras de latência requisição/resposta
    notas_eventos = []  # alterações de notas OBSERVADAS a partir da instrumentação
    pontes = {}         # ponte_id -> {visto, versao, rtt_ms, host}
    escopo_pastas = []  # [{nome, escrita, existe, sandbox_codex, ferramentas_claude}] declarado pela ponte
    trafego = []        # transferências HTTP medidas em memória: {t, canal, sentido, bytes, rota}
    openbot_agentes = None  # estado dos agentes do OpenBot lido pela ponte (só metadados; em memória)
    chaves = {}         # "tipo:chave" -> {t, id}: chaves de repetição já usadas (validade por tempo, não por posição)
    revisao = 0
    sujo = set()


E = Estado()


def carregar():
    E.cfg = ler("config.json", {})
    if E.cfg and not E.cfg.get("chave_openbot"):
        E.cfg["chave_openbot"] = secrets.token_urlsafe(32)
        gravar("config.json", E.cfg)
    E.agentes = ler("agentes.json", [])
    E.tarefas = ler("tarefas.json", [])
    E.ops = ler("ops.json", [])
    salvo = ler("ponte.json", None)
    if salvo:
        E.ponte.update(salvo)
    E.dispositivos = ler("dispositivos.json", [])
    E.orcamentos = ler("orcamentos.json", {})
    E.uso = ler("uso.json", [])
    E.logs = ler("logs.json", [])
    E.latencias = ler("latencias.json", [])
    E.notas_eventos = ler("notas_eventos.json", [])
    E.chaves = ler("chaves.json", {})
    E.escopo_pastas = (salvo or {}).get("escopo_pastas", [])
    # tarefa que ficou "rodando" quando o servidor caiu volta a ser decidida pela ponte
    os.makedirs(NOTAS, exist_ok=True)


_cfg_mtime = [0.0]


def recarregar_cfg():
    """Relê config.json se mudou (ex.: --nova-senha com a Central ligada), sem precisar reiniciar."""
    try:
        m = os.path.getmtime(CONFIG)
    except OSError:
        return
    if m != _cfg_mtime[0]:
        novo = ler("config.json", None)
        if novo:
            with trava:
                E.cfg = novo
                _cfg_mtime[0] = m


def agora_ms():
    return int(time.time() * 1000)


def novo_id(prefixo):
    return prefixo + secrets.token_hex(5)


def arq_nota(nid):
    return os.path.join(NOTAS, hashlib.sha1(nid.encode()).hexdigest() + ".json")


# ------------------------------------------------------------------------ sessao
def assinar(exp):
    """Assinatura do cookie de sessão. Inclui a "geração" das sessões web: ao sair, a geração avança e todo
    cookie emitido antes deixa de valer. Geração 0 mantém o formato antigo (ninguém é deslogado na atualização)."""
    ger = int(E.cfg.get("geracao_sessao") or 0)
    msg = str(exp) if ger == 0 else "%s.%d" % (exp, ger)
    return hmac.new(bytes.fromhex(E.cfg["segredo"]), msg.encode(), hashlib.sha256).hexdigest()


def cookie_valido(cabecalho):
    for parte in (cabecalho or "").split(";"):
        k, _, v = parte.strip().partition("=")
        if k == "sessao" and "." in v:
            exp, _, sig = v.partition(".")
            if exp.isdigit() and int(exp) > time.time() and hmac.compare_digest(sig, assinar(exp)):
                return True
    return False


LIMPO = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")


def txt(v, n):
    return LIMPO.sub("", str(v or ""))[:n].strip()


def openbot_catalogo():
    with trava:
        E.openbot_visto = agora_ms()
        return {"agentes": E.agentes, "projetos": E.ponte.get("pastas", [])}


def openbot_tarefa(tid):
    with trava:
        E.openbot_visto = agora_ms()
        return next((dict(t) for t in E.tarefas if t["id"] == tid), None)


def openbot_enviar(d):
    with trava:
        E.openbot_visto = agora_ms()
        texto = txt(d.get("texto"), 6000)
        agente_id = txt(d.get("agente"), 60)
        projeto = txt(d.get("projeto"), 60)
        agente = next((a for a in E.agentes if a["id"] == agente_id), None)
        if not texto or len(str(d.get("texto") or "")) > 6000 or not agente:
            return 400, {"erro": "mensagem ou agente inválido"}
        if projeto not in E.ponte.get("pastas", []):
            return 400, {"erro": "projeto não está liberado pela ponte"}
        if txt(agente.get("pasta"), 60) != projeto:
            return 400, {"erro": "o agente não está associado ao projeto escolhido"}
        idem = txt(d.get("idem"), 80) or None
        # Rede de segurança para quando o OpenBot não manda chave: a MESMA mensagem para o MESMO agente e
        # projeto, repetida em até 2 minutos, é tratada como reenvio (o modelo costuma repetir a chamada).
        # Depois desse prazo o mesmo texto vira uma tarefa nova, porque pedir de novo é legítimo.
        marca = hashlib.sha256(("%s\n%s\n%s" % (agente_id, projeto, texto)).encode("utf-8")).hexdigest()[:32]
        if not idem:
            limite = agora_ms() - OPENBOT_REPETICAO_S * 1000
            igual = next((t for t in reversed(E.tarefas) if t.get("marca_openbot") == marca and t["criado"] >= limite), None)
            if igual:
                registrar("info", "central", "openbot_reenvio_ignorado", tarefa=igual["id"], agente=agente_id)
                return 202, {"id": igual["id"], "status": igual["status"], "repetida": True}
        cod, tarefa = criar_tarefa(agente_id, "Projeto: %s\n\n%s" % (projeto, texto), "openbot", idem=idem)
        if cod >= 400:
            return cod, tarefa
        if tarefa.get("repetida"):
            return 202, {"id": tarefa["id"], "status": tarefa["status"], "repetida": True}
        real = next((t for t in reversed(E.tarefas) if t["id"] == tarefa["id"]), None)
        if real is not None:
            real["marca_openbot"] = marca
            E.sujo.add("tarefas.json")
        dia = time.strftime("%Y-%m-%d")
        titulo = "OpenBot - " + txt(texto.splitlines()[0], 70)
        conteudo = ("---\ntipo: tarefa-openbot\nstatus: pendente\norigem: openbot\ndata: %s\n"
                    "confianca: media\n---\n\n# Mensagem recebida pelo OpenBot\n\n"
                    "## Roteamento\n- Projeto: %s\n- Agente: %s\n\n## Pedido\n\n%s\n") % (dia, projeto, agente["nome"], texto)
        op = {"id": novo_id("op"), "status": "pendente", "criado": agora_ms(),
              "tipo": "criar", "titulo": titulo, "conteudo": conteudo, "tarefa": tarefa["id"]}
        E.ops.append(op)
        E.ops = E.ops[-40:]
        gravar("ops.json", E.ops)
        return 202, {"id": tarefa["id"], "status": tarefa["status"]}


# ================================================================ v2: núcleo
SEGREDO_RE = re.compile(r"(?i)((?:authorization\s*[=:]\s*)?bearer\s+)[A-Za-z0-9._~+/=-]{8,}|((?:senha|password|token|chave|secret|cookie|authorization|api[_-]?key)\s*[=:]\s*)\S+")
CHAVES_PROIBIDAS = re.compile(r"(?i)senha|password|token|chave|secret|cookie|authorization|api.?key")
LOG_MAX, LOG_DIAS = 4000, 7
LAT_MAX, USO_MAX, NOTAS_EV_MAX = 1500, 5000, 5000
LEASE_S, PONTE_PERDIDA_S = 45, 180
DISPOSITIVO_S = 30 * 24 * 3600
ESCOPOS_PADRAO = ["ler", "tarefas", "notas", "agentes", "orcamentos", "dispositivos"]


def redigir(s):
    return SEGREDO_RE.sub(lambda m: (m.group(1) or m.group(2) or "") + "<redigido>", str(s or ""))


def sujar(*nomes):
    E.sujo.update(nomes)
    E.revisao += 1


def registrar(nivel, comp, ev, msg="", **campos):
    """Log estruturado. Nunca grava segredos nem textos integrais de prompts/notas."""
    reg = {"t": agora_ms(), "nivel": nivel, "comp": comp, "ev": ev, "msg": redigir(msg)[:300]}
    for k, v in campos.items():
        if v is None or CHAVES_PROIBIDAS.search(k):
            continue
        reg[k] = redigir(v)[:120] if isinstance(v, str) else v
    with trava:
        E.logs.append(reg)
        corte = agora_ms() - LOG_DIAS * 86_400_000
        if len(E.logs) > LOG_MAX or (E.logs and E.logs[0]["t"] < corte):
            E.logs = [x for x in E.logs if x["t"] >= corte][-LOG_MAX:]
        E.sujo.add("logs.json")


def gravador():
    """Grava em lote o que é volumoso (logs, uso, latências) para poupar a memória flash do J7."""
    while True:
        time.sleep(4)
        with trava:
            nomes, E.sujo = set(E.sujo), set()
            dados = {n: copy.deepcopy(getattr(E, n[:-5])) for n in nomes
                     if n in ("logs.json", "uso.json", "latencias.json", "notas_eventos.json",
                              "dispositivos.json", "orcamentos.json", "tarefas.json", "ops.json", "chaves.json")}
            serie = next(_serie)  # tirada junto com a cópia, ainda sob a trava
        for n, v in dados.items():
            try:
                if n == "dispositivos.json":  # outro processo (CLI) pode ter revogado/criado aparelhos
                    v = mesclar_dispositivos(v, ler("dispositivos.json", []))
                    with trava:
                        E.dispositivos = mesclar_dispositivos(E.dispositivos, v)
                gravar(n, v, serie)
            except OSError:
                pass


def evento(t, ev, comp, **dados):
    """Evento da trilha da tarefa com horário da Central (UTC ms)."""
    e = {"t": agora_ms(), "ev": ev, "comp": comp}
    e.update({k: v for k, v in dados.items() if v is not None})
    t.setdefault("eventos", []).append(e)
    if len(t["eventos"]) > 60:
        t["eventos"] = t["eventos"][:5] + t["eventos"][-50:]
    registrar("info", comp, ev, tarefa=t["id"], agente=t.get("agente"), trace=t.get("trace_id"),
              tentativa=t.get("tentativa", 1))


TRAFEGO_MAX = 600


def canal_da_rota(rota):
    """Classifica a rota HTTP pelo lado da conexão (quem fala com a Central)."""
    if rota.startswith("/ponte/"):
        return "ponte"
    if rota.startswith("/openbot"):
        return "openbot"
    return "app"


def medir_trafego(rota, sentido, nbytes):
    """Registra uma transferência real (bytes do corpo HTTP). Só em memória; não guarda conteúdo."""
    if nbytes <= 0:
        return
    with trava:
        E.trafego.append({"t": agora_ms(), "canal": canal_da_rota(rota), "sentido": sentido,
                          "bytes": int(nbytes), "rota": rota[:40]})
        if len(E.trafego) > TRAFEGO_MAX:
            del E.trafego[:len(E.trafego) - TRAFEGO_MAX]


def nome_agente(aid):
    a = next((x for x in E.agentes if x["id"] == aid), None)
    return a.get("nome") if a else None


def consumo_agente(agente_id, inicio_ms, fim_ms=None, projeto=None, modelo=None):
    """Soma de tokens (M.total_tokens) do agente no intervalo. None se não há registro algum."""
    total, achou = 0, False
    for u in E.uso:
        if u.get("agente") != agente_id or u["t"] < inicio_ms or (fim_ms and u["t"] >= fim_ms):
            continue
        if projeto and u.get("projeto") != projeto:
            continue
        if modelo and u.get("modelo") != modelo:
            continue
        v = M.total_tokens(u)
        if v is not None:
            total += v
            achou = True
    return total if achou else None


def media_tokens_tarefa(agente_id, n=20):
    por = {}
    for u in E.uso:
        if u.get("agente") == agente_id:
            v = M.total_tokens(u)
            if v is not None:
                por[u["tarefa"]] = por.get(u["tarefa"], 0) + v
    vals = list(por.values())[-n:]
    return (sum(vals) / len(vals)) if vals else 0


CHAVES_DIAS, CHAVES_MAX = 7, 3000


def lembrar_chave(chave, tipo, ident):
    """Registra uma chave de repetição usada. Chamar com a trava."""
    if not chave:
        return
    agora = agora_ms()
    E.chaves[tipo + ":" + chave] = {"t": agora, "id": ident}
    if len(E.chaves) > CHAVES_MAX:
        corte = agora - CHAVES_DIAS * 86_400_000
        E.chaves = dict(sorted(((k, v) for k, v in E.chaves.items() if v["t"] >= corte), key=lambda kv: kv[1]["t"])[-CHAVES_MAX:])
    E.sujo.add("chaves.json")


def chave_usada(chave, tipo):
    """Id criado com esta chave nos últimos CHAVES_DIAS dias, ou None. Vale mesmo que o item já tenha saído
    das listas curtas (300 tarefas, 40 notas). Chamar com a trava."""
    r = E.chaves.get(tipo + ":" + chave) if chave else None
    return r["id"] if r and r["t"] >= agora_ms() - CHAVES_DIAS * 86_400_000 else None


def criar_tarefa(agente_id, texto, origem, idem=None, tentativa_de=None, registrar_nota=True, dispositivo=None):
    """Única porta de entrada de tarefas (web, app, OpenBot, repetição). Chamar com a trava."""
    if idem:
        for t in reversed(E.tarefas):
            if t.get("idem") == idem:
                r = dict(t)
                r["repetida"] = True
                return 200, r
        antiga = chave_usada(idem, "tarefa")
        if antiga:  # já foi criada, mas saiu da lista de tarefas recentes
            return 200, {"id": antiga, "agente": agente_id, "status": "arquivada", "repetida": True}
    agente = next((a for a in E.agentes if a["id"] == agente_id), None)
    if not texto or not agente:
        return 400, {"erro": "Escreva a tarefa e escolha um agente.", "codigo": "entrada_invalida"}
    if sum(1 for t in E.tarefas if t["status"] in ("fila", "rodando")) >= 30:
        return 400, {"erro": "Fila cheia (30 tarefas).", "codigo": "fila_cheia"}
    orc = E.orcamentos.get(agente_id)
    if orc and orc.get("bloquear") and orc.get("limite_tokens") is not None:
        tz, _ = M.fuso(orc.get("fuso") or "America/Sao_Paulo")
        ini = M.inicio_periodo(orc.get("periodo") or "dia", agora_ms(), tz)
        c = consumo_agente(agente_id, ini) or 0
        pend = sum(1 for t in E.tarefas if t["agente"] == agente_id and t["status"] in ("fila", "rodando"))
        reserva = pend * media_tokens_tarefa(agente_id)
        if c + reserva >= orc["limite_tokens"]:
            registrar("aviso", "central", "bloqueio_orcamento", agente=agente_id, consumo=c, reserva=int(reserva))
            return 409, {"erro": "Orçamento local de tokens do agente atingido neste período (consumo %d + reserva estimada %d ≥ limite %d)."
                         % (c, reserva, orc["limite_tokens"]), "codigo": "orcamento_esgotado"}
    base = next((t for t in E.tarefas if t["id"] == tentativa_de), None) if tentativa_de else None
    tarefa = {"id": novo_id("t"), "agente": agente_id, "texto": texto, "status": "fila", "criado": agora_ms(),
              "origem": origem, "trace_id": (base or {}).get("trace_id") or ("tr" + secrets.token_hex(6)),
              "tentativa": ((base or {}).get("tentativa", 1) + 1) if base else 1,
              "projeto": agente.get("pasta") or "", "registrar_nota": bool(registrar_nota)}
    if idem:
        tarefa["idem"] = idem
    if base:
        tarefa["tentativa_de"] = base["id"]
    if dispositivo:
        tarefa["dispositivo"] = dispositivo
    evento(tarefa, "criada", "central", origem=origem)
    lembrar_chave(idem, "tarefa", tarefa["id"])
    E.tarefas.append(tarefa)
    E.tarefas = E.tarefas[-300:]
    gravar("tarefas.json", E.tarefas)
    E.revisao += 1
    return 201, dict(tarefa)


def status_componente(idade_ms, online_s, degradado_s, sem_dados_s, nunca=False):
    if nunca:
        return "nunca_visto"
    if idade_ms < online_s * 1000:
        return "online"
    if idade_ms < degradado_s * 1000:
        return "degradado"
    if idade_ms < sem_dados_s * 1000:
        return "sem_dados_recentes"
    return "offline"


def componentes():
    t = agora_ms()
    visto = E.ponte.get("visto", 0)
    idade = t - visto
    ativas = {k: v for k, v in E.pontes.items() if t - v["visto"] < 60_000}
    st_ponte = status_componente(idade, PONTE_ONLINE_S, 60, 600, nunca=not visto)
    avisos = []
    if len(ativas) > 1:
        st_ponte = "degradado"
        avisos.append("%d pontes ativas ao mesmo tempo: tarefas e notas podem ser disputadas." % len(ativas))
    est = E.ponte.get("estado") or {}
    execs = E.ponte.get("executores") or {}
    ultimo_ok = {}
    for x in E.tarefas:
        if x["status"] == "concluida" and x.get("fim"):
            motor = (next((a for a in E.agentes if a["id"] == x["agente"]), {}) or {}).get("motor")
            if motor:
                ultimo_ok[motor] = max(ultimo_ok.get(motor, 0), x.get("fim_central") or x["fim"])
    mcp = est.get("mcp") or {}
    return {
        "central": {"estado": "online", "visto": t, "idade_ms": 0, "versao": VERSAO_BACKEND,
                    "desde": INICIO_MS},
        "ponte": {"estado": st_ponte, "visto": visto or None, "idade_ms": idade if visto else None,
                  "ativas": [{"id": k, "visto": v["visto"], "versao": v.get("versao"), "rtt_ms": v.get("rtt_ms")}
                             for k, v in ativas.items()], "avisos": avisos},
        "openbot": {"estado": status_componente(t - E.openbot_visto, OPENBOT_ONLINE_S, 600, 3600,
                                                nunca=not E.openbot_visto),
                    "visto": E.openbot_visto or None, "idade_ms": (t - E.openbot_visto) if E.openbot_visto else None,
                    "nota": "O OpenBot só contata a Central quando usa as ferramentas; ausência não prova falha."},
        "executores": {m: {"declarado": bool(execs.get(m)), "ultimo_sucesso": ultimo_ok.get(m),
                           "estado": ("declarado" if execs.get(m) else "ausente") if st_ponte in ("online", "degradado") else "sem_dados_recentes"}
                       for m in ("codex", "claude")},
        "obsidian_mcp": {"estado": ("ativo" if mcp.get("ativo") else "inativo") if est else "sem_dados",
                         "porta": mcp.get("porta"), "observado_em": est.get("agora"),
                         "nota": "Observado pela ponte no PC (porta local do MCP); não testa as ferramentas."},
        "nota": "Um sinal de vida recente não prova que todas as ferramentas funcionam.",
    }


def tarefa_resumo(t):
    r = {k: t.get(k) for k in ("id", "agente", "status", "criado", "inicio", "fim", "origem", "trace_id",
                               "tentativa", "tentativa_de", "projeto", "nota", "nota_status", "atividade",
                               "motivo", "modelo", "cancelar")}
    r["texto"] = (t.get("texto") or "")[:200]
    r["tempos"] = M.tempos_tarefa(t)
    r["tokens"] = sum(M.total_tokens(u) or 0 for u in E.uso if u.get("tarefa") == t["id"]) or None
    return r


def trilha(t):
    """Percurso: origem -> Central -> fila -> ponte -> executor -> resultado -> vault -> app."""
    ev = {}
    for e in t.get("eventos") or []:
        ev.setdefault(e["ev"], e)
    final = t["status"] in M.FINAIS
    nota_st = t.get("nota_status") or ("nao_solicitada" if not t.get("registrar_nota", True) else None)
    etapas = [
        ("origem", "Origem (%s)" % (t.get("origem") or "web"), "ok", ev.get("criada")),
        ("central", "Central recebeu", "ok" if ev.get("criada") else "desconhecido", ev.get("criada")),
        ("fila", "Fila", "ok" if ev.get("entregue_ponte") else ("cancelada" if t["status"] == "cancelada" else "aguardando"), ev.get("entregue_ponte")),
        ("ponte", "Ponte do PC", "ok" if ev.get("inicio_recebido") else ("aguardando" if not final else "nao_alcancado"), ev.get("inicio_recebido")),
        ("executor", "Executor %s" % (t.get("motor") or ""), {"concluida": "ok", "falhou": "erro", "cancelada": "cancelada"}.get(t["status"], "executando" if t["status"] == "rodando" else "aguardando"), ev.get("fim_recebido")),
        ("resultado", "Resultado", "ok" if t.get("resultado") else ("aguardando" if not final else "vazio"), ev.get("fim_recebido")),
        ("vault", "Gravação no vault", {"gravada": "ok", "erro": "erro", "nao_solicitada": "nao_solicitada", "pendente": "pendente"}.get(nota_st, "aguardando" if not final else ("nao_gravada" if t["status"] != "concluida" else "pendente")), ev.get("nota_gravada")),
    ]
    return [{"etapa": k, "rotulo": r, "estado": s, "t": (e or {}).get("t")} for k, r, s, e in etapas]


def visao_v1(dispositivo=None):
    with trava:
        base = visao()
        est = E.ponte.get("estado") or {}
        sess = []
        for tipo in ("codex", "claude"):
            for s in ((est.get(tipo) or {}).get("sessoes") or [])[:12]:
                sess.append({k: s.get(k) for k in ("id", "tipo", "titulo", "estado", "atividade", "detalhe",
                                                    "memoria", "atualizado", "agente", "projeto")})
        tok = tokens_visao({})
        return {
            "agora": base["agora"], "revisao": E.revisao, "versao_backend": VERSAO_BACKEND,
            "componentes": componentes(),
            "agentes": base["agentes"],
            "projetos": E.ponte.get("pastas", []),
            "escopo_pastas": E.escopo_pastas,
            "tarefas": [tarefa_resumo(t) for t in reversed(E.tarefas[-100:])],
            "ops": [{k: v for k, v in o.items() if k != "conteudo"} for o in E.ops[-20:]],
            "sessoes": sess, "feed": (est.get("feed") or [])[:30],
            "estado_pc_em": est.get("agora"),
            "tokens_resumo": tok["resumo"],
            "fluxo": base["fluxo"],
            "openbot_agentes": E.openbot_agentes,
        }


def tokens_visao(q):
    """Painel de tokens: orçamento local, cota do provedor, contexto e vazão SEPARADOS."""
    agora = agora_ms()
    periodo_q = q.get("periodo")
    filtro_ag, filtro_proj, filtro_mod = q.get("agente"), q.get("projeto"), q.get("modelo")
    ags = []
    perto = []
    for a in E.agentes:
        if filtro_ag and a["id"] != filtro_ag:
            continue
        orc = E.orcamentos.get(a["id"]) or {}
        periodo = periodo_q or orc.get("periodo") or "dia"
        tz, tz_desc = M.fuso(orc.get("fuso") or "America/Sao_Paulo")
        ini = M.inicio_periodo(periodo, agora, tz)
        regs = [u for u in E.uso if u.get("agente") == a["id"] and u["t"] >= ini
                and (not filtro_proj or u.get("projeto") == filtro_proj) and (not filtro_mod or u.get("modelo") == filtro_mod)]
        consumo = consumo_agente(a["id"], ini, projeto=filtro_proj, modelo=filtro_mod)
        limite = orc.get("limite_tokens")
        calc = M.orcamento(consumo, limite) if limite is not None else M.orcamento(consumo, None)
        alerta = M.alertas_cruzados(calc["percentual_consumido"], orc.get("alertas") or [70, 85, 95])
        por_modelo = {}
        for u in regs:
            k = u.get("modelo") or "não informado"
            por_modelo[k] = por_modelo.get(k, 0) + (M.total_tokens(u) or 0)
        por_tarefa = {}
        for u in regs:
            p = por_tarefa.setdefault(u["tarefa"], {"tarefa": u["tarefa"], "tentativa": u.get("tentativa"), "tokens": 0,
                                                     "entrada": 0, "saida": 0, "modelo": u.get("modelo"), "custo_usd": None,
                                                     "status": u.get("status_tarefa")})
            p["tokens"] += M.total_tokens(u) or 0
            p["entrada"] += u.get("entrada") or 0
            p["saida"] += u.get("saida") or 0
            if u.get("custo_usd") is not None:
                p["custo_usd"] = (p["custo_usd"] or 0) + u["custo_usd"]
        hist = {}
        for u in E.uso:
            if u.get("agente") == a["id"] and u["t"] >= agora - 30 * 86_400_000:
                from datetime import datetime
                d = datetime.fromtimestamp(u["t"] / 1000, tz).strftime("%Y-%m-%d")
                hist[d] = hist.get(d, 0) + (M.total_tokens(u) or 0)
        ultimo = max([u["t"] for u in E.uso if u.get("agente") == a["id"]] or [0]) or None
        item = {
            "agente": a["id"], "nome": a.get("nome"), "motor": a.get("motor"), "projeto": a.get("pasta"),
            "orcamento_local": {"configurado": limite is not None, "limite_tokens": limite, "periodo": periodo,
                                "fuso": tz_desc, "inicio_periodo": ini,
                                "renovacao": M.proxima_renovacao(periodo, agora, tz), "tipo_janela": "calendário" if periodo != "total" else "acumulado",
                                "alertas": orc.get("alertas") or [70, 85, 95], "bloquear": bool(orc.get("bloquear")),
                                "origem": "orçamento local configurado na Central (não é limite do provedor)",
                                **calc, "alerta_atingido": alerta,
                                "projecao": M.projecao_esgotamento([(u["t"], M.total_tokens(u)) for u in regs], limite, ini, agora) if limite else None},
            "consumo_periodo": consumo, "chamadas": len(regs), "ultima_atualizacao": ultimo,
            "por_modelo": [{"modelo": k, "tokens": v} for k, v in sorted(por_modelo.items(), key=lambda x: -x[1])],
            "por_tarefa": sorted(por_tarefa.values(), key=lambda x: x["tarefa"])[-50:],
            "historico_dia": [{"dia": k, "tokens": v} for k, v in sorted(hist.items())],
            "cota_provedor": {"disponivel": False, "motivo": "Os CLIs codex e claude não informam cota/limite em tokens da conta; nada é estimado."},
            "contexto": {"disponivel": False, "motivo": "Ocupação atual da janela de contexto não é exposta pela execução não interativa."},
            "vazao": {"disponivel": False, "motivo": "Limites por minuto/janelas de assinatura não são expostos em tokens pelos CLIs."},
            "cobertura": "parcial: somente tarefas executadas pela ponte; uso direto no PC ou em outros apps não é observado",
        }
        ags.append(item)
        if calc["percentual_consumido"] is not None and calc["percentual_consumido"] >= min(item["orcamento_local"]["alertas"] or [70]):
            perto.append({"agente": a["id"], "nome": a.get("nome"), "percentual": calc["percentual_consumido"]})
    total = sum(x["consumo_periodo"] or 0 for x in ags)
    return {"agora": agora, "agentes": ags,
            "resumo": {"consumo_total_periodo": total if any(x["consumo_periodo"] is not None for x in ags) else None,
                       "agentes_perto_limite": sorted(perto, key=lambda x: -x["percentual"]),
                       "com_orcamento": sum(1 for x in ags if x["orcamento_local"]["configurado"]),
                       "cobertura": "parcial (somente tarefas da ponte)"},
            "definicao_total": "OpenAI/Codex: entrada (já inclui cache) + saída; Anthropic/Claude: entrada + cache_criação + cache_leitura + saída"}


def metricas_visao(q):
    from collections import Counter
    from datetime import datetime
    agora = agora_ms()
    periodo = q.get("periodo") or "7d"
    janela = {"24h": 86_400_000, "7d": 7 * 86_400_000, "30d": 30 * 86_400_000}.get(periodo)
    desde = (agora - janela) if janela else 0
    filtro_ag = q.get("agente")
    tz, tz_desc = M.fuso(q.get("fuso") or "America/Sao_Paulo")
    est = E.ponte.get("estado") or {}
    grafo = est.get("grafo") or {}
    notas = [n for n in grafo.get("nos", []) if n.get("tipo") == "nota"]
    pc_agora = est.get("agora")
    cont = Counter(n.get("grupo") or "vault" for n in notas)
    pastas = [{"pasta": k, "n": v} for k, v in sorted(cont.items(), key=lambda p: (-p[1], p[0]))]
    dist = Counter()
    for n in notas:
        if n.get("mod"):
            dist[datetime.fromtimestamp(n["mod"] / 1000, tz).strftime("%Y-%m-%d")] += 1
    evs = Counter()
    for e in E.notas_eventos:
        if e["t"] >= desde and e.get("tipo") == "alterada":
            evs[datetime.fromtimestamp(e["t"] / 1000, tz).strftime("%Y-%m-%d")] += 1
    disp = []
    for n in notas:
        if n.get("mod") and pc_agora:
            disp.append({"id": n["id"], "rotulo": n.get("rotulo"), "pasta": n.get("grupo") or "vault",
                         "dias": max(0.0, (pc_agora - n["mod"]) / 86_400_000), "ligacoes": n.get("conexoes", 0)})
    tars = [t for t in E.tarefas if (t.get("criado") or 0) >= desde and (not filtro_ag or t["agente"] == filtro_ag)]
    linhas = []
    for t in tars:
        tp = M.tempos_tarefa(t)
        linhas.append({"id": t["id"], "agente": t["agente"], "nome": nome_agente(t["agente"]), "status": t["status"],
                       "criado": t.get("criado"), "fim_central": t.get("fim_central"), "tentativa": t.get("tentativa", 1),
                       "origem": t.get("origem"), "modelo": t.get("modelo"), "legado": not t.get("eventos"), **tp})
    por_ag = {}
    for t in tars:
        por_ag.setdefault(t["agente"], []).append(t)
    resumo = []
    for aid, lote in sorted(por_ag.items(), key=lambda x: (nome_agente(x[0]) or "").casefold()):
        tps = [M.tempos_tarefa(t) for t in lote]
        resumo.append({"agente": aid, "nome": nome_agente(aid),
                       "espera_fila_ms": M.resumo([x["espera_fila_ms"] for x in tps]),
                       "execucao_ms": M.resumo([x["execucao_ms"] for x in tps]),
                       "primeira_saida_ms": M.resumo([x["primeira_saida_ms"] for x in tps]),
                       "total_tarefa_ms": M.resumo([x["total_tarefa_ms"] for x in tps]),
                       "sucesso": M.taxa_sucesso(lote)})
    lat = [x for x in E.latencias if x["t"] >= desde]
    por_comp = {}
    for x in lat:
        por_comp.setdefault(x["comp"], []).append(x["ms"])
    return {
        "agora": agora, "periodo": periodo, "desde": desde, "fuso": tz_desc,
        "barras_pastas": {"dados": pastas, "fonte": "grafo do vault enviado pela ponte", "observado_em": pc_agora},
        "linhas_mtime": {"dados": [{"dia": k, "n": v} for k, v in sorted(dist.items())],
                         "rotulo": "Distribuição da última alteração (mtime)",
                         "aviso": "mtime indica só a última alteração de cada nota, não o histórico de edições."},
        "linhas_eventos": {"dados": [{"dia": k, "n": v} for k, v in sorted(evs.items())],
                           "rotulo": "Alterações observadas pela instrumentação",
                           "desde": (E.notas_eventos[0]["t"] if E.notas_eventos else None),
                           "aviso": "Contagem real a partir do início da coleta; não reconstrói o passado."},
        "dispersao": {"dados": disp, "x": "dias desde a última alteração (dias, relógio do PC)",
                      "y": "ligações da nota (arestas no grafo)", "tamanho": "raio proporcional a √(ligações+1)",
                      "cor": "pasta de topo"},
        "tarefas": {"dados": sorted(linhas, key=lambda x: x["criado"] or 0)[-200:]},
        "resumo_agentes": resumo,
        "sucesso_geral": M.taxa_sucesso(tars),
        "comunicacao": {"componentes": {k: M.resumo(v) for k, v in por_comp.items()},
                        "serie": lat[-300:],
                        "definicao": "latencia_requisicao_ms = recebimento_resposta - inicio_requisicao, medida com relógio monotônico no cliente que faz a requisição"},
        "percentil": "interpolação linear (tipo 7); aviso quando n < %d" % M.AMOSTRA_MINIMA,
    }


def logs_visao(q):
    try:
        limite = max(1, min(500, int(q.get("limite") or 100)))
        antes = int(q.get("antes") or 0)
        desde = int(q.get("desde") or 0)
    except ValueError:
        limite, antes, desde = 100, 0, 0
    niveis = {"debug": 0, "info": 1, "aviso": 2, "erro": 3}
    nmin = niveis.get(q.get("nivel") or "debug", 0)
    res = []
    for x in reversed(E.logs):
        if antes and x["t"] >= antes:
            continue
        if desde and x["t"] < desde:
            break
        if niveis.get(x.get("nivel"), 1) < nmin:
            continue
        if q.get("tarefa") and x.get("tarefa") != q["tarefa"]:
            continue
        if q.get("agente") and x.get("agente") != q["agente"]:
            continue
        if q.get("comp") and x.get("comp") != q["comp"]:
            continue
        res.append(x)
        if len(res) >= limite:
            break
    return {"logs": res, "proximo": (res[-1]["t"] if len(res) == limite else None),
            "retencao": "%d entradas ou %d dias, o que vier primeiro" % (LOG_MAX, LOG_DIAS)}


def diagnostico(disp):
    est = E.ponte.get("estado") or {}
    url = None
    try:
        with open(os.path.join(DADOS, "url.txt"), encoding="utf-8") as f:
            url = f.read().strip() or None
    except OSError:
        pass
    return {"agora": agora_ms(), "versao_backend": VERSAO_BACKEND, "versao_api": VERSAO_API, "pid": os.getpid(),
            "central_desde": INICIO_MS, "uptime_s": int(time.monotonic() - INICIO_MONO),
            "componentes": componentes(), "url_tunel": url,
            "ultima_sincronizacao_ponte": E.ponte.get("visto") or None,
            "estado_pc_em": est.get("agora"),
            "sessao": {"dispositivo": (disp or {}).get("id"), "nome": (disp or {}).get("nome"),
                       "expira": (disp or {}).get("expira")},
            "contagens": {"tarefas": len(E.tarefas), "fila": sum(1 for t in E.tarefas if t["status"] == "fila"),
                          "rodando": sum(1 for t in E.tarefas if t["status"] == "rodando"),
                          "ops_pendentes": sum(1 for o in E.ops if o["status"] in ("pendente", "entregue")),
                          "logs": len(E.logs), "uso": len(E.uso)},
            "sessao_remota_pc": {"disponivel": False, "motivo": "Nenhuma capacidade oficial verificada; não implementada."}}


# ----------------------------------------------------------- v2: dispositivos
def hash_token(tok):
    return hashlib.sha256(("disp:" + tok).encode()).hexdigest()


_disp_mtime = [0.0]


def recarregar_dispositivos():
    """Relê dispositivos.json se outro processo (CLI --novo-dispositivo/--revogar-dispositivo) o alterou."""
    try:
        m = os.path.getmtime(os.path.join(DADOS, "dispositivos.json"))
    except OSError:
        return
    if m != _disp_mtime[0]:
        with trava:
            _disp_mtime[0] = m
            E.dispositivos = mesclar_dispositivos(E.dispositivos, ler("dispositivos.json", []))


def mesclar_dispositivos(memoria, disco):
    """Une memória e disco sem desfazer revogação: revogado em qualquer lado vence; aparelhos novos do disco entram."""
    por_id = {d["id"]: dict(d) for d in memoria}
    for d in disco:
        atual = por_id.get(d["id"])
        if atual is None:
            por_id[d["id"]] = dict(d)
        else:
            if d.get("revogado") and not atual.get("revogado"):
                atual["revogado"] = d["revogado"]
            atual["visto"] = max(atual.get("visto", 0), d.get("visto", 0))
    return list(por_id.values())


def dispositivo_do_token(cab):
    a = cab or ""
    if not a.startswith("Bearer "):
        return None
    recarregar_dispositivos()
    h = hash_token(a[7:].strip())
    agora = time.time()
    with trava:
        for d in E.dispositivos:
            if hmac.compare_digest(d["token_hash"], h) and not d.get("revogado") and d["expira"] > agora:
                if agora_ms() - d.get("visto", 0) > 60_000:
                    d["visto"] = agora_ms()
                    E.sujo.add("dispositivos.json")
                return d
    return None


def verificar_senha(senha):
    """Mesma regra de bloqueio do login web. Retorna (ok, erro_http)."""
    recarregar_cfg()
    with trava:
        if time.time() < E.bloqueado_ate:
            return False, (429, {"erro": "Muitas tentativas. Aguarde %d s." % (E.bloqueado_ate - time.time() + 1)})
        # A tentativa é contada ANTES de conferir a senha, ainda sob a trava: pedidos simultâneos não
        # conseguem todos passar pela checagem de bloqueio antes de algum deles contar como erro.
        E.falhas_login += 1
        if E.falhas_login >= 5:
            E.bloqueado_ate = time.time() + min(900, 30 * 2 ** (E.falhas_login - 5))
    ok = hmac.compare_digest(hash_senha(str(senha or ""), E.cfg["sal"]), E.cfg["senha_hash"])
    if not ok:
        return False, (401, {"erro": "Senha incorreta.", "codigo": "senha"})
    with trava:
        E.falhas_login, E.bloqueado_ate = 0, 0
    return True, None


# ------------------------------------------------------------------- visao do app
def visao():
    with trava:
        t = agora_ms()
        online = (t - E.ponte.get("visto", 0)) < PONTE_ONLINE_S * 1000
        por_agente = {}
        for tar in E.tarefas:
            por_agente.setdefault(tar["agente"], []).append(tar)
        # sessões interativas no PC (Claude Code / Codex abertos à mão), enviadas pela ponte em "estado".
        # Sem isso, um agente trabalhando direto no PC aparecia "descansando" no app.
        sessoes_pc = {}
        est = E.ponte.get("estado") if online else None
        if isinstance(est, dict):
            for motor in ("claude", "codex"):
                ss = (est.get(motor) or {}).get("sessoes") or []
                sessoes_pc[motor] = [s for s in ss if isinstance(s, dict)]
        agentes = []
        for a in E.agentes:
            ts = por_agente.get(a["id"], [])
            rodando = next((x for x in ts if x["status"] == "rodando"), None)
            fila = sum(1 for x in ts if x["status"] == "fila")
            b = dict(a)
            b["status"] = "trabalhando" if (rodando and online) else ("fila" if (fila or rodando) else "ocioso")
            b["atividade"] = (rodando or {}).get("atividade", "")
            b["detalhe"] = (rodando or {}).get("detalhe", "")
            b["memoria"] = bool((rodando or {}).get("memoria"))
            b["tarefa"] = (rodando or {}).get("texto", "")
            b["na_fila"] = fila
            b["atualizado"] = max([x.get("fim") or x.get("inicio") or x["criado"] for x in ts] + [a.get("criado", 0)])
            pc = sessoes_pc.get(a.get("motor"), [])
            ativas = [s for s in pc if s.get("status") == "trabalhando"]
            b["sessoes_pc_ativas"] = len(ativas)
            if pc:
                b["atualizado"] = max([b["atualizado"]] + [int(s.get("atualizado") or 0) for s in pc])
            if ativas and b["status"] == "ocioso":
                s = max(ativas, key=lambda s: s.get("atualizado") or 0)
                b["status"] = "trabalhando"
                b["atividade"] = s.get("atividade") or "trabalhando no PC"
                b["detalhe"] = "sessão no PC: " + str(s.get("titulo") or "")[:60]
                b["memoria"] = bool(s.get("memoria"))
                b["tarefa"] = s.get("pedido", "")
            agentes.append(b)
        return {
            "agora": t,
            "ponte": {"online": online, "visto": E.ponte.get("visto", 0),
                      "executores": E.ponte.get("executores", {}), "pastas": E.ponte.get("pastas", [])},
            "openbot": {"online": (t - E.openbot_visto) < OPENBOT_ONLINE_S * 1000,
                        "visto": E.openbot_visto},
            "estado": E.ponte.get("estado"),
            "agentes": agentes,
            "tarefas": E.tarefas[-60:],
            "ops": E.ops[-12:],
            "fluxo": M.fluxo_trafego(E.trafego, t),
        }


_cache_grafico = {}


def grafico_png(tipo):
    """PNG com cache pela versão dos dados (revisão da Central + assinatura do grafo); renderização serializada."""
    with trava:
        g = ((E.ponte.get("estado") or {}).get("grafo") or {})
        chave = (tipo, E.revisao, len(g.get("nos", [])), max([n.get("mod", 0) for n in g.get("nos", [])] or [0]),
                 len(E.tarefas), E.tarefas[-1].get("fim_central") if E.tarefas else None, int(time.time() // 3600))
    if chave in _cache_grafico:
        return _cache_grafico[chave]
    png = _grafico_png(tipo)
    for k in [k for k in _cache_grafico if k[0] == tipo]:  # só a versão vigente de cada tipo (memória do J7)
        del _cache_grafico[k]
    _cache_grafico[chave] = png
    return png


def _grafico_png(tipo):
    """Renderiza uma vista estatística usando o grafo e os tempos registrados."""
    from collections import Counter, defaultdict
    from datetime import date, datetime, timedelta
    from statistics import median

    permitidos = {"pastas", "atividade", "dispersao", "comunicacao", "resposta"}
    if tipo not in permitidos:
        raise ValueError("tipo de gráfico inválido")
    with trava:
        estado = copy.deepcopy(E.ponte.get("estado") or {})
        tarefas = copy.deepcopy(E.tarefas[-300:])
        agentes = copy.deepcopy(E.agentes)
    grafo = estado.get("grafo") or {}
    notas = [n for n in grafo.get("nos", []) if n.get("tipo") == "nota"]
    agora = agora_ms()

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates

    fundo, painel, texto, suave = "#0e1320", "#101627", "#e7ecf7", "#93a0bd"
    azul, vermelho = "#6d82ff", "#ff6b6b"
    with grafico_trava:
        with plt.rc_context({"font.size": 9, "axes.titleweight": "medium"}):
            fig, ax = plt.subplots(figsize=(7.5, 4.2), dpi=128)
            fig.patch.set_facecolor(fundo)
            ax.set_facecolor(painel)
            ax.tick_params(colors=suave, labelsize=8)
            for lado in ax.spines.values():
                lado.set_color("#40506f")
            ax.grid(True, color="#6b7894", alpha=0.22, linestyle="--", linewidth=0.7)
            ax.set_axisbelow(True)

            def titulo(t, x, y):
                ax.set_title(t, loc="left", color=texto, pad=12)
                ax.set_xlabel(x, color=suave)
                ax.set_ylabel(y, color=suave)

            def vazio(mensagem):
                ax.text(0.5, 0.5, mensagem, transform=ax.transAxes,
                        ha="center", va="center", color=suave, wrap=True)
                ax.set_axis_off()

            if tipo == "pastas":
                contagem = Counter(n.get("grupo") or "Vault" for n in notas)
                dados = sorted(contagem.items(), key=lambda p: (-p[1], p[0]))[:18]
                titulo("Arquivos por pasta", "Quantidade de notas", "Pasta")
                if dados:
                    nomes, valores = zip(*dados)
                    barras = ax.barh(nomes, valores, color=azul, alpha=0.88, height=0.68)
                    ax.invert_yaxis()
                    ax.bar_label(barras, padding=4, color=texto, fontsize=8)
                    ax.margins(x=0.14)
                else:
                    vazio("Nenhuma nota sincronizada")

            elif tipo == "atividade":
                hoje = date.today()
                dias = [hoje - timedelta(days=29 - i) for i in range(30)]
                por_dia = Counter()
                for n in notas:
                    try:
                        por_dia[datetime.fromtimestamp(int(n.get("mod", 0)) / 1000).date()] += 1
                    except (OSError, TypeError, ValueError):
                        continue
                valores = [por_dia[d] for d in dias]
                titulo("Atividade nas notas", "Dia", "Notas alteradas")
                if any(valores):
                    x = list(range(len(dias)))
                    ax.plot(x, valores, color=azul, linewidth=2, marker="o", markersize=3.5,
                            label="Notas alteradas")
                    ax.set_xticks(x[::5], [dias[i].strftime("%d/%m") for i in x[::5]])
                    ax.set_xlim(0, 29)
                    ax.set_ylim(bottom=0)
                else:
                    vazio("Sem alterações de notas nos últimos 30 dias")

            elif tipo == "dispersao":
                titulo("Idade e conexões dos arquivos", "Dias desde a última alteração", "Ligações por arquivo")
                grupos = sorted({n.get("grupo") or "Vault" for n in notas})
                paleta = plt.get_cmap("tab10")
                if notas:
                    for i, grupo in enumerate(grupos[:8]):
                        lote = [n for n in notas if (n.get("grupo") or "Vault") == grupo]
                        xs, ys, tamanhos = [], [], []
                        for n in lote:
                            try:
                                xs.append(max(0, (agora - int(n.get("mod", agora))) / 86_400_000))
                            except (TypeError, ValueError):
                                xs.append(0)
                            grau = max(0, int(n.get("conexoes", 0)))
                            ys.append(grau)
                            tamanhos.append(22 + min(100, 10 * grau))
                        ax.scatter(xs, ys, s=tamanhos, color=paleta(i), alpha=0.82,
                                   edgecolors="#e7ecf7", linewidths=0.35, label=grupo)
                    if len(grupos) > 8:
                        outros = [n for n in notas if (n.get("grupo") or "Vault") not in grupos[:8]]
                        xs = [max(0, (agora - int(n.get("mod", agora))) / 86_400_000) for n in outros]
                        ys = [max(0, int(n.get("conexoes", 0))) for n in outros]
                        ax.scatter(xs, ys, s=24, color=suave, alpha=0.7, label="Outras pastas")
                    ax.legend(frameon=False, labelcolor=texto, fontsize=7, loc="best")
                    ax.set_ylim(bottom=0)
                else:
                    vazio("Nenhuma nota sincronizada")

            else:
                nomes_agentes = {a["id"]: a.get("nome", "Agente") for a in agentes}
                concluidas = [t for t in tarefas if t.get("status") in ("concluida", "falhou")
                              and t.get("criado") and t.get("inicio") and t.get("fim")
                              and t["criado"] <= t["inicio"] <= t["fim"]]
                titulo("", "", "")
                if tipo == "comunicacao":
                    titulo("Tempo médio por agente", "Minutos por tarefa concluída", "Agente")
                    por_agente = defaultdict(list)
                    for t in concluidas:
                        por_agente[t.get("agente", "")].append(t)
                    resumo = []
                    for aid, lote in por_agente.items():
                        espera = sum(t["inicio"] - t["criado"] for t in lote) / len(lote) / 60_000
                        execucao = sum(t["fim"] - t["inicio"] for t in lote) / len(lote) / 60_000
                        resumo.append((nomes_agentes.get(aid, "Agente"), espera, execucao, len(lote)))
                    resumo.sort(key=lambda x: x[0].casefold())
                    if resumo:
                        ys = list(range(len(resumo)))
                        ax.barh(ys, [x[1] for x in resumo], color=azul, alpha=0.9, label="Espera até iniciar")
                        ax.barh(ys, [x[2] for x in resumo], left=[x[1] for x in resumo],
                                color=vermelho, alpha=0.88, label="Execução")
                        ax.set_yticks(ys, [f"{x[0]} (n={x[3]})" for x in resumo])
                        ax.invert_yaxis()
                        ax.set_xlim(left=0)
                        ax.legend(frameon=False, labelcolor=texto, fontsize=8)
                    else:
                        vazio("Ainda não há tarefas concluídas com horários completos")
                else:
                    titulo("Tempo de resposta das tarefas", "Horário de conclusão", "Minutos")
                    concluidas.sort(key=lambda t: t["fim"])
                    concluidas = concluidas[-60:]
                    if concluidas:
                        datas = [datetime.fromtimestamp(t["fim"] / 1000) for t in concluidas]
                        total = [(t["fim"] - t["criado"]) / 60_000 for t in concluidas]
                        fila = [(t["inicio"] - t["criado"]) / 60_000 for t in concluidas]
                        execucao = [(t["fim"] - t["inicio"]) / 60_000 for t in concluidas]
                        ax.plot(datas, total, color=azul, marker="o", markersize=4, linewidth=1.8,
                                label="Envio até resposta")
                        ax.plot(datas, fila, color=vermelho, marker="o", markersize=3, linewidth=1.5,
                                label="Espera até iniciar")
                        ax.plot(datas, execucao, color="#3ddc97", marker="o", markersize=3, linewidth=1.5,
                                label="Execução")
                        media = sum(total) / len(total)
                        ax.set_title(f"Tempo de resposta das tarefas · média {media:.1f} min · mediana {median(total):.1f} min · n={len(total)}",
                                     loc="left", color=texto, pad=12)
                        ax.set_xlabel("Horário de conclusão", color=suave)
                        ax.set_ylabel("Minutos", color=suave)
                        ax.xaxis.set_major_formatter(mdates.DateFormatter("%d/%m %H:%M"))
                        fig.autofmt_xdate(rotation=20, ha="right")
                        ax.set_ylim(bottom=0)
                        ax.legend(frameon=False, labelcolor=texto, fontsize=7)
                    else:
                        vazio("Ainda não há tarefas concluídas com horários completos")

            fig.tight_layout()
            buffer = io.BytesIO()
            fig.savefig(buffer, format="png", dpi=128, facecolor=fundo, bbox_inches="tight")
            plt.close(fig)
            return buffer.getvalue()


# ---------------------------------------------------------------------- servidor
class H(BaseHTTPRequestHandler):
    server_version = "Escritorio"
    sys_version = ""

    def log_message(self, *a):
        pass

    # HTTP/1.1: permite reaproveitar a conexão (keep-alive). Toda resposta leva Content-Length;
    # se o corpo do pedido não foi lido (ex.: recusa antes de ler), a conexão é fechada para não
    # misturar bytes restantes com o próximo pedido.
    protocol_version = "HTTP/1.1"
    timeout = 30  # conexão ociosa não segura uma thread do J7 para sempre

    def setup(self):
        super().setup()
        self._corpo_lido = False

    def enviar(self, cod, corpo, tipo="application/json; charset=utf-8", extra=None):
        if not isinstance(corpo, (bytes, bytearray)):
            corpo = json.dumps(corpo, ensure_ascii=False).encode("utf-8")
        pendente = int(self.headers.get("Content-Length") or 0) > 0 and not getattr(self, "_corpo_lido", False)
        self.send_response(cod)
        if pendente:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        if self.headers.get("X-Forwarded-Proto") == "https":
            self.send_header("Strict-Transport-Security", "max-age=31536000")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(corpo)
        medir_trafego(urlparse(self.path).path, "saida", len(corpo))

    def corpo_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_CORPO:
            return None
        try:
            bruto = self.rfile.read(n)
            self._corpo_lido = True
            medir_trafego(urlparse(self.path).path, "entrada", len(bruto))
            v = json.loads(bruto.decode("utf-8"))
            return v if isinstance(v, dict) else None
        except Exception:
            return None

    def logado(self):
        return cookie_valido(self.headers.get("Cookie"))

    def http_pelo_tunel(self):
        """Pedido que chegou pelo tunel em http:// puro: manda para https (GET) ou recusa (POST)."""
        if self.headers.get("X-Forwarded-Proto") != "http":
            return False
        host = re.sub(r"[^A-Za-z0-9.:-]", "", self.headers.get("Host") or "")
        if self.command == "GET" and host:
            self.send_response(301)
            self.send_header("Location", "https://%s%s" % (host, urlparse(self.path).path or "/"))
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            self.enviar(403, {"erro": "use https"})
        return True

    def da_ponte(self):
        a = self.headers.get("Authorization") or ""
        return a.startswith("Bearer ") and hmac.compare_digest(a[7:].strip(), E.cfg["chave_ponte"])

    def do_openbot(self):
        a = self.headers.get("Authorization") or ""
        chave = E.cfg.get("chave_openbot", "")
        return bool(chave) and a.startswith("Bearer ") and hmac.compare_digest(a[7:].strip(), chave)

    # ------------------------------------------------------------- API v1 (app)
    def api_v1(self, metodo, u):
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        rota = u.path[len("/api/v1"):].rstrip("/") or "/"
        t0 = time.monotonic()
        if rota == "/info" and metodo == "GET":
            return self.enviar(200, {"servico": "central-escritorio", "versao_api": VERSAO_API,
                                     "versao_backend": VERSAO_BACKEND, "agora": agora_ms(),
                                     "https": self.headers.get("X-Forwarded-Proto") == "https" or bool(self.headers.get("Cf-Ray"))})
        if metodo == "POST":
            if self.headers.get("X-Requested-With") not in ("escritorio", "escritorio-android"):
                return self.enviar(400, {"erro": "requisição inválida"})
            d = self.corpo_json()
            if d is None:
                return self.enviar(400, {"erro": "corpo inválido"})
        else:
            d = {}
        if rota == "/dispositivos/parear" and metodo == "POST":
            ok, err = verificar_senha(d.get("senha"))
            if not ok:
                registrar("aviso", "central", "pareamento_recusado", codigo=err[0])
                time.sleep(0.3)
                return self.enviar(*err)
            tok = secrets.token_urlsafe(32)
            disp = {"id": novo_id("dv"), "nome": txt(d.get("nome"), 60) or "Android", "token_hash": hash_token(tok),
                    "criado": agora_ms(), "expira": int(time.time()) + DISPOSITIVO_S, "visto": agora_ms(),
                    "escopos": list(ESCOPOS_PADRAO), "projetos": "*"}
            with trava:
                E.dispositivos = [x for x in E.dispositivos if not x.get("revogado") and x["expira"] > time.time()][-19:]
                E.dispositivos.append(disp)
                gravar("dispositivos.json", E.dispositivos)
            registrar("info", "central", "dispositivo_pareado", dispositivo=disp["id"], nome=disp["nome"])
            return self.enviar(201, {"token": tok, "dispositivo": {k: v for k, v in disp.items() if k != "token_hash"}})

        disp = dispositivo_do_token(self.headers.get("Authorization"))
        if not disp and self.logado():
            disp = {"id": "web", "nome": "painel web", "escopos": list(ESCOPOS_PADRAO), "projetos": "*", "expira": None}
        if not disp:
            return self.enviar(401, {"erro": "Sessão inválida ou expirada. Entre novamente.", "codigo": "sessao"})

        def pode(escopo):
            return escopo in (disp.get("escopos") or [])

        def proibido():
            return self.enviar(403, {"erro": "Este dispositivo não tem permissão para esta operação.", "codigo": "permissao"})

        def projeto_ok(proj):
            ps = disp.get("projetos", "*")
            return ps == "*" or proj in (ps or [])

        m = re.match(r"^/tarefas/([A-Za-z0-9]{3,40})(?:/(cancelar|repetir))?$", rota)
        try:
            if metodo == "GET":
                if not pode("ler"):
                    return proibido()
                if rota == "/estado":
                    return self.enviar(200, visao_v1(disp))
                if rota == "/grafo":
                    with trava:
                        g = (E.ponte.get("estado") or {}).get("grafo") or {"nos": [], "arestas": []}
                        ver = hashlib.sha1(json.dumps([len(g.get("nos", [])), len(g.get("arestas", [])),
                                                       max([n.get("mod", 0) for n in g.get("nos", [])] or [0])]).encode()).hexdigest()[:16]
                        if self.headers.get("If-None-Match") == ver:
                            self.send_response(304)
                            self.send_header("ETag", ver)
                            self.send_header("Content-Length", "0")
                            self.end_headers()
                            return
                        corpo = {"versao": ver, "grafo": g, "observado_em": (E.ponte.get("estado") or {}).get("agora")}
                    return self.enviar(200, corpo, extra={"ETag": ver})
                if m and not m.group(2):
                    with trava:
                        t = next((x for x in E.tarefas if x["id"] == m.group(1)), None)
                        if not t:
                            return self.enviar(404, {"erro": "Tarefa não encontrada.", "codigo": "nao_encontrada"})
                        if not projeto_ok(t.get("projeto") or ""):
                            return proibido()
                        r = dict(t)
                        r.pop("idem", None)
                        r.pop("marca_openbot", None)
                        r["tempos"] = M.tempos_tarefa(t)
                        r["trilha"] = trilha(t)
                        r["agente_nome"] = nome_agente(t["agente"])
                        r["uso"] = [dict(x) for x in E.uso if x.get("tarefa") == t["id"]]
                        r["tentativas"] = [{"id": x["id"], "tentativa": x.get("tentativa", 1), "status": x["status"]}
                                           for x in E.tarefas if t.get("trace_id") and x.get("trace_id") == t.get("trace_id")]
                    return self.enviar(200, r)
                if rota == "/agentes":
                    with trava:
                        return self.enviar(200, {"agentes": E.agentes, "projetos": E.ponte.get("pastas", []),
                                                 "escopo_pastas": E.escopo_pastas})
                if rota == "/notas":
                    return self.enviar(200, buscar_notas(q))
                if rota == "/nota":
                    nid = q.get("id") or ""
                    try:
                        with open(arq_nota(nid), encoding="utf-8") as f:
                            return self.enviar(200, json.load(f))
                    except Exception:
                        return self.enviar(404, {"erro": "A nota ainda não foi sincronizada pelo PC.", "codigo": "nao_sincronizada"})
                mo = re.match(r"^/ops/([A-Za-z0-9]{3,40})$", rota)
                if mo:
                    with trava:
                        op = next((o for o in E.ops if o["id"] == mo.group(1)), None)
                        return self.enviar(200, {k: v for k, v in op.items() if k != "conteudo"}) if op else self.enviar(404, {"erro": "Operação não encontrada."})
                if rota == "/metricas":
                    with trava:
                        return self.enviar(200, metricas_visao(q))
                if rota == "/tokens":
                    with trava:
                        return self.enviar(200, tokens_visao(q))
                if rota == "/logs":
                    with trava:
                        return self.enviar(200, logs_visao(q))
                if rota == "/diagnostico":
                    with trava:
                        r = diagnostico(disp)
                    r["processamento_ms"] = round((time.monotonic() - t0) * 1000, 2)
                    return self.enviar(200, r)
                if rota == "/dispositivos":
                    if not pode("dispositivos"):
                        return proibido()
                    with trava:
                        return self.enviar(200, {"dispositivos": [{k: v for k, v in x.items() if k != "token_hash"} for x in E.dispositivos],
                                                 "atual": disp.get("id")})
                if rota == "/grafico":
                    try:
                        return self.enviar(200, grafico_png(q.get("tipo") or ""), "image/png")
                    except ValueError as erro:
                        return self.enviar(400, {"erro": str(erro)})
                    except ImportError:
                        return self.enviar(503, {"erro": "Matplotlib não está instalado no Termux."})
                return self.enviar(404, {"erro": "não encontrado"})

            # ---------------------------------------------------------- POST
            if rota == "/sessao/sair":
                with trava:
                    for x in E.dispositivos:
                        if x["id"] == disp.get("id"):
                            x["revogado"] = agora_ms()
                    gravar("dispositivos.json", E.dispositivos)
                registrar("info", "central", "dispositivo_saiu", dispositivo=disp.get("id"))
                return self.enviar(200, {"ok": True})
            mr = re.match(r"^/dispositivos/([A-Za-z0-9]{3,40})/revogar$", rota)
            if mr:
                if not pode("dispositivos"):
                    return proibido()
                with trava:
                    alvo = next((x for x in E.dispositivos if x["id"] == mr.group(1)), None)
                    if not alvo:
                        return self.enviar(404, {"erro": "Dispositivo não encontrado."})
                    alvo["revogado"] = agora_ms()
                    gravar("dispositivos.json", E.dispositivos)
                registrar("aviso", "central", "dispositivo_revogado", dispositivo=alvo["id"], por=disp.get("id"))
                return self.enviar(200, {"ok": True})
            if rota == "/tarefas":
                if not pode("tarefas"):
                    return proibido()
                with trava:
                    ag = next((a for a in E.agentes if a["id"] == d.get("agente")), None)
                    if ag and not projeto_ok(ag.get("pasta") or ""):
                        return proibido()
                    cod, r = criar_tarefa(d.get("agente"), txt(d.get("texto"), 6000), "web" if disp.get("id") == "web" else "android",
                                          idem=txt(d.get("idem"), 80) or None, registrar_nota=d.get("registrar_nota", True),
                                          dispositivo=disp.get("id"))
                if cod < 400:
                    r = tarefa_resumo(r) | {"repetida": bool(r.get("repetida"))}
                return self.enviar(cod, r)
            if m and m.group(2) == "cancelar":
                if not pode("tarefas"):
                    return proibido()
                with trava:
                    t = next((x for x in E.tarefas if x["id"] == m.group(1)), None)
                    if not t:
                        return self.enviar(404, {"erro": "Tarefa não encontrada."})
                    if not projeto_ok(t.get("projeto") or ""):
                        return proibido()
                    cancelar_tarefa(t, disp.get("id"))
                    return self.enviar(200, tarefa_resumo(t))
            if m and m.group(2) == "repetir":
                if not pode("tarefas"):
                    return proibido()
                with trava:
                    t = next((x for x in E.tarefas if x["id"] == m.group(1)), None)
                    if not t:
                        return self.enviar(404, {"erro": "Tarefa não encontrada."})
                    if not projeto_ok(t.get("projeto") or ""):
                        return proibido()
                    if t["status"] not in M.FINAIS:
                        return self.enviar(409, {"erro": "Só é possível repetir uma tarefa já finalizada.", "codigo": "nao_finalizada"})
                    cod, r = criar_tarefa(t["agente"], t.get("texto", ""), "repeticao", idem=txt(d.get("idem"), 80) or None,
                                          tentativa_de=t["id"], registrar_nota=t.get("registrar_nota", True), dispositivo=disp.get("id"))
                if cod < 400:
                    r = tarefa_resumo(r) | {"repetida": bool(r.get("repetida"))}
                return self.enviar(cod, r)
            if rota == "/agentes":
                if not pode("agentes"):
                    return proibido()
                with trava:
                    cod, r = salvar_agente(d)
                return self.enviar(cod, r)
            ma = re.match(r"^/agentes/([A-Za-z0-9]{3,40})/excluir$", rota)
            if ma:
                if not pode("agentes"):
                    return proibido()
                with trava:
                    excluir_agente(ma.group(1))
                return self.enviar(200, {"ok": True})
            if rota == "/notas":
                if not pode("notas"):
                    return proibido()
                with trava:
                    cod, r = nova_op_nota(d, disp.get("id"))
                return self.enviar(cod, r)
            if rota == "/orcamentos":
                if not pode("orcamentos"):
                    return proibido()
                with trava:
                    cod, r = salvar_orcamento(d)
                return self.enviar(cod, r)
            if rota == "/latencias":
                # o app relata latências que ELE mediu (monotônico do app); a Central só armazena
                with trava:
                    for x in (d.get("amostras") or [])[:50]:
                        try:
                            ms = float(x.get("ms"))
                        except (TypeError, ValueError):
                            continue
                        if 0 <= ms < 600_000:
                            E.latencias.append({"t": agora_ms(), "comp": "app→central", "ms": round(ms, 1),
                                                "rota": txt(x.get("rota"), 40), "dispositivo": disp.get("id")})
                    E.latencias = E.latencias[-LAT_MAX:]
                    E.sujo.add("latencias.json")
                return self.enviar(200, {"ok": True})
            return self.enviar(404, {"erro": "não encontrado"})
        except Exception as e:  # nunca devolve stack ao cliente
            registrar("erro", "central", "erro_interno", rota=rota, msg=type(e).__name__)
            return self.enviar(500, {"erro": "Erro interno da Central (registrado no log).", "codigo": "interno"})

    # ------------------------------------------------------------------- GET
    def do_GET(self):
        self._corpo_lido = False
        if self.http_pelo_tunel():
            return
        u = urlparse(self.path)
        if u.path.startswith("/api/v1/"):
            return self.api_v1("GET", u)
        if u.path in ("/", "/index.html"):
            try:
                with open(os.path.join(AQUI, "app.html"), "rb") as f:
                    return self.enviar(200, f.read(), "text/html; charset=utf-8",
                                       {"Content-Security-Policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'"})
            except OSError:
                return self.enviar(500, {"erro": "app.html ausente"})
        if u.path == "/api/estado":
            if not self.logado():
                return self.enviar(401, {"erro": "login"})
            return self.enviar(200, visao())
        if u.path == "/api/grafico":
            if not self.logado():
                return self.enviar(401, {"erro": "login"})
            tipo = (parse_qs(u.query).get("tipo") or [""])[0]
            try:
                return self.enviar(200, grafico_png(tipo), "image/png")
            except ValueError as erro:
                return self.enviar(400, {"erro": str(erro)})
            except ImportError:
                return self.enviar(503, {"erro": "Matplotlib não está instalado no Termux."})
        if u.path in ("/openbot/catalogo", "/openbot/tarefa"):
            if not self.do_openbot():
                return self.enviar(401, {"erro": "autenticação OpenBot inválida"})
            if u.path.endswith("/catalogo"):
                return self.enviar(200, openbot_catalogo())
            tid = (parse_qs(u.query).get("id") or [""])[0]
            tarefa = openbot_tarefa(tid)
            return self.enviar(200, tarefa) if tarefa else self.enviar(404, {"erro": "tarefa não encontrada"})
        if u.path == "/api/nota":
            if not self.logado():
                return self.enviar(401, {"erro": "login"})
            nid = (parse_qs(u.query).get("id") or [""])[0]
            try:
                with open(arq_nota(nid), encoding="utf-8") as f:
                    return self.enviar(200, json.load(f))
            except Exception:
                return self.enviar(404, {"erro": "A nota ainda não foi sincronizada pelo PC."})
        return self.enviar(404, {"erro": "não encontrado"})

    # ------------------------------------------------------------------ POST
    def do_POST(self):
        self._corpo_lido = False
        if self.http_pelo_tunel():
            return
        u = urlparse(self.path)
        if u.path.startswith("/api/v1/"):
            return self.api_v1("POST", u)
        if u.path == "/openbot/mcp":
            if not self.do_openbot():
                time.sleep(0.2)
                return self.enviar(401, {"erro": "autenticação OpenBot inválida"})
            pedido = self.corpo_json()
            if pedido is None:
                return self.enviar(400, {"erro": "corpo MCP inválido"})
            mid, metodo, params = pedido.get("id"), pedido.get("method"), pedido.get("params") or {}
            if not isinstance(metodo, str) or not isinstance(params, dict):
                return self.enviar(400, {"erro": "pedido MCP inválido"})
            versao = "2025-03-26"
            cab = {"MCP-Protocol-Version": versao}
            if metodo in ("notifications/initialized", "notifications/cancelled") or mid is None:
                with trava:
                    E.openbot_visto = agora_ms()
                return self.enviar(202, b"", extra=cab)
            if metodo == "initialize":
                resultado = {"protocolVersion": versao, "capabilities": {"tools": {"listChanged": False}},
                             "serverInfo": {"name": "escritorio-openbot", "version": "1.0.0"}}
            elif metodo == "ping":
                E.openbot_visto = agora_ms()
                resultado = {}
            elif metodo == "tools/list":
                openbot_catalogo()
                resultado = {"tools": [
                    {"name": "listar_projetos_agentes", "description": "Lista projetos liberados e agentes disponíveis no Escritório.", "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
                    {"name": "enviar_tarefa", "description": "Envia a mensagem do usuário ao agente escolhido no projeto correspondente.", "inputSchema": {"type": "object", "properties": {"texto": {"type": "string", "maxLength": 6000}, "projeto": {"type": "string", "maxLength": 60}, "agente": {"type": "string", "maxLength": 60}, "idem": {"type": "string", "maxLength": 80, "description": "Opcional: identificador único desta mensagem. Repita o MESMO valor ao tentar de novo, para não criar a tarefa duas vezes."}}, "required": ["texto", "projeto", "agente"], "additionalProperties": False}},
                    {"name": "consultar_tarefa", "description": "Consulta o estado e o relatório de uma tarefa enviada.", "inputSchema": {"type": "object", "properties": {"id": {"type": "string", "maxLength": 60}}, "required": ["id"], "additionalProperties": False}}
                ]}
            elif metodo == "tools/call":
                nome = params.get("name")
                args = params.get("arguments") or {}
                if not isinstance(args, dict):
                    args = {}
                if nome == "listar_projetos_agentes":
                    resultado = {"content": [{"type": "text", "text": json.dumps(openbot_catalogo(), ensure_ascii=False)}]}
                elif nome == "enviar_tarefa":
                    cod, payload = openbot_enviar(args)
                    resultado = {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}], "isError": cod >= 400}
                elif nome == "consultar_tarefa":
                    tarefa = openbot_tarefa(txt(args.get("id"), 60))
                    resultado = {"content": [{"type": "text", "text": json.dumps(tarefa or {"erro": "tarefa não encontrada"}, ensure_ascii=False)}], "isError": tarefa is None}
                else:
                    resultado = {"content": [{"type": "text", "text": "ferramenta desconhecida"}], "isError": True}
            else:
                erro = {"code": -32601, "message": "método MCP desconhecido"}
                return self.enviar(200, {"jsonrpc": "2.0", "id": mid, "error": erro}, extra=cab)
            return self.enviar(200, {"jsonrpc": "2.0", "id": mid, "result": resultado}, extra=cab)
        if u.path == "/ponte/sincronizar":
            if not self.da_ponte():
                time.sleep(0.5)
                return self.enviar(401, {"erro": "chave da ponte inválida"})
            d = self.corpo_json()
            if d is None:
                return self.enviar(400, {"erro": "corpo inválido"})
            return self.enviar(200, sincronizar(d))

        if u.path in ("/openbot/sinal", "/openbot/mensagem"):
            if not self.do_openbot():
                time.sleep(0.2)
                return self.enviar(401, {"erro": "autenticação OpenBot inválida"})
            d = self.corpo_json()
            if d is None:
                return self.enviar(400, {"erro": "corpo inválido"})
            if u.path.endswith("/sinal"):
                with trava:
                    E.openbot_visto = agora_ms()
                return self.enviar(200, {"ok": True})
            cod, payload = openbot_enviar(d)
            return self.enviar(cod, payload)

        if self.headers.get("X-Requested-With") != "escritorio":
            return self.enviar(400, {"erro": "requisição inválida"})
        d = self.corpo_json()
        if d is None:
            return self.enviar(400, {"erro": "corpo inválido"})

        if u.path == "/api/login":
            ok, err = verificar_senha(d.get("senha"))  # mesma regra (e mesma contagem) do pareamento do app
            if not ok:
                return self.enviar(err[0], {"erro": err[1]["erro"]})
            exp = int(time.time()) + SESSAO_S
            https = (self.headers.get("X-Forwarded-Proto") == "https") or bool(self.headers.get("Cf-Ray"))
            ck = "sessao=%d.%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Strict%s" % (exp, assinar(exp), SESSAO_S, "; Secure" if https else "")
            return self.enviar(200, {"ok": True}, extra={"Set-Cookie": ck})

        if not self.logado():
            return self.enviar(401, {"erro": "login"})

        if u.path == "/api/sair":
            with trava:  # avança a geração: este cookie (e cópias dele) deixa de valer no servidor
                E.cfg["geracao_sessao"] = int(E.cfg.get("geracao_sessao") or 0) + 1
                gravar("config.json", E.cfg)
                try:
                    os.chmod(CONFIG, 0o600)
                except OSError:
                    pass
            registrar("info", "central", "sessao_web_encerrada")
            return self.enviar(200, {"ok": True}, extra={"Set-Cookie": "sessao=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"})

        if u.path == "/api/agentes":
            with trava:
                if d.get("acao") == "excluir":
                    excluir_agente(d.get("id"))
                else:
                    cod, r = salvar_agente(d)
                    if cod >= 400:
                        return self.enviar(cod, r)
            return self.enviar(200, {"ok": True})

        if u.path == "/api/tarefas":
            with trava:
                if d.get("acao") == "cancelar":
                    for t in E.tarefas:
                        if t["id"] == d.get("id"):
                            cancelar_tarefa(t, "web")
                    gravar("tarefas.json", E.tarefas)
                else:
                    cod, r = criar_tarefa(d.get("agente"), txt(d.get("texto"), 6000), "web", idem=txt(d.get("idem"), 80) or None)
                    if cod >= 400:
                        return self.enviar(cod, {"erro": r["erro"]})
            return self.enviar(200, {"ok": True})

        if u.path == "/api/notas":
            with trava:
                cod, r = nova_op_nota(d, "web")
            if cod >= 400:
                return self.enviar(cod, r)
            return self.enviar(200, {"ok": True, "op": r["op"]})

        return self.enviar(404, {"erro": "não encontrado"})


# ------------------------------------------------------------ ações compartilhadas
def salvar_agente(d):
    nome = txt(d.get("nome"), 40)
    motor = d.get("motor") if d.get("motor") in ("codex", "claude") else "codex"
    if not nome:
        return 400, {"erro": "Dê um nome ao agente.", "codigo": "entrada_invalida"}
    campos = {"nome": nome, "motor": motor, "funcao": txt(d.get("funcao"), 1500), "pasta": txt(d.get("pasta"), 60)}
    a = next((x for x in E.agentes if x["id"] == d.get("id")), None)
    if a:
        a.update(campos)
    else:
        idem = txt(d.get("idem"), 80) or None
        ja = chave_usada(idem, "agente")
        repetido = next((x for x in E.agentes if x["id"] == ja), None) if ja else None
        if repetido:  # mesma criação reenviada (resposta perdida, duplo clique): devolve o agente já criado
            return 200, dict(repetido, repetida=True)
        if len(E.agentes) >= 24:
            return 400, {"erro": "Limite de 24 agentes.", "codigo": "limite"}
        campos.update({"id": novo_id("ag"), "criado": agora_ms()})
        E.agentes.append(campos)
        a = campos
        lembrar_chave(idem, "agente", a["id"])
    gravar("agentes.json", E.agentes)
    registrar("info", "central", "agente_salvo", agente=a["id"])
    sujar()
    return 200, dict(a)


def excluir_agente(aid):
    E.agentes = [a for a in E.agentes if a["id"] != aid]
    for t in E.tarefas:
        if t["agente"] == aid:
            cancelar_tarefa(t, "exclusao_agente")
    E.orcamentos.pop(aid, None)
    gravar("agentes.json", E.agentes)
    gravar("tarefas.json", E.tarefas)
    gravar("orcamentos.json", E.orcamentos)
    registrar("info", "central", "agente_excluido", agente=aid)
    sujar()


def cancelar_tarefa(t, por):
    """Na fila: cancela na hora. Rodando: pede à ponte dona; o estado final vem da ponte."""
    if t["status"] == "fila":
        t["status"], t["fim"], t["fim_central"] = "cancelada", agora_ms(), agora_ms()
        evento(t, "cancelada", "central", por=por)
    elif t["status"] == "rodando" and not t.get("cancelar"):
        t["cancelar"] = True
        evento(t, "cancelamento_solicitado", "central", por=por)
    E.sujo.add("tarefas.json")
    E.revisao += 1


def nova_op_nota(d, por):
    conteudo = str(d.get("conteudo") or "")[:400_000]
    idem = txt(d.get("idem"), 80) or None
    if idem:
        prev = next((o for o in E.ops if o.get("idem") == idem), None)
        if prev:
            return 200, {"ok": True, "op": prev["id"], "status": prev["status"], "repetida": True}
        antiga = chave_usada(idem, "nota")
        if antiga:
            return 200, {"ok": True, "op": antiga, "status": "arquivada", "repetida": True}
    op = {"id": novo_id("op"), "status": "pendente", "criado": agora_ms(), "conteudo": conteudo, "por": por}
    if idem:
        op["idem"] = idem
    if d.get("nota"):
        try:
            base = int(d.get("base_mod") or 0)
        except (TypeError, ValueError):
            base = 0
        if not base:
            return 400, {"erro": "Versão base da nota ausente; reabra a nota antes de editar.", "codigo": "sem_versao"}
        op.update({"tipo": "editar", "nota": txt(d.get("nota"), 400), "base_mod": base})
    else:
        titulo = txt(d.get("titulo"), 90)
        if not titulo:
            return 400, {"erro": "Dê um título à nota.", "codigo": "entrada_invalida"}
        op.update({"tipo": "criar", "titulo": titulo})
    lembrar_chave(idem, "nota", op["id"])
    E.ops.append(op)
    E.ops = E.ops[-40:]
    gravar("ops.json", E.ops)
    registrar("info", "central", "nota_op_criada", op=op["id"], tipo=op["tipo"])
    sujar()
    return 202, {"ok": True, "op": op["id"], "status": "pendente"}


def salvar_orcamento(d):
    aid = d.get("agente")
    if not any(a["id"] == aid for a in E.agentes):
        return 400, {"erro": "Agente inválido."}
    if d.get("remover"):
        E.orcamentos.pop(aid, None)
    else:
        lim = d.get("limite_tokens")
        if lim is not None:
            try:
                lim = int(lim)
            except (TypeError, ValueError):
                return 400, {"erro": "Limite precisa ser um número inteiro de tokens."}
            if lim < 0:
                return 400, {"erro": "Limite não pode ser negativo."}
        periodo = d.get("periodo") if d.get("periodo") in ("dia", "semana", "mes", "total") else "dia"
        try:
            alertas = sorted({int(x) for x in (d.get("alertas") or [70, 85, 95]) if 1 <= int(x) <= 100})[:6]
        except (TypeError, ValueError):
            return 400, {"erro": "Alertas inválidos."}
        E.orcamentos[aid] = {"limite_tokens": lim, "periodo": periodo, "fuso": txt(d.get("fuso"), 60) or "America/Sao_Paulo",
                             "alertas": alertas or [70, 85, 95], "bloquear": bool(d.get("bloquear")), "atualizado": agora_ms()}
    gravar("orcamentos.json", E.orcamentos)
    registrar("info", "central", "orcamento_salvo", agente=aid)
    sujar()
    return 200, {"ok": True, "orcamento": E.orcamentos.get(aid)}


def buscar_notas(q):
    termo = (q.get("q") or "").strip().casefold()[:100]
    pasta = q.get("pasta")
    try:
        limite = max(1, min(200, int(q.get("limite") or 60)))
    except ValueError:
        limite = 60
    with trava:
        nos = [n for n in ((E.ponte.get("estado") or {}).get("grafo") or {}).get("nos", []) if n.get("tipo") == "nota"]
    res = []
    for n in sorted(nos, key=lambda n: -(n.get("mod") or 0)):
        if pasta and (n.get("grupo") or "vault") != pasta:
            continue
        trecho = None
        if termo:
            if termo in (n.get("rotulo") or "").casefold() or termo in n["id"].casefold():
                trecho = ""
            else:
                try:
                    with open(arq_nota(n["id"]), encoding="utf-8") as f:
                        c = json.load(f).get("conteudo", "")
                    i = c.casefold().find(termo)
                    if i < 0:
                        continue
                    trecho = c[max(0, i - 60): i + 80].replace("\n", " ")
                except Exception:
                    continue
        res.append({"id": n["id"], "rotulo": n.get("rotulo"), "pasta": n.get("grupo") or "vault", "mod": n.get("mod"),
                    "ligacoes": n.get("conexoes", 0), "trecho": trecho,
                    "sincronizada": os.path.exists(arq_nota(n["id"]))})
        if len(res) >= limite:
            break
    return {"notas": res, "total": len(nos)}


STATUS_DA_PONTE = ("rodando", "concluida", "falhou", "cancelada")
TAMANHO_CAMPO_PONTE = {"atividade": 120, "detalhe": 300, "resultado": 60_000, "nota": 400, "nota_status": 30,
                       "motivo": 60, "modelo": 80, "motor": 20}


def limpar_atualizacao(up):
    """Campos de uma atualização de tarefa vinda da ponte, com tipo, valor e tamanho conferidos.
    Campo desconhecido, status fora da lista ou tipo errado é ignorado (o valor atual da tarefa fica)."""
    r = {}
    if up.get("status") in STATUS_DA_PONTE:
        r["status"] = up["status"]
    for k in ("inicio", "fim", "codigo_saida"):
        v = up.get(k)
        if isinstance(v, (int, float)) and not isinstance(v, bool) and abs(v) < 10 ** 15:
            r[k] = int(v)
    for k, n in TAMANHO_CAMPO_PONTE.items():
        if k in up:
            r[k] = None if up[k] is None else str(up[k])[:n]
    if "memoria" in up:
        v = up["memoria"]
        r["memoria"] = v if v is None or isinstance(v, (bool, int, float)) else bool(v)
    if isinstance(up.get("medidas"), dict):
        r["medidas"] = {str(k)[:40]: (v[:120] if isinstance(v, str) else v) for k, v in list(up["medidas"].items())[:12]
                        if v is None or isinstance(v, (bool, int, float, str))}
    return r


def limpar_openbot(a):
    """Aceita só os campos conhecidos, com tipo e tamanho conferidos (nada de texto livre de mensagens)."""
    def inteiro(v):
        return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v < 10 ** 15 else None
    custo = a.get("custo_24h_usd")
    return {
        "id": txt(a.get("id"), 60), "nome": txt(a.get("nome"), 40), "titulo": txt(a.get("titulo"), 60),
        "provedor": txt(a.get("provedor"), 20).lower(), "provedor_openbot": txt(a.get("provedor_openbot"), 20).lower(),
        "modelo": txt(a.get("modelo"), 60), "trabalhando": a.get("trabalhando") is True,
        "atualizado": inteiro(a.get("atualizado")), "ultimo_status": txt(a.get("ultimo_status"), 20) or None,
        "falhou_ultimo": a.get("falhou_ultimo") is True, "sem_cota": a.get("sem_cota") is True,
        "tokens_24h": inteiro(a.get("tokens_24h")), "tokens_total": inteiro(a.get("tokens_total")),
        "chamadas_24h": inteiro(a.get("chamadas_24h")) or 0,
        "custo_24h_usd": round(float(custo), 6) if isinstance(custo, (int, float)) and not isinstance(custo, bool) and 0 <= custo < 1e6 else None,
        "ultima_chamada": inteiro(a.get("ultima_chamada")),
    }


def sincronizar(d):
    """Recebe o estado da ponte e devolve o que ela precisa fazer."""
    with trava:
        mudou_tarefas = False
        agora = agora_ms()
        pid = txt(d.get("ponte_id"), 40) or "legada"
        legada = pid == "legada"
        anterior = E.pontes.get(pid)
        E.pontes[pid] = {"visto": agora, "versao": txt(d.get("versao"), 20) or "1.x", "rtt_ms": d.get("rtt_ms")}
        if not anterior:
            registrar("info", "ponte", "ponte_conectou", ponte=pid, versao=E.pontes[pid]["versao"])
            ativas = [k for k, v in E.pontes.items() if agora - v["visto"] < 60_000]
            if len(ativas) > 1:
                registrar("aviso", "central", "pontes_concorrentes", n=len(ativas), pontes=",".join(ativas))
        E.ponte["visto"] = agora
        ex = d.get("executores")
        E.ponte["executores"] = {txt(k, 20): bool(v) for k, v in list(ex.items())[:10]} if isinstance(ex, dict) else {}
        E.ponte["pastas"] = [txt(p, 60) for p in (d.get("pastas") or [])][:40]
        if isinstance(d.get("escopo_pastas"), list):
            E.escopo_pastas = [{"nome": txt(p.get("nome"), 60), "escrita": bool(p.get("escrita")), "existe": bool(p.get("existe")),
                                "sandbox_codex": txt(p.get("sandbox_codex"), 30), "ferramentas_claude": txt(p.get("ferramentas_claude"), 200),
                                "fonte": "declarado pela ponte (ponte.json)"} for p in d["escopo_pastas"][:40] if isinstance(p, dict)]
        rtt = d.get("rtt_ms")
        if isinstance(rtt, (int, float)) and 0 <= rtt < 600_000:
            E.latencias.append({"t": agora, "comp": "ponte→central", "ms": round(float(rtt), 1), "ponte": pid})
            E.latencias = E.latencias[-LAT_MAX:]
            E.sujo.add("latencias.json")
        ob = d.get("openbot")
        if isinstance(ob, dict) and isinstance(ob.get("agentes"), list):
            E.openbot_agentes = {"visto": agora, "lido_em": ob.get("lido_em") if isinstance(ob.get("lido_em"), int) else None,
                                 "agentes": [limpar_openbot(a) for a in ob["agentes"][:20] if isinstance(a, dict)]}
        for lg in (d.get("logs") or [])[:100]:
            if isinstance(lg, dict):
                registrar(lg.get("nivel") if lg.get("nivel") in ("debug", "info", "aviso", "erro") else "info", "ponte",
                          txt(lg.get("ev"), 40) or "log", txt(lg.get("msg"), 300), tarefa=lg.get("tarefa"),
                          agente=lg.get("agente"), ponte=pid)
        est = d.get("estado")
        if isinstance(est, dict):
            if est.get("grafo") is None and E.ponte.get("estado"):
                est["grafo"] = E.ponte["estado"].get("grafo")  # ponte só manda o grafo quando muda
            E.ponte["estado"] = est
        for n in (d.get("notas") or [])[:60]:
            if isinstance(n, dict) and n.get("id"):
                cam = arq_nota(str(n["id"]))
                mod_ant = None
                try:
                    with open(cam, encoding="utf-8") as f:
                        mod_ant = json.load(f).get("mod")
                except Exception:
                    pass
                if mod_ant is not None and mod_ant != n.get("mod"):
                    # alteração real observada entre duas sincronizações (não reconstrói o passado)
                    E.notas_eventos.append({"t": agora, "nota": str(n["id"])[:300], "mod_pc": n.get("mod"), "tipo": "alterada"})
                elif mod_ant is None:
                    E.notas_eventos.append({"t": agora, "nota": str(n["id"])[:300], "mod_pc": n.get("mod"), "tipo": "primeira_observacao"})
                E.notas_eventos = E.notas_eventos[-NOTAS_EV_MAX:]
                E.sujo.add("notas_eventos.json")
                with open(cam, "w", encoding="utf-8") as f:
                    json.dump({"id": n["id"], "mod": n.get("mod", 0), "conteudo": str(n.get("conteudo") or "")[:400_000]}, f, ensure_ascii=False)
        por_id = {t["id"]: t for t in E.tarefas}
        for up in d.get("tarefas") or []:
            t = por_id.get(up.get("id"))
            if not t:
                continue
            dono = t.get("ponte_id")
            if not legada and dono and dono != pid:
                registrar("aviso", "central", "atualizacao_rejeitada", tarefa=t["id"], ponte=pid, dono=dono,
                          msg="atualização de ponte que não é dona da tarefa")
                continue
            if t["status"] in M.FINAIS and up.get("status") not in (None, t["status"]):
                continue  # estado final é definitivo; a Central arbitra
            st_ant = t["status"]
            t.update(limpar_atualizacao(up))
            if (t["status"] == "rodando" and st_ant != "rodando") or (t["status"] in M.FINAIS and st_ant == "fila"):
                evento(t, "inicio_recebido", "ponte", ponte=pid,
                       obs=None if t["status"] == "rodando" else "início e fim informados no mesmo ciclo de sincronização")
            if up.get("primeira_saida") and not any(e["ev"] == "primeira_saida_recebida" for e in t.get("eventos", [])):
                evento(t, "primeira_saida_recebida", "ponte")
            if t["status"] in M.FINAIS and st_ant not in M.FINAIS:
                t["fim_central"] = agora
                evento(t, "fim_recebido", "ponte", status=t["status"], motivo=t.get("motivo"))
            if up.get("nota_status") == "gravada" and not any(e["ev"] == "nota_gravada" for e in t.get("eventos", [])):
                evento(t, "nota_gravada", "ponte", nota=t.get("nota"))
            for u in (up.get("uso") or [])[:20]:
                if not isinstance(u, dict) or not u.get("chamada_id"):
                    continue
                chave = (t["id"], str(u["chamada_id"])[:80])
                reg = {"t": agora, "tarefa": t["id"], "tentativa": t.get("tentativa", 1), "agente": t["agente"],
                       "projeto": t.get("projeto"), "trace_id": t.get("trace_id"), "chamada_id": chave[1],
                       "status_tarefa": t["status"]}
                for k in ("provedor", "modelo", "fonte", "custo_fonte"):
                    if u.get(k) is not None:
                        reg[k] = txt(u[k], 80)
                for k in ("entrada", "saida", "cache_leitura", "cache_criacao"):
                    v = u.get(k)
                    reg[k] = int(v) if isinstance(v, (int, float)) and v >= 0 else None
                reg["entrada_inclui_cache"] = bool(u.get("entrada_inclui_cache"))
                reg["custo_usd"] = float(u["custo_usd"]) if isinstance(u.get("custo_usd"), (int, float)) else None
                reg["final"] = bool(u.get("final"))
                existente = next((x for x in E.uso if (x["tarefa"], x["chamada_id"]) == chave), None)
                if existente:
                    if existente.get("final") and not reg["final"]:
                        continue  # parcial atrasado não sobrescreve o total final (reconciliação)
                    reg["t"] = existente["t"]
                    existente.update(reg)
                else:
                    E.uso.append(reg)
                E.uso = E.uso[-USO_MAX:]
                E.sujo.add("uso.json")
            if t["status"] not in ("fila", "rodando"):
                t.pop("cancelar", None)
                t.pop("lease_ate", None)
            mudou_tarefas = True
        for feito in d.get("ops") or []:
            for op in E.ops:
                if op["id"] == feito.get("id") and op["status"] in ("pendente", "entregue"):
                    if not legada and op.get("ponte_id") and op["ponte_id"] != pid:
                        continue
                    op["status"] = "conflito" if feito.get("conflito") else ("ok" if feito.get("ok") else "erro")
                    op["msg"] = txt(feito.get("msg"), 300)
                    op["concluido"] = agora
                    if feito.get("arquivo"):
                        op["arquivo"] = txt(feito.get("arquivo"), 300)
                    op.pop("conteudo", None)
                    registrar("info" if op["status"] == "ok" else "aviso", "ponte", "nota_" + op["status"], op=op["id"],
                              tarefa=op.get("tarefa"))
        # ---- entrega com responsável único por tentativa (lease)
        entregar = []
        for t in E.tarefas:
            if t["status"] != "fila":
                continue
            dono, lease = t.get("ponte_id"), t.get("lease_ate", 0)
            if legada or not dono or dono == pid or lease < agora:
                if not legada:
                    if dono and dono != pid:
                        evento(t, "lease_expirado", "central", dono_anterior=dono)
                    t["ponte_id"], t["lease_ate"] = pid, agora + LEASE_S * 1000
                if not any(e["ev"] == "entregue_ponte" for e in t.get("eventos", [])):
                    evento(t, "entregue_ponte", "central", ponte=pid)
                    mudou_tarefas = True
                entregar.append(t)
        rodando_meus = []
        for t in E.tarefas:
            if t["status"] != "rodando":
                continue
            dono = t.get("ponte_id")
            if legada or not dono or dono == pid:
                rodando_meus.append(t["id"])
                if not legada:
                    t["lease_ate"] = agora + LEASE_S * 1000
            elif agora - E.pontes.get(dono, {}).get("visto", 0) > PONTE_PERDIDA_S * 1000:
                t.update({"status": "falhou", "motivo": "ponte_perdida", "fim_central": agora,
                          "resultado": (t.get("resultado") or "") + "\n[A ponte responsável parou de responder.]"})
                evento(t, "fim_recebido", "central", status="falhou", motivo="ponte_perdida")
                mudou_tarefas = True
        ops_entregar = []
        for op in E.ops:
            if op["status"] not in ("pendente", "entregue"):
                continue
            dono, lease = op.get("ponte_id"), op.get("lease_ate", 0)
            if legada or not dono or dono == pid or lease < agora:
                if not legada:
                    op["ponte_id"], op["lease_ate"] = pid, agora + LEASE_S * 1000
                op["status"] = "entregue" if not legada else op["status"]
                ops_entregar.append(op)
        if d.get("ops") or ops_entregar:
            gravar("ops.json", E.ops)
        if mudou_tarefas:
            gravar("tarefas.json", E.tarefas)
            E.revisao += 1
        if d.get("salvar"):
            gravar("ponte.json", dict({k: E.ponte[k] for k in ("visto", "executores", "pastas", "estado")},
                                      escopo_pastas=E.escopo_pastas))
        return {
            "agentes": E.agentes,
            "tarefas": entregar,
            "rodando": rodando_meus,
            "cancelar": [t["id"] for t in E.tarefas if t.get("cancelar") and (legada or t.get("ponte_id") in (None, pid))],
            "ops": ops_entregar,
            "quer_grafo": not (E.ponte.get("estado") or {}).get("grafo"),
            "agora": agora,
        }


def main():
    ap = argparse.ArgumentParser(description="Central do Escritório dos agentes")
    ap.add_argument("--porta", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0", help="127.0.0.1 = só túnel; 0.0.0.0 = túnel + rede local")
    ap.add_argument("--nova-senha", action="store_true", help="gera/define nova senha de acesso e sai")
    ap.add_argument("--mostrar-chave", action="store_true", help="mostra a chave da ponte e sai")
    ap.add_argument("--novo-dispositivo", metavar="NOME",
                    help="cria um token de aparelho (para quem já tem acesso ao servidor) e o escreve na saída padrão")
    ap.add_argument("--revogar-dispositivo", metavar="ID", help="revoga um aparelho pelo id")
    a = ap.parse_args()
    os.makedirs(NOTAS, exist_ok=True)
    if not os.path.exists(CONFIG):
        _, senha = criar_config()
        print("\n=== PRIMEIRA EXECUÇÃO: guarde estes dados (não aparecem de novo) ===")
        print("Senha do painel :", senha)
        print("Chave da ponte  :", ler("config.json", {})["chave_ponte"])
        print("====================================================================\n")
    carregar()
    if a.nova_senha:
        senha = secrets.token_urlsafe(12)
        E.cfg["sal"] = secrets.token_hex(16)
        E.cfg["senha_hash"] = hash_senha(senha, E.cfg["sal"])
        E.cfg["segredo"] = secrets.token_hex(32)  # derruba sessões antigas
        gravar("config.json", E.cfg)
        print("Nova senha do painel:", senha)
        return
    if a.mostrar_chave:
        print(E.cfg["chave_ponte"])
        return
    if a.novo_dispositivo:
        tok = secrets.token_urlsafe(32)
        disp = {"id": novo_id("dv"), "nome": txt(a.novo_dispositivo, 60), "token_hash": hash_token(tok), "criado": agora_ms(),
                "expira": int(time.time()) + DISPOSITIVO_S, "visto": 0, "escopos": list(ESCOPOS_PADRAO), "projetos": "*",
                "via": "cli"}
        E.dispositivos.append(disp)
        gravar("dispositivos.json", E.dispositivos)
        print(tok)
        print("dispositivo:", disp["id"], file=sys.stderr)
        return
    if a.revogar_dispositivo:
        n = 0
        for x in E.dispositivos:
            if x["id"] == a.revogar_dispositivo and not x.get("revogado"):
                x["revogado"] = agora_ms()
                n += 1
        gravar("dispositivos.json", E.dispositivos)
        print("revogados:", n)
        return
    try:
        srv = ThreadingHTTPServer((a.host, a.porta), H)
    except OSError as e:
        print("Não consegui abrir a porta %d: %s" % (a.porta, e))
        sys.exit(1)
    threading.Thread(target=gravador, daemon=True).start()
    registrar("info", "central", "central_iniciada", versao=VERSAO_BACKEND, pid=os.getpid())
    print("Central rodando em http://%s:%d" % (a.host, a.porta), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
