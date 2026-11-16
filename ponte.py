#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ponte PC <-> Central (celular).

Roda no PC, onde estao o Codex, o Claude Code e o vault do Obsidian:
  - envia para a Central o estado das sessoes e a rede de notas;
  - busca as tarefas que voce criou no painel e executa com o agente escolhido
    (codex exec --json / claude -p --output-format stream-json), devolvendo
    resultado, tempos medidos com relogio monotonico e uso real de tokens;
  - aplica no vault as notas criadas/editadas pelo painel (uma unica vez por
    operacao, mesmo se a confirmacao se perder na rede).

Seguranca: quem decide em quais pastas um agente pode trabalhar e se pode
escrever e o arquivo ponte.json, aqui no PC. O painel remoto so escolhe entre
os nomes de pasta que voce liberou. O texto da tarefa nunca vai para a linha
de comando (vai pela entrada padrao do agente).

So pode haver UMA ponte ligada por PC: a segunda instancia se recusa a iniciar
(trava em dados_ponte/ponte.lock). A Central tambem atribui cada tarefa a uma
unica ponte (lease) e recusa atualizacoes de quem nao e dono.
"""
import glob
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import server as painel  # noqa: E402  (leitura das sessões e do vault)

VERSAO = "2.0.0"
ARQ_CFG = os.path.join(AQUI, "ponte.json")
DADOS_PONTE = os.path.join(AQUI, "dados_ponte")
ARQ_OPS = os.path.join(DADOS_PONTE, "ops_aplicadas.json")
CASA = os.path.expanduser("~")
INTERVALO = 2.5
INTERVALO_OCIOSO = 5.0
MAX_SAIDA = 12000
SEGREDO_RE = re.compile(r"(?i)((?:authorization\s*[=:]\s*)?bearer\s+)[A-Za-z0-9._~+/=-]{8,}|((?:senha|password|token|chave|secret|api[_-]?key)\s*[=:]\s*)\S+")

_logs_envio = []
_logs_trava = threading.Lock()


def log(*a, nivel="info", ev="log", tarefa=None, agente=None):
    """Mostra no console e enfileira para a Central (sem segredos, sem textos integrais)."""
    msg = " ".join(str(x) for x in a)
    print(time.strftime("%H:%M:%S"), msg, flush=True)
    with _logs_trava:
        _logs_envio.append({"nivel": nivel, "ev": ev, "msg": SEGREDO_RE.sub(lambda m: (m.group(1) or m.group(2) or "") + "<redigido>", msg)[:300],
                            "tarefa": tarefa, "agente": agente})
        del _logs_envio[:-200]


def tirar_logs():
    with _logs_trava:
        lote = _logs_envio[:100]
        del _logs_envio[:100]
    return lote


# ------------------------------------------------------------- instância única
class TravaUnica:
    """Trava de arquivo do SO: liberada automaticamente se o processo morrer."""

    def __init__(self, caminho):
        os.makedirs(os.path.dirname(caminho), exist_ok=True)
        self.f = open(caminho, "a+")
        try:
            if os.name == "nt":
                import msvcrt
                self.f.seek(0)
                msvcrt.locking(self.f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.f.close()
            raise RuntimeError("Já existe uma ponte ligada neste PC. Feche a outra janela antes de abrir esta.")
        self.f.seek(0)
        self.f.truncate()
        self.f.write(str(os.getpid()))
        self.f.flush()


# ------------------------------------------------------------------- configuração
def carregar_cfg():
    if os.path.exists(ARQ_CFG):
        with open(ARQ_CFG, encoding="utf-8") as f:
            return json.load(f)
    print("Primeira execução da ponte. Os dados ficam só neste PC, em ponte.json.\n")
    hub = input("Endereço da Central (ex.: http://192.168.0.50:8765 ou https://painel.seudominio.com):\n> ").strip().rstrip("/")
    chave = input("Chave da ponte (mostrada na instalação do celular):\n> ").strip()
    cfg = {
        "hub": hub, "chave": chave,
        "vault": os.path.join(os.path.dirname(AQUI), "memoria"),
        "pastas": {},
        "executaveis": {"codex": "", "claude": ""},
        "registrar_resultados": True,
        "tempo_max_min": 30,
    }
    print("\nPastas de projeto em que os agentes podem trabalhar (Enter vazio para terminar).")
    while True:
        cam = input("Caminho da pasta: ").strip().strip('"')
        if not cam:
            break
        if not os.path.isdir(cam):
            print("  pasta não encontrada.")
            continue
        nome = input("  Nome curto para aparecer no painel [%s]: " % os.path.basename(cam)).strip() or os.path.basename(cam)
        escrita = input("  O agente pode ALTERAR arquivos nela? [s/N] ").strip().lower() in ("s", "sim")
        cfg["pastas"][nome] = {"caminho": cam, "escrita": escrita}
    with open(ARQ_CFG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("Configuração salva em", ARQ_CFG, "\n")
    return cfg


def achar_executaveis(cfg):
    cand = {
        "codex": [cfg.get("executaveis", {}).get("codex"), shutil.which("codex")],
        "claude": [cfg.get("executaveis", {}).get("claude"), shutil.which("claude"),
                   os.path.join(CASA, ".local", "bin", "claude.exe"),
                   os.path.join(os.environ.get("APPDATA", ""), "npm", "claude.cmd")],
    }
    try:  # o app do Codex para Windows registra o caminho do CLI no config.toml
        with open(os.path.join(CASA, ".codex", "config.toml"), encoding="utf-8") as f:
            m = re.search(r"CODEX_CLI_PATH\s*=\s*'([^']+)'", f.read())
            if m:
                cand["codex"].append(m.group(1))
    except OSError:
        pass
    achados = {}
    for motor, lista in cand.items():
        for exe in lista:
            if not exe or not os.path.exists(exe):
                continue
            try:
                r = subprocess.run([exe, "--version"], capture_output=True, text=True, timeout=20)
                if r.returncode == 0:
                    achados[motor] = exe
                    log("executor %s: %s (%s)" % (motor, exe, (r.stdout or r.stderr).strip().splitlines()[0][:60]), ev="executor_encontrado")
                    break
            except Exception as e:
                log("executor %s em %s não respondeu: %s" % (motor, exe, e), nivel="aviso", ev="executor_falhou")
    for motor in ("codex", "claude"):
        if motor not in achados:
            log("AVISO: não achei o comando '%s'. Tarefas para esse motor vão falhar até você informar o caminho em ponte.json > executaveis." % motor,
                nivel="aviso", ev="executor_ausente")
    return achados


LEITURA_VAULT = ",".join("mcp__obsidian__" + t for t in (
    "read_note", "search_notes", "search_content", "list_folder", "get_note_metadata",
    "get_backlinks", "list_all_tags", "describe_vault", "list_recent_notes"))


def ferramentas_claude(escrita):
    return "Read,Grep,Glob,WebSearch,WebFetch," + ("mcp__obsidian,Edit,Write" if escrita else LEITURA_VAULT)


def escopo_pastas(cfg):
    out = []
    for nome, p in sorted(cfg.get("pastas", {}).items()):
        esc = bool(p.get("escrita"))
        out.append({"nome": nome, "escrita": esc, "existe": os.path.isdir(p.get("caminho", "")),
                    "sandbox_codex": "workspace-write" if esc else "read-only",
                    "ferramentas_claude": "leitura+escrita (Edit, Write, MCP completo)" if esc else "somente leitura"})
    return out


# ------------------------------------------------------- leitura do uso de tokens
def modelo_codex_por_thread(thread_id):
    """O rollout da sessão do Codex registra o modelo realmente usado (turn_context)."""
    if not thread_id:
        return None
    base = os.environ.get("CODEX_HOME") or os.path.join(CASA, ".codex")
    try:
        achados = glob.glob(os.path.join(base, "sessions", "*", "*", "*", "rollout-*%s*.jsonl" % thread_id))
        for cam in achados[:1]:
            with open(cam, encoding="utf-8", errors="replace") as f:
                for linha in f:
                    m = re.search(r'"model"\s*:\s*"([^"]{1,80})"', linha)
                    if m:
                        return m.group(1)
    except OSError:
        pass
    return None


class Leitor:
    """Interpreta a saída JSONL do executor sem inventar nada que não esteja nos eventos."""

    def __init__(self, motor):
        self.motor = motor
        self.texto_final = []
        self.uso = {}         # chamada_id -> registro
        self.modelo = None
        self.thread = None
        self.erros = []
        self.bruto = []

    def linha(self, s):
        self.bruto.append(s)
        if len(self.bruto) > 4000:
            del self.bruto[:2000]
        s = s.strip()
        if not s.startswith("{"):
            return
        try:
            ev = json.loads(s)
        except ValueError:
            return
        tipo = ev.get("type")
        if self.motor == "codex":
            if tipo == "thread.started":
                self.thread = ev.get("thread_id")
            elif tipo == "item.completed":
                it = ev.get("item") or {}
                if it.get("type") in ("agent_message", "assistant_message") and it.get("text"):
                    self.texto_final.append(it["text"])
                elif it.get("type") == "error" and it.get("message"):
                    self.erros.append(it["message"])
            elif tipo == "turn.completed":
                u = ev.get("usage") or {}
                n = len([k for k in self.uso if k.startswith("turno")]) + 1
                cid = "%s:turno%d" % (self.thread or "codex", n)
                self.uso[cid] = {"chamada_id": cid, "provedor": "openai", "fonte": "codex exec --json (turn.completed)",
                                 "entrada": u.get("input_tokens"), "saida": u.get("output_tokens"),
                                 "cache_leitura": u.get("cached_input_tokens"), "cache_criacao": None,
                                 "entrada_inclui_cache": True, "final": True}
            elif tipo in ("turn.failed", "error"):
                self.erros.append(str((ev.get("error") or {}).get("message") or ev.get("message") or tipo))
        else:
            if tipo == "system" and ev.get("subtype") == "init":
                self.modelo = ev.get("model") or self.modelo
                self.thread = ev.get("session_id") or self.thread
            elif tipo == "assistant":
                msg = ev.get("message") or {}
                self.modelo = msg.get("model") or self.modelo
                u = msg.get("usage") or {}
                mid = msg.get("id")
                if mid and u:  # parcial por mensagem; reconciliado pelo 'result' final
                    self.uso.setdefault("msg:" + mid, {"chamada_id": "msg:" + mid, "provedor": "anthropic",
                                                       "fonte": "claude stream-json (assistant.usage, parcial)", "final": False})
                    self.uso["msg:" + mid].update({"entrada": u.get("input_tokens"), "saida": u.get("output_tokens"),
                                                   "cache_leitura": u.get("cache_read_input_tokens"),
                                                   "cache_criacao": u.get("cache_creation_input_tokens"),
                                                   "entrada_inclui_cache": False, "modelo": msg.get("model")})
            elif tipo == "result":
                if ev.get("result"):
                    self.texto_final = [str(ev["result"])]
                if ev.get("is_error"):
                    self.erros.append(str(ev.get("result") or ev.get("subtype") or "erro"))
                u = ev.get("usage") or {}
                if u:
                    # o total da sessão substitui os parciais por mensagem (evita dupla contagem)
                    self.uso = {k: v for k, v in self.uso.items() if not k.startswith("msg:")}
                    cid = "%s:resultado" % (ev.get("session_id") or self.thread or "claude")
                    self.uso[cid] = {"chamada_id": cid, "provedor": "anthropic", "fonte": "claude stream-json (result.usage)",
                                     "entrada": u.get("input_tokens"), "saida": u.get("output_tokens"),
                                     "cache_leitura": u.get("cache_read_input_tokens"),
                                     "cache_criacao": u.get("cache_creation_input_tokens"),
                                     "entrada_inclui_cache": False, "final": True, "modelo": self.modelo}
                    if isinstance(ev.get("total_cost_usd"), (int, float)):
                        self.uso[cid]["custo_usd"] = ev["total_cost_usd"]
                        self.uso[cid]["custo_fonte"] = "total_cost_usd informado pelo Claude Code CLI"
                    mu = ev.get("modelUsage") or {}
                    if len(mu) == 1:
                        self.modelo = next(iter(mu))
                        self.uso[cid]["modelo"] = self.modelo

    def resultado(self):
        if self.texto_final:
            return "\n\n".join(self.texto_final).strip()
        nao_json = [l for l in self.bruto if not l.strip().startswith("{")]
        return "".join(nao_json).strip()

    def registros_uso(self):
        if self.motor == "codex" and not self.modelo:
            self.modelo = modelo_codex_por_thread(self.thread)
        out = []
        for r in self.uso.values():
            r = dict(r)
            r.setdefault("modelo", self.modelo)
            if not r.get("modelo"):
                r["modelo"] = self.modelo
            out.append(r)
        return out


# ----------------------------------------------------------------------- tarefas
class Tarefas:
    def __init__(self, cfg, exes):
        self.cfg, self.exes = cfg, exes
        self.trava = threading.Lock()
        self.ativas = {}      # id -> dict(proc, agente, inicio, antes, cancelar)
        self.envios = {}      # id -> atualização pendente de envio
        self.sessoes = {}     # id tarefa -> id da sessão observada

    def ocupado(self, agente_id):
        return any(t["agente"]["id"] == agente_id for t in self.ativas.values())

    def iniciar(self, tarefa, agente, sessoes_atuais, recebida_mono):
        reg = {"agente": agente, "inicio": int(time.time() * 1000), "antes": set(sessoes_atuais), "cancelar": False,
               "proc": None, "recebida_mono": recebida_mono}
        with self.trava:
            self.ativas[tarefa["id"]] = reg
            self.envios[tarefa["id"]] = {"id": tarefa["id"], "status": "rodando", "inicio": reg["inicio"],
                                         "atividade": "começando a tarefa", "motor": agente.get("motor", "codex")}
        threading.Thread(target=self._rodar, args=(tarefa, agente, reg), daemon=True).start()

    def _fim(self, tid, status, resultado, nota="", motivo=None, medidas=None, uso=None, modelo=None, nota_status=None, codigo=None):
        with self.trava:
            self.ativas.pop(tid, None)
            up = {"id": tid, "status": status, "fim": int(time.time() * 1000),
                  "resultado": resultado[-MAX_SAIDA:], "atividade": "", "detalhe": "", "memoria": False, "nota": nota}
            for k, v in (("motivo", motivo), ("medidas", medidas), ("uso", uso), ("modelo", modelo),
                         ("nota_status", nota_status), ("codigo_saida", codigo)):
                if v is not None:
                    up[k] = v
            self.envios[tid] = up

    def _rodar(self, tarefa, agente, reg):
        tid, motor = tarefa["id"], agente.get("motor", "codex")
        medidas = {"relogio": "monotônico da ponte"}
        exe = self.exes.get(motor)
        if not exe:
            return self._fim(tid, "falhou", "O comando '%s' não foi encontrado neste PC. Informe o caminho em ponte.json > executaveis." % motor,
                             motivo="executor_ausente", medidas=medidas)
        pasta = self.cfg.get("pastas", {}).get(agente.get("pasta") or "")
        if pasta and os.path.isdir(pasta.get("caminho", "")):
            cwd, escrita = pasta["caminho"], bool(pasta.get("escrita"))
        else:
            cwd, escrita = os.path.join(AQUI, "_trabalho", agente["id"]), False
            os.makedirs(cwd, exist_ok=True)
        prompt = (
            "Você é o agente \"%s\" de uma equipe de agentes.\n"
            "Sua função: %s\n\n"
            "Regras: fique dentro da sua função; se a tarefa fugir dela, diga isso em vez de improvisar. "
            "Consulte a memória compartilhada (MCP 'obsidian') quando o assunto puder já estar registrado. "
            "%s"
            "Termine com um relatório curto: o que fez, o que encontrou e o que recomenda.\n\n"
            "Tarefa:\n%s\n"
        ) % (agente.get("nome"), agente.get("funcao") or "(sem função definida)",
             "" if escrita else "Você está em modo somente leitura: analise e relate, não altere arquivos. ",
             tarefa.get("texto", ""))
        if motor == "codex":
            cmd = [exe, "exec", "--json", "--skip-git-repo-check", "--color", "never",
                   "-s", "workspace-write" if escrita else "read-only", "-C", cwd, "-"]
        else:
            # sem permissão de escrita, o agente só lê o vault (nada de criar/editar/apagar notas pelo MCP)
            cmd = [exe, "-p", "--output-format", "stream-json", "--verbose", "--allowedTools", ferramentas_claude(escrita)]
        log("tarefa %s -> %s (%s) em %s%s" % (tid, agente.get("nome"), motor, cwd, " [escrita]" if escrita else ""),
            ev="tarefa_iniciada", tarefa=tid, agente=agente.get("id"))
        leitor = Leitor(motor)
        primeira = {}
        try:
            proc = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="replace")
        except Exception as e:
            return self._fim(tid, "falhou", "Não consegui iniciar o agente: %s" % e, motivo="falha_inicio", medidas=medidas)
        t_ini = time.monotonic()
        medidas["espera_local_ms"] = round((t_ini - reg["recebida_mono"]) * 1000, 1)
        reg["proc"] = proc

        def ler():
            for linha in proc.stdout:
                if "ms" not in primeira:
                    primeira["ms"] = round((time.monotonic() - t_ini) * 1000, 1)
                    with self.trava:
                        e = self.envios.setdefault(tid, {"id": tid})
                        e["primeira_saida"] = True
                leitor.linha(linha)
        th = threading.Thread(target=ler, daemon=True)
        th.start()
        try:
            proc.stdin.write(prompt)
            proc.stdin.close()
        except Exception:
            pass
        limite = time.monotonic() + 60 * int(self.cfg.get("tempo_max_min", 30))
        motivo = ""
        while proc.poll() is None:
            if reg["cancelar"]:
                motivo = "cancelada"
            elif time.monotonic() > limite:
                motivo = "tempo esgotado"
            if motivo:
                try:
                    proc.kill()
                except Exception:
                    pass
                break
            time.sleep(0.25)
        try:
            proc.wait(timeout=10)
        except Exception:
            pass
        medidas["execucao_ms"] = round((time.monotonic() - t_ini) * 1000, 1)
        if "ms" in primeira:
            medidas["primeira_saida_ms"] = primeira["ms"]
            medidas["primeira_saida_def"] = "primeira linha emitida pelo processo do executor (inclui eventos de inicialização)"
        th.join(timeout=3)
        texto = leitor.resultado()
        uso = leitor.registros_uso()
        modelo = leitor.modelo
        if motivo == "cancelada":
            return self._fim(tid, "cancelada", texto or "Cancelada.", motivo="cancelada", medidas=medidas, uso=uso, modelo=modelo,
                             nota_status="nao_gravada")
        if motivo:
            return self._fim(tid, "falhou", (texto + "\n\n[interrompida: tempo máximo atingido]").strip(), motivo="tempo_esgotado",
                             medidas=medidas, uso=uso, modelo=modelo, nota_status="nao_gravada")
        ok = proc.returncode == 0 and not (motor == "claude" and leitor.erros)
        nota, nota_status = "", "nao_solicitada"
        if ok and self.cfg.get("registrar_resultados", True) and tarefa.get("registrar_nota", True):
            nota = registrar_resultado(self.cfg["vault"], agente, tarefa, texto)
            nota_status = "gravada" if nota else "erro"
        if not ok and leitor.erros:
            texto = (texto + "\n\n" + "\n".join(leitor.erros[-3:])).strip()
        self._fim(tid, "concluida" if ok else "falhou", texto or ("(sem saída; código %s)" % proc.returncode), nota,
                  motivo=None if ok else "codigo_saida", medidas=medidas, uso=uso, modelo=modelo,
                  nota_status=nota_status, codigo=proc.returncode)
        log("tarefa %s %s em %.1f s" % (tid, "concluída" if ok else "falhou (código %s)" % proc.returncode, medidas["execucao_ms"] / 1000),
            nivel="info" if ok else "aviso", ev="tarefa_fim", tarefa=tid, agente=agente.get("id"))


def nome_seguro(t, n=60):
    t = re.sub(r'[\\/:*?"<>|#^\[\]\r\n\t]+', " ", t or "").strip(" .")
    return re.sub(r"\s+", " ", t)[:n].rstrip(" .") or "sem título"


def escrever_novo(pasta, nome, conteudo):
    os.makedirs(pasta, exist_ok=True)
    for i in range(1, 50):
        cam = os.path.join(pasta, nome + ("" if i == 1 else " (%d)" % i) + ".md")
        try:
            with open(cam, "x", encoding="utf-8") as f:
                f.write(conteudo)
            return cam
        except FileExistsError:
            continue
    raise OSError("muitas notas com o mesmo nome")


def registrar_resultado(vault, agente, tarefa, texto):
    hoje = datetime.now().strftime("%Y-%m-%d")
    origem = "codex" if agente.get("motor") == "codex" else "claude-code"
    corpo = "\n".join([
        "---", "tipo: resultado-de-tarefa", "status: pendente", "origem: " + origem,
        "agente: " + nome_seguro(agente.get("nome"), 40), "data: " + hoje,
        "tarefa: " + str(tarefa.get("id", "")), "trace: " + str(tarefa.get("trace_id", "")),
        "confianca: media (relatório do agente, não revisado)", "---", "",
        "# Tarefa de " + nome_seguro(agente.get("nome"), 40), "",
        "## Pedido", "", tarefa.get("texto", ""), "", "## Relatório", "", texto[-MAX_SAIDA:], ""])
    try:
        cam = escrever_novo(os.path.join(vault, "00-Inbox"),
                            "%s Tarefa %s - %s" % (hoje, nome_seguro(agente.get("nome"), 24), nome_seguro(tarefa.get("texto"), 40)), corpo)
        return os.path.relpath(cam, vault).replace("\\", "/")
    except Exception as e:
        log("não consegui registrar o resultado no vault:", e, nivel="erro", ev="nota_resultado_erro", tarefa=tarefa.get("id"))
        return ""


# ------------------------------------------------------------------ edição de notas
def aplicar_op(vault, op):
    """Retorna (ok, msg, conflito, arquivo)."""
    try:
        conteudo = op.get("conteudo", "")
        if op.get("tipo") == "criar":
            if not conteudo.lstrip().startswith("---"):
                conteudo = "---\ntipo: nota\nstatus: pendente\norigem: painel-remoto\ndata: %s\n---\n\n%s" % (datetime.now().strftime("%Y-%m-%d"), conteudo)
            cam = escrever_novo(os.path.join(vault, "00-Inbox"), nome_seguro(op.get("titulo"), 80), conteudo)
            return True, "Nota criada em 00-Inbox: " + os.path.basename(cam), False, os.path.relpath(cam, vault).replace("\\", "/")
        rel = str(op.get("nota", ""))
        if not rel.startswith("nota:"):
            return False, "nota inválida", False, None
        raiz = os.path.realpath(vault)
        cam = os.path.realpath(os.path.join(raiz, rel[5:]))
        if os.path.commonpath([raiz, cam]) != raiz or not cam.lower().endswith(".md") or not os.path.isfile(cam):
            return False, "Nota não encontrada no vault.", False, None
        if "%s.obsidian%s" % (os.sep, os.sep) in cam:
            return False, "Arquivo protegido.", False, None
        if int(os.path.getmtime(cam) * 1000) != int(op.get("base_mod") or 0):
            return False, "A nota mudou no PC depois que você abriu. Reabra e edite de novo.", True, None
        copias = os.path.join(AQUI, "_versoes")
        os.makedirs(copias, exist_ok=True)
        shutil.copy2(cam, os.path.join(copias, "%s %s.md" % (os.path.basename(cam)[:-3], time.strftime("%Y%m%d-%H%M%S"))))
        with open(cam, "w", encoding="utf-8", newline="") as f:
            f.write(conteudo)
        return True, "Nota salva (versão anterior guardada em escritorio/_versoes).", False, rel[5:]
    except Exception as e:
        return False, "Erro ao gravar: %s" % e, False, None


class OpsAplicadas:
    """Lembra (em disco) as operações já aplicadas: reentrega não grava a nota duas vezes."""

    def __init__(self):
        try:
            with open(ARQ_OPS, encoding="utf-8") as f:
                self.d = json.load(f)
        except Exception:
            self.d = {}

    def get(self, oid):
        return self.d.get(oid)

    def marcar(self, oid, res):
        self.d[oid] = res
        if len(self.d) > 500:
            for k in list(self.d)[:-400]:
                self.d.pop(k, None)
        tmp = ARQ_OPS + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.d, f, ensure_ascii=False)
        os.replace(tmp, ARQ_OPS)


# ------------------------------------------------------------------------- rede
def enviar(cfg, corpo):
    req = urllib.request.Request(cfg["hub"] + "/ponte/sincronizar", data=json.dumps(corpo, ensure_ascii=False).encode("utf-8"),
                                 method="POST", headers={"Content-Type": "application/json", "Authorization": "Bearer " + cfg["chave"],
                                                         "User-Agent": "EscritorioPonte/" + VERSAO})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))


# --------------------------------------------------------------------- OpenBot
# Provedor do OpenBot -> agente original mostrado no app (o Gemini roda pelo provedor "antigravity").
OPENBOT_PROVEDOR = {"antigravity": "gemini", "gemini": "gemini", "codex": "codex", "claude": "claude", "kimi": "kimi"}
OPENBOT_COTA_RE = re.compile(r"(?i)quota|rate.?limit|credit|insufficient|billing|\b429\b|usage limit|limit (reached|exceeded)|out of tokens|too many requests")
_openbot_cache = {"t": 0.0, "v": None}


def _iso_ms(v):
    try:
        return int(datetime.fromisoformat(str(v).replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return None


def ler_openbot(cfg, agora=None):
    """Estado dos agentes do OpenBot, lido do banco local dele em modo SOMENTE LEITURA.

    Só metadados: nome, provedor, modelo, se há turno ativo, horário da última atividade, resultado do último
    turno e contagem de tokens. Nunca envia textos de mensagens, descrições nem o texto dos erros.
    Devolve None se o OpenBot não estiver instalado/legível (a Central mostra "sem dados").
    """
    agora = agora or time.time()
    if agora - _openbot_cache["t"] < 5:
        return _openbot_cache["v"]
    _openbot_cache["t"] = agora
    caminho = cfg.get("openbot_db") or os.path.join(os.environ.get("APPDATA") or os.path.join(CASA, "AppData", "Roaming"), "OpenBot", "openbot.db")
    res = None
    if os.path.isfile(caminho):
        try:
            c = sqlite3.connect("file:" + caminho.replace("\\", "/") + "?mode=ro", uri=True, timeout=1)
            try:
                dia = (agora - 86400) * 1000  # janela móvel de 24 h (não zera à meia-noite)
                uso = {}
                for aid, quando, tj, custo in c.execute("select agent_id, occurred_at, tokens_json, estimated_cost_usd from agent_usage_records"):
                    try:
                        tk = json.loads(tj or "{}")
                    except ValueError:
                        tk = {}
                    n = sum(int(tk.get(k) or 0) for k in ("uncachedInput", "cachedInput", "cacheCreation", "output"))
                    ms = _iso_ms(quando) or 0
                    u = uso.setdefault(aid, {"total": 0, "h24": 0, "chamadas_24h": 0, "custo_24h": 0.0, "ultima": 0})
                    u["total"] += n
                    u["ultima"] = max(u["ultima"], ms)
                    if ms >= dia:
                        u["h24"] += n
                        u["chamadas_24h"] += 1
                        u["custo_24h"] += float(custo or 0)
                ultimo = {}
                for aid, st, fim in c.execute("select th.agent_id, t.status, coalesce(t.completed_at, t.started_at) from projection_turns t "
                                              "join projection_threads th on th.thread_id = t.thread_id order by t.started_at"):
                    ultimo[aid] = (st, _iso_ms(fim))
                falha = {}
                for aid, st, erro in c.execute("select recipient_agent_id, status, error from projection_deliveries order by created_at"):
                    falha[aid] = (st == "failed", bool(st == "failed" and erro and OPENBOT_COTA_RE.search(erro)))
                ativo = {aid: (bool(turno), _iso_ms(upd)) for aid, turno, upd in
                         c.execute("select agent_id, active_turn_id, updated_at from projection_threads")}
                res = []
                for aid, modelo, upd, aj in c.execute("select agent_id, model, updated_at, agent_json from projection_agents order by sort_order limit 20"):
                    try:
                        j = json.loads(aj or "{}")
                    except ValueError:
                        j = {}
                    bruto = str(j.get("provider") or "").lower()
                    u = uso.get(aid, {})
                    st, fim = ultimo.get(aid, (None, None))
                    trab, upd_th = ativo.get(aid, (False, None))
                    falhou, cota = falha.get(aid, (False, False))
                    res.append({
                        "id": str(aid)[:60], "nome": str(j.get("name") or "")[:40], "titulo": str(j.get("title") or "")[:60],
                        "provedor": OPENBOT_PROVEDOR.get(bruto, bruto)[:20], "provedor_openbot": bruto[:20], "modelo": str(modelo or j.get("model") or "")[:60],
                        "trabalhando": trab, "atualizado": max([x for x in (upd_th, _iso_ms(upd), fim, u.get("ultima")) if x] or [0]) or None,
                        "ultimo_status": st, "falhou_ultimo": falhou, "sem_cota": cota,
                        "tokens_24h": u.get("h24"), "tokens_total": u.get("total"), "chamadas_24h": u.get("chamadas_24h", 0),
                        "custo_24h_usd": round(u["custo_24h"], 6) if u else None, "ultima_chamada": u.get("ultima") or None,
                    })
            finally:
                c.close()
        except (sqlite3.Error, OSError):
            res = None
    _openbot_cache["v"] = res
    return res


class Cfg:  # o mesmo formato que server.estado() espera
    pass


def main():
    try:
        trava_unica = TravaUnica(os.path.join(DADOS_PONTE, "ponte.lock"))  # noqa: F841 (mantida aberta até o fim)
    except RuntimeError as e:
        print(e)
        sys.exit(2)
    cfg = carregar_cfg()
    exes = achar_executaveis(cfg)
    pc = Cfg()
    pc.vault = cfg.get("vault") or os.path.join(os.path.dirname(AQUI), "memoria")
    pc.codex = os.environ.get("CODEX_HOME") or os.path.join(CASA, ".codex")
    pc.claude = os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(CASA, ".claude")
    cfg["vault"] = pc.vault
    ponte_id = "p" + secrets.token_hex(5)
    tarefas = Tarefas(cfg, exes)
    ops_db = OpsAplicadas()
    enviados, assin_grafo, ops_feitas = {}, None, []
    quer_grafo, ciclo, conectado, rtt_ms = True, 0, None, None
    log("ponte %s v%s ligada (PID %d). Central: %s | vault: %s" % (ponte_id, VERSAO, os.getpid(), cfg["hub"], pc.vault), ev="ponte_iniciada")
    log("pastas liberadas:", ", ".join("%s%s" % (k, " [escrita]" if v.get("escrita") else "") for k, v in cfg.get("pastas", {}).items()) or "(nenhuma: agentes rodam em pasta vazia, só leitura)")
    while True:
        ciclo += 1
        ocioso = True
        try:
            est = painel.estado(pc)
            grafo = est["grafo"]
            sessoes = est["codex"]["sessoes"] + est["claude"]["sessoes"]
            ids_sessoes = [s["id"] for s in sessoes]
            # liga cada tarefa em execução à sessão nova que apareceu depois que ela começou
            with tarefas.trava:
                usados = set()
                for tid, reg in tarefas.ativas.items():
                    pref = "codex:" if reg["agente"].get("motor") == "codex" else "claude:"
                    s = next((s for s in sessoes if s["id"].startswith(pref) and s["id"] not in reg["antes"] and s["id"] not in usados), None)
                    if s:
                        usados.add(s["id"])
                        s["agente"] = reg["agente"].get("nome")
                        tarefas.envios.setdefault(tid, {"id": tid}).update(
                            {"atividade": s["atividade"] or "trabalhando", "detalhe": s["detalhe"], "memoria": s["memoria"]})
                atualizacoes = [dict(v) for v in tarefas.envios.values()]
                ocioso = not tarefas.ativas and not atualizacoes
            nova_assin = (len(grafo["nos"]), len(grafo["arestas"]), max([n.get("mod", 0) for n in grafo["nos"]] or [0]))
            mandar_grafo = quer_grafo or nova_assin != assin_grafo
            lote = []
            for n in grafo["nos"]:
                if n["tipo"] == "nota" and enviados.get(n["id"]) != n["mod"]:
                    try:
                        with open(os.path.join(pc.vault, n["id"][5:]), encoding="utf-8", errors="replace") as f:
                            lote.append({"id": n["id"], "mod": n["mod"], "conteudo": f.read(400_000)})
                    except OSError:
                        enviados[n["id"]] = n["mod"]
                    if len(lote) >= 40:
                        break
            if not mandar_grafo:
                est["grafo"] = None
            logs_lote = tirar_logs()
            corpo = {"ponte_id": ponte_id, "versao": VERSAO, "host": socket.gethostname()[:40], "rtt_ms": rtt_ms,
                     "estado": est, "executores": {m: True for m in exes}, "pastas": sorted(cfg.get("pastas", {})),
                     "escopo_pastas": escopo_pastas(cfg), "logs": logs_lote,
                     "notas": lote, "tarefas": atualizacoes, "ops": ops_feitas, "salvar": mandar_grafo or ciclo % 24 == 0}
            ob = ler_openbot(cfg)
            if ob is not None:
                corpo["openbot"] = {"lido_em": int(time.time() * 1000), "agentes": ob}
            t0 = time.monotonic()
            try:
                resp = enviar(cfg, corpo)
            except Exception:
                with _logs_trava:  # devolve os logs para a próxima tentativa
                    _logs_envio[:0] = logs_lote
                raise
            rtt_ms = round((time.monotonic() - t0) * 1000, 1)
            recebida = time.monotonic()
            if conectado is not True:
                log("conectado à Central.", ev="conectado")
                conectado = True
            assin_grafo, quer_grafo = nova_assin, bool(resp.get("quer_grafo"))
            for n in lote:
                enviados[n["id"]] = n["mod"]
            ops_feitas = []
            with tarefas.trava:
                for up in atualizacoes:  # o que terminou já foi entregue
                    atual = tarefas.envios.get(up["id"])
                    if up.get("status") in ("concluida", "falhou", "cancelada") and atual is not None and atual.get("status") == up.get("status") and atual.get("fim") == up.get("fim"):
                        tarefas.envios.pop(up["id"], None)
                for tid in resp.get("cancelar", []):
                    if tid in tarefas.ativas:
                        tarefas.ativas[tid]["cancelar"] = True
                for tid in resp.get("rodando", []):  # a Central acha que roda, mas a ponte reiniciou
                    if tid not in tarefas.ativas and tid not in tarefas.envios:
                        tarefas.envios[tid] = {"id": tid, "status": "falhou", "fim": int(time.time() * 1000), "motivo": "ponte_reiniciada",
                                               "resultado": "A ponte no PC foi reiniciada enquanto a tarefa rodava.", "atividade": ""}
            agentes = {a["id"]: a for a in resp.get("agentes", [])}
            for t in resp.get("tarefas", []):
                ag = agentes.get(t["agente"])
                if ag and t["id"] not in tarefas.ativas and t["id"] not in tarefas.envios and not tarefas.ocupado(ag["id"]):
                    tarefas.iniciar(t, ag, ids_sessoes, recebida)
                    ocioso = False
            for op in resp.get("ops", []):
                feito = ops_db.get(op["id"])
                if feito is None:
                    ok, msg, conflito, arquivo = aplicar_op(pc.vault, op)
                    feito = {"id": op["id"], "ok": ok, "msg": msg, "conflito": conflito, "arquivo": arquivo}
                    ops_db.marcar(op["id"], feito)
                    log("nota (%s): %s" % (op.get("tipo"), msg), nivel="info" if ok else "aviso", ev="nota_op")
                ops_feitas.append(feito)
                ocioso = False
        except urllib.error.HTTPError as e:
            if conectado is not False:
                log("a Central recusou (HTTP %d)%s" % (e.code, " - confira a chave da ponte em ponte.json" if e.code == 401 else ""),
                    nivel="erro", ev="central_recusou")
                conectado = False
            time.sleep(5)
        except (urllib.error.URLError, OSError, ValueError) as e:
            if conectado is not False:
                log("sem conexão com a Central (%s). Tentando de novo..." % getattr(e, "reason", e), nivel="aviso", ev="sem_conexao")
                conectado = False
            time.sleep(5)
        except KeyboardInterrupt:
            break
        time.sleep(INTERVALO if not ocioso else INTERVALO_OCIOSO if ciclo % 2 else INTERVALO)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
