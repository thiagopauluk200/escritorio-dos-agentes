#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Importa o historico exportado do ChatGPT para o vault do Obsidian.

Uso:  arraste o ZIP da exportacao sobre "3 - Importar ChatGPT.cmd"
      (ou: python importar_chatgpt.py caminho\\do\\export.zip)

Cada conversa vira uma nota em 05-Fontes/ChatGPT, com origem, data e
confianca no cabecalho. Notas que ja existem NAO sao sobrescritas.
So o indice ("_ChatGPT - indice") e regerado a cada importacao.
"""
import glob
import json
import os
import re
import sys
import zipfile
from datetime import datetime

AQUI = os.path.dirname(os.path.abspath(__file__))
MAX_MSG = 20000


def nome_seguro(t, n=70):
    t = re.sub(r'[\\/:*?"<>|#^\[\]\r\n\t]+', " ", t or "").strip(" .")
    t = re.sub(r"\s+", " ", t)
    return (t[:n].rstrip(" .") or "Conversa sem título")


def carregar(origem):
    """Lista de conversas a partir de um .zip, de uma pasta ou de um .json."""
    conversas = []

    def soma(dado):
        if isinstance(dado, list):
            conversas.extend(c for c in dado if isinstance(c, dict))

    if os.path.isdir(origem):
        for arq in sorted(glob.glob(os.path.join(origem, "conversations*.json"))):
            with open(arq, encoding="utf-8") as f:
                soma(json.load(f))
    elif origem.lower().endswith(".zip"):
        with zipfile.ZipFile(origem) as z:
            for nome in sorted(z.namelist()):
                base = os.path.basename(nome).lower()
                if base.startswith("conversations") and base.endswith(".json"):
                    soma(json.loads(z.read(nome).decode("utf-8")))
    else:
        with open(origem, encoding="utf-8") as f:
            soma(json.load(f))
    return conversas


def texto_da_mensagem(msg):
    c = msg.get("content") or {}
    tipo = c.get("content_type")
    if tipo in ("text", "multimodal_text"):
        partes = []
        for p in c.get("parts") or []:
            if isinstance(p, str):
                partes.append(p)
            elif isinstance(p, dict):
                partes.append("*[anexo: %s]*" % (p.get("content_type") or "arquivo"))
        return "\n".join(partes).strip()
    if tipo == "code":
        return "```\n%s\n```" % (c.get("text") or "").strip() if c.get("text") else ""
    return ""


def linha_do_tempo(conv):
    """Mensagens do ramo final da conversa, em ordem."""
    mapa = conv.get("mapping") or {}
    no = conv.get("current_node")
    if no not in mapa:
        no = None
        for k, v in mapa.items():  # folha mais recente
            if not v.get("children"):
                no = k
    cadeia, vistos = [], set()
    while no and no in mapa and no not in vistos:
        vistos.add(no)
        cadeia.append(mapa[no])
        no = mapa[no].get("parent")
    saida = []
    for item in reversed(cadeia):
        msg = item.get("message")
        if not isinstance(msg, dict):
            continue
        papel = (msg.get("author") or {}).get("role")
        if papel not in ("user", "assistant"):
            continue
        if (msg.get("metadata") or {}).get("is_visually_hidden_from_conversation"):
            continue
        txt = texto_da_mensagem(msg)
        if txt:
            if len(txt) > MAX_MSG:
                txt = txt[:MAX_MSG] + "\n\n*[mensagem cortada na importação]*"
            saida.append((papel, txt))
    return saida


def data(ts):
    try:
        return datetime.fromtimestamp(float(ts))
    except Exception:
        return None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    vault = os.path.join(os.path.dirname(AQUI), "memoria")
    for a in sys.argv[1:]:
        if a.startswith("--vault="):
            vault = a.split("=", 1)[1]
    if not args:
        origem = input("Arraste aqui o ZIP exportado do ChatGPT e aperte Enter:\n> ").strip().strip('"')
    else:
        origem = args[0]
    if not os.path.exists(origem):
        print("Arquivo não encontrado:", origem)
        return 1
    if not os.path.isdir(vault):
        print("Vault não encontrado:", vault)
        return 1

    try:
        conversas = carregar(origem)
    except Exception as e:
        print("Não consegui ler a exportação:", e)
        return 1
    if not conversas:
        print("Não achei conversations.json nessa exportação.")
        return 1

    destino = os.path.join(vault, "05-Fontes", "ChatGPT")
    os.makedirs(destino, exist_ok=True)
    hoje = datetime.now().strftime("%Y-%m-%d")
    novas = puladas = vazias = 0
    indice = []
    existentes = {}
    for arq in os.listdir(destino):
        m = re.search(r"\(([0-9a-f]{8})\)\.md$", arq)
        if m:
            existentes[m.group(1)] = arq[:-3]

    for conv in conversas:
        cid = str(conv.get("conversation_id") or conv.get("id") or "")
        curto = (re.sub(r"[^0-9a-f]", "", cid.lower()) + "00000000")[:8]
        titulo = conv.get("title") or "Conversa sem título"
        criado = data(conv.get("create_time")) or datetime.now()
        atualizado = data(conv.get("update_time")) or criado
        if curto in existentes:
            indice.append((criado, existentes[curto], titulo))
            puladas += 1
            continue
        msgs = linha_do_tempo(conv)
        if not msgs:
            vazias += 1
            continue
        nome = "%s %s (%s)" % (criado.strftime("%Y-%m-%d"), nome_seguro(titulo), curto)
        linhas = [
            "---",
            "tipo: conversa",
            "origem: chatgpt",
            "data: " + criado.strftime("%Y-%m-%d"),
            "atualizado: " + atualizado.strftime("%Y-%m-%d"),
            "importado: " + hoje,
            "confianca: fonte bruta (conversa não revisada)",
            "id: " + cid,
            "---",
            "",
            "# " + titulo.replace("\n", " "),
            "",
            "Conversa importada do ChatGPT. Índice: [[_ChatGPT - índice]]",
            "",
        ]
        for papel, txt in msgs:
            linhas += ["## " + ("Eu" if papel == "user" else "ChatGPT"), "", txt, ""]
        with open(os.path.join(destino, nome + ".md"), "x", encoding="utf-8") as f:
            f.write("\n".join(linhas))
        existentes[curto] = nome
        indice.append((criado, nome, titulo))
        novas += 1

    indice.sort(key=lambda x: x[0], reverse=True)
    out = ["---", "tipo: indice", "origem: chatgpt", "gerado: " + hoje, "---", "",
           "# ChatGPT - índice das conversas importadas", "",
           "Gerado automaticamente por `importar_chatgpt.py`. %d conversas." % len(indice), ""]
    mes = None
    for criado, nome, titulo in indice:
        m = criado.strftime("%Y-%m")
        if m != mes:
            mes = m
            out += ["", "## " + m, ""]
        out.append("- [[%s|%s]]" % (nome, titulo.replace("|", "-").replace("\n", " ").replace("]", ")").replace("[", "(")))
    with open(os.path.join(destino, "_ChatGPT - índice.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    print("\nImportação concluída em:", destino)
    print("  %d conversas novas  |  %d já existiam (mantidas)  |  %d sem texto (ignoradas)" % (novas, puladas, vazias))
    return 0


if __name__ == "__main__":
    try:
        codigo = main()
    except KeyboardInterrupt:
        codigo = 1
    input("\nAperte Enter para fechar...")
    sys.exit(codigo)
