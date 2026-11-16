#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Testes de integração de repetição e concorrência da Central (rodar: python -m unittest discover -s tests).

Cada teste dispara a MESMA operação duas vezes, e depois várias vezes ao mesmo tempo, e prova que o efeito
acontece uma vez só. Servem de trava antes de mexer no servidor: se um deles quebrar, o comportamento mudou.

Os testes marcados com @unittest.expectedFailure descrevem o comportamento CORRETO que a Central ainda não
tem (achados da auditoria de 16/11/2026). Quando a correção entrar, o teste passa a dar "sucesso inesperado":
aí é só remover a marca.
"""
import importlib
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(RAIZ, "celular"))
SENHA = "senha-de-teste-sintetica"


class Base(unittest.TestCase):
    """Sobe a Central real numa porta livre, com dados temporários."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="central_conc_")
        import hub
        cls.hub = importlib.reload(hub)
        cls.hub.DADOS = cls.tmp
        cls.hub.NOTAS = os.path.join(cls.tmp, "notas")
        cls.hub.CONFIG = os.path.join(cls.tmp, "config.json")
        cls.cfg, _ = cls.hub.criar_config(SENHA)
        cls.hub.carregar()
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), cls.hub.H)
        cls.url = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        c, r = cls.req("POST", "/api/v1/dispositivos/parear", {"senha": SENHA, "nome": "teste"})
        assert c == 201, r
        cls.tok = r["token"]
        cls.req("POST", "/ponte/sincronizar", {"ponte_id": "pC", "pastas": ["proj"]}, token=cls.cfg["chave_ponte"])

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def req(cls, metodo, rota, corpo=None, token=None):
        h = {"Content-Type": "application/json", "X-Requested-With": "escritorio-android"}
        if token:
            h["Authorization"] = "Bearer " + token
        r = urllib.request.Request(cls.url + rota, method=metodo, headers=h,
                                   data=json.dumps(corpo).encode() if corpo is not None else None)
        try:
            with urllib.request.urlopen(r, timeout=20) as resp:
                return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}")

    def juntas(self, n, fn):
        """Dispara fn(i) em n threads liberadas no mesmo instante; devolve a lista de resultados."""
        barreira, res = threading.Barrier(n), [None] * n

        def rodar(i):
            barreira.wait()
            res[i] = fn(i)
        ts = [threading.Thread(target=rodar, args=(i,)) for i in range(n)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        return res

    def agente(self, nome):
        c, r = self.req("POST", "/api/v1/agentes", {"nome": nome, "motor": "codex", "pasta": "proj"}, token=self.tok)
        self.assertEqual(c, 200, r)
        return r["id"]

    def tarefas_de(self, aid):
        with self.hub.trava:
            return [t for t in self.hub.E.tarefas if t["agente"] == aid]


class Repeticao(Base):
    """O mesmo pedido, 2 vezes em sequência e várias vezes ao mesmo tempo, só tem efeito uma vez."""

    def test_tarefa_mesma_chave_2_vezes(self):
        aid = self.agente("seq")
        corpo = {"agente": aid, "texto": "tarefa sintética", "idem": "chave-seq-1"}
        a = self.req("POST", "/api/v1/tarefas", corpo, token=self.tok)
        b = self.req("POST", "/api/v1/tarefas", corpo, token=self.tok)
        self.assertEqual((a[0], b[0]), (201, 200))
        self.assertEqual(a[1]["id"], b[1]["id"])
        self.assertTrue(b[1]["repetida"])
        self.assertEqual(len(self.tarefas_de(aid)), 1)

    def test_tarefa_mesma_chave_ao_mesmo_tempo(self):
        aid = self.agente("par")
        corpo = {"agente": aid, "texto": "tarefa sintética", "idem": "chave-par-1"}
        res = self.juntas(12, lambda i: self.req("POST", "/api/v1/tarefas", corpo, token=self.tok))
        self.assertEqual(sorted(c for c, _ in res), [200] * 11 + [201])
        self.assertEqual(len({r["id"] for _, r in res}), 1)
        self.assertEqual(len(self.tarefas_de(aid)), 1)

    def test_repetir_mesma_chave_ao_mesmo_tempo(self):
        aid = self.agente("rep")
        _, t = self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "x", "idem": "chave-rep-0"}, token=self.tok)
        self.req("POST", "/api/v1/tarefas/%s/cancelar" % t["id"], {}, token=self.tok)
        res = self.juntas(8, lambda i: self.req("POST", "/api/v1/tarefas/%s/repetir" % t["id"], {"idem": "chave-rep-1"}, token=self.tok))
        self.assertEqual(len({r["id"] for _, r in res}), 1)
        self.assertEqual(len(self.tarefas_de(aid)), 2)  # a original cancelada + UMA nova tentativa

    def test_nota_mesma_chave_ao_mesmo_tempo(self):
        corpo = {"titulo": "nota sintética", "conteudo": "c", "idem": "chave-nota-1"}
        res = self.juntas(10, lambda i: self.req("POST", "/api/v1/notas", corpo, token=self.tok))
        self.assertEqual(len({r["op"] for _, r in res}), 1)
        with self.hub.trava:
            self.assertEqual(sum(1 for o in self.hub.E.ops if o.get("idem") == "chave-nota-1"), 1)

    def test_openbot_mesma_chave_ao_mesmo_tempo(self):
        aid = self.agente("ob")
        corpo = {"agente": aid, "projeto": "proj", "texto": "mensagem sintética", "idem": "chave-ob-1"}
        res = self.juntas(8, lambda i: self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"]))
        self.assertEqual({c for c, _ in res}, {202})
        self.assertEqual(len(self.tarefas_de(aid)), 1)
        tid = self.tarefas_de(aid)[0]["id"]
        with self.hub.trava:  # e uma única nota de registro em 00-Inbox
            self.assertEqual(sum(1 for o in self.hub.E.ops if o.get("tarefa") == tid), 1)

    # I-1 (corrigido em 16/11/2026): sem chave, a mesma mensagem em até 2 minutos é tratada como reenvio
    def test_openbot_sem_chave_repetido_nao_duplica(self):
        aid = self.agente("ob2")
        corpo = {"agente": aid, "projeto": "proj", "texto": "mensagem repetida sem chave"}
        a = self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"])
        b = self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"])
        self.assertEqual(a[1]["id"], b[1]["id"])
        self.assertTrue(b[1]["repetida"])
        self.assertEqual(len(self.tarefas_de(aid)), 1)
        tid = a[1]["id"]
        with self.hub.trava:
            self.assertEqual(sum(1 for o in self.hub.E.ops if o.get("tarefa") == tid), 1)  # uma nota só em 00-Inbox

    def test_openbot_sem_chave_ao_mesmo_tempo(self):
        aid = self.agente("ob3")
        corpo = {"agente": aid, "projeto": "proj", "texto": "mensagem simultânea sem chave"}
        res = self.juntas(8, lambda i: self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"]))
        self.assertEqual(len({r["id"] for _, r in res}), 1)
        self.assertEqual(len(self.tarefas_de(aid)), 1)

    def test_openbot_mesmo_texto_depois_do_prazo_e_tarefa_nova(self):
        """Pedir a mesma coisa de novo mais tarde é legítimo: só o reenvio imediato é ignorado."""
        aid = self.agente("ob4")
        corpo = {"agente": aid, "projeto": "proj", "texto": "pedido que se repete de propósito"}
        self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"])
        with self.hub.trava:
            for t in self.hub.E.tarefas:
                if t["agente"] == aid:
                    t["criado"] -= (self.hub.OPENBOT_REPETICAO_S + 5) * 1000
        self.req("POST", "/openbot/mensagem", corpo, token=self.cfg["chave_openbot"])
        self.assertEqual(len(self.tarefas_de(aid)), 2)

    def test_openbot_textos_diferentes_nao_se_confundem(self):
        aid = self.agente("ob5")
        for texto in ("primeiro pedido", "segundo pedido"):
            self.req("POST", "/openbot/mensagem", {"agente": aid, "projeto": "proj", "texto": texto}, token=self.cfg["chave_openbot"])
        self.assertEqual(len(self.tarefas_de(aid)), 2)

    def test_openbot_mcp_repetido_nao_duplica(self):
        """O caminho real do OpenBot: ferramenta MCP enviar_tarefa chamada duas vezes com os mesmos argumentos."""
        aid = self.agente("ob6")
        pedido = {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                  "params": {"name": "enviar_tarefa", "arguments": {"agente": aid, "projeto": "proj", "texto": "pedido pelo MCP"}}}
        for _ in range(2):
            c, r = self.req("POST", "/openbot/mcp", pedido, token=self.cfg["chave_openbot"])
            self.assertEqual(c, 200, r)
            self.assertFalse(r["result"].get("isError"), r)
        self.assertEqual(len(self.tarefas_de(aid)), 1)

    def test_openbot_mcp_anuncia_a_chave_opcional(self):
        c, r = self.req("POST", "/openbot/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, token=self.cfg["chave_openbot"])
        enviar = next(t for t in r["result"]["tools"] if t["name"] == "enviar_tarefa")
        self.assertIn("idem", enviar["inputSchema"]["properties"])
        self.assertNotIn("idem", enviar["inputSchema"]["required"])

    # I-2 (corrigido em 16/11/2026): criar agente aceita chave de repetição
    def test_criar_agente_repetido_nao_duplica(self):
        corpo = {"nome": "agente repetido", "motor": "codex", "pasta": "proj", "idem": "chave-ag-1"}
        self.req("POST", "/api/v1/agentes", corpo, token=self.tok)
        self.req("POST", "/api/v1/agentes", corpo, token=self.tok)
        with self.hub.trava:
            self.assertEqual(sum(1 for a in self.hub.E.agentes if a["nome"] == "agente repetido"), 1)


class PainelWeb(Base):
    """O painel web (cookie de sessão) passa a mandar uma chave por operação; o servidor precisa honrá-la."""

    @classmethod
    def web(cls, rota, corpo, cookie=None):
        h = {"Content-Type": "application/json", "X-Requested-With": "escritorio"}
        if cookie:
            h["Cookie"] = cookie
        r = urllib.request.Request(cls.url + rota, method="POST", headers=h, data=json.dumps(corpo).encode())
        try:
            with urllib.request.urlopen(r, timeout=20) as resp:
                return resp.status, json.loads(resp.read() or b"{}"), resp.headers.get("Set-Cookie")
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}"), None

    def entrar(self):
        c, _, ck = self.web("/api/login", {"senha": SENHA})
        self.assertEqual(c, 200)
        return ck.split(";")[0]

    def test_tarefa_do_painel_com_a_mesma_chave(self):
        ck, aid = self.entrar(), self.agente("web")
        corpo = {"agente": aid, "texto": "tarefa do painel", "idem": "web-chave-1"}
        res = self.juntas(8, lambda i: self.web("/api/tarefas", corpo, ck)[0])
        self.assertEqual(set(res), {200})
        self.assertEqual(len(self.tarefas_de(aid)), 1)

    def test_nota_do_painel_com_a_mesma_chave(self):
        ck = self.entrar()
        corpo = {"titulo": "nota do painel", "conteudo": "c", "idem": "web-chave-nota-1"}
        res = self.juntas(8, lambda i: self.web("/api/notas", corpo, ck))
        self.assertEqual(len({r["op"] for _, r, _ in res}), 1)


class RiscoBaixo(Base):
    """Correções de 16/11/2026 dos itens de risco baixo da auditoria."""

    def test_r3_chave_vale_mesmo_depois_de_a_tarefa_sair_da_lista(self):
        aid = self.agente("r3")
        corpo = {"agente": aid, "texto": "tarefa antiga", "idem": "chave-r3-1"}
        _, a = self.req("POST", "/api/v1/tarefas", corpo, token=self.tok)
        with self.hub.trava:  # simula a tarefa ter saído das 300 mais recentes
            self.hub.E.tarefas = [t for t in self.hub.E.tarefas if t["id"] != a["id"]]
        c, b = self.req("POST", "/api/v1/tarefas", corpo, token=self.tok)
        self.assertEqual((c, b["id"], b["repetida"]), (200, a["id"], True))
        self.assertEqual(self.tarefas_de(aid), [])

    def test_r3_chave_de_nota_vale_depois_de_sair_da_lista(self):
        corpo = {"titulo": "nota antiga", "conteudo": "c", "idem": "chave-r3-nota"}
        _, a = self.req("POST", "/api/v1/notas", corpo, token=self.tok)
        with self.hub.trava:
            self.hub.E.ops = [o for o in self.hub.E.ops if o["id"] != a["op"]]
        c, b = self.req("POST", "/api/v1/notas", corpo, token=self.tok)
        self.assertEqual((c, b["op"], b["repetida"]), (200, a["op"], True))

    def test_i2_criar_agente_ao_mesmo_tempo_com_a_mesma_chave(self):
        corpo = {"nome": "agente simultâneo", "motor": "codex", "pasta": "proj", "idem": "chave-ag-par"}
        res = self.juntas(8, lambda i: self.req("POST", "/api/v1/agentes", corpo, token=self.tok))
        self.assertEqual(len({r["id"] for _, r in res}), 1)
        with self.hub.trava:
            self.assertEqual(sum(1 for a in self.hub.E.agentes if a["nome"] == "agente simultâneo"), 1)

    def test_a4_origem_nao_vem_do_cliente(self):
        aid = self.agente("a4")
        self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "x", "idem": "chave-a4", "origem": "openbot"}, token=self.tok)
        self.assertEqual(self.tarefas_de(aid)[0]["origem"], "android")

    def test_a5_sair_do_painel_invalida_o_cookie_no_servidor(self):
        def web(metodo, rota, corpo=None, cookie=None):
            h = {"Content-Type": "application/json", "X-Requested-With": "escritorio"}
            if cookie:
                h["Cookie"] = cookie
            r = urllib.request.Request(self.url + rota, method=metodo, headers=h, data=json.dumps(corpo).encode() if corpo is not None else None)
            try:
                with urllib.request.urlopen(r, timeout=20) as resp:
                    return resp.status, resp.headers.get("Set-Cookie")
            except urllib.error.HTTPError as e:
                with e:
                    return e.code, None
        _, ck = web("POST", "/api/login", {"senha": SENHA})
        copia = ck.split(";")[0]                       # alguém copiou o cookie
        self.assertEqual(web("GET", "/api/estado", cookie=copia)[0], 200)
        self.assertEqual(web("POST", "/api/sair", {}, cookie=copia)[0], 200)
        self.assertEqual(web("GET", "/api/estado", cookie=copia)[0], 401)   # a cópia não vale mais
        _, novo = web("POST", "/api/login", {"senha": SENHA})
        self.assertEqual(web("GET", "/api/estado", cookie=novo.split(";")[0])[0], 200)
        self.assertEqual(self.req("GET", "/api/v1/estado", token=self.tok)[0], 200)  # o app (token próprio) não é afetado

    def test_a6_ponte_nao_grava_status_inventado_nem_campo_gigante(self):
        aid = self.agente("a6")
        _, t = self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "x", "idem": "chave-a6"}, token=self.tok)
        ponte = lambda **c: self.req("POST", "/ponte/sincronizar", dict(c, ponte_id="pA6", pastas=["proj"]), token=self.cfg["chave_ponte"])
        ponte()
        ponte(tarefas=[{"id": t["id"], "status": "inventado", "atividade": "a" * 5000, "inicio": "ontem", "campo_estranho": 1}],
              executores={"codex": 1, "x" * 99: "sim"})
        real = self.tarefas_de(aid)[0]
        self.assertEqual(real["status"], "fila")
        self.assertEqual(len(real["atividade"]), 120)
        self.assertNotIn("inicio", real)
        self.assertNotIn("campo_estranho", real)
        with self.hub.trava:
            self.assertEqual(self.hub.E.ponte["executores"], {"codex": True, "x" * 20: True})
        ponte(tarefas=[{"id": t["id"], "status": "concluida", "resultado": "ok", "fim": 123}])  # atualização válida continua valendo
        real = self.tarefas_de(aid)[0]
        self.assertEqual((real["status"], real["resultado"], real["fim"]), ("concluida", "ok", 123))

    def test_a7_dispositivo_restrito_nao_mexe_em_tarefa_de_outro_projeto(self):
        aid = self.agente("a7")
        _, t = self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "x", "idem": "chave-a7"}, token=self.tok)
        c, r = self.req("POST", "/api/v1/dispositivos/parear", {"senha": SENHA, "nome": "restrito"})
        with self.hub.trava:
            next(d for d in self.hub.E.dispositivos if d["id"] == r["dispositivo"]["id"])["projetos"] = ["outro-projeto"]
        tok = r["token"]
        self.assertEqual(self.req("GET", "/api/v1/tarefas/" + t["id"], token=tok)[0], 403)
        self.assertEqual(self.req("POST", "/api/v1/tarefas/%s/cancelar" % t["id"], {}, token=tok)[0], 403)
        self.assertEqual(self.req("POST", "/api/v1/tarefas/%s/repetir" % t["id"], {"idem": "chave-a7-rep"}, token=tok)[0], 403)
        self.assertEqual(self.tarefas_de(aid)[0]["status"], "fila")
        self.assertEqual(self.req("GET", "/api/v1/tarefas/" + t["id"], token=self.tok)[0], 200)  # o dispositivo sem restrição segue normal


