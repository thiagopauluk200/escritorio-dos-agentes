#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Escritorio dos agentes - painel local.

Le (somente leitura) os registros de sessao do Codex e do Claude Code e as
notas do vault do Obsidian, e serve uma pagina em http://127.0.0.1:8765
com os agentes trabalhando e a rede de conhecimento.

Nao envia dados do vault para a internet. NetworkX calcula as metricas da rede; o posicionamento em camadas e feito em Python de forma leve e deterministica.
"""
import argparse
import glob
import json
import os
import re
import socket
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import networkx as nx
except ImportError:  # sem o extra, o painel continua disponível com métricas locais
    nx = None

AQUI = os.path.dirname(os.path.abspath(__file__))
TAIL_BYTES = 600 * 1024
JANELA_SESSOES_H = 24          # sessoes mexidas nas ultimas N horas aparecem
MAX_POR_SALA = 6
TRABALHANDO_MAX_S = 15 * 60    # turno aberto, mas sem escrever ha mais que isso = parado
USO_MEMORIA_S = 120            # "consultando a memoria" fica aceso por N segundos


# ----------------------------------------------------------------- utilidades
def iso_ms(s):
    """'2026-11-14T21:16:50.110Z' -> epoch ms (ou None)."""
    if not isinstance(s, str):
        return None
    try:
        return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
    except Exception:
        return None


def curto(txt, n=90):
    txt = re.sub(r"\s+", " ", str(txt or "")).strip()
    return txt if len(txt) <= n else txt[: n - 1] + "…"


def ler_cauda(caminho):
    """Ultimas linhas JSON de um .jsonl (le so o final do arquivo)."""
    linhas = []
    try:
        tam = os.path.getsize(caminho)
        with open(caminho, "rb") as f:
            inicio = max(0, tam - TAIL_BYTES)
            f.seek(inicio)
            bruto = f.read().decode("utf-8", "ignore")
    except OSError:
        return linhas
    partes = bruto.split("\n")
    if inicio > 0:
        partes = partes[1:]
    for p in partes:
        p = p.strip()
        if not p or p[0] != "{":
            continue
        try:
            linhas.append(json.loads(p))
        except Exception:
            pass
    return linhas


_cache = {}


def com_cache(chave, assinatura, fn):
    item = _cache.get(chave)
    if item and item[0] == assinatura:
        return item[1]
    valor = fn()
    _cache[chave] = (assinatura, valor)
    return valor


# ---------------------------------------------------------------------- Codex
def codex_titulos(codex_home):
    titulos = {}
    try:
        with open(os.path.join(codex_home, "session_index.jsonl"), encoding="utf-8") as f:
            for l in f:
                try:
                    o = json.loads(l)
                    titulos[o["id"]] = o.get("thread_name") or ""
                except Exception:
                    pass
    except OSError:
        pass
    return titulos


def codex_atividade(o):
    """(rotulo, detalhe, usa_memoria) para um registro do rollout, ou None."""
    t = o.get("type")
    p = o.get("payload") if isinstance(o.get("payload"), dict) else {}
    pt = p.get("type")
    if t == "response_item":
        if pt == "reasoning":
            return ("pensando", "", False)
        if pt in ("custom_tool_call", "function_call", "local_shell_call", "mcp_tool_call"):
            nome = str(p.get("name") or "")
            entrada = p.get("input") or p.get("arguments") or ""
            if not isinstance(entrada, str):
                entrada = json.dumps(entrada, ensure_ascii=False)
            alvo = nome + " " + entrada[:4000]
            m = re.search(r"tools\.([A-Za-z0-9_]+)", entrada)
            ferramenta = m.group(1) if m else nome
            if "obsidian" in alvo.lower():
                return ("consultando a memória", ferramenta, True)
            if ferramenta in ("exec_command", "shell", "local_shell", "exec"):
                mc = re.search(r"cmd\s*:\s*\"((?:[^\"\\]|\\.)*)", entrada)
                return ("rodando comando", curto(mc.group(1), 60) if mc else "", False)
            if "patch" in ferramenta or "write" in ferramenta or "edit" in ferramenta:
                return ("editando arquivos", ferramenta, False)
            if "search" in ferramenta or "browser" in ferramenta or "open_page" in ferramenta:
                return ("navegando / pesquisando", ferramenta, False)
            return ("usando ferramenta", ferramenta, False)
        if pt in ("custom_tool_call_output", "function_call_output"):
            return ("analisando resultado", "", False)
        if pt == "message" and p.get("role") == "assistant":
            return ("escrevendo resposta", "", False)
        return None
    if t == "event_msg":
        if pt == "task_started":
            return ("começando a tarefa", "", False)
        if pt == "item_completed":
            it = p.get("item") if isinstance(p.get("item"), dict) else {}
            k = it.get("type")
            if k == "Reasoning":
                return ("pensando", "", False)
            if k == "CommandExecution":
                return ("rodando comando", "", False)
            if k == "Extension":
                tipo = str(it.get("kind") or "")
                if "search" in tipo:
                    return ("pesquisando na web", curto(it.get("query"), 60), False)
                return ("usando extensão", tipo, False)
            if k == "AgentMessage":
                return ("escrevendo resposta", "", False)
            if k == "UserMessage":
                return ("lendo o pedido", "", False)
    return None


def codex_sessao(caminho, titulos, agora):
    st = os.stat(caminho)
    mtime = int(st.st_mtime * 1000)

    def analisar():
        linhas = ler_cauda(caminho)
        em_turno = False
        ativ = None
        memoria_ts = 0
        eventos = []
        pedido = ""
        for o in linhas:
            ts = iso_ms(o.get("timestamp")) or mtime
            p = o.get("payload") if isinstance(o.get("payload"), dict) else {}
            if o.get("type") == "event_msg":
                if p.get("type") == "task_started":
                    em_turno = True
                elif p.get("type") in ("task_complete", "turn_aborted", "task_aborted", "error"):
                    em_turno = False
                    eventos.append((ts, "terminou a tarefa" if p.get("type") == "task_complete" else "tarefa interrompida"))
                elif p.get("type") == "item_completed":
                    it = p.get("item") if isinstance(p.get("item"), dict) else {}
                    if it.get("type") == "UserMessage":
                        txt = " ".join(c.get("text", "") for c in it.get("content", []) if isinstance(c, dict))
                        pedido = curto(txt, 110)
                        eventos.append((ts, "recebeu um pedido: " + curto(txt, 70)))
            a = codex_atividade(o)
            if a:
                ativ = a
                if a[2]:
                    memoria_ts = ts
                    eventos.append((ts, "consultou a memória no Obsidian" + (" (" + a[1] + ")" if a[1] else "")))
        return em_turno, ativ, memoria_ts, eventos[-6:], pedido

    em_turno, ativ, memoria_ts, eventos, pedido = com_cache(("codex", caminho), (mtime, st.st_size), analisar)
    sid = os.path.basename(caminho)[:-len(".jsonl")][-36:]
    parado_ha = (agora - mtime) / 1000
    trabalhando = em_turno and parado_ha < TRABALHANDO_MAX_S
    return {
        "id": "codex:" + sid,
        "tipo": "codex",
        "titulo": titulos.get(sid) or "Sessão do Codex",
        "status": "trabalhando" if trabalhando else ("ocioso" if parado_ha < 3600 else "dormindo"),
        "atividade": (ativ[0] if (ativ and trabalhando) else ""),
        "detalhe": (ativ[1] if (ativ and trabalhando) else ""),
        "memoria": bool(memoria_ts and (agora - memoria_ts) / 1000 < USO_MEMORIA_S),
        "pedido": pedido,
        "atualizado": mtime,
        "_eventos": eventos,
    }


def codex_sessoes(codex_home, agora):
    titulos = codex_titulos(codex_home)
    achados = []
    limite = time.time() - JANELA_SESSOES_H * 3600
    for c in glob.glob(os.path.join(codex_home, "sessions", "*", "*", "*", "rollout-*.jsonl")):
        try:
            if os.path.getmtime(c) >= limite:
                achados.append(c)
        except OSError:
            pass
    achados.sort(key=os.path.getmtime, reverse=True)
    out = []
    for c in achados[:MAX_POR_SALA]:
        try:
            out.append(codex_sessao(c, titulos, agora))
        except Exception:
            pass
    return out


# ---------------------------------------------------------------- Claude Code
CLAUDE_FERRAMENTAS = {
    "Bash": "rodando comando", "PowerShell": "rodando comando",
    "Read": "lendo arquivo", "Edit": "editando arquivo", "Write": "escrevendo arquivo",
    "MultiEdit": "editando arquivo", "NotebookEdit": "editando notebook",
    "Grep": "procurando no código", "Glob": "procurando arquivos",
    "WebSearch": "pesquisando na web", "WebFetch": "lendo página da web",
    "Task": "delegando a um subagente", "Agent": "delegando a um subagente",
    "TodoWrite": "organizando tarefas", "TaskCreate": "organizando tarefas",
    "TaskUpdate": "organizando tarefas",
}


def claude_sessao(caminho, agora):
    st = os.stat(caminho)
    mtime = int(st.st_mtime * 1000)

    def analisar():
        linhas = ler_cauda(caminho)
        titulo = ""
        ativ = None
        aguardando_usuario = False
        memoria_ts = 0
        eventos = []
        pedido = ""
        for o in linhas:
            if o.get("isSidechain"):
                continue
            t = o.get("type")
            ts = iso_ms(o.get("timestamp")) or mtime
            if t == "summary" and o.get("summary"):
                titulo = o["summary"]
            for chave in ("aiTitle", "customTitle", "title"):
                if isinstance(o.get(chave), str) and o.get(chave):
                    titulo = o[chave]
            msg = o.get("message") if isinstance(o.get("message"), dict) else {}
            cont = msg.get("content")
            if t == "user":
                if isinstance(cont, str):
                    blocos = [{"type": "text", "text": cont}]
                else:
                    blocos = cont if isinstance(cont, list) else []
                for b in blocos:
                    if not isinstance(b, dict):
                        continue
                    if b.get("type") == "tool_result":
                        ativ = ("analisando resultado", "", False)
                        aguardando_usuario = False
                    elif b.get("type") == "text" and not o.get("isMeta"):
                        txt = b.get("text", "")
                        if txt.lstrip().startswith("<"):
                            continue
                        pedido = curto(txt, 110)
                        ativ = ("lendo o pedido", "", False)
                        aguardando_usuario = False
                        eventos.append((ts, "recebeu um pedido: " + curto(txt, 70)))
            elif t == "assistant":
                blocos = cont if isinstance(cont, list) else []
                teve_ferramenta = False
                for b in blocos:
                    if not isinstance(b, dict):
                        continue
                    if b.get("type") == "thinking":
                        ativ = ("pensando", "", False)
                    elif b.get("type") == "text":
                        ativ = ("escrevendo resposta", "", False)
                    elif b.get("type") == "tool_use":
                        teve_ferramenta = True
                        nome = str(b.get("name") or "")
                        if "obsidian" in nome.lower():
                            ativ = ("consultando a memória", nome.split("__")[-1], True)
                            memoria_ts = ts
                            eventos.append((ts, "consultou a memória no Obsidian (" + nome.split("__")[-1] + ")"))
                        elif nome.startswith("mcp__"):
                            ativ = ("usando ferramenta", nome.split("__")[-1], False)
                        else:
                            ativ = (CLAUDE_FERRAMENTAS.get(nome, "usando ferramenta"), nome, False)
                fim = msg.get("stop_reason")
                if fim == "end_turn":
                    aguardando_usuario = True
                    eventos.append((ts, "terminou a tarefa"))
                elif teve_ferramenta:
                    aguardando_usuario = False
        return titulo, ativ, aguardando_usuario, memoria_ts, eventos[-6:], pedido

    titulo, ativ, aguardando, memoria_ts, eventos, pedido = com_cache(("claude", caminho), (mtime, st.st_size), analisar)
    parado_ha = (agora - mtime) / 1000
    trabalhando = (parado_ha < 20) or (not aguardando and ativ is not None and parado_ha < TRABALHANDO_MAX_S)
    projeto = os.path.basename(os.path.dirname(caminho))
    projeto = re.split(r"-+", projeto.strip("-"))[-1] if projeto else ""
    return {
        "id": "claude:" + os.path.basename(caminho)[:-len(".jsonl")],
        "tipo": "claude",
        "titulo": curto(titulo or pedido or ("Projeto " + projeto if projeto else "Sessão do Claude Code"), 60),
        "status": "trabalhando" if trabalhando else ("ocioso" if parado_ha < 3600 else "dormindo"),
        "atividade": (ativ[0] if (ativ and trabalhando) else ""),
        "detalhe": (ativ[1] if (ativ and trabalhando) else ""),
        "memoria": bool(memoria_ts and (agora - memoria_ts) / 1000 < USO_MEMORIA_S),
        "pedido": pedido,
        "atualizado": mtime,
        "_eventos": eventos,
    }


def claude_sessoes(claude_home, agora):
    achados = []
    limite = time.time() - JANELA_SESSOES_H * 3600
    for c in glob.glob(os.path.join(claude_home, "projects", "*", "*.jsonl")):
        try:
            if os.path.getmtime(c) >= limite:
                achados.append(c)
        except OSError:
            pass
    achados.sort(key=os.path.getmtime, reverse=True)
    out = []
    for c in achados[:MAX_POR_SALA]:
        try:
            out.append(claude_sessao(c, agora))
        except Exception:
            pass
    return out


# ---------------------------------------------------------------------- Vault
RE_LINK = re.compile(r"\[\[([^\]\|#\^]+)(?:[#\^][^\]\|]*)?(?:\|[^\]]*)?\]\]")
RE_ORIGEM = re.compile(r"^(?:origem|fonte|source|agente)\s*:\s*[\"']?([^\"'\n]+)", re.I | re.M)
AGENTES = {"codex": "Codex", "claude": "Claude Code", "chatgpt": "ChatGPT"}


def nota_info(caminho):
    try:
        with open(caminho, encoding="utf-8", errors="ignore") as f:
            txt = f.read(200_000)
    except OSError:
        return [], ""
    links = list({m.strip() for m in RE_LINK.findall(txt)})
    origem = ""
    if txt.startswith("---"):
        fim = txt.find("\n---", 3)
        if fim > 0:
            m = RE_ORIGEM.search(txt[:fim])
            if m:
                v = m.group(1).lower()
                if "codex" in v:
                    origem = "codex"
                elif "claude" in v:
                    origem = "claude"
                elif "chatgpt" in v or "gpt" in v:
                    origem = "chatgpt"
    return links, origem


def grafo(vault):
    nos, arestas, por_nome = [], [], {}
    notas = []
    pastas = set()
    for raiz, dirs, arqs in os.walk(vault):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for a in arqs:
            if not a.lower().endswith(".md"):
                continue
            cam = os.path.join(raiz, a)
            rel = os.path.relpath(cam, vault).replace("\\", "/")
            try:
                mt = int(os.path.getmtime(cam) * 1000)
            except OSError:
                continue
            notas.append((rel, cam, mt))
            if len(notas) >= 4000:
                break
    try:
        for d in sorted(os.listdir(vault)):
            if os.path.isdir(os.path.join(vault, d)) and not d.startswith("."):
                pastas.add(d)
    except OSError:
        pass

    nos.append({"id": "vault", "rotulo": "Memória", "grupo": "vault", "tipo": "centro"})
    for p in sorted(pastas):
        nos.append({"id": "pasta:" + p, "rotulo": p, "grupo": p, "tipo": "pasta"})
        arestas.append(["vault", "pasta:" + p])
    for k, rot in AGENTES.items():
        nos.append({"id": "agente:" + k, "rotulo": rot, "grupo": k, "tipo": "agente"})
        arestas.append(["agente:" + k, "vault"])

    infos = {}
    for rel, cam, mt in notas:
        nome = re.sub(r"(\.md)+$", "", os.path.basename(rel), flags=re.I)
        topo = rel.split("/")[0] if "/" in rel else ""
        nid = "nota:" + rel
        por_nome.setdefault(nome.lower(), nid)
        por_nome.setdefault(re.sub(r"(\.md)+$", "", rel, flags=re.I).lower(), nid)
        infos[nid] = com_cache(("nota", cam), mt, lambda c=cam: nota_info(c))
        nos.append({"id": nid, "rotulo": nome, "grupo": topo or "vault", "tipo": "nota", "mod": mt})
        arestas.append(["pasta:" + topo if topo else "vault", nid])
    for nid, (links, origem) in infos.items():
        for l in links:
            alvo = por_nome.get(l.lower()) or por_nome.get(l.lower().split("/")[-1])
            if alvo and alvo != nid:
                arestas.append([nid, alvo])
        if origem:
            arestas.append(["agente:" + origem, nid])
    return posicionar_grafo(nos, arestas, len(notas))


def posicionar_grafo(nos, arestas, total_notas):
    """Calcula métricas do grafo e coordenadas em camadas no servidor Python."""
    ordem = {"agente": 0, "centro": 1, "pasta": 2, "nota": 3}
    x_por_camada = {0: -330.0, 1: -150.0, 2: 35.0, 3: 235.0}
    ids = {n["id"] for n in nos}
    pares = {tuple(sorted((a, b))) for a, b in arestas if a in ids and b in ids and a != b}
    conexoes = sorted(pares)

    if nx is not None:
        rede = nx.Graph()
        rede.add_nodes_from(ids)
        rede.add_edges_from(conexoes)
        graus = dict(rede.degree())
        componentes = nx.number_connected_components(rede) if ids else 0
    else:
        graus = {nid: 0 for nid in ids}
        adj = {nid: set() for nid in ids}
        for a, b in conexoes:
            adj[a].add(b); adj[b].add(a)
            graus[a] += 1; graus[b] += 1
        componentes, vistos = 0, set()
        for nid in ids:
            if nid in vistos:
                continue
            componentes += 1
            pilha = [nid]
            while pilha:
                atual = pilha.pop()
                if atual in vistos:
                    continue
                vistos.add(atual); pilha.extend(adj[atual] - vistos)

    grupos_pasta = {}
    for n in nos:
        if n["tipo"] == "nota":
            grupos_pasta.setdefault(n.get("grupo") or "vault", []).append(n)
    pastas = [n for n in nos if n["tipo"] == "pasta"]
    faixa = 440.0
    passo_pasta = faixa / max(1, len(pastas))
    y_pasta = {n["grupo"]: (i - (len(pastas) - 1) / 2) * passo_pasta
               for i, n in enumerate(pastas)}
    if not pastas and grupos_pasta:
        y_pasta["vault"] = 0.0
    indice_nota = {n["id"]: (i, len(lista))
                   for lista in grupos_pasta.values() for i, n in enumerate(lista)}
    agentes = [n for n in nos if n["tipo"] == "agente"]
    y_agente = {n["id"]: (i - (len(agentes) - 1) / 2) * 90.0
                for i, n in enumerate(agentes)}

    for n in nos:
        camada = ordem.get(n["tipo"], 3)
        n["camada"] = camada
        n["conexoes"] = graus.get(n["id"], 0)
        n["raio"] = (13.0 if n["tipo"] == "centro" else
                     10.0 if n["tipo"] == "agente" else
                     8.5 if n["tipo"] == "pasta" else
                     round(min(8.0, 3.0 + n["conexoes"] ** 0.5), 2))
        if n["tipo"] == "centro":
            x, y = x_por_camada[camada], 0.0
        elif n["tipo"] == "agente":
            x, y = x_por_camada[camada], y_agente[n["id"]]
        elif n["tipo"] == "pasta":
            x, y = x_por_camada[camada], y_pasta.get(n["grupo"], 0.0)
        else:
            lista = grupos_pasta.get(n.get("grupo") or "vault", [n])
            pos, total = indice_nota.get(n["id"], (0, 1))
            altura = min(380.0, max(28.0, passo_pasta * 0.82))
            y = y_pasta.get(n.get("grupo") or "vault", 0.0) + ((pos + 0.5) / total - 0.5) * altura
            x = x_por_camada[camada] + (pos % 5) * 7.0
        n["x"], n["y"] = round(x, 2), round(y, 2)

    camadas = [
        {"rotulo": "AGENTES", "x": x_por_camada[0]},
        {"rotulo": "MEMÓRIA", "x": x_por_camada[1]},
        {"rotulo": "PASTAS", "x": x_por_camada[2]},
        {"rotulo": "NOTAS", "x": x_por_camada[3] + 14.0},
    ]
    media = round(sum(graus.values()) / max(1, len(graus)), 1)
    return {"nos": nos, "arestas": conexoes, "total_notas": total_notas,
            "camadas": camadas,
            "metricas": {"nos": len(ids), "ligacoes": len(conexoes),
                         "componentes": componentes, "media_conexoes": media}}


def mcp_info(vault):
    porta, ativo = 27123, False
    try:
        with open(os.path.join(vault, ".obsidian", "plugins", "mcp-server", "data.json"), encoding="utf-8") as f:
            porta = int(json.load(f).get("settings", {}).get("port", porta))
    except Exception:
        pass
    try:
        with socket.create_connection(("127.0.0.1", porta), timeout=0.4):
            ativo = True
    except OSError:
        pass
    return {"porta": porta, "ativo": ativo}


def chatgpt_info(vault):
    pasta = os.path.join(vault, "05-Fontes", "ChatGPT")
    n, ultimo = 0, 0
    try:
        for a in os.listdir(pasta):
            if a.lower().endswith(".md") and not a.startswith("_"):
                n += 1
                ultimo = max(ultimo, int(os.path.getmtime(os.path.join(pasta, a)) * 1000))
    except OSError:
        pass
    return {"conversas": n, "ultimo_import": ultimo}


# --------------------------------------------------------------------- Estado
def estado(cfg):
    agora = int(time.time() * 1000)
    codex = codex_sessoes(cfg.codex, agora) if os.path.isdir(cfg.codex) else []
    claude = claude_sessoes(cfg.claude, agora) if os.path.isdir(cfg.claude) else []
    feed = []
    nomes = {"codex": "Codex", "claude": "Claude Code"}
    for a in codex + claude:
        for ts, txt in a.pop("_eventos", []):
            feed.append({"t": ts, "agente": nomes[a["tipo"]], "tipo": a["tipo"], "texto": txt, "sessao": a["titulo"]})
    g = grafo(cfg.vault)
    lotes = {}
    for n in g["nos"]:
        if n["tipo"] == "nota" and agora - n["mod"] < 24 * 3600 * 1000:
            lotes.setdefault((n["grupo"], n["mod"] // 60000), []).append(n)
    for (grupo, _), ns in lotes.items():
        t = max(n["mod"] for n in ns)
        texto = "nota atualizada: " + ns[0]["rotulo"] if len(ns) == 1 else "%d notas atualizadas" % len(ns)
        feed.append({"t": t, "agente": "Obsidian", "tipo": "vault", "texto": texto, "sessao": grupo})
    feed.sort(key=lambda e: -e["t"])
    return {
        "agora": agora,
        "vault": os.path.basename(os.path.normpath(cfg.vault)),
        "mcp": mcp_info(cfg.vault),
        "codex": {"instalado": os.path.isdir(cfg.codex), "sessoes": codex},
        "claude": {"instalado": os.path.isdir(cfg.claude), "sessoes": claude},
        "chatgpt": chatgpt_info(cfg.vault),
        "grafo": g,
        "feed": feed[:40],
    }


class Handler(BaseHTTPRequestHandler):
    cfg = None

    def _enviar(self, codigo, tipo, corpo):
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):
        rota = self.path.split("?")[0]
        if rota in ("/", "/index.html"):
            try:
                with open(os.path.join(AQUI, "index.html"), "rb") as f:
                    self._enviar(200, "text/html; charset=utf-8", f.read())
            except OSError:
                self._enviar(500, "text/plain; charset=utf-8", "index.html não encontrado".encode())
        elif rota == "/api/estado":
            try:
                corpo = json.dumps(estado(self.cfg), ensure_ascii=False).encode("utf-8")
                self._enviar(200, "application/json; charset=utf-8", corpo)
            except Exception as e:  # nunca derruba o painel
                self._enviar(500, "application/json; charset=utf-8", json.dumps({"erro": str(e)}).encode())
        else:
            self._enviar(404, "text/plain; charset=utf-8", b"nao encontrado")

    def log_message(self, *a):
        pass


def main():
    casa = os.path.expanduser("~")
    ap = argparse.ArgumentParser(description="Escritório dos agentes")
    ap.add_argument("--vault", default=os.path.join(os.path.dirname(AQUI), "memoria"))
    ap.add_argument("--codex", default=os.environ.get("CODEX_HOME") or os.path.join(casa, ".codex"))
    ap.add_argument("--claude", default=os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(casa, ".claude"))
    ap.add_argument("--porta", type=int, default=8765)
    ap.add_argument("--sem-navegador", action="store_true")
    cfg = ap.parse_args()
    if not os.path.isdir(cfg.vault):
        print("Vault não encontrado em:", cfg.vault)
        print("Use: python server.py --vault \"C:\\caminho\\do\\vault\"")
        sys.exit(1)
    Handler.cfg = cfg
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", cfg.porta), Handler)
    except OSError:
        print("A porta %d já está em uso - o Escritório provavelmente já está aberto." % cfg.porta)
        if not cfg.sem_navegador:
            webbrowser.open("http://127.0.0.1:%d" % cfg.porta)
        sys.exit(0)
    url = "http://127.0.0.1:%d" % cfg.porta
    print("Escritório dos agentes rodando em", url)
    print("  vault :", cfg.vault)
    print("  codex :", cfg.codex, "" if os.path.isdir(cfg.codex) else "(não encontrado)")
    print("  claude:", cfg.claude, "" if os.path.isdir(cfg.claude) else "(não encontrado)")
    print("Deixe esta janela aberta. Feche-a (ou Ctrl+C) para encerrar.")
    if not cfg.sem_navegador:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
