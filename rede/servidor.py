#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rede Neural — servidor local (somente leitura) do vault do Obsidian e dos agentes.

    python servidor.py                      # usa ../../memoria como vault, porta 8770
    python servidor.py --vault "C:\\...\\memoria" --porta 8770

Escuta APENAS em 127.0.0.1. O celular acessa pelo cabo USB (adb reverse), o navegador
do PC acessa direto. Veja seguranca.py para o modelo de autenticação.
"""
import argparse
import json
import mimetypes
import os
import re
import secrets
import socket
import ssl
import sys
import threading
import time
import webbrowser
from collections import deque
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)

import coleta  # noqa: E402
from contas import Contas  # noqa: E402
import pareamento  # noqa: E402
import seguranca  # noqa: E402

VERSAO = "3.1.1"
WEB = os.path.join(AQUI, "web")
LIMITE_CORPO = 4096
MAX_NOTA = 96 * 1024

# Padrões que parecem segredos: são mascarados antes de qualquer nota sair do PC.
RE_SEGREDO = [
    re.compile(r"(?i)((?:api[_-]?key|apikey|token|chave|senha|password|secret|segredo|bearer|authorization)"
               r"[\"'\s]*[:=]\s*[\"']?)([A-Za-z0-9_\-\.=+/]{12,})"),
    re.compile(r"\b(sk-[A-Za-z0-9_\-]{16,}|ghp_[A-Za-z0-9]{20,}|xox[bp]-[A-Za-z0-9\-]{10,}|AKIA[0-9A-Z]{16})\b"),
    re.compile(r"(?i)(Bearer\s+)([A-Za-z0-9_\-\.=]{16,})"),
]


def mascarar(txt):
    n = 0
    for r in RE_SEGREDO:
        def troca(m):
            nonlocal n
            n += 1
            if m.lastindex and m.lastindex >= 2:
                return m.group(1) + "•••• (oculto)"
            return "•••• (oculto)"
        txt = r.sub(troca, txt)
    return txt, n


# ================================================================ TRÁFEGO
class Medidor:
    """Bytes reais que entram e saem do servidor, por minuto e por rota/cliente."""

    def __init__(self):
        self.trava = threading.Lock()
        self.minutos = deque(maxlen=24 * 60)    # [minuto, entrada, saida, pedidos]
        self.rotas = {}
        self.clientes = {"app": [0, 0, 0], "navegador": [0, 0, 0]}
        self.inicio = time.time()
        self.total = [0, 0, 0]

    def registrar(self, rota, cliente, entrada, saida):
        m = int(time.time() // 60)
        with self.trava:
            if not self.minutos or self.minutos[-1][0] != m:
                self.minutos.append([m, 0, 0, 0])
            b = self.minutos[-1]
            b[1] += entrada
            b[2] += saida
            b[3] += 1
            r = self.rotas.setdefault(rota, [0, 0, 0])
            r[0] += entrada
            r[1] += saida
            r[2] += 1
            c = self.clientes.setdefault(cliente, [0, 0, 0])
            c[0] += entrada
            c[1] += saida
            c[2] += 1
            self.total[0] += entrada
            self.total[1] += saida
            self.total[2] += 1

    def resumo(self):
        with self.trava:
            agora = int(time.time() // 60)
            ultimo = [b for b in self.minutos if b[0] >= agora - 1]
            return {
                "desde": int(self.inicio * 1000),
                "total": {"entrada": self.total[0], "saida": self.total[1], "pedidos": self.total[2]},
                "por_minuto": [{"m": b[0] * 60000, "entrada": b[1], "saida": b[2], "pedidos": b[3]}
                               for b in self.minutos if b[0] >= agora - 120],
                "taxa_bps": round(sum(b[1] + b[2] for b in ultimo) / 120.0, 1),
                "rotas": {k: {"entrada": v[0], "saida": v[1], "pedidos": v[2]} for k, v in self.rotas.items()},
                "clientes": {k: {"entrada": v[0], "saida": v[1], "pedidos": v[2]} for k, v in self.clientes.items()},
            }


def trafego_mcp(eventos, agora):
    """Tráfego MCP (agentes ↔ Obsidian) a partir das chamadas reais registradas nos logs."""
    por_agente = {}
    serie = {}
    dia = agora - 24 * 3600 * 1000
    hora = agora - 3600 * 1000
    for e in eventos:
        t = e.get("t") or 0
        if t < dia:
            continue
        a = por_agente.setdefault(e["agente"], {"envio": 0, "retorno": 0, "chamadas": 0, "envio_1h": 0,
                                                 "retorno_1h": 0, "ops": {}})
        a["envio"] += e["envio"]
        a["retorno"] += e["retorno"]
        a["chamadas"] += 1
        a["ops"][e["op"]] = a["ops"].get(e["op"], 0) + 1
        if t >= hora:
            a["envio_1h"] += e["envio"]
            a["retorno_1h"] += e["retorno"]
        b = serie.setdefault((t // (15 * 60000)) * 15 * 60000, {"claude": 0, "codex": 0})
        b[e["agente"]] = b.get(e["agente"], 0) + e["envio"] + e["retorno"]
    return {
        "por_agente": por_agente,
        "serie_15min": [dict(m=k, **v) for k, v in sorted(serie.items())],
    }


# ================================================================ ESTADO
class Estado:
    def __init__(self, cfg):
        self.cfg = cfg
        self.vault = coleta.Vault(cfg.vault)
        self.agentes = coleta.Agentes(cfg.claude, cfg.codex)
        self.medidor = Medidor()
        self.guarda = seguranca.Guarda(seguranca.carregar_token(cfg.token))

    def vivo(self, desde):
        agora = int(time.time() * 1000)
        g = self.vault.grafo()
        ag = self.agentes.coletar(agora)
        evs = self.agentes.eventos()
        for e in evs:
            e["no"] = self.vault.resolver(e.pop("caminho", None)) or "vault"
            e.pop("consulta", None)
        novos = [e for e in evs if (e["t"] or 0) > desde][-120:]
        trilhas = {}
        ultimo_por_sessao = {}
        for e in evs:
            if e["op"] not in ("ler", "escrever") or e["ok"] is not True or e["no"] == "vault":
                continue
            sessao = (e["agente"], e["sessao"])
            ant = ultimo_por_sessao.get(sessao)
            if ant and ant["no"] != e["no"] and (e["t"] - ant["t"]) < 15 * 60000 and ant["no"] != "vault":
                k = (ant["no"], e["no"], e["agente"])
                tr = trilhas.setdefault(k, {"de": ant["no"], "para": e["no"], "agente": e["agente"], "n": 0, "t": 0})
                tr["n"] += 1
                tr["t"] = max(tr["t"], e["t"])
            ultimo_por_sessao[sessao] = e
        return {
            "agora": agora,
            "versao": VERSAO,
            "grafo_versao": g["versao"],
            "agentes": ag,
            "eventos": novos,
            "trilhas": sorted(trilhas.values(), key=lambda x: -x["t"])[:80],
            "obsidian": {"ativo": g["fonte"] == "obsidian"},
            "trafego": {"servidor": self.medidor.resumo(), "mcp": trafego_mcp(evs, agora),
                        "vault_bytes": g["metricas"]["bytes"]},
        }

    def nota(self, rel):
        g = self.vault.grafo()
        no = next((n for n in g["nos"] if n["id"] == rel and n["tipo"] == "nota"), None)
        if not no:
            return None
        cam = self.vault.caminho_seguro(rel)
        if not cam or not cam.lower().endswith(".md") or not os.path.isfile(cam):
            return None
        with open(cam, "rb") as f:
            bruto = f.read(MAX_NOTA + 1)
        cortada = len(bruto) > MAX_NOTA
        txt, ocultos = mascarar(bruto[:MAX_NOTA].decode("utf-8", "replace"))
        saida = [b for a, b, t in g["arestas"] if a == rel and t == "link"]
        entrada = [a for a, b, t in g["arestas"] if b == rel and t == "link"]
        return {"id": rel, "rotulo": no.get("rotulo"), "texto": txt, "cortada": cortada, "ocultos": ocultos,
                "saida": saida, "entrada": entrada, "tags": no.get("tags", []), "bytes": no.get("bytes"),
                "mod": no.get("mod"), "origem": no.get("origem")}


# ================================================================ HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "RedeNeural"
    sys_version = ""
    timeout = 8
    recebido = 0

    @property
    def estado(self):
        return self.server.estado

    # ------------------------------------------------------------ apoio
    def _host_ok(self):
        h = (self.headers.get("Host") or "").lower()
        return h in self.server.hosts

    def _cliente(self):
        return "app" if self.server.lan or self.headers.get("X-RN-Auth") else "navegador"

    def _credencial_app(self):
        autorizacao = self.headers.get("Authorization", "")
        return autorizacao.removeprefix("Bearer ") if autorizacao.startswith("Bearer ") else None

    def _enviar(self, codigo, corpo, tipo="application/json; charset=utf-8", extra=None, nonce=None):
        if isinstance(corpo, (dict, list)):
            corpo = json.dumps(corpo, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        elif isinstance(corpo, str):
            corpo = corpo.encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                         "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        if nonce:
            self.send_header("X-RN-Sig", seguranca.assinar_resposta(self.estado.guarda.token, nonce, corpo))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(corpo)
        rota = urlsplit(self.path).path
        rotas = {"/api/grafo", "/api/vivo", "/api/nota", "/api/saude", "/api/entrar", "/api/sair", "/api/sessao",
                 "/api/app/cadastro", "/api/app/login", "/api/app/sair"}
        rota = rota if rota in rotas else "/outras" if rota.startswith("/api/") else "/web"
        self.estado.medidor.registrar(rota, self._cliente(), self.recebido,
                                     0 if self.command == "HEAD" else len(corpo))

    def _autenticar(self):
        """Devolve (ok, nonce). nonce != None quando a resposta deve ser assinada (app)."""
        if self.server.lan:
            return self.server.contas.autenticar(self._credencial_app()), None
        origem = self.client_address[0]
        g = self.estado.guarda
        if g.bloqueado(origem):
            return False, None
        cab = self.headers.get("X-RN-Auth")
        if cab:
            nonce = g.verificar_hmac(cab, self.command, self.path)
            if nonce:
                g.acertou(origem)
                return True, nonce
            g.falhou(origem)
            return False, None
        ck = SimpleCookie(self.headers.get("Cookie") or "")
        sid = ck["rn_sessao"].value if "rn_sessao" in ck else None
        if g.sessao_valida(sid):
            return True, None
        return False, None

    # -------------------------------------------------------------- rotas
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if not self._host_ok():
            return self._enviar(421, {"erro": "host não permitido"})
        partes = urlsplit(self.path)
        rota, q = partes.path, parse_qs(partes.query)
        if rota == "/api/saude":
            return self._enviar(200, {"ok": True, "servico": "rede-neural", "versao": VERSAO})
        if self.server.lan and not rota.startswith("/api/"):
            return self._enviar(404, {"erro": "rota desconhecida"})
        if rota.startswith("/api/"):
            ok, nonce = self._autenticar()
            if not ok:
                return self._enviar(401, {"erro": "não autorizado"})
            try:
                if rota == "/api/grafo":
                    g = self.estado.vault.grafo()
                    if q.get("v", [""])[0] == g["versao"]:
                        return self._enviar(200, {"igual": True, "versao": g["versao"]}, nonce=nonce)
                    return self._enviar(200, g, nonce=nonce)
                if rota == "/api/vivo":
                    try:
                        desde = int(q.get("desde", ["0"])[0])
                    except ValueError:
                        desde = 0
                    return self._enviar(200, self.estado.vivo(desde), nonce=nonce)
                if rota == "/api/nota":
                    n = self.estado.nota(q.get("id", [""])[0])
                    if n is None:
                        return self._enviar(404, {"erro": "nota não encontrada"}, nonce=nonce)
                    return self._enviar(200, n, nonce=nonce)
                if rota == "/api/sessao":
                    return self._enviar(200, {"ok": True, "cliente": self._cliente()}, nonce=nonce)
            except Exception as e:  # nunca derruba o servidor nem vaza detalhes
                sys.stderr.write("erro em %s: %r\n" % (rota, e))
                return self._enviar(500, {"erro": "falha interna"}, nonce=nonce)
            return self._enviar(404, {"erro": "rota desconhecida"}, nonce=nonce)
        return self._estatico(rota)

    def do_POST(self):
        if not self._host_ok():
            return self._enviar(421, {"erro": "host não permitido"})
        rota = urlsplit(self.path).path
        origem_web = self.headers.get("Origin")
        origem_ok = (origem_web is None if self.server.lan else origem_web == "http://" + self.headers["Host"])
        if self.headers.get("Transfer-Encoding"):
            return self._enviar(400, {"erro": "envie Content-Length"})
        try:
            tam = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            tam = -1
        if tam < 0:
            return self._enviar(400, {"erro": "tamanho inválido"})
        if tam > LIMITE_CORPO:
            return self._enviar(413, {"erro": "corpo grande demais"})
        corpo = self.rfile.read(tam) if tam else b""
        self.recebido = len(corpo)
        if not origem_ok:
            return self._enviar(403, {"erro": "origem recusada"})
        if (self.server.lan or rota != "/api/sair") and self.headers.get_content_type() != "application/json":
            return self._enviar(415, {"erro": "envie um corpo JSON"})
        if self.server.lan:
            return self._post_app(rota, corpo)
        origem = self.client_address[0]
        g = self.estado.guarda
        if rota == "/api/entrar":
            if g.bloqueado(origem):
                return self._enviar(429, {"erro": "muitas tentativas; aguarde"})
            try:
                token = json.loads(corpo or b"{}").get("token", "")
            except (ValueError, AttributeError):
                token = ""
            sid = g.entrar(token)
            if not sid:
                g.falhou(origem)
                return self._enviar(401, {"erro": "chave incorreta"})
            g.acertou(origem)
            return self._enviar(200, {"ok": True}, extra={
                "Set-Cookie": "rn_sessao=%s; HttpOnly; SameSite=Strict; Path=/; Max-Age=%d"
                              % (sid, seguranca.SESSAO_S)})
        if rota == "/api/sair":
            ck = SimpleCookie(self.headers.get("Cookie") or "")
            if "rn_sessao" in ck:
                g.sair(ck["rn_sessao"].value)
            return self._enviar(200, {"ok": True}, extra={
                "Set-Cookie": "rn_sessao=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"})
        return self._enviar(404, {"erro": "rota desconhecida"})

    def _post_app(self, rota, corpo):
        contas = self.server.contas
        if rota == "/api/app/sair":
            contas.sair(self._credencial_app())
            return self._enviar(200, {"ok": True})
        if rota not in ("/api/app/cadastro", "/api/app/login"):
            return self._enviar(404, {"erro": "rota desconhecida"})
        g = self.server.limite
        ip = self.client_address[0]
        if g.bloqueado(ip):
            return self._enviar(429, {"erro": "Muitas tentativas. Aguarde antes de tentar de novo."})
        try:
            dados = json.loads(corpo)
        except (ValueError, RecursionError):
            dados = None
        if not isinstance(dados, dict):
            return self._enviar(400, {"erro": "Dados de acesso inválidos."})
        status, resposta = contas.entrar(dados, criar=rota.endswith("/cadastro"))
        if status == 200:
            g.acertou(ip)
        elif status in (401, 403):
            g.falhou(ip)
        return self._enviar(status, resposta)

    def _estatico(self, rota):
        nome = "index.html" if rota in ("/", "") else rota.lstrip("/")
        if not re.fullmatch(r"[a-z0-9_\-]+\.(html|js|css|svg|png|webmanifest)", nome):
            return self._enviar(404, {"erro": "não encontrado"})
        cam = os.path.join(WEB, nome)
        if not os.path.isfile(cam):
            return self._enviar(404, {"erro": "não encontrado"})
        with open(cam, "rb") as f:
            dados = f.read()
        tipo = mimetypes.guess_type(nome)[0] or "application/octet-stream"
        if tipo.startswith("text/") or tipo.endswith("javascript"):
            tipo += "; charset=utf-8"
        return self._enviar(200, dados, tipo)

    def log_message(self, fmt, *args):
        if self.estado.cfg.verboso:
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


class Servidor(ThreadingHTTPServer):
    allow_reuse_address = os.name != "nt"

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def __init__(self, endereco, estado, contexto=None, contas=None):
        super().__init__(endereco, Handler)
        self.estado = estado
        self.contexto = contexto
        self.lan = contexto is not None
        self.contas = contas
        self.limite = seguranca.Guarda("")
        host, porta = self.server_address
        self.hosts = {f"{host}:{porta}"}
        if not self.lan:
            self.hosts.add(f"localhost:{porta}")

    def handle_error(self, request, client_address):
        if not isinstance(sys.exception(), (ssl.SSLError, TimeoutError, ConnectionError)):
            super().handle_error(request, client_address)

    def finish_request(self, request, client_address):
        if not self.lan:
            return super().finish_request(request, client_address)
        request.settimeout(Handler.timeout)
        with self.contexto.wrap_socket(request, server_side=True) as seguro:
            super().finish_request(seguro, client_address)


def criar_servidor(cfg):
    return Servidor(("127.0.0.1", cfg.porta), Estado(cfg))


def criar_lan(cfg, estado, pasta=None):
    ip = pareamento.ip_local(cfg.ip) if cfg.ip else pareamento.descobrir_ip()
    pasta = pasta or seguranca.pasta_dados()
    contexto, pin = pareamento.tls(pasta)
    contas = Contas(os.path.join(pasta, "contas.sqlite3"), secrets.token_urlsafe(24))
    lan = Servidor((ip, cfg.porta_app), estado, contexto, contas)
    return lan, pin


def codigo_lan(lan, pin):
    ip, porta = lan.server_address
    convite = None if lan.contas.existe() else lan.contas.convite
    return pareamento.codigo(ip, porta, pin, convite)


def argumentos(argv=None):
    casa = os.path.expanduser("~")
    raiz = os.path.dirname(os.path.dirname(AQUI))
    ap = argparse.ArgumentParser(description="Rede Neural — servidor local")
    ap.add_argument("--vault", default=os.path.join(raiz, "memoria"))
    ap.add_argument("--claude", default=os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(casa, ".claude"))
    ap.add_argument("--codex", default=os.environ.get("CODEX_HOME") or os.path.join(casa, ".codex"))
    ap.add_argument("--porta", type=int, default=8770)
    ap.add_argument("--token", default=None, help="arquivo do segredo (padrão: %%LOCALAPPDATA%%/RedeNeural/token)")
    ap.add_argument("--verboso", action="store_true")
    ap.add_argument("--abrir", action="store_true", help="abre o painel no navegador padrão")
    ap.add_argument("--lan", action="store_true", help="habilita o HTTPS para o app Android na rede privada")
    ap.add_argument("--ip", help="IPv4 privado do computador; por padrão selecionado pela rota de rede")
    ap.add_argument("--porta-app", type=int, default=8771)
    return ap.parse_args(argv)


def main():
    cfg = argumentos()
    if not os.path.isdir(cfg.vault):
        print("Vault não encontrado:", cfg.vault)
        sys.exit(1)
    lan = None
    try:
        srv = criar_servidor(cfg)
        if cfg.lan:
            lan, pin = criar_lan(cfg, srv.estado)
            ip, porta = lan.server_address
            threading.Thread(target=lan.serve_forever, daemon=True).start()
            print(f"Android disponível na rede privada em {ip}:{porta}", flush=True)
            print("Cole o código abaixo em Conectar computador no Android.", flush=True)
            print(codigo_lan(lan, pin), flush=True)
    except (OSError, ImportError, ValueError) as e:
        print("Não foi possível iniciar o servidor:", e)
        sys.exit(1)
    print("Painel do navegador em http://127.0.0.1:%d/" % cfg.porta)
    print("  vault :", cfg.vault)
    print("  claude:", cfg.claude)
    print("  codex :", cfg.codex)
    print("Para entrar pelo navegador use a chave em", cfg.token or os.path.join(seguranca.pasta_dados(), "token"))
    if cfg.abrir:
        webbrowser.open("http://127.0.0.1:%d/" % cfg.porta)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if lan:
            lan.shutdown()
            lan.server_close()
        srv.server_close()


if __name__ == "__main__":
    main()
