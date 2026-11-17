# -*- coding: utf-8 -*-
"""
Coleta de dados REAIS para a Rede Neural (somente leitura).

- Grafo: lê as notas .md do vault do Obsidian (links [[...]], pastas, tags, frontmatter).
- Agentes: lê de forma incremental os registros de sessão do Claude Code
  (~/.claude/projects/*/*.jsonl) e do Codex (~/.codex/sessions/**/rollout-*.jsonl):
  estado (trabalhando / aguardando tokens / aguardando você / descansando / dormindo),
  tokens consumidos, modelo, limites de uso e cada chamada ao MCP do Obsidian.
- Fluxo: cada chamada MCP vira um evento agente→nota (ou nota→agente) com bytes reais
  de ida e de volta; leituras sucessivas da mesma sessão formam trilhas nota→nota.
"""
import glob
import hashlib
import json
import os
import re
import http.client
import ssl
import uuid
import threading
import time
from collections import deque
from datetime import datetime

try:
    import networkx as nx
except ImportError:
    nx = None

LEITURA_INICIAL_MAX = 24 * 1024 * 1024
JANELA_SESSOES_S = 36 * 3600
MAX_SESSOES = 8


def ms(s):
    if not isinstance(s, str):
        return None
    try:
        return int(datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def curto(txt, n=120):
    txt = re.sub(r"\s+", " ", str(txt or "")).strip()
    return txt if len(txt) <= n else txt[: n - 1] + "…"


def tamanho(obj):
    if obj is None:
        return 0
    if isinstance(obj, (bytes, bytearray)):
        return len(obj)
    if isinstance(obj, str):
        return len(obj.encode("utf-8", "ignore"))
    try:
        return len(json.dumps(obj, ensure_ascii=False).encode("utf-8", "ignore"))
    except (TypeError, ValueError):
        return 0


# ====================================================================== GRAFO
RE_LINK = re.compile(r"(!?)\[\[([^\]\|#\^]+)(?:[#\^][^\]\|]*)?(?:\|[^\]]*)?\]\]")
RE_MDLINK = re.compile(r"\]\(([^)\s]+\.md)\)")
RE_TAG = re.compile(r"(?:^|\s)#([A-Za-zÀ-ÿ0-9_\-/]{2,60})")
RE_FM_ORIGEM = re.compile(r"^(?:origem|fonte|source|agente|autor|author)\s*:\s*[\"']?([^\"'\n]+)", re.I | re.M)
RE_FM_TAGS = re.compile(r"^tags\s*:\s*(.*(?:\n\s+-\s*.+)*)", re.I | re.M)


def _sem_md(nome):
    return re.sub(r"(\.md)+$", "", nome, flags=re.I)


def _frontmatter(txt):
    if txt.startswith("---"):
        fim = txt.find("\n---", 3)
        if fim > 0:
            return txt[3:fim], txt[fim + 4:]
    return "", txt


def _origem(fm):
    m = RE_FM_ORIGEM.search(fm)
    if not m:
        return ""
    v = m.group(1).lower()
    if "codex" in v:
        return "codex"
    if "claude" in v:
        return "claude"
    return ""


def _tags(fm, corpo):
    tags = set()
    m = RE_FM_TAGS.search(fm)
    if m:
        bruto = m.group(1)
        for t in re.split(r"[\n,\[\]]", bruto):
            t = t.strip().lstrip("-").strip().strip("'\"").lstrip("#")
            if t and len(t) < 60:
                tags.add(t.lower())
    sem_codigo = re.sub(r"```.*?```", "", corpo, flags=re.S)
    for t in RE_TAG.findall(sem_codigo):
        if not t.isdigit():
            tags.add(t.lower())
    return sorted(tags)


def metadados_obsidian(raiz):
    """Consulta o mesmo índice de metadados usado pelas ferramentas do MCP."""
    config = os.path.join(raiz, ".obsidian", "plugins", "obsidian-local-rest-api", "data.json")
    try:
        with open(config, encoding="utf-8") as f:
            cfg = json.load(f)
        chave = cfg["apiKey"]
        if not isinstance(chave, str) or not chave:
            return None
        conexoes = []
        crypto = cfg.get("crypto") or {}
        certificado = crypto.get("caCert") or crypto.get("cert")
        if certificado:
            try:
                contexto = ssl.create_default_context(cadata=certificado)
                conexoes.append(http.client.HTTPSConnection("127.0.0.1", int(cfg.get("port", 27124)),
                                                           timeout=2, context=contexto))
            except (ValueError, TypeError, ssl.SSLError):
                pass
        if cfg.get("enableInsecureServer"):
            conexoes.append(http.client.HTTPConnection("127.0.0.1", int(cfg.get("insecurePort", 27123)), timeout=2))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None
    for conn in conexoes:
        dados = _consultar_obsidian(conn, chave)
        if dados is not None:
            return dados
    return None


def _consultar_obsidian(conn, chave):
    consulta = {"if": [True, [{"var": campo} for campo in ("links", "unresolvedLinks", "tags")], False]}
    try:
        conn.request("POST", "/search/", json.dumps(consulta), {
            "Authorization": "Bearer " + chave,
            "Content-Type": "application/vnd.olrapi.jsonlogic+json",
        })
        r = conn.getresponse()
        if r.status != 200:
            return None
        bruto = r.read(16 * 1024 * 1024 + 1)
        if len(bruto) > 16 * 1024 * 1024:
            return None
        return {e["filename"]: e["result"] for e in json.loads(bruto)}
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException):
        return None
    finally:
        conn.close()