class Limites(Base):
    """Recursos limitados não estouram quando os pedidos chegam juntos."""

    def test_fila_nao_passa_de_30(self):
        aid = self.agente("fila")
        res = self.juntas(40, lambda i: self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "t%d" % i, "idem": "fila-%d" % i}, token=self.tok))
        with self.hub.trava:
            na_fila = sum(1 for t in self.hub.E.tarefas if t["status"] in ("fila", "rodando"))
        self.assertEqual(na_fila, 30)
        self.assertEqual(sum(1 for c, _ in res if c == 201), 30)
        self.assertTrue(all(r.get("codigo") == "fila_cheia" for c, r in res if c != 201))

class Orcamento(Base):
    def test_orcamento_esgotado_recusa_todas(self):
        aid = self.agente("orc")
        self.assertEqual(self.req("POST", "/api/v1/orcamentos", {"agente": aid, "limite_tokens": 0, "periodo": "dia", "bloquear": True}, token=self.tok)[0], 200)
        res = self.juntas(8, lambda i: self.req("POST", "/api/v1/tarefas", {"agente": aid, "texto": "x", "idem": "orc-%d" % i}, token=self.tok))
        self.assertEqual({c for c, _ in res}, {409})
        self.assertEqual(self.tarefas_de(aid), [])


