# -*- coding: utf-8 -*-
"""
Segurança da Rede Neural.

Modelo do painel local e da integração USB de depuração:
- O painel escuta somente em 127.0.0.1. O celular chega até ele pelo cabo USB
  (``adb reverse``), então nada fica exposto na rede Wi-Fi.
- Um segredo de 256 bits (``token``) é gerado no PC e entregue ao app pelo próprio
  cabo (``adb shell run-as``). Ele nunca trafega em claro nas requisições.
- Cada requisição do app leva ``X-RN-Auth: <ts>.<nonce>.<hmac>`` com
  HMAC-SHA256(token, "METODO\\nCAMINHO\\nTS\\nNONCE"). Janela de 90 s e nonce de uso único
  (anti-replay).
- Cada resposta leva ``X-RN-Sig`` = HMAC-SHA256(token, "NONCE\\nSHA256(corpo)"), e o app
  recusa respostas sem assinatura válida: autenticação mútua (um processo intruso
  escutando a mesma porta no celular não consegue se passar pelo PC).
- O navegador do próprio PC entra com o token uma vez e recebe um cookie de sessão
  HttpOnly + SameSite=Strict.
- Tentativas inválidas são limitadas por origem (rate limit com bloqueio progressivo).
"""
import hashlib
import hmac
import os
import secrets
import threading
import time

JANELA_S = 90
SESSAO_S = 12 * 3600


def pasta_dados():
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    p = os.path.abspath(os.environ.get("REDE_NEURAL_DADOS") or os.path.join(base, "RedeNeural"))
    os.makedirs(p, mode=0o700, exist_ok=True)
    return p


def carregar_token(caminho=None, criar=True):
    caminho = caminho or os.path.join(pasta_dados(), "token")
    try:
        with open(caminho, encoding="ascii") as f:
            t = f.read().strip()
            if len(t) >= 40:
                return t
    except OSError:
        pass
    if not criar:
        return None
    t = secrets.token_urlsafe(32)
    fd = os.open(caminho, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as f:
        f.write(t)
    return t


def assinar_pedido(token, metodo, caminho, ts, nonce):
    msg = "%s\n%s\n%s\n%s" % (metodo.upper(), caminho, ts, nonce)
    return hmac.new(token.encode(), msg.encode(), hashlib.sha256).hexdigest()


def assinar_resposta(token, nonce, corpo):
    msg = nonce + "\n" + hashlib.sha256(corpo).hexdigest()
    return hmac.new(token.encode(), msg.encode(), hashlib.sha256).hexdigest()


class Guarda:
    """Verifica autenticação, controla nonces, sessões de navegador e tentativas."""

    def __init__(self, token):
        self.token = token
        self._nonces = {}
        self._sessoes = {}
        self._falhas = {}
        self._trava = threading.Lock()

    # ---------------------------------------------------------- rate limit
    def bloqueado(self, origem):
        with self._trava:
            n, ate = self._falhas.get(origem, (0, 0))
            return time.time() < ate

    def falhou(self, origem):
        with self._trava:
            n, _ = self._falhas.get(origem, (0, 0))
            n += 1
            espera = 0 if n < 5 else min(300, 2 ** min(n - 4, 9))
            self._falhas[origem] = (n, time.time() + espera)

    def acertou(self, origem):
        with self._trava:
            self._falhas.pop(origem, None)

    # ------------------------------------------------------------- HMAC
    def verificar_hmac(self, cabecalho, metodo, caminho):
        """Devolve o nonce se o cabeçalho for válido; senão None."""
        try:
            ts, nonce, assinatura = cabecalho.split(".", 2)
            ts_i = int(ts)
        except (ValueError, AttributeError):
            return None
        if not (8 <= len(nonce) <= 64) or not nonce.isascii() or not nonce.replace("-", "").replace("_", "").isalnum():
            return None
        if len(assinatura) != 64 or not assinatura.isascii():
            return None
        if abs(time.time() - ts_i) > JANELA_S:
            return None
        esperado = assinar_pedido(self.token, metodo, caminho, ts, nonce)
        if not hmac.compare_digest(esperado, assinatura):
            return None
        agora = time.time()
        with self._trava:
            if nonce in self._nonces:
                return None  # replay
            self._nonces[nonce] = agora
            if len(self._nonces) > 5000:
                self._nonces = {k: v for k, v in self._nonces.items() if agora - v < JANELA_S * 2}
        return nonce

    # --------------------------------------------------------- sessões
    def entrar(self, token_digitado):
        if (not isinstance(token_digitado, str) or not token_digitado.isascii()
                or not hmac.compare_digest(token_digitado.strip(), self.token)):
            return None
        sid = secrets.token_urlsafe(32)
        with self._trava:
            self._sessoes[sid] = time.time() + SESSAO_S
        return sid

    def sessao_valida(self, sid):
        if not sid:
            return False
        with self._trava:
            ate = self._sessoes.get(sid)
            if not ate or ate < time.time():
                self._sessoes.pop(sid, None)
                return False
            return True

    def sair(self, sid):
        with self._trava:
            self._sessoes.pop(sid, None)
