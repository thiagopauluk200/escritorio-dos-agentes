"""Regressões do lançamento Android: HTTPS, convite, sessões e revogação."""
import hashlib
import http.client
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "rede"))
import servidor


class TesteAcessoAndroid(unittest.TestCase):
    def setUp(self):
        temporario = tempfile.TemporaryDirectory()
        self.addCleanup(temporario.cleanup)
        pasta = Path(temporario.name)
        self.pasta = pasta
        vault = pasta / "vault"
        vault.mkdir()
        (vault / "amostra.md").write_text("# Amostra", encoding="utf-8")
        cfg = servidor.argumentos(["--vault", str(vault), "--porta", "0", "--ip", "127.0.0.1",
                                   "--porta-app", "0", "--token", str(pasta / "token")])
        self.web = servidor.criar_servidor(cfg)
        self.addCleanup(self.web.server_close)
        self.lan, self.pin = servidor.criar_lan(cfg, self.web.estado, str(pasta))
        self.addCleanup(self.lan.server_close)
        threading.Thread(target=self.lan.serve_forever, daemon=True).start()
        self.addCleanup(self.lan.shutdown)

    def pedir(self, rota, dados=None, sessao=None, origem=None, pin=None):
        # A pinagem precede o envio de qualquer cabeçalho ou senha, como no app.
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        c = http.client.HTTPSConnection("127.0.0.1", self.lan.server_port, context=ctx, timeout=5)
        try:
            c.connect()
            recebido = hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest()
            if recebido != (pin or self.pin):
                raise ssl.SSLError("Identidade incorreta")
            headers = {}
            if sessao:
                headers["Authorization"] = "Bearer " + sessao
            if origem:
                headers["Origin"] = origem
            if dados is not None:
                headers["Content-Type"] = "application/json"
                c.request("POST", rota, json.dumps(dados), headers)
            else:
                c.request("GET", rota, headers=headers)
            r = c.getresponse()
            return r.status, json.load(r)
        finally:
            c.close()

    def test_conta_e_vault(self):
        cadastro = {"usuario": "teste.local", "senha": "Frase de teste longa e privada."}
        self.assertEqual(self.pedir("/api/grafo")[0], 401)
        self.assertEqual(self.pedir("/api/app/cadastro", cadastro)[0], 403)
        self.assertEqual(self.pedir("/api/app/login", cadastro, origem="https://example.com")[0], 403)
        with self.assertRaises(ssl.SSLError):
            self.pedir("/api/app/login", cadastro, pin="0" * 64)
        convite = dict(cadastro, codigo=self.lan.contas.convite)
        status, resultado = self.pedir("/api/app/cadastro", convite)
        self.assertEqual(status, 200)
        sessao = resultado["token"]
        self.assertEqual(self.pedir("/api/app/cadastro", convite)[0], 409)
        status, grafo = self.pedir("/api/grafo", sessao=sessao)
        self.assertEqual(status, 200)
        self.assertEqual(grafo["metricas"]["notas"], 1)
        self.assertEqual(self.pedir("/api/nota?id=amostra.md", sessao=sessao)[0], 200)
        self.assertEqual(self.pedir("/api/app/login", dict(cadastro, senha="senha de teste errada"))[0], 401)
        status, outra = self.pedir("/api/app/login", cadastro)
        self.assertEqual(status, 200)
        persistida = servidor.Contas(str(self.pasta / "contas.sqlite3"), "novo-convite")
        self.assertTrue(persistida.autenticar(outra["token"]))
        self.assertEqual(self.pedir("/api/app/sair", {}, sessao=sessao)[0], 200)
        self.assertFalse(persistida.autenticar(sessao))
        self.assertNotIn(cadastro["senha"].encode(), (self.pasta / "contas.sqlite3").read_bytes())
        self.assertNotIn(outra["token"].encode(), (self.pasta / "contas.sqlite3").read_bytes())

    def test_uma_instancia_por_endereco(self):
        with self.assertRaises(OSError):
            servidor.Servidor(self.web.server_address, self.web.estado).server_close()


if __name__ == "__main__":
    unittest.main()
