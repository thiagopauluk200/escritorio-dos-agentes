"""Conta única da instalação e sessões persistentes do aplicativo."""
import hashlib
import hmac
import re
import secrets
import sqlite3
import threading
import time
from contextlib import closing

VIDA_SESSAO = 30 * 24 * 3600
ITERACOES = 600_000


class Contas:
    def __init__(self, arquivo, convite):
        self.arquivo = arquivo
        self.convite = convite
        self.trava = threading.Lock()
        with closing(self._db()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS dono (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    usuario TEXT NOT NULL, sal BLOB NOT NULL, senha BLOB NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sessoes (
                    hash TEXT PRIMARY KEY, expira INTEGER NOT NULL
                );
            """)

    def _db(self):
        return sqlite3.connect(self.arquivo, timeout=5)

    def existe(self):
        with closing(self._db()) as db:
            return bool(db.execute("SELECT 1 FROM dono").fetchone())

    def entrar(self, dados, criar=False):
        usuario = dados.get("usuario")
        senha = dados.get("senha")
        if not isinstance(usuario, str) or not re.fullmatch(r"[A-Za-z0-9._-]{3,40}", usuario):
            return 400, {"erro": "Use de 3 a 40 letras, números, ponto, hífen ou sublinhado no usuário."}
        usuario = usuario.lower()
        if not isinstance(senha, str) or not 12 <= len(senha) <= 128:
            return 400, {"erro": "A senha deve ter de 12 a 128 caracteres."}
        try:
            senha = senha.encode("utf-8")
        except UnicodeEncodeError:
            return 400, {"erro": "A senha contém caracteres inválidos."}
        if not self.trava.acquire(blocking=False):
            return 429, {"erro": "Aguarde alguns segundos e tente de novo."}
        try:
            return self._entrar(usuario, senha, criar, dados.get("codigo"))
        finally:
            self.trava.release()

    @staticmethod
    def _derivar(senha, sal):
        return hashlib.pbkdf2_hmac("sha256", senha, sal, ITERACOES)

    def _entrar(self, usuario, senha, criar, convite):
        with closing(self._db()) as db, db:
            dono = db.execute("SELECT usuario, sal, senha FROM dono").fetchone()
            if criar:
                if dono:
                    return 409, {"erro": "Este computador já tem uma conta. Use Entrar."}
                if (not self.convite or not isinstance(convite, str) or not convite.isascii()
                        or not hmac.compare_digest(self.convite, convite)):
                    return 403, {"erro": "Copie o código inicial da janela do computador."}
                sal = secrets.token_bytes(16)
                db.execute("INSERT INTO dono VALUES (1, ?, ?, ?)", (usuario, sal, self._derivar(senha, sal)))
            else:
                if not dono:
                    return 409, {"erro": "Crie a conta local primeiro."}
                nome, sal, hash_senha = dono
                valido = hmac.compare_digest(self._derivar(senha, sal), hash_senha)
                if not valido or usuario != nome:
                    return 401, {"erro": "Usuário ou senha incorretos."}

            token = secrets.token_urlsafe(32)
            agora = int(time.time())
            db.execute("DELETE FROM sessoes WHERE expira <= ?", (agora,))
            db.execute("DELETE FROM sessoes WHERE hash IN "
                       "(SELECT hash FROM sessoes ORDER BY expira DESC, rowid DESC LIMIT -1 OFFSET 11)")
            db.execute("INSERT INTO sessoes VALUES (?, ?)", (self._hash(token), agora + VIDA_SESSAO))
        return 200, {"usuario": usuario, "token": token}

    @staticmethod
    def _hash(token):
        return hashlib.sha256(token.encode("ascii")).hexdigest()

    @staticmethod
    def _valido(token):
        return bool(token and re.fullmatch(r"[A-Za-z0-9_-]{43}", token))

    def autenticar(self, token):
        if not self._valido(token):
            return False
        with closing(self._db()) as db:
            return bool(db.execute("SELECT 1 FROM sessoes WHERE hash = ? AND expira > ?",
                                   (self._hash(token), int(time.time()))).fetchone())

    def sair(self, token):
        if self._valido(token):
            with closing(self._db()) as db, db:
                db.execute("DELETE FROM sessoes WHERE hash = ?", (self._hash(token),))
