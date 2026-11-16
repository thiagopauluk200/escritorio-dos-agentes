#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Testes de caracterização da Central: registram o comportamento de HOJE das rotas que tinham pouca cobertura
(painel web antigo `/api/*` e protocolo MCP do OpenBot), para proteger a refatoração do hub.py.

Não julgam se o comportamento é o ideal: se um destes testes quebrar durante a refatoração, alguma coisa
mudou sem querer. Mudança proposital de comportamento = atualizar o teste no mesmo commit, explicando.
"""
import json
import os
import sys
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_concorrencia import Base, SENHA  # noqa: E402  (mesma Central real em porta livre)


class PainelWebAntigo(Base):
    def web(self, metodo, rota, corpo=None, cookie=None, cabecalho="escritorio"):
        h = {"Content-Type": "application/json"}
        if cabecalho:
            h["X-Requested-With"] = cabecalho
        if cookie:
            h["Cookie"] = cookie
        r = urllib.request.Request(self.url + rota, method=metodo, headers=h, data=json.dumps(corpo).encode() if corpo is not None else None)
        try:
            with urllib.request.urlopen(r, timeout=20) as resp:
                bruto = resp.read()
                return resp.status, (json.loads(bruto) if resp.headers.get_content_type() == "application/json" else bruto), resp.headers
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}"), e.headers

    def entrar(self):
        c, r, h = self.web("POST", "/api/login", {"senha": SENHA})
        self.assertEqual((c, r), (200, {"ok": True}))
        ck = h.get("Set-Cookie")
        for parte in ("sessao=", "Path=/", "HttpOnly", "SameSite=Strict", "Max-Age=1209600"):
            self.assertIn(parte, ck)
        self.assertNotIn("Secure", ck)  # http local: sem Secure; pelo túnel https o cabeçalho entra
        return ck.split(";")[0]

    def test_pagina_e_cabecalhos_de_seguranca(self):
        c, corpo, h = self.web("GET", "/")
        self.assertEqual(c, 200)
        self.assertIn(b"<title>Escrit", corpo)
        self.assertEqual(h.get_content_type(), "text/html")
        self.assertIn("default-src 'self'", h.get("Content-Security-Policy"))
        self.assertEqual((h.get("X-Frame-Options"), h.get("X-Content-Type-Options"), h.get("Cache-Control"), h.get("Referrer-Policy")),
                         ("DENY", "nosniff", "no-store", "no-referrer"))

    def test_sem_login_e_sem_cabecalho(self):
        self.assertEqual(self.web("GET", "/api/estado")[:2], (401, {"erro": "login"}))
        self.assertEqual(self.web("GET", "/api/nota?id=x")[:2], (401, {"erro": "login"}))
        self.assertEqual(self.web("POST", "/api/tarefas", {"agente": "x", "texto": "y"})[:2], (401, {"erro": "login"}))
        self.assertEqual(self.web("POST", "/api/login", {"senha": SENHA}, cabecalho=None)[:2], (400, {"erro": "requisição inválida"}))
        self.assertEqual(self.web("POST", "/api/login", {"senha": "errada"})[:2], (401, {"erro": "Senha incorreta."}))
        with self.hub.trava:
            self.hub.E.falhas_login, self.hub.E.bloqueado_ate = 0, 0
        self.assertEqual(self.web("GET", "/rota-que-nao-existe")[:2], (404, {"erro": "não encontrado"}))

    def test_estado_tem_os_campos_que_o_painel_usa(self):
        ck = self.entrar()
        c, e, _ = self.web("GET", "/api/estado", cookie=ck)
        self.assertEqual(c, 200)
        self.assertEqual(set(e), {"agora", "ponte", "openbot", "estado", "agentes", "tarefas", "ops", "fluxo"})
        self.assertEqual(set(e["ponte"]), {"online", "visto", "executores", "pastas"})
        self.assertEqual(set(e["openbot"]), {"online", "visto"})
        self.assertEqual(set(e["fluxo"]), {"janela_s", "serie_entrada", "serie_saida", "taxa", "canais", "req_min", "total_bytes", "recentes"})

    def test_agente_tarefa_e_cancelamento(self):
        ck = self.entrar()
        self.assertEqual(self.web("POST", "/api/agentes", {"nome": "", "motor": "codex"}, ck)[:2],
                         (400, {"erro": "Dê um nome ao agente.", "codigo": "entrada_invalida"}))
        self.assertEqual(self.web("POST", "/api/agentes", {"nome": "Web antigo", "motor": "nao-existe", "pasta": "proj"}, ck)[:2], (200, {"ok": True}))
        _, e, _ = self.web("GET", "/api/estado", cookie=ck)
        ag = next(a for a in e["agentes"] if a["nome"] == "Web antigo")
        self.assertEqual((ag["motor"], ag["status"], ag["na_fila"], ag["pasta"]), ("codex", "ocioso", 0, "proj"))  # motor desconhecido vira codex
        self.assertEqual(self.web("POST", "/api/tarefas", {"agente": ag["id"], "texto": ""}, ck)[:2],
                         (400, {"erro": "Escreva a tarefa e escolha um agente."}))
        self.assertEqual(self.web("POST", "/api/tarefas", {"agente": ag["id"], "texto": "tarefa pelo painel"}, ck)[:2], (200, {"ok": True}))
        _, e, _ = self.web("GET", "/api/estado", cookie=ck)
        t = next(x for x in e["tarefas"] if x["agente"] == ag["id"])
        self.assertEqual((t["status"], t["origem"], t["texto"], t["tentativa"], t["projeto"]), ("fila", "web", "tarefa pelo painel", 1, "proj"))
        self.assertEqual(next(a for a in e["agentes"] if a["id"] == ag["id"])["status"], "fila")
        self.assertEqual(self.web("POST", "/api/tarefas", {"acao": "cancelar", "id": t["id"]}, ck)[:2], (200, {"ok": True}))
        _, e, _ = self.web("GET", "/api/estado", cookie=ck)
        self.assertEqual(next(x for x in e["tarefas"] if x["id"] == t["id"])["status"], "cancelada")
        self.assertEqual(self.web("POST", "/api/agentes", {"acao": "excluir", "id": ag["id"]}, ck)[:2], (200, {"ok": True}))
        _, e, _ = self.web("GET", "/api/estado", cookie=ck)
        self.assertFalse(any(a["id"] == ag["id"] for a in e["agentes"]))

    def test_notas(self):
        ck = self.entrar()
        self.assertEqual(self.web("POST", "/api/notas", {"conteudo": "sem título"}, ck)[:2],
                         (400, {"erro": "Dê um título à nota.", "codigo": "entrada_invalida"}))
        self.assertEqual(self.web("POST", "/api/notas", {"nota": "nota:x.md", "conteudo": "c"}, ck)[:2],
                         (400, {"erro": "Versão base da nota ausente; reabra a nota antes de editar.", "codigo": "sem_versao"}))
        c, r, _ = self.web("POST", "/api/notas", {"titulo": "Nota do painel antigo", "conteudo": "c"}, ck)
        self.assertEqual((c, set(r), r["ok"]), (200, {"ok", "op"}, True))
        _, e, _ = self.web("GET", "/api/estado", cookie=ck)
        op = next(o for o in e["ops"] if o["id"] == r["op"])
        self.assertEqual((op["status"], op["tipo"], op["titulo"], op["por"]), ("pendente", "criar", "Nota do painel antigo", "web"))
        self.assertEqual(self.web("GET", "/api/nota?id=nota:nao-existe.md", cookie=ck)[:2],
                         (404, {"erro": "A nota ainda não foi sincronizada pelo PC."}))

    def test_grafico_tipo_invalido(self):
        ck = self.entrar()
        c, r, _ = self.web("GET", "/api/grafico?tipo=nao-existe", cookie=ck)
        self.assertIn(c, (400, 503))  # 503 quando o Matplotlib não está instalado (caso deste PC)
        self.assertIn("erro", r)


class ProtocoloMcp(Base):
    def mcp(self, pedido, chave=None):
        h = {"Content-Type": "application/json", "Authorization": "Bearer " + (chave or self.cfg["chave_openbot"])}
        r = urllib.request.Request(self.url + "/openbot/mcp", method="POST", headers=h, data=json.dumps(pedido).encode())
        try:
            with urllib.request.urlopen(r, timeout=20) as resp:
                bruto = resp.read()
                return resp.status, (json.loads(bruto) if bruto else None), resp.headers
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}"), e.headers

    def chamar(self, nome, args):
        c, r, _ = self.mcp({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": nome, "arguments": args}})
        self.assertEqual((c, r["jsonrpc"], r["id"]), (200, "2.0", 5))
        return r["result"]

    def test_handshake(self):
        c, r, h = self.mcp({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertEqual(c, 200)
        self.assertEqual(r, {"jsonrpc": "2.0", "id": 1, "result": {
            "protocolVersion": "2025-03-26", "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "escritorio-openbot", "version": "1.0.0"}}})
        self.assertEqual(h.get("MCP-Protocol-Version"), "2025-03-26")
        c, r, h = self.mcp({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual((c, r, h.get("MCP-Protocol-Version")), (202, None, "2025-03-26"))
        self.assertEqual(self.mcp({"jsonrpc": "2.0", "id": 2, "method": "ping"})[1], {"jsonrpc": "2.0", "id": 2, "result": {}})

    def test_recusas(self):
        self.assertEqual(self.mcp({"jsonrpc": "2.0", "id": 1, "method": "ping"}, chave="chave-errada-sintetica")[:2],
                         (401, {"erro": "autenticação OpenBot inválida"}))
        self.assertEqual(self.mcp({"jsonrpc": "2.0", "id": 1, "method": "ping"}, chave=self.cfg["chave_ponte"])[0], 401)  # chave da ponte não serve
        self.assertEqual(self.mcp({"jsonrpc": "2.0", "id": 1, "method": 7})[:2], (400, {"erro": "pedido MCP inválido"}))
        self.assertEqual(self.mcp({"jsonrpc": "2.0", "id": 3, "method": "recurso/inexistente"})[1],
                         {"jsonrpc": "2.0", "id": 3, "error": {"code": -32601, "message": "método MCP desconhecido"}})

    def test_ferramentas(self):
        _, r, _ = self.mcp({"jsonrpc": "2.0", "id": 4, "method": "tools/list"})
        ferramentas = r["result"]["tools"]
        self.assertEqual([f["name"] for f in ferramentas], ["listar_projetos_agentes", "enviar_tarefa", "consultar_tarefa"])
        self.assertTrue(all(f["inputSchema"]["additionalProperties"] is False for f in ferramentas))
        self.assertEqual(ferramentas[1]["inputSchema"]["required"], ["texto", "projeto", "agente"])

        aid = self.agente("mcp")
        cat = json.loads(self.chamar("listar_projetos_agentes", {})["content"][0]["text"])
        self.assertEqual(set(cat), {"agentes", "projetos"})
        self.assertIn("proj", cat["projetos"])
        self.assertTrue(any(a["id"] == aid for a in cat["agentes"]))

        erro = self.chamar("enviar_tarefa", {"agente": aid, "projeto": "projeto-nao-liberado", "texto": "x"})
        self.assertEqual((erro["isError"], json.loads(erro["content"][0]["text"])), (True, {"erro": "projeto não está liberado pela ponte"}))
        erro = self.chamar("enviar_tarefa", {"agente": "ag-inexistente", "projeto": "proj", "texto": "x"})
        self.assertEqual(json.loads(erro["content"][0]["text"]), {"erro": "mensagem ou agente inválido"})

        ok = self.chamar("enviar_tarefa", {"agente": aid, "projeto": "proj", "texto": "pedido de caracterização"})
        env = json.loads(ok["content"][0]["text"])
        self.assertEqual((ok["isError"], set(env), env["status"]), (False, {"id", "status"}, "fila"))
        t = json.loads(self.chamar("consultar_tarefa", {"id": env["id"]})["content"][0]["text"])
        self.assertEqual((t["origem"], t["texto"], t["status"]), ("openbot", "Projeto: proj\n\npedido de caracterização", "fila"))
        with self.hub.trava:  # a mensagem também vira uma nota pendente para 00-Inbox
            op = next(o for o in self.hub.E.ops if o.get("tarefa") == env["id"])
        self.assertEqual((op["tipo"], op["titulo"], op["status"]), ("criar", "OpenBot - pedido de caracterização", "pendente"))

        nao = self.chamar("consultar_tarefa", {"id": "t-inexistente"})
        self.assertEqual((nao["isError"], json.loads(nao["content"][0]["text"])), (True, {"erro": "tarefa não encontrada"}))
        self.assertEqual(self.chamar("ferramenta_que_nao_existe", {}), {"content": [{"type": "text", "text": "ferramenta desconhecida"}], "isError": True})

    def test_rotas_diretas_do_openbot(self):
        ch = self.cfg["chave_openbot"]
        self.assertEqual(self.req("POST", "/openbot/sinal", {"ok": 1}, token=ch), (200, {"ok": True}))
        self.assertEqual(self.req("POST", "/openbot/sinal", {"ok": 1}, token="errada")[0], 401)
        c, cat = self.req("GET", "/openbot/catalogo", token=ch)
        self.assertEqual((c, set(cat)), (200, {"agentes", "projetos"}))
        self.assertEqual(self.req("GET", "/openbot/tarefa?id=nao-existe", token=ch), (404, {"erro": "tarefa não encontrada"}))
        with self.hub.trava:
            self.assertGreater(self.hub.E.openbot_visto, 0)  # qualquer contato autenticado conta como sinal de vida


if __name__ == "__main__":
    unittest.main()