class Vault:
    def __init__(self, raiz):
        self.raiz = os.path.abspath(raiz)
        self._cache_nota = {}
        self._grafo = None
        self._assinatura = None
        self._meta = None
        self._proxima_consulta = 0
        self._trava = threading.Lock()

    def caminho_seguro(self, rel):
        """Resolve um caminho relativo garantindo que fique dentro do vault (anti path traversal)."""
        if not isinstance(rel, str) or "\x00" in rel or len(rel) > 500:
            return None
        partes = rel.split("/")
        if any(not p or p.startswith(".") or ":" in p or "\\" in p for p in partes):
            return None
        alvo = os.path.realpath(os.path.join(self.raiz, *partes))
        raiz = os.path.realpath(self.raiz)
        try:
            dentro = os.path.commonpath([alvo, raiz]) == raiz
        except ValueError:
            dentro = False
        if not dentro:
            return None
        partes = os.path.relpath(alvo, raiz).replace("\\", "/").split("/")
        if any(p.startswith(".") for p in partes):
            return None
        return alvo

    def _listar(self):
        notas = []
        for raiz, dirs, arqs in os.walk(self.raiz):
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))
            for a in sorted(arqs):
                if a.lower().endswith(".md"):
                    cam = os.path.join(raiz, a)
                    if not self.caminho_seguro(os.path.relpath(cam, self.raiz).replace("\\", "/")):
                        continue
                    try:
                        st = os.stat(cam)
                    except OSError:
                        continue
                    rel = os.path.relpath(cam, self.raiz).replace("\\", "/")
                    notas.append((rel, cam, st))
        return notas

    def _ler_nota(self, cam, st):
        chave = (st.st_mtime_ns, st.st_size)
        item = self._cache_nota.get(cam)
        if item and item[0] == chave:
            return item[1]
        try:
            with open(cam, encoding="utf-8", errors="ignore") as f:
                txt = f.read(400_000)
        except OSError:
            txt = ""
        fm, corpo = _frontmatter(txt)
        links = []
        for emb, alvo in RE_LINK.findall(txt):
            links.append(alvo.strip())
        links += [l.split("/")[-1] for l in RE_MDLINK.findall(txt)]
        info = {
            "links": links,
            "tags": _tags(fm, corpo),
            "origem": _origem(fm),
            "palavras": len(re.findall(r"\w+", corpo)),
        }
        self._cache_nota[cam] = (chave, info)
        return info

    def grafo(self):
        with self._trava:
            notas = self._listar()
            agora = time.monotonic()
            if agora >= self._proxima_consulta:
                self._meta = metadados_obsidian(self.raiz)
                self._proxima_consulta = agora + 5
            meta = self._meta
            # Um vault diferente ou um índice ainda carregando não deve resolver nossos links.
            if meta is not None and meta.keys() != {r for r, _, _ in notas}:
                meta = None
            assinatura = hashlib.sha1(
                json.dumps([[(r, s.st_mtime_ns, s.st_size) for r, _, s in notas], meta],
                           sort_keys=True, ensure_ascii=False).encode()
            ).hexdigest()
            if self._grafo and assinatura == self._assinatura:
                return self._grafo
            self._grafo = self._montar(notas, assinatura, meta)
            self._assinatura = assinatura
            return self._grafo

    def _montar(self, notas, assinatura, meta):
        nos, arestas = {}, []
        por_nome = {}
        for rel, cam, st in notas:
            nome = _sem_md(os.path.basename(rel))
            por_nome.setdefault(nome.lower(), rel)
            por_nome.setdefault(_sem_md(rel).lower(), rel)

        pastas = set()
        for rel, cam, st in notas:
            partes = rel.split("/")
            for i in range(1, len(partes)):
                pastas.add("/".join(partes[:i]))
        nos["vault"] = {"id": "vault", "tipo": "raiz", "rotulo": os.path.basename(self.raiz) or "vault"}
        for p in sorted(pastas):
            nos["pasta:" + p] = {"id": "pasta:" + p, "tipo": "pasta", "rotulo": p.split("/")[-1], "pasta": p}
            pai = "/".join(p.split("/")[:-1])
            arestas.append(("pasta:" + pai if pai else "vault", "pasta:" + p, "pasta"))

        tags = {}
        for rel, cam, st in notas:
            info = self._ler_nota(cam, st)
            if meta is None:
                links = [(l, por_nome.get(l.lower()) or por_nome.get(l.lower().split("/")[-1]))
                         for l in info["links"]]
                etiquetas = info["tags"]
            else:
                resolvidos, pendentes, etiquetas = (valor or [] for valor in meta[rel])
                links = [(l, l) for l in resolvidos] + [(l, None) for l in pendentes]
            pasta = "/".join(rel.split("/")[:-1])
            nos[rel] = {
                "id": rel, "tipo": "nota", "rotulo": _sem_md(os.path.basename(rel)),
                "pasta": pasta, "topo": rel.split("/")[0] if "/" in rel else "",
                "bytes": st.st_size, "palavras": info["palavras"], "mod": int(st.st_mtime * 1000),
                "tags": etiquetas, "origem": info["origem"],
            }
            arestas.append(("pasta:" + pasta if pasta else "vault", rel, "pasta"))
            vistos = set()
            for l, alvo in links:
                if alvo is None:
                    fid = "fantasma:" + l.lower()
                    if fid not in nos:
                        nos[fid] = {"id": fid, "tipo": "fantasma", "rotulo": l}
                    alvo = fid
                if alvo not in vistos:
                    vistos.add(alvo)
                    arestas.append((rel, alvo, "link"))
            for t in etiquetas:
                t = t.lstrip("#")
                tags.setdefault(t, 0)
                tags[t] += 1
                arestas.append((rel, "tag:" + t, "tag"))
            if info["origem"]:
                arestas.append(("agente:" + info["origem"], rel, "origem"))
        for t, n in tags.items():
            nos["tag:" + t] = {"id": "tag:" + t, "tipo": "tag", "rotulo": "#" + t, "usos": n}
        for k, rot in (("claude", "Claude"), ("codex", "Codex")):
            nos["agente:" + k] = {"id": "agente:" + k, "tipo": "agente", "rotulo": rot}

        # métricas
        grau = {k: 0 for k in nos}
        entrada = {k: 0 for k in nos}
        for a, b, tipo in arestas:
            if a in grau and b in grau:
                grau[a] += 1
                grau[b] += 1
                if tipo == "link":
                    entrada[b] += 1
        links_reais = [(a, b) for a, b, t in arestas if t == "link" and nos.get(b, {}).get("tipo") == "nota"]
        orfas = [k for k, n in nos.items() if n["tipo"] == "nota"
                 and not any(k in (a, b) for a, b in links_reais)]
        centralidade = {}
        componentes = 0
        if nx is not None:
            g = nx.Graph()
            g.add_nodes_from(k for k, n in nos.items() if n["tipo"] == "nota")
            g.add_edges_from(links_reais)
            componentes = nx.number_connected_components(g) if g.number_of_nodes() else 0
            try:
                centralidade = nx.pagerank(nx.DiGraph(links_reais)) if links_reais else {}
            except Exception:
                centralidade = {}
        for k, n in nos.items():
            n["grau"] = grau.get(k, 0)
            n["entrada"] = entrada.get(k, 0)
            if k in centralidade:
                n["rank"] = round(centralidade[k], 5)
        notas_n = [n for n in nos.values() if n["tipo"] == "nota"]
        hubs = sorted(notas_n, key=lambda n: (n["entrada"], n["grau"]), reverse=True)[:6]
        total_bytes = sum(n["bytes"] for n in notas_n)
        n_notas = len(notas_n)
        dens = (2 * len(links_reais)) / (n_notas * (n_notas - 1)) if n_notas > 1 else 0
        return {
            "versao": assinatura[:16],
            "fonte": "obsidian" if meta is not None else "disco",
            "gerado": int(time.time() * 1000),
            "nos": list(nos.values()),
            "arestas": [[a, b, t] for a, b, t in arestas if a in nos and b in nos],
            "metricas": {
                "notas": n_notas, "pastas": len(pastas), "tags": len(tags),
                "fantasmas": sum(1 for n in nos.values() if n["tipo"] == "fantasma"),
                "links": len(links_reais), "orfas": len(orfas),
                "componentes": componentes, "densidade": round(dens, 4),
                "bytes": total_bytes, "palavras": sum(n["palavras"] for n in notas_n),
                "hubs": [{"id": h["id"], "rotulo": h["rotulo"], "entrada": h["entrada"]} for h in hubs],
            },
        }

    def resolver(self, caminho):
        """Converte um caminho citado por um agente no id de nó do grafo."""
        if not caminho:
            return None
        c = str(caminho).replace("\\", "/").strip().strip("/")
        if c.startswith(self.raiz.replace("\\", "/")):
            c = c[len(self.raiz.replace("\\", "/")):].strip("/")
        g = self._grafo
        if not g:
            return None
        ids = {n["id"] for n in g["nos"]}
        if c in ids:
            return c
        if c + ".md" in ids:
            return c + ".md"
        if "pasta:" + c in ids:
            return "pasta:" + c
        base = _sem_md(c.split("/")[-1]).lower()
        for n in g["nos"]:
            if n["tipo"] == "nota" and n["rotulo"].lower() == base:
                return n["id"]
        return None