class LimiteAgentes(Base):
    def test_nao_passa_de_24_agentes(self):
        res = self.juntas(32, lambda i: self.req("POST", "/api/v1/agentes", {"nome": "a%d" % i, "motor": "codex"}, token=self.tok))
        with self.hub.trava:
            self.assertEqual(len(self.hub.E.agentes), 24)
        self.assertEqual(sum(1 for c, _ in res if c == 200), 24)


class Senha(Base):
    def tearDown(self):
        with self.hub.trava:
            self.hub.E.falhas_login, self.hub.E.bloqueado_ate = 0, 0

    def test_bloqueia_depois_de_5_erros_em_sequencia(self):
        cods = [self.req("POST", "/api/v1/dispositivos/parear", {"senha": "errada-%d" % i})[0] for i in range(7)]
        self.assertEqual(cods[:5], [401] * 5)
        self.assertEqual(cods[5:], [429, 429])

    # R-1 (corrigido em 16/11/2026): a tentativa é contada sob a trava antes de conferir a senha
    def test_bloqueia_depois_de_5_erros_simultaneos(self):
        res = self.juntas(24, lambda i: self.req("POST", "/api/v1/dispositivos/parear", {"senha": "errada-%d" % i})[0])
        self.assertLessEqual(res.count(401), 5, "senhas conferidas na mesma rajada: %d" % res.count(401))


