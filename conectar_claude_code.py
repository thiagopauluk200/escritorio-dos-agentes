#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Conecta o Claude Code ao servidor MCP do Obsidian (o mesmo que o Codex ja usa).

O que faz, nesta ordem:
  1. Le a porta e o token direto das configuracoes do plugin MCP Server do vault
     (o token nunca e mostrado na tela nem copiado para outro lugar alem do
     arquivo de configuracao do proprio Claude Code).
  2. Testa de verdade o servidor MCP com esse token.
  3. Registra o servidor "obsidian" no Claude Code (escopo do usuario).
  4. Acrescenta as regras de memoria compartilhada em ~/.claude/CLAUDE.md.
  5. Se achar os hooks antigos do Roving Office (que apontam para um servico
     que nao esta rodando), pergunta se pode remover.

Sempre cria copia de seguranca antes de alterar um arquivo.
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

AQUI = os.path.dirname(os.path.abspath(__file__))
CASA = os.path.expanduser("~")
MARCA = "<!-- memoria-compartilhada:obsidian -->"


def achar_vault():
    if len(sys.argv) > 1 and os.path.isdir(sys.argv[1]):
        return sys.argv[1]
    return os.path.join(os.path.dirname(AQUI), "memoria")


def backup(caminho):
    if os.path.exists(caminho):
        destino = caminho + ".bak-" + time.strftime("%Y%m%d-%H%M%S")
        shutil.copy2(caminho, destino)
        print("   cópia de segurança:", destino)


def testar_mcp(url, token):
    corpo = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                   "clientInfo": {"name": "escritorio-teste", "version": "1.0"}},
    }).encode()
    req = urllib.request.Request(url, data=corpo, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": "Bearer " + token,
    })
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            txt = r.read(4000).decode("utf-8", "ignore")
            return r.status == 200 and "serverInfo" in txt, "HTTP %d" % r.status
    except urllib.error.HTTPError as e:
        return False, "HTTP %d (%s)" % (e.code, "token recusado" if e.code in (401, 403) else e.reason)
    except Exception as e:
        return False, "sem resposta (%s)" % e


def ja_configurado(cfg, url):
    srv = (cfg.get("mcpServers") or {}).get("obsidian")
    return isinstance(srv, dict) and srv.get("url") == url and "Authorization" in (srv.get("headers") or {})


def registrar(url, token):
    arq = os.path.join(CASA, ".claude.json")
    cfg = {}
    if os.path.exists(arq):
        try:
            with open(arq, encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception as e:
            print("   Não consegui ler %s (%s). Não alterei nada." % (arq, e))
            return False
    entrada = {"type": "http", "url": url, "headers": {"Authorization": "Bearer " + token}}
    atual = (cfg.get("mcpServers") or {}).get("obsidian")
    if atual == entrada:
        print("   O Claude Code já tem o servidor 'obsidian' com o endereço e o token atuais.")
        return True

    cli = shutil.which("claude")
    if cli and not atual:
        r = subprocess.run([cli, "mcp", "add", "--scope", "user", "--transport", "http", "obsidian", url,
                            "--header", "Authorization: Bearer " + token],
                           capture_output=True, text=True, shell=(os.name == "nt"))
        if r.returncode == 0:
            print("   Registrado pelo comando 'claude mcp add' (escopo do usuário).")
            return True
        print("   'claude mcp add' falhou; vou gravar direto no arquivo de configuração.")

    input("   Feche o Claude Code (se estiver aberto) e aperte Enter para gravar a configuração... ")
    if os.path.exists(arq):  # relê: o Claude Code pode ter regravado o arquivo
        with open(arq, encoding="utf-8") as f:
            cfg = json.load(f)
    backup(arq)
    cfg.setdefault("mcpServers", {})["obsidian"] = entrada
    tmp = arq + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, arq)
    print("   Gravado em", arq, "(atualizado)" if atual else "(novo)")
    return True