# ===================================================== SESSÕES DOS AGENTES
OPS = {
    "vault_read": "ler", "vault_read_binary": "ler", "vault_get_document_map": "ler",
    "vault_get_download_url": "ler", "active_file_get_path": "ler", "open_file": "ler",
    "vault_write": "escrever", "vault_append": "escrever", "vault_patch": "escrever",
    "vault_copy": "escrever", "vault_move": "escrever", "vault_get_upload_url": "escrever",
    "vault_delete": "apagar",
    "vault_list": "listar", "tag_list": "listar", "command_list": "listar",
    "search_simple": "buscar", "search_query": "buscar",
    "command_execute": "comando", "events_get_listener_url": "outro",
    "read_note": "ler", "get_note": "ler", "write_note": "escrever", "update_note": "escrever",
    "search_content": "buscar", "search_notes": "buscar", "list_notes": "listar",
    "list_mcp_resources": "listar", "list_mcp_resource_templates": "listar",
}

CLAUDE_ROTULOS = {
    "Bash": "rodando comando", "PowerShell": "rodando comando", "Read": "lendo arquivo",
    "Edit": "editando código", "Write": "escrevendo arquivo", "Grep": "procurando no código",
    "Glob": "procurando arquivos", "WebSearch": "pesquisando na web", "WebFetch": "lendo a web",
    "Agent": "delegando a um subagente", "Task": "delegando a um subagente",
    "Skill": "carregando habilidade",
}


