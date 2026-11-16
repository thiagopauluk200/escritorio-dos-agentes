#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Camada de dados da Central: leitura e gravação dos arquivos JSON em disco.

Não conhece tarefas, agentes nem HTTP: recebe a pasta, o nome do arquivo e o valor. Quem decide O QUE gravar
e QUANDO é o hub.py (regras) — aqui só mora o COMO gravar sem corromper nem perder dados:

- gravação atômica: escreve num arquivo temporário próprio e troca pelo definitivo de uma vez;
- uma gravação por vez (`trava_disco`), para duas threads não escreverem no mesmo arquivo juntas;
- ordem por "série": um retrato dos dados tirado antes nunca sobrescreve um tirado depois, mesmo que
  chegue atrasado ao disco (caso do gravador em lote do hub).

Só biblioteca padrão do Python.
"""
import itertools
import json
import os
import secrets
import threading

trava_disco = threading.Lock()   # nunca é segurada junto com espera de rede
serie = itertools.count(1)       # ordem em que cada retrato dos dados foi tirado (next() é atômico no CPython)
_gravado = {}                    # caminho -> série do último retrato que chegou ao disco


def ler(pasta, nome, padrao):
    """Conteúdo do JSON `nome` em `pasta`; `padrao` se o arquivo não existir ou estiver ilegível."""
    try:
        with open(os.path.join(pasta, nome), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return padrao


def gravar(pasta, nome, valor, n_serie=None):
    """Grava `valor` como JSON em `pasta/nome` de forma atômica.

    `n_serie` identifica QUANDO o retrato foi tirado. Sem ela, vale "agora" (a próxima série). Um retrato
    mais antigo que o já gravado naquele arquivo é descartado em silêncio.
    """
    if n_serie is None:
        n_serie = next(serie)
    cam = os.path.join(pasta, nome)
    with trava_disco:
        if n_serie < _gravado.get(cam, 0):
            return
        tmp = "%s.%s.tmp" % (cam, secrets.token_hex(4))
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(valor, f, ensure_ascii=False)
            os.replace(tmp, cam)
        except BaseException:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise
        _gravado[cam] = n_serie
