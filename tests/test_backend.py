# -*- coding: utf-8 -*-
"""
Testes da Central v2 e da ponte. Usam AMOSTRAS SINTÉTICAS (marcadas como tal) em
pasta temporária; nunca tocam dados reais. Rodar:  python -m unittest tests.test_backend -v
"""
import importlib
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(RAIZ, "celular"))
sys.path.insert(0, RAIZ)
import metricas as M  # noqa: E402


class TestFormulas(unittest.TestCase):
    def test_percentil_linear(self):
        self.assertEqual(M.percentil([1, 2, 3, 4], 50), 2.5)
        self.assertAlmostEqual(M.percentil(list(range(1, 101)), 95), 95.05)
        self.assertIsNone(M.percentil([], 50))
        self.assertEqual(M.percentil([None, 7], 95), 7.0)

    def test_resumo_amostra_pequena(self):
        r = M.resumo([100, 200, None])
        self.assertEqual(r["n"], 2)
        self.assertIn("amostra pequena", r["aviso"])
        self.assertEqual(M.resumo([])["media"], None)

    def test_orcamento_casos(self):
        self.assertEqual(M.orcamento(500, None)["estado"], "limite_desconhecido")
        self.assertIsNone(M.orcamento(500, None)["percentual_consumido"])
        z = M.orcamento(10, 0)
        self.assertEqual(z["estado"], "limite_zero")
        self.assertIsNone(z["percentual_consumido"])
        self.assertEqual(M.orcamento(None, 1000)["estado"], "sem_dados")
        self.assertIsNone(M.orcamento(None, 1000)["percentual_consumido"])  # nunca 0% por falta de dado
        e = M.orcamento(1500, 1000)
        self.assertEqual(e["percentual_consumido"], 150.0)
        self.assertEqual(e["saldo_tokens"], 0)
        self.assertEqual(e["percentual_disponivel"], 0.0)
        self.assertEqual(e["excesso_tokens"], 500)
        self.assertEqual(e["estado"], "excedido")
        s = M.orcamento(0, 1000)
        self.assertEqual(s["estado"], "sem_uso")
        self.assertEqual(s["percentual_consumido"], 0.0)

    def test_total_tokens_sem_dupla_contagem(self):
        openai = {"entrada": 1000, "saida": 50, "cache_leitura": 800, "entrada_inclui_cache": True}
        anth = {"entrada": 10, "saida": 50, "cache_leitura": 800, "cache_criacao": 200, "entrada_inclui_cache": False}
        self.assertEqual(M.total_tokens(openai), 1050)
        self.assertEqual(M.total_tokens(anth), 1060)
        self.assertIsNone(M.total_tokens({}))

    def test_periodo_e_fuso(self):
        tz, _ = M.fuso("America/Sao_Paulo")
        # 2026-11-16 02:30 UTC = 2026-11-15 23:30 em São Paulo -> o "dia" ainda é 15/11 local
        agora = 1794795000000
        ini = M.inicio_periodo("dia", agora, tz)
        self.assertEqual(ini, 1794711600000)  # 2026-11-15 00:00 -03:00
        self.assertEqual(M.proxima_renovacao("dia", agora, tz), ini + 86_400_000)
        semana = M.inicio_periodo("semana", agora, tz)
        self.assertLessEqual(semana, ini)
        from datetime import timezone
        self.assertNotEqual(M.inicio_periodo("dia", agora, timezone.utc), ini)

    def test_alertas_e_projecao(self):
        self.assertEqual(M.alertas_cruzados(86, [70, 85, 95]), 85)
        self.assertIsNone(M.alertas_cruzados(None, [70]))
        self.assertIsNone(M.projecao_esgotamento([(1, 10)], 100, 0, 3_600_000))  # amostra insuficiente

    def test_taxa_sucesso_denominador(self):
        ts = [{"status": "concluida"}, {"status": "falhou"}, {"status": "cancelada"}, {"status": "fila"}]
        r = M.taxa_sucesso(ts)
        self.assertEqual(r["denominador"], 2)
        self.assertEqual(r["sucesso_percentual"], 50.0)
        self.assertIsNone(M.taxa_sucesso([{"status": "cancelada"}])["sucesso_percentual"])

    def test_tempos_mesmo_relogio(self):
        t = {"criado": 1000, "eventos": [{"ev": "criada", "t": 1000}, {"ev": "entregue_ponte", "t": 1500},
                                         {"ev": "fim_recebido", "t": 9000}],
             "medidas": {"espera_local_ms": 20, "execucao_ms": 7000}}
        tp = M.tempos_tarefa(t)
        self.assertEqual(tp["espera_fila_ms"], 520)
        self.assertEqual(tp["execucao_ms"], 7000)
        self.assertEqual(tp["total_tarefa_ms"], 8000)
        legado = M.tempos_tarefa({"criado": 5, "inicio": 1, "fim": 9})  # relógios diferentes: não calcula
        self.assertIsNone(legado["espera_fila_ms"])
        self.assertIsNone(legado["total_tarefa_ms"])