def _caminho_args(args):
    if isinstance(args, dict):
        for k in ("path", "filename", "file", "source", "destination", "target_path", "dir"):
            v = args.get(k)
            if isinstance(v, str) and v:
                return v
    return None


class Sessao:
    """Estado incremental de UM arquivo de sessão."""

    def __init__(self, agente, caminho):
        self.agente = agente
        self.caminho = caminho
        self.offset = 0
        self.resto = b""
        self.id = os.path.basename(caminho)[:-6][-36:]
        self.titulo = ""
        self.pedido = ""
        self.projeto = ""
        self.modelo = ""
        self.ultimo = ""         # pedido | resultado | ferramenta | pensando | texto | fim
        self.ultimo_ts = 0
        self.ferramenta = ""
        self.inicio = 0
        self.tokens = {"entrada": 0, "saida": 0, "cache_leitura": 0, "cache_escrita": 0}
        self.serie = deque(maxlen=600)      # (ts, tokens de saída)
        self.limites = None
        self.limite_ate = 0
        self.eventos = deque(maxlen=400)     # chamadas MCP do Obsidian
        self.pendentes = {}
        self._ids = deque(maxlen=4000)
        self._idset = set()
        self.revisor = False
        self.trava = threading.Lock()

    # ------------------------------------------------------------ leitura
    def atualizar(self):
        try:
            tam = os.path.getsize(self.caminho)
        except OSError:
            return
        if tam < self.offset:            # arquivo truncado: recomeça
            self.__init__(self.agente, self.caminho)
        if tam == self.offset:
            return
        with self.trava:
            inicio = self.offset
            if inicio == 0 and tam > LEITURA_INICIAL_MAX:
                inicio = tam - LEITURA_INICIAL_MAX
            try:
                with open(self.caminho, "rb") as f:
                    f.seek(inicio)
                    bruto = f.read(tam - inicio)
            except OSError:
                return
            primeira = inicio > 0 and self.offset == 0
            self.offset = tam
            dados = self.resto + bruto
            linhas = dados.split(b"\n")
            self.resto = linhas.pop() if not dados.endswith(b"\n") else b""
            if primeira and linhas:
                linhas = linhas[1:]
            for l in linhas:
                l = l.strip()
                if not l or l[:1] != b"{":
                    continue
                try:
                    o = json.loads(l)
                except ValueError:
                    continue
                try:
                    if self.agente == "claude":
                        self._claude(o)
                    else:
                        self._codex(o)
                except Exception:
                    continue

    def _marca(self, ts, estado, ferramenta=""):
        if ts and ts >= self.ultimo_ts:
            self.ultimo_ts = ts
            self.ultimo = estado
            self.ferramenta = ferramenta
        if ts and not self.inicio:
            self.inicio = ts

    def _evento_mcp(self, ts, ferramenta, args, chave, bytes_envio):
        op = OPS.get(ferramenta, "outro")
        ev = {
            "id": uuid.uuid4().hex, "t": ts, "agente": self.agente, "sessao": self.id, "ferramenta": ferramenta, "op": op,
            "caminho": _caminho_args(args), "consulta": None,
            "envio": bytes_envio, "retorno": 0, "ok": None,
        }
        if op == "buscar" and isinstance(args, dict):
            q = args.get("query")
            ev["consulta"] = curto(q if isinstance(q, str) else json.dumps(q, ensure_ascii=False), 80)
        self.eventos.append(ev)
        if chave:
            self.pendentes[chave] = ev
            if len(self.pendentes) > 200:
                self.pendentes.pop(next(iter(self.pendentes)))
        return ev

    def _resultado(self, chave, conteudo, erro=False, ts=None):
        ev = self.pendentes.pop(chave, None)
        if ev is not None:
            ev["retorno"] = tamanho(conteudo)
            ev["ok"] = not erro
            if ts:
                ev["dur"] = max(0, ts - ev["t"])

    # ------------------------------------------------------------- Claude
    def _claude(self, o):
        if o.get("isSidechain"):
            return
        t = o.get("type")
        ts = ms(o.get("timestamp"))
        if o.get("isApiErrorMessage"):
            if o.get("apiErrorStatus") == 429 and ts:
                quota = o.get("quotaLimits") or {}
                self.limite_ate = ms_ou(quota.get("resetsAt"), 0) * 1000
                self._marca(ts, "limite")
            return
        if o.get("cwd") and not self.projeto:
            self.projeto = os.path.basename(str(o["cwd"]).rstrip("\\/"))
        for k in ("aiTitle", "customTitle", "title"):
            if isinstance(o.get(k), str) and o[k]:
                self.titulo = o[k]
        if t == "summary" and o.get("summary"):
            self.titulo = o["summary"]
        msg = o.get("message") if isinstance(o.get("message"), dict) else {}
        cont = msg.get("content")
        if t == "user":
            blocos = [{"type": "text", "text": cont}] if isinstance(cont, str) else (cont if isinstance(cont, list) else [])
            for b in blocos:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "tool_result":
                    self._resultado(b.get("tool_use_id"), b.get("content"), bool(b.get("is_error")), ts)
                    self._marca(ts, "resultado")
                elif b.get("type") == "text" and not o.get("isMeta"):
                    txt = b.get("text", "")
                    if txt.lstrip().startswith("<"):
                        continue
                    self.pedido = curto(txt, 140)
                    self._marca(ts, "pedido")
        elif t == "assistant":
            mid = msg.get("id")
            u = msg.get("usage") if isinstance(msg.get("usage"), dict) else None
            if msg.get("model") and not str(msg["model"]).startswith("<"):
                self.modelo = msg["model"]
            if u and mid and mid not in self._idset:
                if len(self._ids) == self._ids.maxlen:
                    self._idset.discard(self._ids[0])
                self._ids.append(mid)
                self._idset.add(mid)
                self.tokens["entrada"] += int(u.get("input_tokens") or 0)
                self.tokens["saida"] += int(u.get("output_tokens") or 0)
                self.tokens["cache_leitura"] += int(u.get("cache_read_input_tokens") or 0)
                self.tokens["cache_escrita"] += int(u.get("cache_creation_input_tokens") or 0)
                if ts:
                    self.serie.append((ts, int(u.get("output_tokens") or 0)))
            for b in cont if isinstance(cont, list) else []:
                if not isinstance(b, dict):
                    continue
                k = b.get("type")
                if k == "thinking":
                    self._marca(ts, "pensando")
                elif k == "text":
                    self._marca(ts, "texto")
                elif k == "tool_use":
                    nome = str(b.get("name") or "")
                    args = b.get("input")
                    if nome.startswith("mcp__") and "obsidian" in nome.lower():
                        fer = nome.split("__")[-1]
                        self._evento_mcp(ts, fer, args, b.get("id"), tamanho(args))
                        self._marca(ts, "ferramenta", "memória: " + fer)
                    else:
                        rot = CLAUDE_ROTULOS.get(nome) or ("usando " + nome.split("__")[-1])
                        self._marca(ts, "ferramenta", rot)
            if msg.get("stop_reason") == "end_turn":
                self._marca(ts, "fim")

    # -------------------------------------------------------------- Codex
    def _codex(self, o):
        t = o.get("type")
        ts = ms(o.get("timestamp"))
        p = o.get("payload") if isinstance(o.get("payload"), dict) else {}
        pt = p.get("type")
        if t == "session_meta":
            if p.get("cwd"):
                self.projeto = os.path.basename(str(p["cwd"]).rstrip("\\/"))
            return
        if t == "turn_context":
            if p.get("model"):
                self.modelo = p["model"]
            return
        if t == "event_msg":
            if pt == "task_started":
                self._marca(ts, "pedido")
            elif pt in ("task_complete", "turn_aborted", "task_aborted"):
                self._marca(ts, "fim")
            elif pt == "token_count":
                info = p.get("info") if isinstance(p.get("info"), dict) else {}
                tot = info.get("total_token_usage") or {}
                ult = info.get("last_token_usage") or {}
                if tot:
                    cache = int(tot.get("cached_input_tokens") or 0)
                    self.tokens["entrada"] = max(0, int(tot.get("input_tokens") or 0) - cache)
                    self.tokens["cache_leitura"] = cache
                    self.tokens["saida"] = int(tot.get("output_tokens") or 0)
                if ult and ts:
                    self.serie.append((ts, int(ult.get("output_tokens") or 0)))
                rl = p.get("rate_limits")
                if isinstance(rl, dict):
                    lim = {}
                    for k in ("primary", "secondary"):
                        v = rl.get(k)
                        if isinstance(v, dict):
                            lim[k] = {"usado": v.get("used_percent"), "janela_min": v.get("window_minutes"),
                                      "renova": v.get("resets_at")}
                    if lim:
                        self.limites = lim
                        bloqueios = [v["renova"] for v in lim.values()
                                     if isinstance(v["usado"], (int, float)) and v["usado"] >= 100
                                     and isinstance(v["renova"], (int, float))]
                        self.limite_ate = max(bloqueios, default=0) * 1000
            elif pt == "item_completed":
                it = p.get("item") if isinstance(p.get("item"), dict) else {}
                if it.get("type") == "McpToolCall" and "obsidian" in str(it.get("server", "")).lower():
                    args = it.get("arguments")
                    ev = self._evento_mcp(ms_ou(it.get("started_at_ms"), ts), str(it.get("tool")), args, None, tamanho(args))
                    res = it.get("result") if isinstance(it.get("result"), dict) else {}
                    ev["retorno"] = tamanho(res.get("content"))
                    ev["ok"] = not res.get("isError") and it.get("status") != "failed"
                    if it.get("completed_at_ms") and it.get("started_at_ms"):
                        ev["dur"] = int(it["completed_at_ms"]) - int(it["started_at_ms"])
                elif it.get("type") == "UserMessage":
                    txt = " ".join(c.get("text", "") for c in it.get("content", []) if isinstance(c, dict))
                    if "Codex agent history" in txt[:200]:
                        self.revisor = True
                    else:
                        self.pedido = curto(txt, 140)
            return
        if t == "response_item":
            if pt == "reasoning":
                self._marca(ts, "pensando")
            elif pt == "message" and p.get("role") == "assistant":
                self._marca(ts, "texto")
            elif pt == "message" and p.get("role") == "user":
                for c in p.get("content") or []:
                    if isinstance(c, dict) and "Codex agent history" in str(c.get("text", ""))[:200]:
                        self.revisor = True
            elif pt in ("custom_tool_call", "function_call", "local_shell_call"):
                nome = str(p.get("name") or "")
                entrada = p.get("input") if pt == "custom_tool_call" else p.get("arguments")
                entrada = entrada if isinstance(entrada, str) else json.dumps(entrada or "", ensure_ascii=False)
                chamadas = re.findall(r"mcp__obsidian__([a-z_]+)\s*\(\s*(\{.*?\})?\s*\)", entrada, re.S)
                direto = nome.startswith("mcp__obsidian__")
                if direto:
                    chamadas = [(nome.split("__")[-1], entrada)]
                if chamadas:
                    for fer, args_txt in chamadas:
                        args = _args_soltos(args_txt)
                        # Um exec pode agrupar ferramentas heterogêneas. Seu retorno agregado
                        # não pode ser atribuído a uma chamada individual do Obsidian.
                        self._evento_mcp(ts, fer, args, p.get("call_id") if direto else None, tamanho(args_txt))
                    self._marca(ts, "ferramenta", "memória: " + chamadas[-1][0])
                else:
                    rot = "rodando comando" if nome in ("exec", "exec_command", "shell", "local_shell") else "usando " + nome
                    m = re.search(r"tools\.([A-Za-z0-9_]+)", entrada)
                    if m and m.group(1).startswith("web"):
                        rot = "pesquisando na web"
                    elif "apply_patch" in entrada[:200] or nome == "apply_patch":
                        rot = "editando código"
                    self._marca(ts, "ferramenta", rot)
            elif pt in ("custom_tool_call_output", "function_call_output"):
                self._resultado(p.get("call_id"), p.get("output"), False, ts)
                self._marca(ts, "resultado")

    # ------------------------------------------------------------- estado
    def resumo(self, agora):
        idade = (agora - self.ultimo_ts) / 1000 if self.ultimo_ts else 1e9
        u = self.ultimo
        quota = self.limite_ate > agora and (self.agente == "codex" or u == "limite")
        quota_recente = u == "limite" and not self.limite_ate and idade < 5 * 60
        if quota or quota_recente:
            estado = "limite"
        elif u == "fim":
            estado = "aguardando_voce" if idade < 20 * 60 else ("descansando" if idade < 3 * 3600 else "dormindo")
        elif u == "ferramenta":
            estado = "trabalhando" if idade < 15 * 60 else ("descansando" if idade < 3 * 3600 else "dormindo")
        elif u in ("pedido", "resultado", "pensando", "texto"):
            estado = "tokens" if idade < 5 * 60 else ("descansando" if idade < 3 * 3600 else "dormindo")
        elif u == "limite" and idade < 3 * 3600:
            estado = "descansando"
        else:
            estado = "dormindo"
        t5 = agora - 5 * 60 * 1000
        saida_5min = sum(n for ts, n in self.serie if ts >= t5)
        return {
            "id": self.id, "agente": self.agente,
            "modelo": self.modelo,
            "estado": estado, "atividade": self.ferramenta if estado == "trabalhando" else "",
            "ultimo": self.ultimo_ts, "inicio": self.inicio,
            "tokens": dict(self.tokens), "tokens_min": round(saida_5min / 5, 1),
            "limites": self.limites,
            "renova": self.limite_ate if estado == "limite" else None,
        }


