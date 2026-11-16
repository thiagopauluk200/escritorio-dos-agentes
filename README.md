# Escritório dos Agentes

Um ambiente pessoal para acompanhar e coordenar agentes de inteligência artificial. O projeto reúne um painel web local, uma central de tarefas, uma ponte para execução no computador e um aplicativo Android.

## O que o projeto faz

- **Painel local:** apresenta as sessões ativas do Codex e do Claude Code e visualiza a rede de conhecimento do Obsidian.
- **Central de tarefas:** organiza agentes, tarefas e notas em uma interface web adaptada para uso no celular.
- **Ponte com o computador:** conecta a Central aos agentes locais, executa tarefas escolhidas e devolve resultados e estados.
- **Aplicativo Android:** acompanha agentes, tarefas, métricas e conhecimento, com cache local e armazenamento protegido para credenciais.
- **Importação de conversas:** transforma um arquivo de exportação do ChatGPT em notas Markdown.
- **Proteções operacionais:** restringe a ponte a pastas configuradas localmente, evita execuções concorrentes e trata operações repetidas de forma idempotente.

## Arquitetura

```text
Aplicativo Android ──HTTPS──┐
                            ├── Central web ── Ponte local ── Codex / Claude Code
Navegador ─────────────────┘                   └──────────── Obsidian
```

O painel local lê registros de sessão e notas no próprio computador. A Central pode rodar em um dispositivo separado; a ponte faz a comunicação entre ela e os agentes instalados no computador. O projeto não inclui dados de usuários ou credenciais de demonstração.

## Tecnologias

- Python para os serviços locais, a Central e as ferramentas de integração.
- Kotlin e Jetpack Compose para o aplicativo Android.
- HTML, CSS e JavaScript para as interfaces web.
- SQLite e Room para armazenamento local, com sincronização orientada a operações idempotentes.

## Estrutura

| Caminho | Conteúdo |
|---|---|
| `server.py`, `index.html` | Painel web local e leitura das sessões e da rede de notas |
| `ponte.py` | Comunicação entre a Central e os agentes no computador |
| `celular/` | Serviço da Central e sua interface web |
| `android/` | Aplicativo Android nativo |
| `importar_chatgpt.py` | Importação de conversas exportadas para Markdown |
| `tests/` | Testes automatizados do backend e da concorrência |

## Execução local

Requisitos: Python 3.10 ou superior. Para métricas completas da rede, instale as dependências:

```bash
python -m pip install -r requirements.txt
```

Inicie o painel apontando para um vault Obsidian existente:

```bash
python server.py --vault "/caminho/para/seu/vault"
```

O painel fica disponível apenas no computador local, na porta padrão `8765`.

Para compilar o aplicativo, abra `android/` no Android Studio com o JDK e o SDK Android instalados. A configuração local do SDK fica em `android/local.properties`, que não deve ser versionada.

## Configuração e dados locais

Endereços de serviço, caminhos do computador, credenciais, logs, bancos de dados, exportações e artefatos de build são específicos de cada instalação e ficam fora do repositório. Configure a Central e a ponte no ambiente local antes de usá-las; não publique arquivos de configuração preenchidos.

## Segurança

- Não inclua senhas, tokens, chaves, endereços privados ou dados reais em commits, issues ou capturas de tela.
- Arquivos locais de configuração e dados operacionais estão cobertos pelo `.gitignore`.
- Use HTTPS ao acessar a Central remotamente e restrinja o acesso à rede conforme seu ambiente.
- Revise alterações e arquivos novos antes de publicar uma atualização.

## Status

Projeto experimental de portfólio. A integração depende de ferramentas instaladas e configuradas pelo usuário; a publicação do código não disponibiliza uma Central hospedada nem credenciais de acesso.
