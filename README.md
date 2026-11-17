# Rede Neural · Escritório dos Agentes

**Explore em 3D as notas do seu vault Obsidian e acompanhe a atividade local do Claude Code e do Codex.** Use o app no Android ou abra o painel no navegador do computador.

## Baixar

**[Baixar tudo em um ZIP](https://github.com/thiagopauluk200/escritorio-dos-agentes/releases/latest/download/Rede-Neural-Kit.zip)** — inclui o APK assinado, o Conector Windows e os hashes dos arquivos.

| Download | Para que serve |
| --- | --- |
| [APK Android](https://github.com/thiagopauluk200/escritorio-dos-agentes/releases/latest/download/RedeNeural-Android.apk) | Instale no celular ou tablet com Android 8 ou superior e Android System WebView atualizado. |
| [Conector Windows x64](https://github.com/thiagopauluk200/escritorio-dos-agentes/releases/latest/download/Conector-Rede-Neural-Windows.zip) | Abra o vault no PC, gere o QR de pareamento e inicie o painel. O Python vem incluído. |
| [SHA-256](https://github.com/thiagopauluk200/escritorio-dos-agentes/releases/latest/download/SHA256SUMS.txt) | Confira a integridade dos downloads. |
| [Releases](https://github.com/thiagopauluk200/escritorio-dos-agentes/releases) | Veja as mudanças e baixe versões anteriores. |

[Instalar e conectar](#instalar-e-conectar) · [Obsidian e MCP](#integrar-o-obsidian-e-o-mcp) · [Rodar em Python](#rodar-a-partir-do-código-em-python) · [Resolver problemas](#resolver-problemas)

## Instalar e conectar

### 1. Prepare o computador

Se baixou o kit completo, extraia o kit e depois o ZIP do Conector.

1. Extraia o ZIP do Conector e abra **Abrir-Conector.bat**.
2. No Obsidian, abra o vault que deseja visualizar. No Conector, selecione **a mesma pasta do vault**, a que contém a pasta oculta `.obsidian`.
3. Clique em **Iniciar conexão** e deixe a janela aberta. Se o Windows pedir acesso à rede, escolha redes privadas.

O Conector mostra o QR e o endereço do painel. A escolha do vault já permite visualizar as notas. A integração com o plugin adiciona os links resolvidos pelo Obsidian.

### 2. Pareie o Android

1. Instale o **APK Android**. Se o sistema pedir permissão para instalar a partir do navegador ou gerenciador de arquivos, autorize a origem que você usou para abrir o APK.
2. Coloque o Android e o PC na mesma rede local. No app, toque em **Ler QR com a câmera**, fotografe o QR inteiro da janela do Conector e confirme a foto.
3. Crie a conta local com um usuário e uma senha de pelo menos 12 caracteres. Em outros Androids pareados ao mesmo Conector, use **Entrar** com essa conta.

Se preferir, use **Copiar código** no Conector. Transfira-o por um meio privado e, no app, toque em **Usar código manual**, cole e escolha **Verificar conexão**. Quem já está conectado pode conferir o QR em **Ajustes → Conta e computador → Testar QR do Conector**.

[Guia oficial de instalação e verificação no Android](https://developer.android.com/developer-verification/guides?hl=pt-BR)

### 3. Abra o painel web

1. No Conector, clique em **Abrir painel web**. O endereço padrão no PC é [127.0.0.1:8772](http://127.0.0.1:8772/). Se a porta estiver ocupada, vale o endereço exibido na janela.
2. Clique em **Copiar chave web** e cole a chave na página de entrada.

O painel web é local ao computador. A chave web dá acesso ao navegador, e o QR ou código manual serve para o pareamento do Android.

## Integrar o Obsidian e o MCP

### Ative o índice do Obsidian

1. Abra o vault no Obsidian e vá a **Configurações → Plugins da comunidade**.
2. Procure **Local REST API with MCP**, instale e ative o plugin.
3. No Conector, confirme que a pasta selecionada é a raiz desse vault. Deixe o Obsidian aberto para carregar os metadados e os links resolvidos.
4. Abra **Ajustes** na Rede Neural e confira a origem do grafo. O Conector usa as configurações e o certificado locais do plugin. Se o serviço estiver indisponível, ele lê as notas em disco.

[Documentação do plugin Local REST API with MCP](https://github.com/coddingtonbear/obsidian-local-rest-api)

### Conecte Claude Code e Codex ao vault

O app monta o grafo mesmo sem configurar os agentes. Para acompanhar as chamadas à memória, conecte o MCP aos clientes que você usa no PC do Conector.

1. Faça login no cliente oficial com `claude auth login` ou `codex login`, conforme necessário.
2. Abra as configurações do plugin no Obsidian e copie a **chave de API do plugin**. Use o servidor MCP HTTPS padrão `https://127.0.0.1:27124/mcp/` se os clientes confiarem no certificado local do plugin.
3. Para configurar o exemplo abaixo, habilite **Enable HTTP server** no plugin e mantenha o serviço restrito ao próprio PC. O endereço HTTP padrão é `http://127.0.0.1:27123/mcp/`. Se você alterou a porta no Obsidian, use a porta configurada.

No PowerShell 7, leia a chave em uma variável de ambiente da sessão e registre o servidor em um ou nos dois clientes:

```powershell
$env:OBSIDIAN_API_KEY = Read-Host -MaskInput "Chave de API do plugin Obsidian"

codex mcp add obsidian --url http://127.0.0.1:27123/mcp/ --bearer-token-env-var OBSIDIAN_API_KEY

claude mcp add --transport http --scope user obsidian http://127.0.0.1:27123/mcp/ --header 'Authorization: Bearer ${OBSIDIAN_API_KEY}'
```

Inicie os clientes a partir do mesmo terminal enquanto a variável estiver disponível. Em um novo terminal, carregue a chave novamente antes de iniciar o agente. Se um servidor chamado `obsidian` já estiver configurado, confira a entrada existente antes de adicionar outra.

**Teste:** execute `codex mcp list` ou `claude mcp list`, peça ao agente para listar as notas do vault e abra **Agentes → Fluxo de memória** na Rede Neural. O painel observa os registros locais do Claude Code e do Codex. Atividade que só existe na nuvem fica fora dessa leitura.

[Configuração MCP do Codex](https://developers.openai.com/codex/mcp) · [Configuração MCP do Claude Code](https://code.claude.com/docs/en/mcp) · [Endereços e certificado do plugin](https://github.com/coddingtonbear/obsidian-local-rest-api#mcp-clients)

## Rodar a partir do código em Python

Use Python 3.11 ou superior. Para a janela gráfica, instale também o suporte a Tk da sua distribuição Python. No Linux, ele pode vir em um pacote separado, como `python3-tk`.

```bash
git clone https://github.com/thiagopauluk200/escritorio-dos-agentes.git
cd escritorio-dos-agentes
python -m venv .venv
```

Ative o ambiente virtual:

- Windows PowerShell: `.\.venv\Scripts\Activate.ps1` a partir da pasta do repositório.
- macOS ou Linux: `source .venv/bin/activate`.

Instale as dependências e abra a janela do Conector:

```bash
python -m pip install -r rede/requirements.txt
python rede/conector.py
```

Para rodar pelo terminal, informe o caminho do seu vault e use `--lan` para ativar a conexão Android. O inicializador exibe o código de pareamento e o caminho da chave web:

```bash
python rede/servidor.py --vault "/caminho/do/vault" --porta 8772 --lan --abrir
```

Use `python3` nos sistemas em que esse é o nome do comando. Para conferir os testes do pareamento e da autenticação local:

```bash
python -m unittest discover -s tests -p test_rede_acesso.py -v
```

## Resolver problemas

| O que aparece | O que fazer |
| --- | --- |
| **Página recusou a conexão** | Abra o Conector, clique em **Iniciar conexão** e use **Abrir painel web** para ir ao endereço atual. |
| **Chave web incorreta** | Use **Copiar chave web** no Conector que está rodando e cole a chave no painel do PC. |
| **QR ilegível** | Enquadre o quadrado inteiro, aproxime o celular e confirme a foto. Você também pode usar **Usar código manual**. |
| **Android não encontra o PC** | Confira se os dois estão na mesma rede local e se o Firewall permite o Conector em redes privadas. Redes de convidados podem separar os aparelhos. |
| **O IP do PC mudou** | Reinicie a conexão no Conector e pareie com o novo QR. Se necessário, indique o IPv4 do Wi-Fi ou Ethernet na janela do Conector. |
| **O grafo mostra leitura pelo disco** | Selecione a raiz do vault, deixe esse vault aberto no Obsidian e confira se o plugin está ativo ali. |
| **O agente aparece sem sessões** | Abra e use o cliente oficial no PC que roda o Conector. Confira o MCP com os comandos de listagem acima. |

## Como funciona

```mermaid
flowchart LR
  A["Android"] -->|HTTPS na rede local| C["Conector no computador"]
  B["Navegador no PC"] -->|127.0.0.1| C
  C --> O["Vault e índice do Obsidian"]
  C --> L["Registros locais de Claude Code e Codex"]
```

O Conector lê o vault e os registros de atividade. O gráfico permite girar, aproximar, buscar notas e mostrar ou ocultar legendas, pastas e tags. O painel de tráfego mede o serviço local e as cargas MCP identificadas nos registros.

A conta Android e os dados de sessão ficam na instalação local. O pareamento vincula o app ao certificado do Conector. O navegador usa uma chave própria. Guarde senhas, chaves, QR de cadastro e arquivos do vault fora de commits e capturas públicas.

## Código e distribuição

| Pasta | Conteúdo |
| --- | --- |
| `android/` | App em Kotlin, telas de acesso e integração com o painel. |
| `rede/` | Conector Python, autenticação, pareamento e interface compartilhada. |
| `tests/` | Testes do acesso e dos serviços locais. |
| `celular/`, `ponte.py` | Central de tarefas e ponte do Escritório dos Agentes. |

[Detalhes do Conector e recuperação da conta local](rede/README.md) · [Compilar, assinar e publicar uma versão](rede/PUBLICAR.md)