def regras_memoria():
    pasta = os.path.join(CASA, ".claude")
    arq = os.path.join(pasta, "CLAUDE.md")
    modelo = os.path.join(AQUI, "regras-memoria.md")
    with open(modelo, encoding="utf-8") as f:
        bloco = f.read().replace("{AGENTE}", "claude-code")
    atual = ""
    if os.path.exists(arq):
        with open(arq, encoding="utf-8") as f:
            atual = f.read()
    if MARCA in atual:
        print("   As regras de memória já estão em", arq)
        return
    os.makedirs(pasta, exist_ok=True)
    backup(arq)
    with open(arq, "a", encoding="utf-8") as f:
        f.write(("\n\n" if atual.strip() else "") + bloco.strip() + "\n")
    print("   Regras de memória acrescentadas em", arq)


def hooks_antigos():
    arq = os.path.join(CASA, ".claude", "settings.json")
    if not os.path.exists(arq):
        return
    try:
        with open(arq, encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        return
    hooks = cfg.get("hooks")
    if not isinstance(hooks, dict):
        return

    def antigo(h):
        return "roving" in json.dumps(h, ensure_ascii=False).lower()

    achados = sum(1 for grupos in hooks.values() if isinstance(grupos, list)
                  for g in grupos for h in (g.get("hooks", []) if isinstance(g, dict) else []) if antigo(h))
    if not achados:
        return
    print("\n5) Achei %d hook(s) antigos do Roving Office no Claude Code." % achados)
    print("   Eles apontam para um serviço que não está rodando; o Escritório novo não precisa deles.")
    if input("   Remover esses hooks? [s/N] ").strip().lower() not in ("s", "sim"):
        print("   Mantidos.")
        return
    backup(arq)
    for evento in list(hooks):
        grupos = hooks[evento]
        if not isinstance(grupos, list):
            continue
        novos = []
        for g in grupos:
            if isinstance(g, dict) and isinstance(g.get("hooks"), list):
                g["hooks"] = [h for h in g["hooks"] if not antigo(h)]
                if g["hooks"]:
                    novos.append(g)
            else:
                novos.append(g)
        if novos:
            hooks[evento] = novos
        else:
            del hooks[evento]
    if not hooks:
        cfg.pop("hooks", None)
    with open(arq, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("   Hooks antigos removidos.")


def main():
    vault = achar_vault()
    dados = os.path.join(vault, ".obsidian", "plugins", "mcp-server", "data.json")
    print("Conectar Claude Code  <->  Obsidian (MCP)\n")
    print("1) Lendo as configurações do plugin MCP Server em:", vault)
    try:
        with open(dados, encoding="utf-8") as f:
            s = json.load(f)["settings"]
        porta, token = int(s.get("port", 27123)), s["authToken"]
    except Exception as e:
        print("   Não encontrei o plugin MCP Server neste vault (%s)." % e)
        return 1
    url = "http://127.0.0.1:%d/mcp" % porta
    print("   endereço:", url, "| token: lido (não exibido)")

    print("\n2) Testando o servidor MCP com esse token...")
    ok, detalhe = testar_mcp(url, token)
    if ok:
        print("   OK - o servidor respondeu e aceitou o token.")
    else:
        print("   FALHOU:", detalhe)
        print("   Abra o Obsidian no vault 'memoria' (o servidor só funciona com ele aberto) e rode de novo.")
        if input("   Continuar e gravar a configuração mesmo assim? [s/N] ").strip().lower() not in ("s", "sim"):
            return 1

    print("\n3) Registrando o servidor 'obsidian' no Claude Code...")
    registrar(url, token)

    print("\n4) Regras de memória compartilhada...")
    regras_memoria()
    hooks_antigos()

    print("\nPronto. Abra (ou reabra) o Claude Code e peça:")
    print('   "Use o MCP obsidian para ler o README do vault e resuma as regras de memória."')
    print("Se ele listar as regras, a conexão está funcionando.")
    return 0


if __name__ == "__main__":
    try:
        codigo = main()
    except KeyboardInterrupt:
        codigo = 1
    input("\nAperte Enter para fechar...")
    sys.exit(codigo)