class CentralTeste(unittest.TestCase):
    """Sobe a Central real numa porta livre com dados temporários."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="central_teste_")
        import hub
        cls.hub = importlib.reload(hub)
        cls.hub.DADOS = cls.tmp
        cls.hub.NOTAS = os.path.join(cls.tmp, "notas")
        cls.hub.CONFIG = os.path.join(cls.tmp, "config.json")
        cfg, cls.senha = cls.hub.criar_config("senha-de-teste-sintetica")
        cls.hub.carregar()
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), cls.hub.H)
        cls.url = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.chave_ponte = cfg["chave_ponte"]

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def req(self, metodo, rota, corpo=None, token=None, extra=None):
        h = {"Content-Type": "application/json", "X-Requested-With": "escritorio-android"}
        if token:
            h["Authorization"] = "Bearer " + token
        h.update(extra or {})
        r = urllib.request.Request(self.url + rota, method=metodo, headers=h,
                                   data=json.dumps(corpo).encode() if corpo is not None else None)
        try:
            with urllib.request.urlopen(r, timeout=10) as resp:
                return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def ponte(self, pid, **corpo):
        corpo.setdefault("pastas", ["proj"])
        return self.req("POST", "/ponte/sincronizar", dict(corpo, ponte_id=pid), token=self.chave_ponte)

    def token(self):
        if not hasattr(self.__class__, "_tok"):
            c, r = self.req("POST", "/api/v1/dispositivos/parear", {"senha": "senha-de-teste-sintetica", "nome": "teste"})
            self.assertEqual(c, 201, r)
            self.__class__._tok = r["token"]
        return self.__class__._tok

    def agente(self, nome="Agente sintético"):
        c, r = self.req("POST", "/api/v1/agentes", {"nome": nome, "motor": "codex", "pasta": "proj"}, token=self.token())
        self.assertEqual(c, 200, r)
        return r["id"]

    def test_01_autenticacao(self):
        c, _ = self.req("GET", "/api/v1/estado")
        self.assertEqual(c, 401)
        c, _ = self.req("POST", "/api/v1/dispositivos/parear", {"senha": "errada"})
        self.assertEqual(c, 401)
        self.hub.E.falhas_login = 0
        c, r = self.req("GET", "/api/v1/estado", token=self.token())
        self.assertEqual(c, 200)
        self.assertIn("componentes", r)
        c, _ = self.req("GET", "/api/v1/estado", token="token-invalido-sintetico")
        self.assertEqual(c, 401)
        c, _ = self.req("GET", "/api/v1/estado", token=self.chave_ponte)  # chave da ponte NÃO serve para o app
        self.assertEqual(c, 401)

    def test_02_revogacao(self):
        c, r = self.req("POST", "/api/v1/dispositivos/parear", {"senha": "senha-de-teste-sintetica", "nome": "descartavel"})
        tok = r["token"]
        self.assertEqual(self.req("POST", "/api/v1/sessao/sair", {}, token=tok)[0], 200)
        self.assertEqual(self.req("GET", "/api/v1/estado", token=tok)[0], 401)

    def test_03_idempotencia_resposta_perdida(self):
        ag = self.agente()
        corpo = {"agente": ag, "texto": "tarefa sintética", "idem": "idem-sintetico-1"}
        c1, r1 = self.req("POST", "/api/v1/tarefas", corpo, token=self.token())
        # a resposta "se perdeu": o app reenvia com o MESMO identificador
        c2, r2 = self.req("POST", "/api/v1/tarefas", corpo, token=self.token())
        self.assertEqual(c1, 201)
        self.assertEqual(c2, 200)
        self.assertEqual(r1["id"], r2["id"])
        self.assertTrue(r2["repetida"])
        self.assertEqual(sum(1 for t in self.hub.E.tarefas if t.get("idem") == "idem-sintetico-1"), 1)

    def test_04_lease_duas_pontes(self):
        ag = self.agente("Lease")
        c, t = self.req("POST", "/api/v1/tarefas", {"agente": ag, "texto": "x", "idem": "idem-lease"}, token=self.token())
        _, ra = self.ponte("pA")
        _, rb = self.ponte("pB")
        self.assertIn(t["id"], [x["id"] for x in ra["tarefas"]])
        self.assertNotIn(t["id"], [x["id"] for x in rb["tarefas"]])
        # ponte B tenta concluir: rejeitado
        self.ponte("pB", tarefas=[{"id": t["id"], "status": "concluida", "resultado": "intruso"}])
        tt = next(x for x in self.hub.E.tarefas if x["id"] == t["id"])
        self.assertEqual(tt["status"], "fila")
        self.ponte("pA", tarefas=[{"id": t["id"], "status": "rodando"}])
        self.ponte("pA", tarefas=[{"id": t["id"], "status": "concluida", "resultado": "ok",
                                   "medidas": {"execucao_ms": 1234.5, "espera_local_ms": 3},
                                   "uso": [{"chamada_id": "c1", "entrada": 100, "saida": 10, "entrada_inclui_cache": True, "final": True}]}])
        self.assertEqual(tt["status"], "concluida")
        # final é definitivo: atualização posterior de "rodando" não regride
        self.ponte("pA", tarefas=[{"id": t["id"], "status": "rodando"}])
        self.assertEqual(tt["status"], "concluida")
        evs = [e["ev"] for e in tt["eventos"]]
        for ev in ("criada", "entregue_ponte", "inicio_recebido", "fim_recebido"):
            self.assertIn(ev, evs)
        c, det = self.req("GET", "/api/v1/tarefas/" + t["id"], token=self.token())
        self.assertEqual(det["tempos"]["execucao_ms"], 1234.5)
        self.assertEqual(det["trilha"][0]["etapa"], "origem")
        c, diag = self.req("GET", "/api/v1/diagnostico", token=self.token())
        self.assertTrue(any("pontes ativas" in a for a in diag["componentes"]["ponte"]["avisos"]))

    def test_05_uso_deduplicado_e_reconciliado(self):
        ag = self.agente("Uso")
        _, t = self.req("POST", "/api/v1/tarefas", {"agente": ag, "texto": "x", "idem": "idem-uso"}, token=self.token())
        self.ponte("pU")
        up = {"id": t["id"], "status": "rodando", "uso": [{"chamada_id": "s:resultado", "entrada": 10, "saida": 5, "final": True}]}
        self.ponte("pU", tarefas=[up])
        self.ponte("pU", tarefas=[up])  # retransmissão
        self.ponte("pU", tarefas=[{"id": t["id"], "status": "rodando", "uso": [{"chamada_id": "s:resultado", "entrada": 1, "saida": 1, "final": False}]}])
        regs = [u for u in self.hub.E.uso if u["tarefa"] == t["id"]]
        self.assertEqual(len(regs), 1)
        self.assertEqual(regs[0]["entrada"], 10)  # parcial atrasado não sobrescreve o final

    def test_06_orcamento_bloqueio(self):
        ag = self.agente("Orçamento")
        c, r = self.req("POST", "/api/v1/orcamentos", {"agente": ag, "limite_tokens": 0, "periodo": "dia", "bloquear": True}, token=self.token())
        self.assertEqual(c, 200)
        c, r = self.req("POST", "/api/v1/tarefas", {"agente": ag, "texto": "x", "idem": "idem-orc"}, token=self.token())
        self.assertEqual(c, 409)
        self.assertEqual(r["codigo"], "orcamento_esgotado")
        c, tk = self.req("GET", "/api/v1/tokens?agente=" + ag, token=self.token())
        item = tk["agentes"][0]
        self.assertEqual(item["orcamento_local"]["estado"], "sem_dados")  # sem registro de uso: não é "0%"
        self.assertFalse(item["cota_provedor"]["disponivel"])

    def test_07_repetir_e_cancelar(self):
        ag = self.agente("Repetir")
        _, t = self.req("POST", "/api/v1/tarefas", {"agente": ag, "texto": "x", "idem": "idem-rep"}, token=self.token())
        c, _ = self.req("POST", "/api/v1/tarefas/%s/repetir" % t["id"], {"idem": "idem-rep-2"}, token=self.token())
        self.assertEqual(c, 409)  # ainda não finalizada
        c, r = self.req("POST", "/api/v1/tarefas/%s/cancelar" % t["id"], {}, token=self.token())
        self.assertEqual(r["status"], "cancelada")
        c, n = self.req("POST", "/api/v1/tarefas/%s/repetir" % t["id"], {"idem": "idem-rep-2"}, token=self.token())
        self.assertEqual(c, 201)
        self.assertEqual(n["tentativa"], 2)
        self.assertEqual(n["trace_id"], t["trace_id"])
        self.assertEqual(n["tentativa_de"], t["id"])

    def test_08_nota_conflito_e_idempotencia(self):
        c, r = self.req("POST", "/api/v1/notas", {"titulo": "Nota sintética", "conteudo": "x", "idem": "idem-nota"}, token=self.token())
        c2, r2 = self.req("POST", "/api/v1/notas", {"titulo": "Nota sintética", "conteudo": "x", "idem": "idem-nota"}, token=self.token())
        self.assertEqual(r["op"], r2["op"])
        _, resp = self.ponte("pN")
        self.assertIn(r["op"], [o["id"] for o in resp["ops"]])
        _, resp2 = self.ponte("pOutra")
        self.assertNotIn(r["op"], [o["id"] for o in resp2["ops"]])  # só uma ponte recebe
        self.ponte("pN", ops=[{"id": r["op"], "ok": False, "conflito": True, "msg": "mudou"}])
        _, st = self.req("GET", "/api/v1/ops/" + r["op"], token=self.token())
        self.assertEqual(st["status"], "conflito")
        c, _ = self.req("POST", "/api/v1/notas", {"nota": "nota:00-Inbox/x.md", "conteudo": "y"}, token=self.token())
        self.assertEqual(c, 400)  # edição sem versão base é recusada

    def test_13_openbot_agentes_pela_ponte(self):
        """A ponte informa os agentes do OpenBot; a Central guarda só campos conhecidos e os expõe ao app."""
        tok = self.token()
        c, _ = self.ponte("pOB", **{"openbot": {"lido_em": 1, "agentes": [
            {"id": "agent-1", "nome": "gemini", "provedor": "GEMINI", "modelo": "m", "trabalhando": True, "tokens_24h": 120,
             "sem_cota": "sim", "texto_secreto": "nao deve passar", "custo_24h_usd": 0.5}, "lixo"]}})
        self.assertEqual(c, 200)
        c, e = self.req("GET", "/api/v1/estado", token=tok)
        ags = e["openbot_agentes"]["agentes"]
        self.assertEqual(len(ags), 1)
        self.assertEqual((ags[0]["provedor"], ags[0]["trabalhando"], ags[0]["tokens_24h"], ags[0]["sem_cota"]), ("gemini", True, 120, False))
        self.assertNotIn("texto_secreto", ags[0])

    def test_09_web_legado_continua(self):
        c, _ = self.req("POST", "/api/login", {"senha": "senha-de-teste-sintetica"}, extra={"X-Requested-With": "escritorio"})
        self.assertEqual(c, 200)

    def test_11_keep_alive_reaproveita_conexao(self):
        import http.client
        c = http.client.HTTPConnection("127.0.0.1", self.srv.server_address[1], timeout=5)
        for _ in range(3):  # mesma conexão TCP, três pedidos seguidos
            c.request("GET", "/api/v1/info")
            r = c.getresponse(); r.read()
            self.assertEqual(r.status, 200)
            self.assertEqual(r.version, 11)
        # pedido recusado sem ler o corpo: a Central fecha a conexão (não mistura bytes)
        c.request("POST", "/ponte/sincronizar", body=b'{"x": 1}', headers={"Authorization": "Bearer errada", "Content-Type": "application/json"})
        r = c.getresponse(); r.read()
        self.assertEqual(r.status, 401)
        self.assertEqual(r.getheader("Connection"), "close")
        c.close()

    def test_12_revogacao_cli_nao_e_desfeita(self):
        mem = [{"id": "dvA", "visto": 5}, {"id": "dvB", "visto": 1}]
        disco = [{"id": "dvA", "visto": 2, "revogado": 99}, {"id": "dvB", "visto": 3}, {"id": "dvC", "visto": 0}]
        r = {d["id"]: d for d in self.hub.mesclar_dispositivos(mem, disco)}
        self.assertEqual(r["dvA"]["revogado"], 99)
        self.assertEqual(r["dvA"]["visto"], 5)
        self.assertEqual(r["dvB"]["visto"], 3)
        self.assertIn("dvC", r)

    def test_10_logs_sem_segredo(self):
        self.hub.registrar("info", "teste", "x", "Authorization: Bearer abcdefghijklmnop senha=123456", token="zzz")
        _, r = self.req("GET", "/api/v1/logs?comp=teste", token=self.token())
        txt = json.dumps(r)
        self.assertNotIn("abcdefghijklmnop", txt)
        self.assertNotIn("123456", txt)
        self.assertNotIn("zzz", txt)


class TestPonte(unittest.TestCase):
    def test_leitor_codex(self):
        import ponte
        L = ponte.Leitor("codex")
        for s in ['{"type":"thread.started","thread_id":"th-sintetico"}',
                  '{"type":"item.completed","item":{"type":"agent_message","text":"PONTE OK"}}',
                  '{"type":"turn.completed","usage":{"input_tokens":900,"cached_input_tokens":800,"output_tokens":12}}']:
            L.linha(s)
        self.assertEqual(L.resultado(), "PONTE OK")
        u = L.registros_uso()
        self.assertEqual(len(u), 1)
        self.assertTrue(u[0]["entrada_inclui_cache"])
        self.assertEqual(M.total_tokens(u[0]), 912)

    def test_leitor_claude_reconcilia(self):
        import ponte
        L = ponte.Leitor("claude")
        for s in ['{"type":"system","subtype":"init","model":"modelo-sintetico","session_id":"s1"}',
                  '{"type":"assistant","message":{"id":"m1","model":"modelo-sintetico","usage":{"input_tokens":3,"output_tokens":2}}}',
                  '{"type":"result","result":"feito","session_id":"s1","usage":{"input_tokens":5,"output_tokens":7,"cache_read_input_tokens":100,"cache_creation_input_tokens":0},"total_cost_usd":0.01}']:
            L.linha(s)
        u = L.registros_uso()
        self.assertEqual(len(u), 1)  # parcial substituído pelo total final
        self.assertEqual(M.total_tokens(u[0]), 112)
        self.assertEqual(u[0]["modelo"], "modelo-sintetico")

    def test_ops_aplicadas_persistem(self):
        import ponte
        tmp = tempfile.mkdtemp()
        try:
            ponte.ARQ_OPS = os.path.join(tmp, "ops.json")
            a = ponte.OpsAplicadas()
            a.marcar("op1", {"id": "op1", "ok": True})
            self.assertEqual(ponte.OpsAplicadas().get("op1")["ok"], True)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_trava_unica(self):
        import ponte
        tmp = tempfile.mkdtemp()
        try:
            t1 = ponte.TravaUnica(os.path.join(tmp, "l"))  # noqa: F841
            with self.assertRaises(RuntimeError):
                ponte.TravaUnica(os.path.join(tmp, "l"))
        finally:
            t1.f.close()
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
