# Rede Neural 3.1

Android e painel local para explorar o grafo de um vault do Obsidian. Cada instalação do Conector tem a própria conta e atende os aparelhos do seu dono na rede local.

## Comece em três passos

1. **No computador Windows x64**, baixe e extraia `Conector-Rede-Neural-3.1.1-Windows.zip`. Abra **Abrir-Conector.bat** na pasta extraída. O ZIP já inclui o Python oficial e as bibliotecas. Abra o vault no Obsidian, confira a pasta sugerida e clique em **Iniciar conexão**. Se o Firewall do Windows solicitar permissão, selecione redes privadas.
2. **No Android**, instale `RedeNeural-3.1.1.apk` e abra a Rede Neural. Na mesma rede local do PC, escolha **Ler QR com a câmera** e fotografe o QR inteiro da janela do Conector. Se preferir, use **Copiar código** no PC, transfira-o para o seu aparelho por um meio privado e cole em **Código do computador**. Mantenha o código inicial com você até concluir o cadastro.
3. **Crie sua conta local** com um usuário e uma senha de pelo menos 12 caracteres. A conta é criada no PC. Nos próximos aparelhos, leia o QR do Conector e use **Entrar** com esse usuário e senha. Depois de entrar, **Ajustes → Conta e computador** permite sair ou trocar de computador.

O Android requer Android 8 ou superior e um WebView atualizado. O sistema pode pedir autorização para instalar aplicativos por aquela origem. Dependendo do dispositivo e do país, a instalação também passa pela [verificação de desenvolvedores do Android](https://developer.android.com/developer-verification/guides?hl=pt-BR).

## Obsidian, Claude Code e Codex

**Só o grafo:** escolher a pasta já permite ler as notas. Para carregar os links resolvidos pelo mesmo índice que atende o MCP, mantenha o Obsidian aberto nesse vault, ative **Configurações → Plugins da comunidade**, instale **Local REST API with MCP** e habilite o plugin. O Conector detecta a configuração na pasta escolhida e usa o certificado do plugin pela interface local. A chave do plugin fica no computador. Em **Ajustes**, o painel informa a origem dos links. [Local REST API com MCP](https://github.com/coddingtonbear/obsidian-local-rest-api)

**Atividade dos agentes:** use o Claude Code e o Codex com a sua conta, dentro de cada cliente oficial, no mesmo computador que roda o Conector. Para ver chamadas agente ↔ memória, conecte o MCP do Obsidian aos dois clientes pelos passos do plugin. A Rede Neural lê a atividade registrada localmente, incluindo uso e limites quando registrados. Sessões exclusivas da nuvem não aparecem. [Como configurar o MCP nos clientes](https://github.com/coddingtonbear/obsidian-local-rest-api#mcp-clients)

Se o cliente ainda não estiver autenticado, use `claude auth login` no Claude Code e `codex login` no Codex CLI. Conclua cada login na página oficial que o cliente abrir. [Claude Code](https://code.claude.com/docs/en/overview) · [Codex](https://developers.openai.com/codex/auth)

**Painel no navegador:** deixe o Conector aberto e use **Abrir painel web** e **Copiar chave web**. O endereço padrão é [http://127.0.0.1:8772/](http://127.0.0.1:8772/) no próprio computador. Se essa porta estiver ocupada, use o endereço exibido no Conector. A credencial da conta Android e a chave local do navegador são distintas.

Se já estiver conectado pelo código manual e quiser conferir o QR, abra **Ajustes → Conta e computador → Testar QR do Conector**. Fotografe o quadrado inteiro e confirme a foto. O teste preserva sua sessão.

## Quando a conexão falhar

| Sintoma | Como verificar |
|---|---|
| O Android não encontra o PC | Abra o Conector, confira se o PC e o Android estão na mesma rede privada e verifique a permissão do Firewall para o executável. Uma rede de convidados pode isolar os aparelhos. |
| O Conector selecionou uma interface virtual ou o IP mudou | No PC, veja o IPv4 de Wi-Fi ou Ethernet em Configurações de rede. Informe-o no campo **IPv4 do PC**, inicie a conexão e leia o novo QR. No Android, use **Ajustes → Conta e computador → Esquecer computador** se precisar refazer o pareamento. O QR novo mantém a conta existente do PC. |
| Aparece leitura pelo disco | Abra o vault correto no Obsidian e confira se o mesmo vault tem o plugin ativo. |
| Um agente aparece sem sessões | Abra o cliente oficial nesse PC e execute uma sessão que grave logs locais. Sessões de outras máquinas precisam de um Conector naquele computador. |
| Esqueceu a senha | Feche o Conector. Mova `contas.sqlite3` da pasta `%LOCALAPPDATA%\RedeNeural` para uma pasta privada de backup. Reabra o Conector e crie uma nova conta. Todas as sessões anteriores deixam de funcionar, e as notas continuam no Obsidian. |

## Como os dados circulam

O Conector consulta o Obsidian pela interface local do computador e acompanha os JSONL das sessões do Claude Code e do Codex. O Android envia o login por HTTPS para a porta 8771 do IPv4 privado escolhido. O certificado precisa corresponder à impressão recebida no QR. O servidor guarda a senha derivada com PBKDF2 e emite uma sessão revogável. O Android cifra essa sessão com o Android Keystore. As notas ficam no vault e a visualização expõe prévias de leitura pelo endpoint autenticado. O Conector não hospeda os dados na internet.

O tráfego MCP soma as cargas identificadas nos registros locais. Respostas agrupadas que não podem ser atribuídas a uma chamada ficam fora da medição. O tráfego do painel contabiliza o tamanho dos corpos HTTP.

## Executar a partir do código

O pacote portátil distribuído é Windows x64. No Windows, macOS ou Linux, é possível iniciar o conector gráfico a partir do código com Python 3.11 ou superior e Tk instalado:

```text
python -m pip install -r rede/requirements.txt
python rede/conector.py
```

No modo linha de comando:

```text
python rede/servidor.py --vault CAMINHO_DO_VAULT --lan --abrir
```

O modo USB histórico é exclusivo do APK de depuração. A versão assinada suporta o fluxo **QR → conta local → HTTPS**.

Para distribuir, siga [Publicar no GitHub](PUBLICAR.md).