def ms_ou(v, padrao):
    try:
        return int(v) if v else padrao
    except (TypeError, ValueError):
        return padrao


def _args_soltos(txt):
    """Extrai chaves simples de um objeto JS/JSON escrito pelo Codex (path:"...", query:"...")."""
    if not txt:
        return {}
    try:
        v = json.loads(txt)
        return v if isinstance(v, dict) else {}
    except ValueError:
        pass
    out = {}
    for k, v in re.findall(r"[\"']?([A-Za-z_]+)[\"']?\s*:\s*\"((?:[^\"\\]|\\.)*)\"", txt):
        try:
            out[k] = json.loads('"' + v + '"')
        except ValueError:
            out[k] = v
    return out


PRIORIDADE = {"trabalhando": 6, "tokens": 5, "limite": 4, "aguardando_voce": 3, "descansando": 2, "dormindo": 1}


class Agentes:
    def __init__(self, claude_home, codex_home):
        self.claude_home = claude_home
        self.codex_home = codex_home
        self.sessoes = {}
        self._lista_ts = 0
        self._arquivos = []
        self._trava = threading.Lock()

    def _descobrir(self):
        if time.time() - self._lista_ts < 4 and self._arquivos:
            return self._arquivos
        limite = time.time() - JANELA_SESSOES_S
        achados = []
        padroes = [("claude", os.path.join(self.claude_home, "projects", "*", "*.jsonl")),
                   ("codex", os.path.join(self.codex_home, "sessions", "*", "*", "*", "rollout-*.jsonl"))]
        for ag, pad in padroes:
            arqs = []
            for c in glob.glob(pad):
                try:
                    mt = os.path.getmtime(c)
                except OSError:
                    continue
                if mt >= limite:
                    arqs.append((mt, c))
            arqs.sort(reverse=True)
            achados += [(ag, c) for _, c in arqs[: MAX_SESSOES * 2]]
        self._arquivos = achados
        self._lista_ts = time.time()
        return achados

    def coletar(self, agora):
        with self._trava:
            arqs = self._descobrir()
            vivos = set()
            for ag, c in arqs:
                s = self.sessoes.get(c)
                if s is None:
                    s = self.sessoes[c] = Sessao(ag, c)
                s.atualizar()
                vivos.add(c)
            for c in list(self.sessoes):
                if c not in vivos:
                    del self.sessoes[c]
            res = {}
            for ag in ("claude", "codex"):
                ss = [s for s in self.sessoes.values() if s.agente == ag and not s.revisor]
                resumos = sorted((s.resumo(agora) for s in ss), key=lambda r: -r["ultimo"])[:MAX_SESSOES]
                principal = max(resumos, key=lambda r: (PRIORIDADE[r["estado"]], r["ultimo"]), default=None)
                tot = {"entrada": 0, "saida": 0, "cache_leitura": 0, "cache_escrita": 0}
                dia = agora - 24 * 3600 * 1000
                for r in resumos:
                    if r["ultimo"] >= dia:
                        for k in tot:
                            tot[k] += r["tokens"][k]
                limites = next((r["limites"] for r in resumos if r["limites"]), None)
                res[ag] = {
                    "instalado": os.path.isdir(self.claude_home if ag == "claude" else self.codex_home),
                    "estado": principal["estado"] if principal else "dormindo",
                    "atividade": principal["atividade"] if principal else "",
                    "sessao": principal,
                    "ativas": sum(1 for r in resumos if r["estado"] in ("trabalhando", "tokens")),
                    "sessoes": resumos,
                    "tokens_24h": tot,
                    "tokens_min": round(sum(r["tokens_min"] for r in resumos), 1),
                    "modelo": (principal or {}).get("modelo", ""),
                    "limites": limites,
                }
            return res

    def eventos(self):
        with self._trava:
            evs = [dict(e) for s in self.sessoes.values() if not s.revisor for e in s.eventos if e["t"]]
        evs.sort(key=lambda e: e["t"] or 0)
        return evs