class Disco(Base):
    # R-2 (corrigido em 16/11/2026): arquivo temporário próprio por gravação e uma gravação por vez
    def test_gravacoes_simultaneas_do_mesmo_arquivo(self):
        erros = []

        def gravar(i):
            try:
                for k in range(150):
                    self.hub.gravar("colisao.json", {"de": i, "k": k, "enchimento": "x" * 20000})
            except Exception as e:  # noqa: BLE001 (o teste quer exatamente saber se alguma gravação falha)
                erros.append(type(e).__name__)
        self.juntas(4, gravar)
        self.assertEqual(erros, [])
        with open(os.path.join(self.tmp, "colisao.json"), encoding="utf-8") as f:
            json.load(f)
        self.assertEqual([n for n in os.listdir(self.tmp) if n.endswith(".tmp")], [])

    def test_retrato_antigo_nao_sobrescreve_o_novo(self):
        """O gravador em lote pode chegar atrasado ao disco; a cópia velha não pode apagar a nova."""
        antiga = next(self.hub._serie)
        self.hub.gravar("ordem.json", {"versao": "nova"})          # gravação imediata, tirada depois
        self.hub.gravar("ordem.json", {"versao": "antiga"}, antiga)  # retrato anterior chegando atrasado
        with open(os.path.join(self.tmp, "ordem.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["versao"], "nova")


if __name__ == "__main__":
    unittest.main()
